package com.keyloop.scheduler.shared;

import java.time.Instant;
import java.util.Objects;

/** Half-open service interval: an end equal to another start does not overlap. */
public record ServiceInterval(Instant startsAt, Instant endsAt) {
    public ServiceInterval {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Service interval must have positive duration");
        }
    }

    public boolean overlaps(ServiceInterval other) {
        return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
    }
}
