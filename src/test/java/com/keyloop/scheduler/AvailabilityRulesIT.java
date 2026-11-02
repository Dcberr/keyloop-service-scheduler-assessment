package com.keyloop.scheduler;

import com.keyloop.scheduler.availability.*;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.shared.BusinessException;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AvailabilityRulesIT.FixedTime.class)
@Testcontainers
class AvailabilityRulesIT {
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    @Container static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse(
            java.util.Objects.requireNonNull(System.getProperty("postgres.image"))))
            .withEnv("POSTGRES_DB","availability_test").withEnv("POSTGRES_USER","test").withEnv("POSTGRES_PASSWORD","test-password")
            .withExposedPorts(5432).waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2))
                    .withStrategy(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(90)));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/availability_test");
        r.add("spring.datasource.username",()->"test");r.add("spring.datasource.password",()->"test-password");
    }
    @TestConfiguration static class FixedTime { @Bean @Primary Clock testClock(){return Clock.fixed(NOW,ZoneOffset.UTC);} }
    @Autowired JdbcTemplate jdbc;
    @Autowired AvailabilityService availability;
    @Autowired CatalogService catalog;
    static UUID id(int n){return UUID.fromString(String.format("00000000-0000-4000-8000-%012d",n));}
    void sql(String query){jdbc.execute(query);}
    @BeforeEach void fixtures() {
        sql("TRUNCATE customers,dealerships,service_types CASCADE");
        sql("INSERT INTO customers VALUES ('"+id(1)+"','Owner'),('"+id(2)+"','Other')");
        for(int i=11;i<=13;i++) sql("INSERT INTO vehicles VALUES ('"+id(i)+"','"+id(i==13?2:1)+"','VIN"+i+"','Make','Model')");
        sql("INSERT INTO dealerships VALUES ('"+id(21)+"','One','Asia/Ho_Chi_Minh',true),('"+id(22)+"','Two','UTC',true),('"+id(23)+"','Inactive','UTC',false)");
        sql("INSERT INTO service_types VALUES ('"+id(31)+"','Standard',60,true),('"+id(32)+"','Other dealer',30,true),('"+id(33)+"','Inactive',60,false)");
        sql("INSERT INTO dealership_services VALUES ('"+id(21)+"','"+id(31)+"'),('"+id(21)+"','"+id(33)+"'),('"+id(22)+"','"+id(31)+"'),('"+id(22)+"','"+id(32)+"')");
        for(int i=1;i<=4;i++) {
            int dealer=i==3?22:21;boolean active=i!=4;
            sql("INSERT INTO service_bays VALUES ('"+id(40+i)+"','"+id(dealer)+"','Bay"+i+"',"+active+")");
            sql("INSERT INTO technicians VALUES ('"+id(50+i)+"','"+id(dealer)+"','Tech"+i+"',"+active+")");
            sql("INSERT INTO technician_qualifications VALUES ('"+id(50+i)+"','"+id(31)+"')");
            sql("INSERT INTO technician_shifts VALUES ('"+id(70+i)+"','"+id(50+i)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
        }
        sql("INSERT INTO dealership_opening_windows VALUES ('"+id(81)+"','"+id(21)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z'),('"+id(82)+"','"+id(22)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
    }
    AvailabilityRequest request(){return new AvailabilityRequest(id(1),id(11),id(21),id(31),"2030-01-10T09:00:00Z");}
    AvailabilityResult check(){return availability.check(request());}
    void reason(String value){AvailabilityResult result=check();assertThat(result.available()).isFalse();assertThat(result.reason().name()).isEqualTo(value);}
    long appointments(){return jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class);}
    void appointment(int vehicle,int dealer,int bay,int tech,String start,String end,int duration){
        sql("INSERT INTO appointments(id,customer_id,vehicle_id,dealership_id,service_type_id,technician_id,service_bay_id,starts_at,ends_at,duration_minutes) VALUES ('"+UUID.randomUUID()+"','"+id(vehicle==13?2:1)+"','"+id(vehicle)+"','"+id(dealer)+"','"+id(31)+"','"+id(tech)+"','"+id(bay)+"','2030-01-10T"+start+":00Z','2030-01-10T"+end+":00Z',"+duration+")");
    }
    @Test void validRequestReturnsCalculatedUtcIntervalWithoutReserving() {
        AvailabilityResult result=check();assertThat(result.available()).isTrue();assertThat(result.reason()).isNull();
        assertThat(result.startsAt()).isEqualTo(Instant.parse("2030-01-10T09:00:00Z"));
        assertThat(result.endsAt()).isEqualTo(Instant.parse("2030-01-10T10:00:00Z"));
        assertThat(result.durationMinutes()).isEqualTo(60);assertThat(result.dealershipTimeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(result.checkedAt()).isEqualTo(NOW);check();assertThat(appointments()).isZero();
    }
    @Test void internalCandidatesAreStableWhilePublicResultContainsNoAssignments(){
        var assessment=availability.assess(request());
        assertThat(assessment.serviceBayId()).isEqualTo(id(41));
        assertThat(assessment.technicianId()).isEqualTo(id(51));
        assertThat(appointments()).isZero();
    }
    @Test void exactOpeningAndShiftEndIsAllowed(){
        sql("UPDATE dealership_opening_windows SET ends_at='2030-01-10T10:00:00Z' WHERE dealership_id='"+id(21)+"'");
        sql("UPDATE technician_shifts SET ends_at='2030-01-10T10:00:00Z' WHERE technician_id IN ('"+id(51)+"','"+id(52)+"')");
        assertThat(check().available()).isTrue();
    }
    @Test void equivalentOffsetsProduceSameAnswer(){
        AvailabilityResult result=availability.check(new AvailabilityRequest(id(1),id(11),id(21),id(31),"2030-01-10T16:00:00+07:00"));
        assertThat(result.startsAt()).isEqualTo(check().startsAt());assertThat(result.available()).isTrue();
    }
    @ParameterizedTest @CsvSource({"08:30,09:30,60","09:30,10:30,60","09:00,10:00,60","08:00,11:00,180"})
    void fullBayOverlapShapesPreventAvailability(String start,String end,int duration){
        appointment(12,21,41,51,start,end,duration);appointment(13,21,42,52,start,end,duration);reason("NO_AVAILABLE_BAY");
    }
    @Test void technicianConflictsMatterEvenWhenABayIsFree(){
        appointment(12,21,44,51,"09:30","10:30",60);appointment(13,21,42,52,"09:30","10:30",60);reason("NO_AVAILABLE_TECHNICIAN");
    }
    @Test void adjacentAppointmentsPermitReuse(){
        appointment(12,21,41,51,"08:00","09:00",60);appointment(13,21,42,52,"10:00","11:00",60);assertThat(check().available()).isTrue();
    }
    @Test void bayAtAnotherDealerAndInactiveBayCannotReplaceLocalBays(){sql("UPDATE service_bays SET active=false WHERE dealership_id='"+id(21)+"'");reason("NO_AVAILABLE_BAY");}
    @Test void unqualifiedOtherDealerAndInactiveTechniciansCannotBeAssigned(){sql("DELETE FROM technician_qualifications WHERE technician_id IN ('"+id(51)+"','"+id(52)+"')");reason("NO_AVAILABLE_TECHNICIAN");}
    @Test void inactiveTechniciansAreExcluded(){sql("UPDATE technicians SET active=false WHERE dealership_id='"+id(21)+"'");reason("NO_AVAILABLE_TECHNICIAN");}
    @Test void missingShiftMeansUnavailable(){sql("DELETE FROM technician_shifts WHERE technician_id IN ('"+id(51)+"','"+id(52)+"')");reason("NO_AVAILABLE_TECHNICIAN");}
    @Test void openingMustCoverFullService(){sql("UPDATE dealership_opening_windows SET ends_at='2030-01-10T09:30:00Z' WHERE dealership_id='"+id(21)+"'");reason("OUTSIDE_OPENING_HOURS");}
    @Test void shiftsMustCoverFullService(){sql("UPDATE technician_shifts SET ends_at='2030-01-10T09:30:00Z' WHERE technician_id IN ('"+id(51)+"','"+id(52)+"')");reason("NO_AVAILABLE_TECHNICIAN");}
    @Test void separateOpeningWindowsAcrossBreakAreNotJoined(){
        sql("UPDATE dealership_opening_windows SET ends_at='2030-01-10T09:30:00Z' WHERE dealership_id='"+id(21)+"'");
        sql("INSERT INTO dealership_opening_windows VALUES ('"+id(83)+"','"+id(21)+"','2030-01-10T09:45:00Z','2030-01-10T18:00:00Z')");reason("OUTSIDE_OPENING_HOURS");
    }
    @Test void separateShiftsAcrossBreakAreNotJoined(){
        sql("UPDATE technician_shifts SET ends_at='2030-01-10T09:30:00Z' WHERE technician_id IN ('"+id(51)+"','"+id(52)+"')");
        for(int tech: new int[]{51,52})sql("INSERT INTO technician_shifts VALUES ('"+UUID.randomUUID()+"','"+id(tech)+"','2030-01-10T09:45:00Z','2030-01-10T18:00:00Z')");reason("NO_AVAILABLE_TECHNICIAN");
    }
    @Test void absentOpeningMeansClosed(){sql("DELETE FROM dealership_opening_windows WHERE dealership_id='"+id(21)+"'");reason("OUTSIDE_OPENING_HOURS");}
    @Test void vehicleConflictIsGlobalAcrossDealerships(){appointment(11,22,43,53,"09:00","10:00",60);reason("VEHICLE_UNAVAILABLE");}
    @Test void openingReasonHasPriorityOverVehicleAndResources(){
        appointment(11,22,43,53,"09:00","10:00",60);sql("DELETE FROM dealership_opening_windows WHERE dealership_id='"+id(21)+"'");sql("UPDATE service_bays SET active=false");reason("OUTSIDE_OPENING_HOURS");
    }
    @Test void vehicleReasonHasPriorityOverResourceShortages(){appointment(11,22,43,53,"09:00","10:00",60);sql("UPDATE service_bays SET active=false");sql("UPDATE technicians SET active=false");reason("VEHICLE_UNAVAILABLE");}
    @Test void bayReasonHasPriorityOverTechnicianShortage(){sql("UPDATE service_bays SET active=false");sql("UPDATE technicians SET active=false");reason("NO_AVAILABLE_BAY");}
    @Test void checkReflectsLaterCommittedResourceChangesWithoutHoldingResources(){
        assertThat(check().available()).isTrue();appointment(12,21,41,51,"09:00","10:00",60);appointment(13,21,42,52,"09:00","10:00",60);reason("NO_AVAILABLE_BAY");assertThat(appointments()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"customer","vehicle","dealer","service"})
    void missingReferenceReturns404(String missing){
        AvailabilityRequest r=new AvailabilityRequest(id(missing.equals("customer")?999:1),id(missing.equals("vehicle")?999:11),id(missing.equals("dealer")?999:21),id(missing.equals("service")?999:31),request().startsAt());
        error(()->availability.check(r),404,"RESOURCE_NOT_FOUND");
    }
    @Test void wrongVehicleOwnerReturns422(){error(()->availability.check(new AvailabilityRequest(id(2),id(11),id(21),id(31),request().startsAt())),422,"VEHICLE_CUSTOMER_MISMATCH");}
    @Test void inactiveDealerReturns422(){sql("UPDATE dealerships SET active=false WHERE id='"+id(21)+"'");error(()->check(),422,"SERVICE_NOT_BOOKABLE");}
    @Test void inactiveServiceReturns422(){error(()->availability.check(new AvailabilityRequest(id(1),id(11),id(21),id(33),request().startsAt())),422,"SERVICE_NOT_BOOKABLE");}
    @Test void serviceNotOfferedReturns422(){error(()->availability.check(new AvailabilityRequest(id(1),id(11),id(21),id(32),request().startsAt())),422,"SERVICE_NOT_BOOKABLE");}
    @Test void catalogPagesAreStableAndFilterInactiveEntities(){
        var page=catalog.dealerships(0,1);assertThat(page.totalElements()).isEqualTo(2);assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().id()).isEqualTo(id(21));
        assertThat(catalog.dealerships(1,1).items().getFirst().id()).isEqualTo(id(22));assertThat(catalog.dealerships(2,1).items()).isEmpty();
        assertThat(catalog.services(id(21),0,20).items()).hasSize(1);
        assertThat(catalog.customers(0,20).items()).hasSize(2);assertThat(catalog.vehicles(id(1),0,20).items()).hasSize(2);
    }
    @ParameterizedTest @CsvSource({"-1,20","0,0","0,101"}) void invalidPaginationReturns400(int page,int size){error(()->catalog.dealerships(page,size),400,"INVALID_REQUEST");}
    @Test void catalogValidatesParentExistenceAndInactiveDealer(){
        error(()->catalog.services(id(999),0,20),404,"RESOURCE_NOT_FOUND");error(()->catalog.vehicles(id(999),0,20),404,"RESOURCE_NOT_FOUND");error(()->catalog.services(id(23),0,20),422,"SERVICE_NOT_BOOKABLE");
        sql("INSERT INTO customers VALUES ('"+id(3)+"','No vehicles')");assertThat(catalog.vehicles(id(3),0,20).items()).isEmpty();
    }
    private static void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int status,String code){
        BusinessException failure=catchThrowableOfType(BusinessException.class,action);assertThat(failure).isNotNull();assertThat(failure.status()).isEqualTo(status);assertThat(failure.code()).isEqualTo(code);
    }
}
