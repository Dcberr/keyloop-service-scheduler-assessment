package com.keyloop.scheduler.shared;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class BookingTimePolicy {
    private static final Pattern RFC3339 = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(?:[Zz]|[+-]\\d{2}:\\d{2})");
    private final Clock clock;

    public BookingTimePolicy(Clock clock) { this.clock = clock; }

    public Instant parse(String value) {
        if (value == null || !RFC3339.matcher(value).matches()) {
            throw new BusinessException(400, "INVALID_REQUEST", "startsAt must be an RFC 3339 timestamp with an explicit offset");
        }
        String normalized = value;
        var match = RFC3339.matcher(value);
        match.matches();
        String fraction = match.group(1);
        boolean excessNonzeroPrecision = false;
        if (fraction != null && fraction.length() > 10) {
            excessNonzeroPrecision = fraction.chars().skip(1).anyMatch(c -> c != '0');
            // Parse the complete calendar/offset first, even when precision is unsupported.
            normalized = value.substring(0, match.start(1)) + fraction.substring(0, 10)
                    + value.substring(match.end(1));
        }
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException failure) {
            throw new BusinessException(400, "INVALID_REQUEST", "startsAt is not a valid timestamp");
        }
        if (excessNonzeroPrecision) {
            throw new BusinessException(422, "INVALID_BOOKING_TIME", "Nonzero fractional seconds are not allowed");
        }
        return parsed;
    }

    public ServiceInterval interval(Instant start, int durationMinutes) {
        return interval(start, durationMinutes, clock.instant());
    }

    public ServiceInterval interval(Instant start, int durationMinutes, Instant now) {
        if (start == null || start.getNano() != 0 || Math.floorMod(start.getEpochSecond(), 60) != 0
                || !start.isAfter(now) || start.isAfter(now.plus(Duration.ofDays(30)))) {
            throw new BusinessException(422, "INVALID_BOOKING_TIME", "Start must be a future whole minute within 30 elapsed days");
        }
        if (durationMinutes <= 0) {
            throw new IllegalStateException("Service duration must be positive");
        }
        try {
            return new ServiceInterval(start, start.plus(Duration.ofMinutes(durationMinutes)));
        } catch (DateTimeException | ArithmeticException failure) {
            throw new BusinessException(422, "INVALID_BOOKING_TIME", "Service end is outside the supported time range");
        }
    }
}
