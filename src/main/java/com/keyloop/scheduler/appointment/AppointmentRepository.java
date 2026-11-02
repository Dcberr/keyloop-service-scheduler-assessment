package com.keyloop.scheduler.appointment;

import com.keyloop.scheduler.availability.AvailabilityAssessment;
import com.keyloop.scheduler.availability.AvailabilityRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppointmentRepository {
    private final JdbcClient jdbc;

    public AppointmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void configureTimeouts(long lockTimeoutMs, long statementTimeoutMs) {
        // Local settings disappear on commit/rollback and do not leak through the connection pool.
        jdbc.sql("SELECT set_config('lock_timeout', :value, true)")
                .param("value", lockTimeoutMs + "ms").query(String.class).single();
        jdbc.sql("SELECT set_config('statement_timeout', :value, true)")
                .param("value", statementTimeoutMs + "ms").query(String.class).single();
    }

    public Optional<UUID> lockVehicle(UUID vehicleId) {
        return jdbc.sql("SELECT customer_id FROM vehicles WHERE id=:id FOR UPDATE")
                .param("id", vehicleId).query(UUID.class).optional();
    }

    public boolean lockDealership(UUID dealershipId) {
        return jdbc.sql("SELECT id FROM dealerships WHERE id=:id FOR UPDATE")
                .param("id", dealershipId).query(UUID.class).optional().isPresent();
    }

    public Appointment insert(AvailabilityRequest request, AvailabilityAssessment assessment) {
        var result = assessment.result();
        return jdbc.sql("""
                INSERT INTO appointments
                    (id,customer_id,vehicle_id,dealership_id,service_type_id,technician_id,
                     service_bay_id,starts_at,ends_at,duration_minutes,status)
                VALUES (:id,:customer,:vehicle,:dealer,:service,:technician,:bay,:start,:end,:duration,'CONFIRMED')
                RETURNING id,customer_id,vehicle_id,dealership_id,service_type_id,technician_id,
                          service_bay_id,starts_at,ends_at,duration_minutes,status,created_at
                """).param("id", UUID.randomUUID()).param("customer", request.customerId())
                .param("vehicle", request.vehicleId()).param("dealer", request.dealershipId())
                .param("service", request.serviceTypeId()).param("technician", assessment.technicianId())
                .param("bay", assessment.serviceBayId())
                .param("start", result.startsAt().atOffset(ZoneOffset.UTC))
                .param("end", result.endsAt().atOffset(ZoneOffset.UTC))
                .param("duration", result.durationMinutes())
                .query((rs, row) -> new Appointment(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                        rs.getObject("dealership_id", UUID.class), rs.getObject("service_type_id", UUID.class),
                        rs.getObject("technician_id", UUID.class), rs.getObject("service_bay_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(), rs.getInt("duration_minutes"),
                        result.dealershipTimeZone(), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).single();
    }
}
