package com.keyloop.scheduler.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class BookingTimePolicyTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private final BookingTimePolicy policy = new BookingTimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {"2030-01-10T09:00:00", "2030-01-10", "not-a-time", "2030-02-30T09:00:00Z", "2030-01-10T09:00:00+25:00"})
    void malformedOrOffsetlessInputIsInvalidRequest(String input) {
        assertError(() -> policy.parse(input), 400, "INVALID_REQUEST");
    }

    @ParameterizedTest @ValueSource(strings={"2030-01-10T09:00:00.0000000001Z", "2030-01-10T09:00:00.123456789123+07:00"})
    void validLongNonzeroFractionViolatesMinutePolicyRatherThanSyntax(String input) {
        assertError(()->policy.interval(policy.parse(input),60),422,"INVALID_BOOKING_TIME");
    }

    @Test void arbitraryZeroFractionRetainsWholeMinute() {
        Instant parsed=policy.parse("2030-01-10T09:00:00.000000000000000Z");
        assertThat(parsed).isEqualTo(Instant.parse("2030-01-10T09:00:00Z"));
        assertThatCode(()->policy.interval(parsed,60)).doesNotThrowAnyException();
    }

    @Test void malformedCalendarWithLongFractionIsStillSyntaxError() {
        assertError(()->policy.parse("2030-02-30T09:00:00.000000000001Z"),400,"INVALID_REQUEST");
    }

    @Test void equivalentOffsetsHaveTheSameInstant() {
        assertThat(policy.parse("2030-01-10T09:00:00+07:00"))
                .isEqualTo(policy.parse("2030-01-10T02:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2029-12-31T23:59:00Z", "2030-01-01T00:00:00Z", "2030-01-31T00:01:00Z", "2030-01-10T09:00:01Z", "2030-01-10T09:00:00.001Z"})
    void rejectsPastNowBeyondHorizonAndNonMinuteInstants(String input) {
        assertError(() -> policy.interval(Instant.parse(input), 60), 422, "INVALID_BOOKING_TIME");
    }

    @Test void acceptsExactThirtyDayUpperBoundary() {
        assertThatCode(() -> policy.interval(NOW.plusSeconds(30L * 24 * 60 * 60), 60)).doesNotThrowAnyException();
    }

    @Test void acceptsFutureWholeMinute() {
        assertThatCode(() -> policy.interval(NOW.plusSeconds(60), 60)).doesNotThrowAnyException();
    }

    @Test void nullInputIsInvalidRequest() {
        assertError(() -> policy.parse(null),400,"INVALID_REQUEST");
    }

    @Test void calculatesElapsedDurationAcrossOffsetChange() {
        Instant start=policy.parse("2030-01-10T09:00:00+07:00");
        ServiceInterval interval=policy.interval(start,90);
        assertThat(interval.startsAt()).isEqualTo(Instant.parse("2030-01-10T02:00:00Z"));
        assertThat(interval.endsAt()).isEqualTo(Instant.parse("2030-01-10T03:30:00Z"));
    }

    @ParameterizedTest @ValueSource(ints={0,-1})
    void invalidServiceDurationCannotProduceAnInterval(int duration) {
        assertThatThrownBy(()->policy.interval(NOW.plusSeconds(60),duration)).isInstanceOf(IllegalStateException.class);
    }

    @Test void halfOpenIntervalsTreatTouchingBoundariesAsNonOverlapping() {
        Instant start=NOW.plusSeconds(60);
        ServiceInterval base=new ServiceInterval(start,start.plusSeconds(3600));
        assertThat(base.overlaps(new ServiceInterval(start.plusSeconds(3600),start.plusSeconds(7200)))).isFalse();
        assertThat(base.overlaps(new ServiceInterval(start.minusSeconds(3600),start))).isFalse();
        assertThat(base.overlaps(new ServiceInterval(start.plusSeconds(1800),start.plusSeconds(5400)))).isTrue();
        assertThat(base.overlaps(new ServiceInterval(start.minusSeconds(1800),start.plusSeconds(5400)))).isTrue();
        assertThat(base.overlaps(base)).isTrue();
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        BusinessException error = catchThrowableOfType(BusinessException.class, action);
        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.code()).isEqualTo(code);
    }
}
