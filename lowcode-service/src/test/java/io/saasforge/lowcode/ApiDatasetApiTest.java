package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDatasetApiTest {
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<String> AUTH = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("lowcode.datasource.api.timeout", () -> "500ms");
        registry.add("lowcode.datasource.api.max-response-bytes", () -> 1024);
        registry.add("lowcode.datasource.sql.max-rows", () -> 3);
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    String token;

    @BeforeEach
    void grantManage() throws Exception {
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                TestTokens.MEMBERSHIP_ID,
                List.of("datasource:view", "datasource:manage", "dataset:view", "dataset:manage"));
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void previewExtractsRowsAndInfersFieldsUsingSavedAuthentication() {
        BODY.set("""
                {"data":{"items":[{"region":"华东","amount":12.5,"active":true}]}}
                """);
        var result = preview(create(), "$.data.items");
        assertThat(result.get("code")).isEqualTo("ok");
        var data = (Map<?, ?>) result.get("data");
        assertThat((List<?>) data.get("rows")).isEqualTo(List.of(List.of("华东", 12.5, true)));
        assertThat((List<?>) data.get("fields"))
                .isEqualTo(List.of(
                        field("region", "STRING", "DIMENSION"),
                        field("amount", "NUMBER", "MEASURE"),
                        field("active", "BOOLEAN", "DIMENSION")));
        assertThat(AUTH.get()).isEqualTo("dataset-secret");
    }

    @Test
    void savingReinfersFieldsAndPersistsThePathAndDisplayOverrides() {
        BODY.set("{\"items\":[{\"amount\":12.5,\"day\":\"2026-11-11\"}]}");
        String source = create();
        var body = new HashMap<String, Object>(Map.of("name", "API 销售", "dataSourceId", source, "jsonPath", "$.items"));
        var saved = exchange(HttpMethod.POST, "/datasets", body);
        assertThat(saved.get("httpStatus")).isEqualTo(201);
        var data = (Map<?, ?>) saved.get("data");
        String id = (String) data.get("id");
        assertThat(data.get("jsonPath")).isEqualTo("$.items");
        body.put("fields", List.of(Map.of("name", "day", "displayName", "销售日", "role", "DIMENSION", "date", true)));
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, body).get("code"))
                .isEqualTo("ok");
        var read = (Map<?, ?>) exchange(HttpMethod.GET, "/datasets/" + id, null).get("data");
        assertThat((List<Map<String, Object>>) read.get("fields"))
                .contains(Map.of(
                        "name", "day", "displayName", "销售日", "type", "STRING", "role", "DIMENSION", "date", true));
        assertThat(((List<?>) exchange(HttpMethod.GET, "/datasets", null).get("data")).toString())
                .contains("$.items");
        BODY.set("{\"items\":[{\"other\":1}]}");
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, body).get("code"))
                .isEqualTo("dataset.fieldInvalid");
        assertThat(exchange(HttpMethod.DELETE, "/datasets/" + id, null).get("code"))
                .isEqualTo("ok");
    }

    @Test
    void invalidPathsAndResponseShapesReturnReadableErrors() {
        String source = create();
        BODY.set("{\"items\":[],\"total\":0}");
        for (String path : List.of("", "items", "$.items[", "$.missing")) {
            var result = preview(source, path);
            assertThat(result.get("code")).as(path).isEqualTo("dataset.jsonPathInvalid");
            assertThat(result.get("httpStatus")).isEqualTo(400);
            assertThat((String) result.get("userTip")).contains("JSON 路径");
        }
        assertThat(preview(source, "$.total").get("code")).isEqualTo("dataset.apiResultNotArray");
        for (String body : List.of("not json", "{\"items\":[]} trailing")) {
            BODY.set(body);
            assertThat(preview(source, "$.items").get("code")).isEqualTo("dataset.apiJsonInvalid");
        }
        BODY.set("[1,2,3]");
        assertThat(preview(source, "$").get("code")).isEqualTo("dataset.apiRowsInvalid");
        BODY.set("[{\" \":1}]");
        assertThat(preview(source, "$").get("code")).isEqualTo("dataset.fieldInvalid");
    }

    @Test
    void samplesRespectRowLimitAndMergeMissingNullAndMixedTypes() {
        String source = create();
        BODY.set("""
                [{"amount":null,"mixed":1,"nested":{"id":1}},
                 {"amount":2.5,"mixed":"2","late":true},
                 {"amount":3,"mixed":null},
                 {"amount":"outside sample","ignored":1}]
                """);
        var data = (Map<?, ?>) preview(source, "$").get("data");
        assertThat(data.get("maxRows")).isEqualTo(3);
        assertThat(data.get("truncated")).isEqualTo(true);
        assertThat((List<?>) data.get("fields"))
                .isEqualTo(List.of(
                        field("amount", "NUMBER", "MEASURE"), field("mixed", "OTHER", "DIMENSION"),
                        field("nested", "OTHER", "DIMENSION"), field("late", "BOOLEAN", "DIMENSION")));
        assertThat((List<?>) data.get("rows"))
                .isEqualTo(List.of(
                        Arrays.asList(null, 1, "{\"id\":1}", null),
                        Arrays.asList(2.5, "2", null, true),
                        Arrays.asList(3, null, null, null)));
        BODY.set("[{\"x\":1},{\"x\":2},{\"x\":3}]");
        assertThat(((Map<?, ?>) preview(source, "$").get("data")).get("truncated"))
                .isEqualTo(false);
        BODY.set("[]");
        var empty = (Map<?, ?>) preview(source, "$").get("data");
        assertThat((List<?>) empty.get("fields")).isEmpty();
        assertThat((List<?>) empty.get("rows")).isEmpty();
        assertThat(empty.get("truncated")).isEqualTo(false);
        BODY.set("{\"data.items\":[{\"x\":1}]}");
        assertThat(preview(source, "$['data.items']").get("code")).isEqualTo("ok");
    }

    @Test
    void postUsesSavedStaticBodyAndBasicAuthentication() {
        BODY.set("[{\"amount\":1}]");
        String source = create(Map.of(
                "url",
                url("/report"),
                "method",
                "POST",
                "body",
                "{\"scope\":\"east\"}",
                "authType",
                "BASIC",
                "username",
                "report",
                "password",
                "basic-secret"));
        assertThat(preview(source, "$").get("code")).isEqualTo("ok");
        assertThat(AUTH.get())
                .isEqualTo("Basic "
                        + Base64.getEncoder().encodeToString("report:basic-secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(REQUEST.get()).isEqualTo("POST {\"scope\":\"east\"}");
    }

    @Test
    void previewRetainsHttpSafetyAndResourceBudgets() {
        BODY.set("[]");
        String redirect = create(Map.of("url", url("/redirect"), "method", "GET", "authType", "NONE"));
        REQUEST.set(null);
        assertThat(preview(redirect, "$").get("code")).isEqualTo("datasource.apiRedirect");
        assertThat(REQUEST.get()).isNull();
        BODY.set(" ".repeat(1025));
        assertThat(preview(create(), "$").get("code")).isEqualTo("datasource.apiResponseTooLarge");
        String slow = create(Map.of("url", url("/slow"), "method", "GET", "authType", "NONE"));
        long start = System.nanoTime();
        assertThat(preview(slow, "$").get("code")).isEqualTo("datasource.apiUnreachable");
        assertThat(Duration.ofNanos(System.nanoTime() - start).toSeconds()).isLessThan(3);
    }

    @Test
    void previewAndSaveEnforcePermissionsAndTenantIsolation() throws Exception {
        BODY.set("[{\"amount\":1}]");
        String source = create();
        var body = Map.of("name", "租户 API 数据集", "dataSourceId", source, "jsonPath", "$");
        String id = (String)
                ((Map<?, ?>) exchange(HttpMethod.POST, "/datasets", body).get("data")).get("id");
        UUID viewer = UUID.fromString("019a0110-0000-7000-8000-000000000071");
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, viewer, List.of("dataset:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, viewer);
        assertThat(exchange(HttpMethod.GET, "/datasets/" + id, null).get("code"))
                .isEqualTo("ok");
        assertThat(preview(source, "$").get("code")).isEqualTo("auth.forbidden");
        assertThat(exchange(HttpMethod.POST, "/datasets", body).get("code")).isEqualTo("auth.forbidden");
        UUID other = UUID.fromString("019a0110-0000-7000-8000-000000000072");
        TestGrants.publish(redisTemplate, other, TestTokens.MEMBERSHIP_ID, List.of("dataset:view", "dataset:manage"));
        token = TestTokens.forTenant(other);
        assertThat(preview(source, "$").get("code")).isEqualTo("datasource.notFound");
        assertThat(exchange(HttpMethod.POST, "/datasets", body).get("code")).isEqualTo("datasource.notFound");
        assertThat(exchange(HttpMethod.GET, "/datasets/" + id, null).get("code"))
                .isEqualTo("dataset.notFound");
        assertThat((List<?>) exchange(HttpMethod.GET, "/datasets/sources", null).get("data"))
                .isEmpty();
    }

    private static Map<String, Object> field(String name, String type, String role) {
        return Map.of("name", name, "displayName", name, "type", type, "role", role, "date", false);
    }

    private Map<?, ?> preview(String source, String path) {
        return exchange(HttpMethod.POST, "/datasets/preview", Map.of("dataSourceId", source, "jsonPath", path));
    }

    private String create() {
        return create(Map.of(
                "url",
                url("/report"),
                "method",
                "GET",
                "authType",
                "HEADER",
                "headerName",
                "X-Api-Key",
                "headerValue",
                "dataset-secret"));
    }

    private String create(Map<String, String> api) {
        var result = exchange(HttpMethod.POST, "/data-sources", Map.of("name", "销售接口", "type", "API", "api", api));
        assertThat(result.get("httpStatus")).isEqualTo(201);
        return (String) ((Map<?, ?>) result.get("data")).get("id");
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + path;
    }

    private static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/report", exchange -> {
                AUTH.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                if (AUTH.get() == null) {
                    AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                REQUEST.set(exchange.getRequestMethod() + " "
                        + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", url("/report"));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/slow", exchange -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool(task -> {
                var thread = new Thread(task);
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    Map<?, ?> exchange(HttpMethod method, String uri, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(URI.create("http://localhost:" + port + uri))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
