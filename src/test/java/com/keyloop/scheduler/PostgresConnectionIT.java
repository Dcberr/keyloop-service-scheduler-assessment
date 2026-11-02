package com.keyloop.scheduler;

import java.time.Clock;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import java.time.Duration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PostgresConnectionIT {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse(requiredPostgresImage()))
            .withEnv("POSTGRES_DB", "scheduler_test")
            .withEnv("POSTGRES_USER", "scheduler_test")
            .withEnv("POSTGRES_PASSWORD", "isolated-test-password")
            .withExposedPorts(5432)
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                    .withStrategy(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/scheduler_test");
        registry.add("spring.datasource.username", () -> "scheduler_test");
        registry.add("spring.datasource.password", () -> "isolated-test-password");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired Clock clock;

    @Test
    void startsApplicationWithRealPostgresAndEnabledFlyway() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("scheduler_test");
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(180000, 189999);
        assertThat(flyway.getConfiguration().getDataSource()).isNotNull();
        assertThat(flyway.info().applied()).hasSize(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    private static String requiredPostgresImage() {
        String image = System.getProperty("postgres.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Set postgres.image to the pinned Compose PostgreSQL image");
        }
        return image;
    }
}
