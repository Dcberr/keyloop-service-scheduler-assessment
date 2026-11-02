package com.keyloop.scheduler.availability;

import java.time.Instant;

/** Advisory observation only. No reservation or promised resource assignment. */
public record AvailabilityResult(boolean available, Instant startsAt, Instant endsAt,
                                 int durationMinutes, String dealershipTimeZone,
                                 Instant checkedAt, AvailabilityReason reason) { }
