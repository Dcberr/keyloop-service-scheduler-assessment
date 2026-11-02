package com.keyloop.scheduler.appointment;

import java.time.Instant;
import java.util.UUID;

/** The persisted, confirmed assignment; returned only after the booking transaction commits. */
public record Appointment(UUID id, UUID customerId, UUID vehicleId, UUID dealershipId,
                          UUID serviceTypeId, UUID technicianId, UUID serviceBayId,
                          Instant startsAt, Instant endsAt, int durationMinutes,
                          String dealershipTimeZone, String status, Instant createdAt) { }
