package com.keyloop.scheduler;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import java.time.Duration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SchemaConstraintsIT {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
            Objects.requireNonNull(System.getProperty("postgres.image"), "postgres.image is required")))
            .withEnv("POSTGRES_DB", "schema_test").withEnv("POSTGRES_USER", "schema_test")
            .withEnv("POSTGRES_PASSWORD", "isolated-test-password").withExposedPorts(5432)
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                    .withStrategy(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(90)));
    static Flyway flyway;
    Connection connection;

    @BeforeAll static void migrate() {
        flyway = Flyway.configure().dataSource(url(), "schema_test", "isolated-test-password")
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    static String url() { return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/schema_test"; }
    @BeforeEach void fixtures() throws Exception {
        connection = DriverManager.getConnection(url(), "schema_test", "isolated-test-password");
        sql("TRUNCATE customers,dealerships,service_types CASCADE");
        sql("INSERT INTO customers(id,display_name) VALUES ('"+id(1)+"','Owner'),('"+id(2)+"','Other')");
        sql("INSERT INTO vehicles(id,customer_id,vin,make,model) VALUES ('"+id(11)+"','"+id(1)+"','VIN01','Make','Model'),('"+id(12)+"','"+id(1)+"','VIN02','Make','Model'),('"+id(13)+"','"+id(2)+"','VIN03','Make','Model')");
        sql("INSERT INTO dealerships(id,name,time_zone,active) VALUES ('"+id(21)+"','One','Asia/Ho_Chi_Minh',true),('"+id(22)+"','Two','Asia/Ho_Chi_Minh',true)");
        sql("INSERT INTO service_types(id,name,duration_minutes,active) VALUES ('"+id(31)+"','Standard',60,true),('"+id(32)+"','Other dealer',60,true),('"+id(33)+"','Specialist',90,true)");
        sql("INSERT INTO dealership_services VALUES ('"+id(21)+"','"+id(31)+"'),('"+id(21)+"','"+id(33)+"'),('"+id(22)+"','"+id(31)+"'),('"+id(22)+"','"+id(32)+"')");
        for(int i=1;i<=3;i++) {
            int dealer=i==3?22:21;
            sql("INSERT INTO service_bays(id,dealership_id,name,active) VALUES ('"+id(40+i)+"','"+id(dealer)+"','Bay "+i+"',true)");
            sql("INSERT INTO technicians(id,dealership_id,display_name,active) VALUES ('"+id(50+i)+"','"+id(dealer)+"','Tech "+i+"',true)");
            sql("INSERT INTO technician_qualifications VALUES ('"+id(50+i)+"','"+id(31)+"'),('"+id(50+i)+"','"+id(32)+"')");
        }
    }
    @AfterEach void close() throws Exception { connection.close(); }
    static String id(int n) { return String.format("00000000-0000-4000-8000-%012d",n); }
    void sql(String sql) throws SQLException { try(var s=connection.createStatement()) { s.execute(sql); } }
    long count(String table) throws SQLException { try(var s=connection.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)) { r.next();return r.getLong(1); } }
    String appointment(int vehicle,int bay,int technician,String start,String end,int duration) {
        return "INSERT INTO appointments(id,customer_id,vehicle_id,dealership_id,service_type_id,technician_id,service_bay_id,starts_at,ends_at,duration_minutes,status) VALUES ('"+UUID.randomUUID()+"','"+id(1)+"','"+id(vehicle)+"','"+id(21)+"','"+id(31)+"','"+id(technician)+"','"+id(bay)+"','"+start+"','"+end+"',"+duration+",'CONFIRMED')";
    }
    String base() { return appointment(11,41,51,"2030-01-10T09:00:00Z","2030-01-10T10:00:00Z",60); }
    void rejects(String statement,String state,String constraint) {
        SQLException error=catchThrowableOfType(SQLException.class,()->sql(statement));
        assertThat((Object) error).isNotNull();
        assertThat(error.getSQLState()).isEqualTo(state);
        if(constraint!=null) assertThat(error.getMessage()).contains(constraint);
    }

    @Test void schemaMigratesWithoutAutomaticallySeedingDemoData() throws Exception {
        // Clear reference fixtures to inspect the intentionally explicit seed lifecycle.
        sql("TRUNCATE customers,dealerships,service_types CASCADE");
        for(String table:List.of("customers","vehicles","dealerships","service_types","dealership_services","service_bays","technicians","technician_qualifications","dealership_opening_windows","technician_shifts","appointments")) assertThat(count(table)).isZero();
        assertThat(flyway.info().applied()).hasSize(1);
        flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @CsvSource({"bay,09:00,10:00,60","bay,08:30,09:30,60","bay,09:30,10:30,60","bay,08:00,11:00,180",
        "technician,09:00,10:00,60","technician,08:30,09:30,60","technician,09:30,10:30,60","technician,08:00,11:00,180",
        "vehicle,09:00,10:00,60","vehicle,08:30,09:30,60","vehicle,09:30,10:30,60","vehicle,08:00,11:00,180"})
    void rejectsEachOverlapIndependently(String resource,String start,String end,int duration) throws Exception {
        sql(base());
        int vehicle=resource.equals("vehicle")?11:12;
        int bay=resource.equals("bay")?41:42;
        int tech=resource.equals("technician")?51:52;
        rejects(appointment(vehicle,bay,tech,"2030-01-10T"+start+":00Z","2030-01-10T"+end+":00Z",duration),"23P01","appointments_no_"+resource+"_overlap");
        assertThat(count("appointments")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"bay","technician","vehicle"})
    void permitsAdjacentIntervals(String resource) throws Exception {
        sql(base());
        sql(appointment(resource.equals("vehicle")?11:12,resource.equals("bay")?41:42,resource.equals("technician")?51:52,"2030-01-10T10:00:00Z","2030-01-10T11:00:00Z",60));
        assertThat(count("appointments")).isEqualTo(2);
    }
    @Test void appointmentKeepsDurationSnapshotWhenServiceDefaultChanges() throws Exception {
        sql(base()); sql("UPDATE service_types SET duration_minutes=90 WHERE id='"+id(31)+"'");
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT duration_minutes,EXTRACT(EPOCH FROM ends_at-starts_at)/60 FROM appointments")) {
            assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(60);assertThat(r.getInt(2)).isEqualTo(60);
        }
    }
    @ParameterizedTest
    @CsvSource({"vehicle_customer,11,41,51,31,appointments_vehicle_customer_fk",
        "bay_dealership,11,43,51,31,appointments_bay_dealership_fk",
        "technician_dealership,11,41,53,31,appointments_technician_dealership_fk",
        "dealership_service,11,41,51,32,appointments_dealership_service_fk",
        "technician_qualification,11,41,51,33,appointments_technician_qualification_fk"})
    void rejectsInvalidAppointmentRelationships(String kind,int vehicle,int bay,int tech,int service,String constraint) throws Exception {
        String statement=appointment(vehicle,bay,tech,"2030-01-10T09:00:00Z","2030-01-10T10:00:00Z",60);
        if(kind.equals("vehicle_customer")) statement=statement.replace("'"+id(1)+"'","'"+id(2)+"'");
        if(service!=31) statement=statement.replace("'"+id(31)+"'","'"+id(service)+"'");
        rejects(statement,"23503",constraint);
    }
    @ParameterizedTest
    @CsvSource({"zero,2030-01-10T09:00:00Z,2030-01-10T09:00:00Z,60,appointments_duration_matches_interval",
        "duration,2030-01-10T09:00:00Z,2030-01-10T10:00:00Z,30,appointments_duration_matches_interval",
        "negative_duration,2030-01-10T09:00:00Z,2030-01-10T10:00:00Z,-1,appointments_duration_matches_interval",
        "infinite,infinity,infinity,60,appointments_positive_finite_interval"})
    void rejectsInvalidAppointmentIntervals(String kind,String start,String end,int duration,String constraint) {
        rejects(appointment(11,41,51,start,end,duration),"23514",constraint);
    }
    @Test void rejectsNullMandatoryField() { rejects(base().replace("'"+id(11)+"'","NULL"),"23502",null); }
    @Test void rejectsUnsupportedStatus() { rejects(base().replace("'CONFIRMED'","'CANCELLED'"),"23514","appointments_confirmed_status"); }
    @Test void rejectsInfiniteCreationTime() { rejects(base().replace("status)","status,created_at)").replace("'CONFIRMED')","'CONFIRMED','infinity')"),"23514","appointments_finite_created_at"); }
    @Test void rejectsNonPositiveServiceDuration() { rejects("UPDATE service_types SET duration_minutes=0 WHERE id='"+id(31)+"'","23514","service_types_positive_duration"); }
    @ParameterizedTest @ValueSource(strings={"dealership_opening_windows","technician_shifts"})
    void rejectsInvalidScheduleIntervals(String table) {
        String ownerColumn=table.equals("technician_shifts")?"technician_id":"dealership_id";
        String owner=id(table.equals("technician_shifts")?51:21);
        String constraint=table.equals("technician_shifts")?"technician_shifts_positive_finite_interval":"opening_windows_positive_finite_interval";
        rejects("INSERT INTO "+table+"(id,"+ownerColumn+",starts_at,ends_at) VALUES ('"+UUID.randomUUID()+"','"+owner+"','2030-01-10T09:00:00Z','infinity')","23514",constraint);
        rejects("INSERT INTO "+table+"(id,"+ownerColumn+",starts_at,ends_at) VALUES ('"+UUID.randomUUID()+"','"+owner+"','2030-01-10T09:00:00Z','2030-01-10T09:00:00Z')","23514",constraint);
    }
    @Test void preservesReferencesAndAppointmentsAcrossTimezoneIndependentSeedReruns() throws Exception {
        sql("TRUNCATE customers,dealerships,service_types CASCADE");
        String seed=Files.readString(Path.of("scripts/demo/seed.sql"));
        sql("SET TIME ZONE 'UTC'"); sql(seed);
        List<String> tables=List.of("customers","vehicles","dealerships","service_types","dealership_services","service_bays","technicians","technician_qualifications","dealership_opening_windows","technician_shifts");
        Map<String,Long> before=new LinkedHashMap<>(); for(String table:tables) before.put(table,count(table));
        assertThat(count("dealerships")).isEqualTo(2);assertThat(count("service_types")).isEqualTo(3);
        assertThat(count("appointments")).isZero();
        sql("INSERT INTO appointments(id,customer_id,vehicle_id,dealership_id,service_type_id,technician_id,service_bay_id,starts_at,ends_at,duration_minutes,status) VALUES ('00000000-0000-4000-8000-000000009999','10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001','60000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000001','2030-01-10T09:00:00Z','2030-01-10T09:30:00Z',30,'CONFIRMED')");
        String windowIds = ids("dealership_opening_windows");
        String shiftIds = ids("technician_shifts");
        sql("SET TIME ZONE 'America/New_York'");sql(seed);sql(seed);
        assertThat(ids("dealership_opening_windows")).isEqualTo(windowIds);
        assertThat(ids("technician_shifts")).isEqualTo(shiftIds);
        for(String table:tables) assertThat(count(table)).as(table+" repeat count").isEqualTo(before.get(table));
        assertThat(count("appointments")).isEqualTo(1);
        assertThat(scalar("SELECT count(DISTINCT duration_minutes) FROM service_types")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM technician_qualifications WHERE service_type_id='40000000-0000-4000-8000-000000000003'")).isEqualTo(1);
        // Each open local date has morning and afternoon windows; Sunday is deliberately absent.
        assertThat(scalar("SELECT count(*) FROM dealership_opening_windows WHERE extract(isodow FROM starts_at AT TIME ZONE 'Asia/Ho_Chi_Minh')=7")).isZero();
        assertThat(scalar("SELECT count(*) FROM dealership_opening_windows WHERE (starts_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time NOT IN (time '08:00',time '13:00') OR (ends_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time NOT IN (time '12:00',time '17:00')")).isZero();
        assertThat(scalar("SELECT count(*) FROM (SELECT dealership_id,(starts_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date FROM dealership_opening_windows GROUP BY 1,2 HAVING count(*)<>2) bad")).isZero();
        long openDays=scalar("SELECT count(*) FROM generate_series(0,44) n WHERE extract(isodow FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+n))<>7");
        assertThat(count("dealership_opening_windows")).isEqualTo(openDays*2*2);
        assertThat(count("technician_shifts")).isEqualTo(openDays*2*3);
        assertThat(scalar("SELECT count(*) FROM technician_shifts s JOIN technicians t ON t.id=s.technician_id WHERE NOT EXISTS(SELECT 1 FROM dealership_opening_windows w WHERE w.dealership_id=t.dealership_id AND w.starts_at=s.starts_at AND w.ends_at=s.ends_at)")).isZero();
    }
    @ParameterizedTest
    @CsvSource({"vehicles,appointments_vehicle_customer_fk", "service_bays,appointments_bay_dealership_fk", "technicians,appointments_technician_dealership_fk", "dealership_services,appointments_dealership_service_fk", "technician_qualifications,appointments_technician_qualification_fk"})
    void restrictsMutationOfAppointmentReferences(String table,String constraint) throws Exception {
        sql(base());
        String where=switch(table) {
            case "vehicles" -> "id='"+id(11)+"'";
            case "service_bays" -> "id='"+id(41)+"'";
            case "technicians" -> "id='"+id(51)+"'";
            case "dealership_services" -> "dealership_id='"+id(21)+"' AND service_type_id='"+id(31)+"'";
            default -> "technician_id='"+id(51)+"' AND service_type_id='"+id(31)+"'";
        };
        String mutation=table.equals("technicians")
                ? "UPDATE technicians SET dealership_id='"+id(22)+"' WHERE "+where
                : "DELETE FROM "+table+" WHERE "+where;
        rejects(mutation,"23503",constraint);
        assertThat(count("appointments")).isEqualTo(1);
    }
    String ids(String table) throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery("SELECT string_agg(id::text,',' ORDER BY id) FROM "+table)) {r.next();return r.getString(1);}}
    long scalar(String query) throws SQLException { try(var s=connection.createStatement();var r=s.executeQuery(query)) {r.next();return r.getLong(1);} }
}
