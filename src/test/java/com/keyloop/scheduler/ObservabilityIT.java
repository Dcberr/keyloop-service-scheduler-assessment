package com.keyloop.scheduler;

import com.keyloop.scheduler.appointment.BookingService;
import com.keyloop.scheduler.availability.AvailabilityRequest;
import com.keyloop.scheduler.shared.BusinessException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Import(ObservabilityIT.FixedTime.class)
@Testcontainers
class ObservabilityIT {
    @Container static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse(Objects.requireNonNull(System.getProperty("postgres.image"))))
            .withEnv("POSTGRES_DB","metrics_test").withEnv("POSTGRES_USER","test").withEnv("POSTGRES_PASSWORD","test-password")
            .withExposedPorts(5432).waitingFor(new WaitAllStrategy().withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2)).withStrategy(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(90)));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/metrics_test");r.add("spring.datasource.username",()->"test");r.add("spring.datasource.password",()->"test-password");}
    @TestConfiguration static class FixedTime{@Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"),ZoneOffset.UTC);}}
    @Autowired BookingService booking;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    static UUID id(int n){return UUID.fromString(String.format("00000000-0000-4000-8000-%012d",n));}
    @BeforeEach void seed(){
        jdbc.execute("DROP TRIGGER IF EXISTS metrics_failure ON appointments");jdbc.execute("DROP FUNCTION IF EXISTS metrics_failure_fn()");
        jdbc.execute("TRUNCATE customers,dealerships,service_types CASCADE");
        jdbc.execute("INSERT INTO customers VALUES ('"+id(1)+"','Sensitive customer')");
        jdbc.execute("INSERT INTO vehicles VALUES ('"+id(11)+"','"+id(1)+"','SENSITIVEVIN','Make','Model')");
        jdbc.execute("INSERT INTO dealerships VALUES ('"+id(21)+"','One','UTC',true)");
        jdbc.execute("INSERT INTO service_types VALUES ('"+id(31)+"','Service',60,true)");
        jdbc.execute("INSERT INTO dealership_services VALUES ('"+id(21)+"','"+id(31)+"')");
        jdbc.execute("INSERT INTO service_bays VALUES ('"+id(41)+"','"+id(21)+"','Bay',true)");
        jdbc.execute("INSERT INTO technicians VALUES ('"+id(51)+"','"+id(21)+"','Technician',true)");
        jdbc.execute("INSERT INTO technician_qualifications VALUES ('"+id(51)+"','"+id(31)+"')");
        jdbc.execute("INSERT INTO dealership_opening_windows VALUES ('"+id(81)+"','"+id(21)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
        jdbc.execute("INSERT INTO technician_shifts VALUES ('"+id(71)+"','"+id(51)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
    }
    AvailabilityRequest request(){return new AvailabilityRequest(id(1),id(11),id(21),id(31),"2030-01-10T09:00:00Z");}
    double counter(String outcome,String reason){return meters.find("scheduler.booking.requests").tags("outcome",outcome,"reason",reason).counters().stream().mapToDouble(c->c.count()).sum();}
    long timers(String outcome){return meters.find("scheduler.booking.duration").tag("outcome",outcome).timers().stream().mapToLong(t->t.count()).sum();}
    @Test void successAndBusinessRejectionHaveDistinctOutcomeMetrics(){
        double success=counter("success","none"), rejected=counter("rejected","VEHICLE_UNAVAILABLE");long duration=timers("success");
        booking.book(request());
        assertThatThrownBy(()->booking.book(request())).isInstanceOf(BusinessException.class);
        assertThat(counter("success","none")).isEqualTo(success+1);
        assertThat(counter("rejected","VEHICLE_UNAVAILABLE")).isEqualTo(rejected+1);
        assertThat(timers("success")).isEqualTo(duration+1);
        assertThat(meters.find("scheduler.booking.lock.wait").tag("resource","vehicle").timers()).isNotEmpty();
        assertThat(meters.find("scheduler.booking.lock.wait").tag("resource","dealership").timers()).isNotEmpty();
        assertThat(meters.find("hikaricp.connections.max").gauges()).isNotEmpty();
    }
    @Test void malformedBookingStillHasOneRejectedOutcome(){
        double before=counter("rejected","INVALID_REQUEST");
        assertThatThrownBy(()->booking.book(null)).isInstanceOf(BusinessException.class);
        assertThat(counter("rejected","INVALID_REQUEST")).isEqualTo(before+1);
    }
    @Test void commitFailureRecordsErrorWithoutSuccessAndLabelsContainNoIdentifiers(){
        jdbc.execute("CREATE FUNCTION metrics_failure_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SENSITIVEVIN Sensitive customer private SQL'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER metrics_failure AFTER INSERT ON appointments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION metrics_failure_fn()");
        double success=counter("success","none"),error=counter("error","INTERNAL_ERROR");
        assertThatThrownBy(()->booking.book(request())).isInstanceOf(BusinessException.class);
        assertThat(counter("success","none")).isEqualTo(success);assertThat(counter("error","INTERNAL_ERROR")).isEqualTo(error+1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class)).isZero();
        for(Meter meter:meters.getMeters())if(meter.getId().getName().startsWith("scheduler.booking")){
            for(var tag:meter.getId().getTags()){
                assertThat(tag.getKey()).isIn("outcome","reason","resource");
                assertThat(tag.getValue()).doesNotContain(id(1).toString(),id(11).toString(),"SENSITIVEVIN","Sensitive customer");
            }
        }
    }
}
