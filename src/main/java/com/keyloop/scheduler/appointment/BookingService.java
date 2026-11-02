package com.keyloop.scheduler.appointment;

import com.keyloop.scheduler.availability.AvailabilityRequest;
import com.keyloop.scheduler.availability.AvailabilityService;
import com.keyloop.scheduler.shared.BookingTimePolicy;
import com.keyloop.scheduler.shared.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingService {
    private static final Logger LOG = LoggerFactory.getLogger(BookingService.class);
    private static final Set<String> REASONS = Set.of("INVALID_REQUEST", "RESOURCE_NOT_FOUND",
            "VEHICLE_CUSTOMER_MISMATCH", "SERVICE_NOT_BOOKABLE", "INVALID_BOOKING_TIME",
            "OUTSIDE_OPENING_HOURS", "VEHICLE_UNAVAILABLE", "NO_AVAILABLE_BAY",
            "NO_AVAILABLE_TECHNICIAN", "BOOKING_CONFLICT", "TEMPORARILY_UNAVAILABLE", "INTERNAL_ERROR");
    private final AppointmentRepository repository;
    private final AvailabilityService availability;
    private final BookingTimePolicy timePolicy;
    private final BookingFailureMapper failures;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;
    private final long lockTimeoutMs;
    private final long statementTimeoutMs;

    public BookingService(AppointmentRepository repository, AvailabilityService availability,
                          BookingTimePolicy timePolicy, BookingFailureMapper failures,
                          MeterRegistry meters, PlatformTransactionManager manager,
                          @Value("${scheduler.booking.lock-timeout-ms:2000}") long lockTimeoutMs,
                          @Value("${scheduler.booking.statement-timeout-ms:3000}") long statementTimeoutMs,
                          @Value("${scheduler.booking.transaction-timeout-seconds:5}") int transactionTimeoutSeconds) {
        if (lockTimeoutMs <= 0 || statementTimeoutMs <= 0 || transactionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Booking timeouts must be positive");
        }
        this.repository = repository;
        this.availability = availability;
        this.timePolicy = timePolicy;
        this.failures = failures;
        this.meters = meters;
        this.lockTimeoutMs = lockTimeoutMs;
        this.statementTimeoutMs = statementTimeoutMs;
        transaction = new TransactionTemplate(manager);
        transaction.setName("create-appointment");
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Always own commit: a caller's surrounding transaction cannot delay it until after success.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(transactionTimeoutSeconds);
    }

    public Appointment book(AvailabilityRequest request) {
        long started = System.nanoTime();
        Appointment committed;
        try {
            validateShape(request);
            committed = Objects.requireNonNull(transaction.execute(status -> {
                repository.configureTimeouts(lockTimeoutMs, statementTimeoutMs);
                timedLock("vehicle", () -> repository.lockVehicle(request.vehicleId())
                        .orElseThrow(() -> new BusinessException(404, "RESOURCE_NOT_FOUND", "Vehicle does not exist")));
                // Preserve reference-existence before ownership-error precedence from availability.
                availability.validateReferences(request);
                timedLock("dealership", () -> {
                    if (!repository.lockDealership(request.dealershipId())) {
                        throw new BusinessException(404, "RESOURCE_NOT_FOUND", "Dealership does not exist");
                    }
                    return true;
                });
                // A new statement after lock acquisition sees preceding committed allocations and rechecks Clock.
                var assessment = availability.assess(request);
                if (!assessment.result().available()) {
                    throw new BusinessException(409, assessment.result().reason().name(), "No resources are available for the requested interval");
                }
                return repository.insert(request, assessment);
            }));
        } catch (RuntimeException failure) {
            var mapped = failures.map(failure);
            recordOutcome(mapped.status() >= 500 ? "error" : "rejected", mapped.code(), started, null);
            throw mapped;
        }
        // TransactionTemplate has returned only after commit; no callback publishes premature success.
        recordOutcome("success", "none", started, committed);
        return committed;
    }

    private <T> T timedLock(String resource, Supplier<T> lock) {
        long started = System.nanoTime();
        boolean acquired = false;
        try {
            T result = lock.get();
            acquired = true;
            return result;
        } finally {
            try {
                meters.timer("scheduler.booking.lock.wait", "resource", resource,
                                "outcome", acquired ? "acquired" : "failed")
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                // Monitoring must not change the transaction's result or mask its original failure.
            }
        }
    }

    private void recordOutcome(String outcome, String reason, long started, Appointment appointment) {
        String safeReason = "none".equals(reason) || (reason != null && REASONS.contains(reason)) ? reason : "INTERNAL_ERROR";
        long elapsed = System.nanoTime() - started;
        try {
            meters.counter("scheduler.booking.requests", "outcome", outcome, "reason", safeReason).increment();
            meters.timer("scheduler.booking.duration", "outcome", outcome).record(elapsed, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // A monitoring failure cannot turn a committed appointment into a reported booking failure.
        }
        try {
            var event = ("error".equals(outcome) ? LOG.atError() : LOG.atInfo())
                    .addKeyValue("event", "booking_outcome").addKeyValue("outcome", outcome)
                    .addKeyValue("reason", safeReason).addKeyValue("elapsedMs", elapsed / 1_000_000.0);
            if (appointment != null) event.addKeyValue("appointmentId", appointment.id());
            event.log("Booking completed");
        } catch (RuntimeException ignored) {
            // Do not let an optional logging backend corrupt the application outcome.
        }
    }

    private void validateShape(AvailabilityRequest request) {
        if (request == null || request.customerId() == null || request.vehicleId() == null
                || request.dealershipId() == null || request.serviceTypeId() == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "All booking entity IDs are required");
        }
        timePolicy.parse(request.startsAt());
    }
}
