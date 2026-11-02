package com.keyloop.scheduler;

import com.keyloop.scheduler.appointment.Appointment;
import com.keyloop.scheduler.appointment.BookingService;
import com.keyloop.scheduler.availability.AvailabilityRequest;
import com.keyloop.scheduler.availability.AvailabilityService;
import com.keyloop.scheduler.shared.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(BookingTransactionIT.TestTime.class)
@Testcontainers
class BookingTransactionIT {
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
            Objects.requireNonNull(System.getProperty("postgres.image"))))
            .withEnv("POSTGRES_DB", "booking_test").withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test-password").withExposedPorts(5432)
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                    .withStrategy(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(90)));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BookingTransactionIT::url);
        registry.add("spring.datasource.username", () -> "test");
        registry.add("spring.datasource.password", () -> "test-password");
    }
    static String url() { return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/booking_test"; }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone); }
        public Instant instant() { return now.get(); }
    }
    @TestConfiguration static class TestTime { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    @Autowired BookingService booking;
    @Autowired AvailabilityService availability;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager transactions;
    ExecutorService workers;

    @BeforeEach void fixtures() {
        clock.now.set(NOW);
        workers = Executors.newFixedThreadPool(2);
        jdbc.execute("DROP TRIGGER IF EXISTS injected_failure ON appointments");
        jdbc.execute("DROP FUNCTION IF EXISTS injected_booking_failure()");
        jdbc.execute("TRUNCATE customers,dealerships,service_types CASCADE");
        jdbc.execute("INSERT INTO customers VALUES ('"+id(1)+"','Owner')");
        for (int n : new int[]{11,12,13}) jdbc.execute("INSERT INTO vehicles VALUES ('"+id(n)+"','"+id(1)+"','VIN"+n+"','Make','Model')");
        jdbc.execute("INSERT INTO dealerships VALUES ('"+id(21)+"','One','UTC',true),('"+id(22)+"','Two','UTC',true)");
        jdbc.execute("INSERT INTO service_types VALUES ('"+id(31)+"','Standard',60,true)");
        for (int dealer : new int[]{21,22}) {
            jdbc.execute("INSERT INTO dealership_services VALUES ('"+id(dealer)+"','"+id(31)+"')");
            jdbc.execute("INSERT INTO dealership_opening_windows VALUES ('"+id(dealer+60)+"','"+id(dealer)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
        }
        for (int n=1;n<=3;n++) {
            int dealer=n==3?22:21;
            jdbc.execute("INSERT INTO service_bays VALUES ('"+id(40+n)+"','"+id(dealer)+"','Bay',true)");
            jdbc.execute("INSERT INTO technicians VALUES ('"+id(50+n)+"','"+id(dealer)+"','Tech',true)");
            jdbc.execute("INSERT INTO technician_qualifications VALUES ('"+id(50+n)+"','"+id(31)+"')");
            jdbc.execute("INSERT INTO technician_shifts VALUES ('"+id(70+n)+"','"+id(50+n)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
        }
    }
    @AfterEach void finishWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
    }
    static UUID id(int n) { return UUID.fromString(String.format("00000000-0000-4000-8000-%012d",n)); }
    AvailabilityRequest request(int vehicle,int dealer) { return new AvailabilityRequest(id(1),id(vehicle),id(dealer),id(31),"2030-01-10T09:00:00Z"); }
    void onePair() { jdbc.execute("UPDATE service_bays SET active=false WHERE id='"+id(42)+"'"); jdbc.execute("UPDATE technicians SET active=false WHERE id='"+id(52)+"'"); }
    Connection independent() throws SQLException { return DriverManager.getConnection(url(),"test","test-password"); }
    Connection blocker(String table,int key) throws SQLException {
        Connection connection=independent(); connection.setAutoCommit(false);
        try(var statement=connection.createStatement()) { statement.executeQuery("SELECT id FROM "+table+" WHERE id='"+id(key)+"' FOR UPDATE").close(); }
        return connection;
    }
    int pid(Connection connection) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_backend_pid()")) {result.next();return result.getInt(1);}
    }
    void awaitWaiters(int count) {
        // Observe actual database lock waits; a barrier alone would not prove contention.
        await().atMost(Duration.ofMillis(1500)).pollInterval(Duration.ofMillis(10)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname='booking_test' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0",Integer.class)).isGreaterThanOrEqualTo(count));
    }
    long rowCount() { return jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class); }
    double successes() { return meters.find("scheduler.booking.requests").tags("outcome","success","reason","none").counters().stream().mapToDouble(Counter::count).sum(); }
    Outcome invoke(AvailabilityRequest request) {try{return new Outcome(booking.book(request),null);}catch(BusinessException failure){return new Outcome(null,failure);}}
    record Outcome(Appointment appointment,BusinessException failure) { }
    Future<Outcome> start(AvailabilityRequest request,CyclicBarrier barrier) {return workers.submit(() -> {barrier.await(3,TimeUnit.SECONDS);return invoke(request);});}
    void noOverlap() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments a JOIN appointments b ON a.id<b.id AND (a.service_bay_id=b.service_bay_id OR a.technician_id=b.technician_id OR a.vehicle_id=b.vehicle_id) WHERE tstzrange(a.starts_at,a.ends_at,'[)') && tstzrange(b.starts_at,b.ends_at,'[)')",Long.class)).isZero();
    }
    static void failure(Outcome outcome,int status,String code) {
        assertThat(outcome.appointment()).isNull(); assertThat(outcome.failure()).isNotNull();
        assertThat(outcome.failure().status()).isEqualTo(status);assertThat(outcome.failure().code()).isEqualTo(code);
    }
    void assertSingleSuccess(List<Outcome> outcomes,String conflict) {
        assertThat(outcomes.stream().filter(x->x.appointment()!=null).count()).isEqualTo(1);
        failure(outcomes.stream().filter(x->x.failure()!=null).findFirst().orElseThrow(),409,conflict);
        assertThat(rowCount()).isEqualTo(1); noOverlap();
    }

    @Test void successIsCommittedAndVisibleOnAnotherConnectionBeforeReturn() throws Exception {
        double before=successes(); Appointment result=booking.book(request(11,21));
        assertThat(result.id()).isNotNull();assertThat(result.customerId()).isEqualTo(id(1));assertThat(result.vehicleId()).isEqualTo(id(11));
        assertThat(result.serviceBayId()).isEqualTo(id(41));assertThat(result.technicianId()).isEqualTo(id(51));
        assertThat(result.durationMinutes()).isEqualTo(60);assertThat(result.startsAt()).isEqualTo(Instant.parse("2030-01-10T09:00:00Z"));
        assertThat(result.endsAt()).isEqualTo(Instant.parse("2030-01-10T10:00:00Z"));assertThat(result.status()).isEqualTo("CONFIRMED");
        try(var connection=independent();var statement=connection.prepareStatement("SELECT count(*) FROM appointments WHERE id=?")) {
            statement.setObject(1,result.id());try(var rows=statement.executeQuery()){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
        }
        assertThat(successes()).isEqualTo(before+1);
    }
    @Test void onePairAllowsExactlyOneOfTwoActuallyWaitingRequests() throws Exception {
        onePair();try(var blocker=blocker("dealerships",21)) {
            var barrier=new CyclicBarrier(2);var first=start(request(11,21),barrier);var second=start(request(12,21),barrier);
            awaitWaiters(2);blocker.commit();
            assertSingleSuccess(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)),"NO_AVAILABLE_BAY");
        }
    }
    @Test void twoPairsAllowBothConcurrentRequestsWithoutSharingResources() throws Exception {
        try(var blocker=blocker("dealerships",21)) {
            var barrier=new CyclicBarrier(2);var first=start(request(11,21),barrier);var second=start(request(12,21),barrier);
            awaitWaiters(2);blocker.commit();
            var results=List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result->{assertThat(result.failure()).isNull();assertThat(result.appointment()).isNotNull();});
            assertThat(results.stream().map(x->x.appointment().serviceBayId()).distinct().count()).isEqualTo(2);
            assertThat(results.stream().map(x->x.appointment().technicianId()).distinct().count()).isEqualTo(2);
            assertThat(rowCount()).isEqualTo(2);noOverlap();
        }
    }
    @Test void sameVehicleCannotBookOverlappingServicesAcrossDealerships() throws Exception {
        try(var blocker=blocker("vehicles",11)) {
            var barrier=new CyclicBarrier(2);var first=start(request(11,21),barrier);var second=start(request(11,22),barrier);
            awaitWaiters(2);blocker.commit();
            assertSingleSuccess(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)),"VEHICLE_UNAVAILABLE");
        }
    }
    @Test void anotherDealershipCompletesWhileFirstDealershipIsLocked() throws Exception {
        try(var blocker=blocker("dealerships",21)) {
            Future<Outcome> waiting=workers.submit(()->invoke(request(11,21)));awaitWaiters(1);
            Outcome independent=workers.submit(()->invoke(request(12,22))).get(1200,TimeUnit.MILLISECONDS);
            assertThat(independent.failure()).isNull();assertThat(independent.appointment()).isNotNull();
            assertThat(waiting.isDone()).isFalse();blocker.commit();assertThat(waiting.get(5,TimeUnit.SECONDS).failure()).isNull();
            assertThat(rowCount()).isEqualTo(2);noOverlap();
        }
    }
    @Test void staleAvailabilityCannotBypassBookingRecheck() {
        onePair();assertThat(availability.check(request(12,21)).available()).isTrue();booking.book(request(11,21));
        failure(invoke(request(12,21)),409,"NO_AVAILABLE_BAY");assertThat(rowCount()).isEqualTo(1);
    }
    @Test void lockTimeoutRollsBackAndDoesNotPublishSuccess() throws Exception {
        double before=successes();try(var blocker=blocker("dealerships",21)) {
            Future<Outcome> waiting=workers.submit(()->invoke(request(11,21)));awaitWaiters(1);
            failure(waiting.get(6,TimeUnit.SECONDS),503,"TEMPORARILY_UNAVAILABLE");
            assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
        }
    }
    @Test void timeIsRevalidatedAfterWaitingForDealershipLock() throws Exception {
        clock.now.set(Instant.parse("2030-01-10T08:59:00Z"));double before=successes();
        try(var blocker=blocker("dealerships",21)) {
            Future<Outcome> waiting=workers.submit(()->invoke(request(11,21)));awaitWaiters(1);
            clock.now.set(Instant.parse("2030-01-10T09:00:00Z"));blocker.commit();
            failure(waiting.get(5,TimeUnit.SECONDS),422,"INVALID_BOOKING_TIME");
            assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
        }
    }
    @Test void failureDuringCommitRollsBackAndDoesNotPublishSuccess() {
        inject("23514","injected_unexpected_check",true);double before=successes();
        failure(invoke(request(11,21)),500,"INTERNAL_ERROR");assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
    }
    @ParameterizedTest
    @CsvSource({"appointments_no_bay_overlap,BOOKING_CONFLICT","appointments_no_technician_overlap,BOOKING_CONFLICT","appointments_no_vehicle_overlap,VEHICLE_UNAVAILABLE"})
    void recognizedPostgresExclusionNamesMapToDomainConflict(String constraint,String expectedCode) {
        inject("23P01",constraint,false);double before=successes();
        failure(invoke(request(11,21)),409,expectedCode);assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
    }
    @Test void unknownExclusionConstraintIsNotDisguisedAsCapacityConflict() {
        inject("23P01","unknown_constraint",false);failure(invoke(request(11,21)),500,"INTERNAL_ERROR");assertThat(rowCount()).isZero();
    }
    @Test void exclusionErrorWithoutConstraintDiagnosticIsInternalError() {
        jdbc.execute("CREATE FUNCTION injected_booking_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Unnamed database exclusion failure' USING ERRCODE='23P01'; END $$");
        jdbc.execute("CREATE TRIGGER injected_failure AFTER INSERT ON appointments FOR EACH ROW EXECUTE FUNCTION injected_booking_failure()");
        double before=successes();failure(invoke(request(11,21)),500,"INTERNAL_ERROR");
        assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
    }
    @Test void quotedKnownNamesInUnknownConstraintMessageDoNotCreateFalseConflict() {
        jdbc.execute("CREATE FUNCTION injected_booking_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Unexpected failure mentioning \"appointments_no_bay_overlap\"' USING ERRCODE='23P01', CONSTRAINT='unknown_constraint', DETAIL='\"appointments_no_vehicle_overlap\"'; END $$");
        jdbc.execute("CREATE TRIGGER injected_failure AFTER INSERT ON appointments FOR EACH ROW EXECUTE FUNCTION injected_booking_failure()");
        double before=successes();failure(invoke(request(11,21)),500,"INTERNAL_ERROR");
        assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
    }
    @ParameterizedTest @CsvSource({"57014,query_cancelled","40P01,deadlock_detected"})
    void transientDatabaseErrorsRollBackAsUnavailable(String state,String constraint) {
        inject(state,constraint,false);double before=successes();
        failure(invoke(request(11,21)),503,"TEMPORARILY_UNAVAILABLE");assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
    }
    @Test void serviceCommitDoesNotJoinAnAmbientTransaction() {
        new TransactionTemplate(transactions).execute(status->{
            booking.book(request(11,21));status.setRollbackOnly();
            try(var connection=independent();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM appointments")) {
                rows.next();assertThat(rows.getInt(1)).isEqualTo(1);
            }catch(SQLException failure){throw new AssertionError(failure);}
            return null;
        });
        assertThat(rowCount()).isEqualTo(1);
    }
    @Test void missingCustomerIsNotReportedAsOwnershipMismatch() {
        AvailabilityRequest invalid=new AvailabilityRequest(id(999),id(11),id(21),id(31),request(11,21).startsAt());
        failure(invoke(invalid),404,"RESOURCE_NOT_FOUND");assertThat(rowCount()).isZero();
    }
    @Test void missingServiceTakesPrecedenceOverExistingCustomerOwnershipMismatch() {
        jdbc.execute("INSERT INTO customers VALUES ('"+id(2)+"','Other')");
        AvailabilityRequest invalid=new AvailabilityRequest(id(2),id(11),id(21),id(999),request(11,21).startsAt());
        failure(invoke(invalid),404,"RESOURCE_NOT_FOUND");assertThat(rowCount()).isZero();
    }
    @Test void actualStatementTimeoutCancelsSlowInsertAndRollsBack() {
        jdbc.execute("CREATE FUNCTION injected_booking_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(10); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER injected_failure BEFORE INSERT ON appointments FOR EACH ROW EXECUTE FUNCTION injected_booking_failure()");
        double before=successes();failure(invoke(request(11,21)),503,"TEMPORARILY_UNAVAILABLE");
        assertThat(rowCount()).isZero();assertThat(successes()).isEqualTo(before);
    }
    void inject(String state,String constraint,boolean deferred) {
        jdbc.execute("CREATE FUNCTION injected_booking_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test database failure' USING ERRCODE='"+state+"', CONSTRAINT='"+constraint+"'; END $$");
        jdbc.execute("CREATE "+(deferred?"CONSTRAINT ":"")+"TRIGGER injected_failure AFTER INSERT ON appointments "+(deferred?"DEFERRABLE INITIALLY DEFERRED ":"")+"FOR EACH ROW EXECUTE FUNCTION injected_booking_failure()");
    }
}
