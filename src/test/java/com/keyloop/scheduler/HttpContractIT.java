package com.keyloop.scheduler;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpContractIT.FixedTime.class)
@Testcontainers
class HttpContractIT {
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final List<Map<String,Object>> SAMPLES=Collections.synchronizedList(new ArrayList<>());
    @Container static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse(
            Objects.requireNonNull(System.getProperty("postgres.image"))))
            .withEnv("POSTGRES_DB","http_test").withEnv("POSTGRES_USER","test").withEnv("POSTGRES_PASSWORD","test-password")
            .withExposedPorts(5432).waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2))
                    .withStrategy(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(90)));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",HttpContractIT::url);r.add("spring.datasource.username",()->"test");r.add("spring.datasource.password",()->"test-password");
    }
    static String url(){return "jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/http_test";}
    @TestConfiguration static class FixedTime{@Bean @Primary Clock testClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static String id(int n){return String.format("00000000-0000-4000-8000-%012d",n);}
    void sql(String q){jdbc.execute(q);}
    @BeforeEach void fixtures(){
        sql("DROP TRIGGER IF EXISTS http_commit_failure ON appointments");sql("DROP FUNCTION IF EXISTS http_commit_failure_fn()");
        sql("TRUNCATE customers,dealerships,service_types CASCADE");
        sql("INSERT INTO customers VALUES ('"+id(1)+"','Owner'),('"+id(2)+"','Other'),('"+id(3)+"','No vehicles')");
        for(int n=11;n<=13;n++)sql("INSERT INTO vehicles VALUES ('"+id(n)+"','"+id(n==13?2:1)+"','VIN"+n+"','Make','Model')");
        sql("INSERT INTO dealerships VALUES ('"+id(21)+"','One','UTC',true),('"+id(22)+"','Two','UTC',true),('"+id(23)+"','Inactive','UTC',false)");
        sql("INSERT INTO service_types VALUES ('"+id(31)+"','Standard',60,true),('"+id(32)+"','Other',30,true),('"+id(33)+"','Inactive',60,false)");
        sql("INSERT INTO dealership_services VALUES ('"+id(21)+"','"+id(31)+"'),('"+id(21)+"','"+id(33)+"'),('"+id(22)+"','"+id(31)+"'),('"+id(22)+"','"+id(32)+"')");
        for(int n=1;n<=3;n++){
            int dealer=n==3?22:21;
            sql("INSERT INTO service_bays VALUES ('"+id(40+n)+"','"+id(dealer)+"','Bay',true)");
            sql("INSERT INTO technicians VALUES ('"+id(50+n)+"','"+id(dealer)+"','Tech',true)");
            sql("INSERT INTO technician_qualifications VALUES ('"+id(50+n)+"','"+id(31)+"')");
            sql("INSERT INTO technician_shifts VALUES ('"+id(70+n)+"','"+id(50+n)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
        }
        for(int dealer:new int[]{21,22})sql("INSERT INTO dealership_opening_windows VALUES ('"+id(dealer+60)+"','"+id(dealer)+"','2030-01-10T08:00:00Z','2030-01-10T18:00:00Z')");
    }
    @AfterAll static void exportSamples()throws Exception{
        Path path=Path.of("target/m5-http-contract-samples.json");Files.createDirectories(path.getParent());Files.writeString(path,JSON.writeValueAsString(SAMPLES));
    }
    record Reply(int status,Map<String,Object> body,HttpHeaders headers){}
    Reply send(String method,String path,String body,String requestId,String contentType)throws Exception{return send(method,path,body,requestId,contentType,null);}
    @SuppressWarnings("unchecked") Reply send(String method,String path,String body,String requestId,String contentType,String accept)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(12));
        if(requestId!=null)builder.header("X-Request-ID",requestId);
        if(accept!=null)builder.header("Accept",accept);
        if(body!=null)builder.header("Content-Type",contentType);
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        Map<String,Object> parsed=JSON.readValue(response.body(),Map.class);
        Map<String,Object> sample=new LinkedHashMap<>();sample.put("method",method.toLowerCase(Locale.ROOT));sample.put("path",path.split("\\?",2)[0]);sample.put("status",response.statusCode());sample.put("body",parsed);sample.put("headers",response.headers().map());SAMPLES.add(sample);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        return new Reply(response.statusCode(),parsed,response.headers());
    }
    Reply get(String path)throws Exception{return send("GET",path,null,null,null);}
    Reply post(String body)throws Exception{return send("POST","/api/v1/appointments",body,null,"application/json");}
    String body(int vehicle){return body(1,vehicle,21,31,"2030-01-10T09:00:00Z");}
    String body(int customer,int vehicle,int dealer,int service,String time){return "{\"customerId\":\""+id(customer)+"\",\"vehicleId\":\""+id(vehicle)+"\",\"dealershipId\":\""+id(dealer)+"\",\"serviceTypeId\":\""+id(service)+"\",\"startsAt\":\""+time+"\"}";}
    String availability(String time){return "/api/v1/availability?customerId="+id(1)+"&vehicleId="+id(11)+"&dealershipId="+id(21)+"&serviceTypeId="+id(31)+"&startsAt="+URLEncoder.encode(time,StandardCharsets.UTF_8);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Reply r){return (List<Map<String,Object>>)r.body().get("items");}
    void problem(Reply r,int status,String code){
        assertThat(r.status()).isEqualTo(status);assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.body()).containsKeys("type","title","status","detail","instance","code","requestId");
        assertThat(((Number)r.body().get("status")).intValue()).isEqualTo(status);assertThat(r.body().get("code")).isEqualTo(code);
        assertThat(r.body().get("requestId")).isEqualTo(r.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(r.body().toString()).doesNotContain("SQLException","jdbc:postgresql","INSERT INTO","stackTrace","http_commit_failure_fn");
    }
    @Test void allFourCatalogEndpointsExposeStablePagesAndFilters()throws Exception{
        Reply dealers=get("/api/v1/dealerships?size=1");assertThat(dealers.status()).isEqualTo(200);assertThat(items(dealers)).hasSize(1);assertThat(items(dealers).getFirst().get("id")).isEqualTo(id(21));assertThat(((Number)dealers.body().get("totalElements")).intValue()).isEqualTo(2);
        assertThat(items(get("/api/v1/dealerships?page=1&size=1")).getFirst().get("id")).isEqualTo(id(22));
        Reply empty=get("/api/v1/dealerships?page=2147483647&size=100");assertThat(items(empty)).isEmpty();assertThat(((Number)empty.body().get("totalElements")).intValue()).isEqualTo(2);
        Reply services=get("/api/v1/dealerships/"+id(21)+"/services");assertThat(services.status()).isEqualTo(200);assertThat(items(services)).hasSize(1);assertThat(items(services).getFirst().get("durationMinutes")).isEqualTo(60);
        assertThat(items(get("/api/v1/customers"))).hasSize(3);
        assertThat(items(get("/api/v1/customers/"+id(1)+"/vehicles"))).hasSize(2);
        assertThat(items(get("/api/v1/customers/"+id(3)+"/vehicles"))).isEmpty();
    }
    @Test void catalogParentErrorsAndPaginationMatchContract()throws Exception{
        problem(get("/api/v1/dealerships/"+id(999)+"/services"),404,"RESOURCE_NOT_FOUND");problem(get("/api/v1/customers/"+id(999)+"/vehicles"),404,"RESOURCE_NOT_FOUND");problem(get("/api/v1/dealerships/"+id(23)+"/services"),422,"SERVICE_NOT_BOOKABLE");
        for(String query:List.of("page=-1","size=0","size=101","page=nope"))problem(get("/api/v1/customers?"+query),400,"INVALID_REQUEST");
    }
    @Test void availabilityCreateAndRetrieveFormRealHttpFlow()throws Exception{
        Reply available=get(availability("2030-01-10T16:00:00+07:00"));assertThat(available.status()).isEqualTo(200);assertThat(available.body().get("available")).isEqualTo(true);assertThat(available.body().get("reason")).isNull();assertThat(available.body().get("checkedAt")).isEqualTo(NOW.toString());
        Reply created=post(body(11));assertThat(created.status()).isEqualTo(201);String location=created.headers().firstValue("Location").orElseThrow();assertThat(location).isEqualTo("/api/v1/appointments/"+created.body().get("id"));
        assertThat(created.body().get("startsAt")).isEqualTo("2030-01-10T09:00:00Z");assertThat(created.body().get("endsAt")).isEqualTo("2030-01-10T10:00:00Z");assertThat(created.body().get("createdAt")).isInstanceOf(String.class);assertThat((String)created.body().get("createdAt")).endsWith("Z");
        Reply read=get(location);assertThat(read.status()).isEqualTo(200);assertThat(read.body()).isEqualTo(created.body());
        Reply after=get(availability("2030-01-10T09:00:00Z"));assertThat(after.status()).isEqualTo(200);assertThat(after.body().get("available")).isEqualTo(false);assertThat(after.body().get("reason")).isEqualTo("VEHICLE_UNAVAILABLE");
    }
    @Test void historySupportsOrderingPaginationFilteringAndRecovery()throws Exception{
        Reply a=post(body(11)),b=post(body(12));assertThat(a.status()).isEqualTo(201);assertThat(b.status()).isEqualTo(201);
        String history="/api/v1/appointments?customerId="+id(1);
        Reply all=get(history);assertThat(all.status()).isEqualTo(200);assertThat(items(all).stream().map(x->(String)x.get("id")).toList()).containsExactlyElementsOf(List.of((String)a.body().get("id"),(String)b.body().get("id")).stream().sorted().toList());
        Reply page=get(history+"&size=1");assertThat(items(page)).hasSize(1);assertThat(((Number)page.body().get("totalElements")).intValue()).isEqualTo(2);
        assertThat(items(get(history+"&page=3&size=1"))).isEmpty();
        Reply filtered=get(history+"&vehicleId="+id(12));assertThat(items(filtered)).hasSize(1);assertThat(items(filtered).getFirst().get("id")).isEqualTo(b.body().get("id"));
        assertThat(items(get("/api/v1/appointments?customerId="+id(2)))).isEmpty();
    }
    @Test void missingAndMismatchedHistoryParentsReturnCorrectErrors()throws Exception{
        problem(get("/api/v1/appointments"),400,"INVALID_REQUEST");problem(get("/api/v1/appointments/not-a-uuid"),400,"INVALID_REQUEST");problem(get("/api/v1/appointments/"+id(999)),404,"RESOURCE_NOT_FOUND");
        problem(get("/api/v1/appointments?customerId="+id(999)),404,"RESOURCE_NOT_FOUND");problem(get("/api/v1/appointments?customerId="+id(1)+"&vehicleId="+id(999)),404,"RESOURCE_NOT_FOUND");problem(get("/api/v1/appointments?customerId="+id(2)+"&vehicleId="+id(11)),422,"VEHICLE_CUSTOMER_MISMATCH");
    }
    @ParameterizedTest @ValueSource(strings={"endsAt","technicianId","serviceBayId","unexpected"})
    void unsupportedWriteFieldsAreRejected(String field)throws Exception{problem(post(body(11).replace("}",",\""+field+"\":\"ignored\"}")),400,"INVALID_REQUEST");assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class)).isZero();}
    @ParameterizedTest @ValueSource(strings={"{","null","[]","42","{}"})
    void unreadableOrMissingBodyFieldsReturn400(String input)throws Exception{problem(post(input),400,"INVALID_REQUEST");}
    @Test void malformedUuidNullAndMissingRequiredInputReturn400()throws Exception{
        problem(post(body(11).replace(id(11),"not-a-uuid")),400,"INVALID_REQUEST");problem(post(body(11).replace("\""+id(1)+"\"","null")),400,"INVALID_REQUEST");problem(post(body(11).replace("\"startsAt\":\"2030-01-10T09:00:00Z\"","\"startsAt\":null")),400,"INVALID_REQUEST");
        problem(get("/api/v1/availability?customerId="+id(1)),400,"INVALID_REQUEST");
    }
    @Test void timestampSyntaxAndPolicyFailuresStayDistinct()throws Exception{
        problem(post(body(1,11,21,31,"2030-01-10T09:00:00")),400,"INVALID_REQUEST");problem(post(body(1,11,21,31,"2030-02-30T09:00:00Z")),400,"INVALID_REQUEST");
        for(String time:List.of("2030-01-10T09:00:01Z","2030-01-10T09:00:00.0000000001Z","2030-01-01T00:00:00Z","2030-02-01T00:00:00Z"))problem(post(body(1,11,21,31,time)),422,"INVALID_BOOKING_TIME");
    }
    @Test void referenceValidationReturns404Or422()throws Exception{
        problem(post(body(1,999,21,31,"2030-01-10T09:00:00Z")),404,"RESOURCE_NOT_FOUND");problem(post(body(2,11,21,31,"2030-01-10T09:00:00Z")),422,"VEHICLE_CUSTOMER_MISMATCH");problem(post(body(1,11,21,32,"2030-01-10T09:00:00Z")),422,"SERVICE_NOT_BOOKABLE");
    }
    @Test void availabilityIsAdvisoryAndStaleClientGets409()throws Exception{
        sql("UPDATE service_bays SET active=false WHERE id='"+id(42)+"'");sql("UPDATE technicians SET active=false WHERE id='"+id(52)+"'");
        Reply a=get(availability("2030-01-10T09:00:00Z")),b=get(availability("2030-01-10T09:00:00Z").replace(id(11),id(12)));assertThat(a.body().get("available")).isEqualTo(true);assertThat(b.body().get("available")).isEqualTo(true);
        assertThat(post(body(11)).status()).isEqualTo(201);problem(post(body(12)),409,"NO_AVAILABLE_BAY");assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class)).isEqualTo(1);
    }
    @Test void outsideHoursIs200FalseForAvailabilityBut409ForBooking()throws Exception{
        String time="2030-01-10T17:30:00Z";Reply response=get(availability(time));assertThat(response.status()).isEqualTo(200);assertThat(response.body().get("available")).isEqualTo(false);assertThat(response.body().get("reason")).isEqualTo("OUTSIDE_OPENING_HOURS");problem(post(body(1,11,21,31,time)),409,"OUTSIDE_OPENING_HOURS");
    }
    @Test void requestIdsAreReusedReplacedAndIncludedInErrors()throws Exception{
        Reply valid=send("GET","/api/v1/customers",null,"client.test-01",null);assertThat(valid.headers().firstValue("X-Request-ID").orElseThrow()).isEqualTo("client.test-01");
        Reply invalid=send("GET","/api/v1/customers",null,"invalid token",null);String replacement=invalid.headers().firstValue("X-Request-ID").orElseThrow();assertThatCode(()->UUID.fromString(replacement)).doesNotThrowAnyException();
        Reply next=get("/api/v1/customers");assertThat(next.headers().firstValue("X-Request-ID").orElseThrow()).isNotEqualTo("client.test-01").isNotEqualTo(replacement);
        Reply error=send("GET","/api/v1/appointments/invalid",null,"error-correlation",null);problem(error,400,"INVALID_REQUEST");assertThat(error.body().get("requestId")).isEqualTo("error-correlation");
    }
    @Test void realLockTimeoutMapsToSanitized503()throws Exception{
        try(Connection connection=DriverManager.getConnection(url(),"test","test-password")){
            connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.executeQuery("SELECT id FROM dealerships WHERE id='"+id(21)+"' FOR UPDATE").close();}
            problem(post(body(11)),503,"TEMPORARILY_UNAVAILABLE");assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class)).isZero();
        }
    }
    @Test void commitFailureMapsToSanitized500WithoutPersisting()throws Exception{
        sql("CREATE FUNCTION http_commit_failure_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Sensitive test diagnostic' USING ERRCODE='23514'; END $$");sql("CREATE CONSTRAINT TRIGGER http_commit_failure AFTER INSERT ON appointments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION http_commit_failure_fn()");
        Reply error=post(body(11));problem(error,500,"INTERNAL_ERROR");assertThat(error.body().toString()).doesNotContain("Sensitive test diagnostic");assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments",Long.class)).isZero();
    }
    @Test void noncanonicalUuidInputsAreRejectedRatherThanNormalized()throws Exception{
        problem(get("/api/v1/appointments/1-1-1-1-1"),400,"INVALID_REQUEST");
        problem(get("/api/v1/appointments?customerId=1-1-1-1-1"),400,"INVALID_REQUEST");
        problem(post(body(11).replace(id(11),"1-1-1-1-1")),400,"INVALID_REQUEST");
    }
    @ParameterizedTest @ValueSource(strings={"NO_AVAILABLE_BAY","NO_AVAILABLE_TECHNICIAN"})
    void availabilityShortagesReturn200FalseAndBookingReturns409(String reason)throws Exception{
        if(reason.equals("NO_AVAILABLE_BAY"))sql("UPDATE service_bays SET active=false WHERE dealership_id='"+id(21)+"'");
        else sql("DELETE FROM technician_qualifications WHERE technician_id IN ('"+id(51)+"','"+id(52)+"')");
        Reply r=get(availability("2030-01-10T09:00:00Z"));assertThat(r.status()).isEqualTo(200);assertThat(r.body().get("available")).isEqualTo(false);assertThat(r.body().get("reason")).isEqualTo(reason);problem(post(body(11)),409,reason);
    }
    @Test void unacceptableAcceptHeaderReturns406Problem()throws Exception{
        problem(send("GET","/api/v1/customers",null,null,null,"application/xml"),406,"INVALID_REQUEST");
    }
    @Test void standardHttpErrorsAreNotMaskedAs500()throws Exception{
        Reply method=send("PUT","/api/v1/appointments",body(11),null,"application/json");problem(method,405,"INVALID_REQUEST");assertThat(method.headers().firstValue("Allow").orElse("")).contains("POST");
        problem(send("POST","/api/v1/appointments",body(11),null,"text/plain"),415,"INVALID_REQUEST");problem(get("/api/v1/unknown-route"),404,"RESOURCE_NOT_FOUND");
    }
}
