package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.shared.BookingTimePolicy;
import com.keyloop.scheduler.shared.BusinessException;
import com.keyloop.scheduler.shared.ServiceInterval;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {
    private final AvailabilityRepository repository;
    private final BookingTimePolicy timePolicy;
    private final Clock clock;

    public AvailabilityService(AvailabilityRepository repository, BookingTimePolicy timePolicy, Clock clock) {
        this.repository = repository;
        this.timePolicy = timePolicy;
        this.clock = clock;
    }

    public AvailabilityResult check(AvailabilityRequest request) { return assess(request).result(); }

    /** Read-only assessment; a booking must re-run it after obtaining its transaction locks. */
    public AvailabilityAssessment assess(AvailabilityRequest request) {
        if (request == null || request.customerId() == null || request.vehicleId() == null
                || request.dealershipId() == null || request.serviceTypeId() == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "All booking entity IDs are required");
        }
        Instant start = timePolicy.parse(request.startsAt());
        AvailabilityRepository.ReferenceData reference = validateReferences(request);
        Instant checkedAt = clock.instant();
        ServiceInterval interval = timePolicy.interval(start, reference.durationMinutes(), checkedAt);
        AvailabilityRepository.Snapshot state = repository.snapshot(request, interval);
        AvailabilityReason reason = !state.open() ? AvailabilityReason.OUTSIDE_OPENING_HOURS
                : state.vehicleBusy() ? AvailabilityReason.VEHICLE_UNAVAILABLE
                : state.bayId() == null ? AvailabilityReason.NO_AVAILABLE_BAY
                : state.technicianId() == null ? AvailabilityReason.NO_AVAILABLE_TECHNICIAN : null;
        AvailabilityResult result = new AvailabilityResult(reason == null, interval.startsAt(), interval.endsAt(),
                reference.durationMinutes(), reference.timeZone(), checkedAt, reason);
        return new AvailabilityAssessment(result, reason == null ? state.bayId() : null,
                reason == null ? state.technicianId() : null);
    }
    /** Immutable-reference validation, reusable after a booking has locked its vehicle. */
    public AvailabilityRepository.ReferenceData validateReferences(AvailabilityRequest request) {
        if (request == null || request.customerId() == null || request.vehicleId() == null
                || request.dealershipId() == null || request.serviceTypeId() == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "All booking entity IDs are required");
        }
        AvailabilityRepository.ReferenceData reference = repository.referenceData(request);
        if (!reference.customerExists() || !reference.vehicleExists() || !reference.dealershipExists() || !reference.serviceExists()) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "A requested resource does not exist");
        }
        if (!reference.ownsVehicle()) {
            throw new BusinessException(422, "VEHICLE_CUSTOMER_MISMATCH", "The vehicle does not belong to the customer");
        }
        if (!reference.dealershipActive() || !reference.serviceActive() || !reference.offered()) {
            throw new BusinessException(422, "SERVICE_NOT_BOOKABLE", "The dealership or requested service is not bookable");
        }
        return reference;
    }

}
