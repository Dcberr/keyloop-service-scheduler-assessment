package com.keyloop.scheduler.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfiguration.class);

    @Test
    void providesCurrentUtcClock() {
        Instant before = Instant.now();
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            Clock clock = context.getBean(Clock.class);
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(clock.instant()).isBetween(before, Instant.now());
        });
    }

    @Test
    void acceptsFixedClockForDeterministicBusinessTests() {
        Instant fixedInstant = Instant.parse("2026-11-02T08:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        contextRunner.withBean(Clock.class, () -> fixedClock).run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class)).isSameAs(fixedClock);
            assertThat(context.getBean(Clock.class).instant()).isEqualTo(fixedInstant);
        });
    }
}
