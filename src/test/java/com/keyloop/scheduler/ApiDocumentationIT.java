package com.keyloop.scheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiDocumentationIT {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
            Objects.requireNonNull(System.getProperty("postgres.image"))))
            .withEnv("POSTGRES_DB", "documentation_test")
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "isolated-test-password")
            .withExposedPorts(5432)
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                    .withStrategy(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/documentation_test");
        registry.add("spring.datasource.username", () -> "test");
        registry.add("spring.datasource.password", () -> "isolated-test-password");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @SuppressWarnings("unchecked")
    void servesCanonicalContractWithSameOriginServerAndAllEightOperations() throws Exception {
        var response = get(uri("/openapi.yaml"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(Files.readAllBytes(Path.of("docs/openapi.yaml")));

        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(200);
        Map<String, Object> document = new Yaml(new SafeConstructor(options))
                .load(new String(response.body(), StandardCharsets.UTF_8));
        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        List<Map<String, Object>> servers = (List<Map<String, Object>>) document.get("servers");
        assertThat(servers).hasSize(1);
        assertThat(servers.getFirst().get("url")).isEqualTo("/api/v1");

        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
        List<String> operationIds = new ArrayList<>();
        Set<String> tags = new HashSet<>();
        for (var path : paths.values()) {
            for (String method : List.of("get", "post")) {
                if (!path.containsKey(method)) continue;
                Map<String, Object> operation = (Map<String, Object>) path.get(method);
                operationIds.add((String) operation.get("operationId"));
                List<String> operationTags = (List<String>) operation.get("tags");
                assertThat(operationTags).as("Every operation has a visible group").hasSize(1);
                tags.addAll(operationTags);
            }
        }
        assertThat(operationIds).hasSize(8).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(tags).containsExactlyInAnyOrder("Catalog", "Availability", "Appointments");
    }

    @Test
    void swaggerPageAndItsStylesAndScriptsAreServedLocally() throws Exception {
        long before = appointmentCount();
        String scripts = assertLocalPageAssets("/swagger-ui/index.html");
        assertThat(scripts).contains("SwaggerUIBundle", "/openapi.yaml");
        assertThat(appointmentCount()).isEqualTo(before);
    }

    @Test
    void demoPageAndItsStylesAndScriptsAreServedLocally() throws Exception {
        long before = appointmentCount();
        assertLocalPageAssets("/demo/index.html");
        assertThat(appointmentCount()).isEqualTo(before);
    }

    private String assertLocalPageAssets(String path) throws Exception {
        URI page = uri(path);
        var response = get(page);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        String html = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(html.toLowerCase()).contains("<html", "<title");
        var elements = Pattern.compile("<(script|link)\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        int scriptCount = 0;
        int stylesheetCount = 0;
        StringBuilder scripts = new StringBuilder();
        while (elements.find()) {
            String element = elements.group();
            boolean script = elements.group(1).equalsIgnoreCase("script");
            if (!script && !"stylesheet".equalsIgnoreCase(attribute(element, "rel"))) continue;
            String reference = attribute(element, script ? "src" : "href");
            assertThat(reference).as("Local page assets have explicit URLs: %s", element).isNotBlank();
            URI asset = page.resolve(reference);
            assertThat(asset.getScheme()).isEqualTo(page.getScheme());
            assertThat(asset.getAuthority()).as("No CDN or alternate server asset").isEqualTo(page.getAuthority());
            var resource = get(asset);
            assertThat(resource.statusCode()).as("Asset %s", asset).isEqualTo(200);
            assertThat(resource.body()).isNotEmpty();
            String contentType = resource.headers().firstValue("Content-Type").orElse("");
            if (script) {
                scriptCount++;
                assertThat(contentType).containsAnyOf("javascript", "ecmascript");
                scripts.append(new String(resource.body(), StandardCharsets.UTF_8));
            } else {
                stylesheetCount++;
                assertThat(contentType).startsWith("text/css");
            }
        }
        assertThat(scriptCount).isPositive();
        assertThat(stylesheetCount).isPositive();
        return scripts.toString();
    }

    private static String attribute(String element, String name) {
        var match = Pattern.compile("\\b" + name + "\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE)
                .matcher(element);
        return match.find() ? match.group(1) : "";
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<byte[]> get(URI url) throws Exception {
        return client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private long appointmentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM appointments", Long.class);
    }
}
