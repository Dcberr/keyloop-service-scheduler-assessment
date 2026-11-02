package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.shared.ServiceInterval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AvailabilityRepository {
    private final JdbcClient jdbc;

    public AvailabilityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public ReferenceData referenceData(AvailabilityRequest request) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM customers WHERE id=:customer) customer_exists,
                       EXISTS(SELECT 1 FROM vehicles WHERE id=:vehicle) vehicle_exists,
                       EXISTS(SELECT 1 FROM vehicles WHERE id=:vehicle AND customer_id=:customer) owns_vehicle,
                       EXISTS(SELECT 1 FROM dealerships WHERE id=:dealer) dealer_exists,
                       COALESCE((SELECT active FROM dealerships WHERE id=:dealer), false) dealer_active,
                       (SELECT time_zone FROM dealerships WHERE id=:dealer) time_zone,
                       EXISTS(SELECT 1 FROM service_types WHERE id=:service) service_exists,
                       COALESCE((SELECT active FROM service_types WHERE id=:service), false) service_active,
                       COALESCE((SELECT duration_minutes FROM service_types WHERE id=:service), 0) duration_minutes,
                       EXISTS(SELECT 1 FROM dealership_services WHERE dealership_id=:dealer AND service_type_id=:service) offered
                """).param("customer", request.customerId()).param("vehicle", request.vehicleId())
                .param("dealer", request.dealershipId()).param("service", request.serviceTypeId())
                .query((rs, row) -> new ReferenceData(rs.getBoolean("customer_exists"), rs.getBoolean("vehicle_exists"),
                        rs.getBoolean("owns_vehicle"), rs.getBoolean("dealer_exists"), rs.getBoolean("dealer_active"),
                        rs.getString("time_zone"), rs.getBoolean("service_exists"), rs.getBoolean("service_active"),
                        rs.getInt("duration_minutes"), rs.getBoolean("offered"))).single();
    }

    /** All resource decisions below observe a single PostgreSQL statement snapshot. */
    public Snapshot snapshot(AvailabilityRequest request, ServiceInterval interval) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM dealership_opening_windows w
                    WHERE w.dealership_id=:dealer AND w.starts_at<=:start AND w.ends_at>=:end
                ) open,
                EXISTS (
                    SELECT 1 FROM appointments a WHERE a.vehicle_id=:vehicle
                    AND tstzrange(a.starts_at,a.ends_at,'[)') && tstzrange(CAST(:start AS timestamptz),CAST(:end AS timestamptz),'[)')
                ) vehicle_busy,
                (
                    SELECT b.id FROM service_bays b WHERE b.dealership_id=:dealer AND b.active
                    AND NOT EXISTS (
                        SELECT 1 FROM appointments a WHERE a.service_bay_id=b.id
                        AND tstzrange(a.starts_at,a.ends_at,'[)') && tstzrange(CAST(:start AS timestamptz),CAST(:end AS timestamptz),'[)')
                    ) ORDER BY b.id LIMIT 1
                ) bay_id,
                (
                    SELECT t.id FROM technicians t WHERE t.dealership_id=:dealer AND t.active
                    AND EXISTS (SELECT 1 FROM technician_qualifications q WHERE q.technician_id=t.id AND q.service_type_id=:service)
                    AND EXISTS (SELECT 1 FROM technician_shifts s WHERE s.technician_id=t.id AND s.starts_at<=:start AND s.ends_at>=:end)
                    AND NOT EXISTS (
                        SELECT 1 FROM appointments a WHERE a.technician_id=t.id
                        AND tstzrange(a.starts_at,a.ends_at,'[)') && tstzrange(CAST(:start AS timestamptz),CAST(:end AS timestamptz),'[)')
                    ) ORDER BY t.id LIMIT 1
                ) technician_id
                """).param("dealer", request.dealershipId()).param("vehicle", request.vehicleId())
                .param("service", request.serviceTypeId())
                .param("start", interval.startsAt().atOffset(ZoneOffset.UTC))
                .param("end", interval.endsAt().atOffset(ZoneOffset.UTC))
                .query((rs, row) -> snapshot(rs)).single();
    }

    private Snapshot snapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getBoolean("open"), rs.getBoolean("vehicle_busy"),
                rs.getObject("bay_id", UUID.class), rs.getObject("technician_id", UUID.class));
    }

    public record ReferenceData(boolean customerExists, boolean vehicleExists, boolean ownsVehicle,
                                boolean dealershipExists, boolean dealershipActive, String timeZone,
                                boolean serviceExists, boolean serviceActive, int durationMinutes, boolean offered) { }
    public record Snapshot(boolean open, boolean vehicleBusy, UUID bayId, UUID technicianId) { }
}
