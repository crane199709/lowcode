package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class SqlDatasetApiTest {
    private static final UUID VIEWER = UUID.fromString("019a0110-0000-7000-8000-0000000000d1");
    private static final UUID OUTSIDER = UUID.fromString("019a0110-0000-7000-8000-0000000000d2");

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalDataset();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("lowcode.datasource.sql.timeout-seconds", () -> 1);
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

    // SQL 是用户提交的查询数据，经 HTTP 验证；不是对服务自身数据库内联执行的测试 SQL。
    @Test
    void previewAcceptsOneSelectOrWithAndRejectsOtherStatements() {
        String source = create();
        for (String sql : List.of(
                "SELECT 1 AS amount",
                "WITH q AS (SELECT 1 AS amount) SELECT * FROM q",
                "SELECT '; DELETE' AS text; -- end")) {
            assertThat(preview(source, sql).get("code")).as(sql).isEqualTo("ok");
        }
        for (String sql : List.of(
                "DELETE FROM anything",
                "SELECT 1; SELECT 2",
                "WITH q AS (DELETE FROM anything RETURNING *) SELECT * FROM q",
                "SELECT 1 INTO anything",
                "CALL anything()",
                "not sql",
                "VALUES (1)")) {
            var result = preview(source, sql);
            assertThat(result.get("code")).as(sql).isEqualTo("dataset.sqlNotAllowed");
            assertThat(result.get("httpStatus")).isEqualTo(400);
        }
    }

    @Test
    void savingInfersFieldsAndPersistsOnlyDisplayOverrides() {
        String source = create();
        var body = Map.of(
                "name",
                "销售明细",
                "dataSourceId",
                source,
                "sql",
                "SELECT 12.50 AS amount, 'east' AS region, DATE '2026-11-11' AS day WHERE 1 = 0");
        var saved = exchange(HttpMethod.POST, "/datasets", body);
        assertThat(saved.get("httpStatus")).isEqualTo(201);
        Map<?, ?> data = (Map<?, ?>) saved.get("data");
        assertThat((List<Map<String, Object>>) data.get("fields"))
                .containsExactly(
                        Map.of(
                                "name",
                                "amount",
                                "displayName",
                                "amount",
                                "type",
                                "NUMBER",
                                "role",
                                "MEASURE",
                                "date",
                                false),
                        Map.of(
                                "name",
                                "region",
                                "displayName",
                                "region",
                                "type",
                                "STRING",
                                "role",
                                "DIMENSION",
                                "date",
                                false),
                        Map.of("name", "day", "displayName", "day", "type", "DATE", "role", "DIMENSION", "date", true));
        String id = (String) data.get("id");
        var changed = new HashMap<String, Object>(body);
        changed.put(
                "fields", List.of(Map.of("name", "amount", "displayName", "销售金额", "role", "DIMENSION", "date", true)));
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, changed).get("code"))
                .isEqualTo("ok");
        Map<?, ?> read =
                (Map<?, ?>) exchange(HttpMethod.GET, "/datasets/" + id, null).get("data");
        assertThat((List<Map<String, Object>>) read.get("fields"))
                .contains(Map.of(
                        "name", "amount", "displayName", "销售金额", "type", "NUMBER", "role", "DIMENSION", "date", true));
        assertThat((List<?>) exchange(HttpMethod.GET, "/datasets", null).get("data"))
                .isNotEmpty();
        assertThat(exchange(HttpMethod.DELETE, "/datasets/" + id, null).get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(HttpMethod.GET, "/datasets/" + id, null).get("code"))
                .isEqualTo("dataset.notFound");
    }

    @Test
    void readOnlyTransactionBlocksWritesEvenInsideASelectFunction() {
        String source = create();
        var readOnly = (Map<?, ?>) preview(source, "SELECT current_setting('transaction_read_only') AS flag")
                .get("data");
        assertThat((List<?>) readOnly.get("rows")).isEqualTo(List.of(List.of("on")));
        assertThat(preview(source, "SELECT public.dataset_try_write() AS value").get("code"))
                .isEqualTo("dataset.queryFailed");
        var count = (Map<?, ?>) preview(source, "SELECT COUNT(*) AS total FROM public.dataset_write_probe")
                .get("data");
        assertThat((List<?>) count.get("rows")).isEqualTo(List.of(List.of(0)));
    }

    @Test
    void queryBudgetCancelsSlowQueriesAndLimitsRows() {
        String source = create();
        long start = System.nanoTime();
        assertThat(preview(source, "SELECT pg_sleep(5) AS wait").get("code")).isEqualTo("dataset.queryTimeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start).toSeconds()).isLessThan(5);
        var data = (Map<?, ?>)
                preview(source, "SELECT generate_series(1, 5) AS amount").get("data");
        assertThat(data.get("maxRows")).isEqualTo(3);
        assertThat(data.get("truncated")).isEqualTo(true);
        assertThat((List<?>) data.get("rows")).isEqualTo(List.of(List.of(1), List.of(2), List.of(3)));
        var exact = (Map<?, ?>)
                preview(source, "SELECT generate_series(1, 3) AS amount").get("data");
        assertThat(exact.get("truncated")).isEqualTo(false);
        var nulls = (Map<?, ?>) preview(source, "SELECT CAST(NULL AS INTEGER) AS missing, false AS active")
                .get("data");
        assertThat((List<?>) nulls.get("rows")).isEqualTo(List.of(Arrays.asList(null, false)));
    }

    @Test
    void viewPermissionCannotWriteOrRunArbitrarySqlAndTenantsAreIsolated() throws Exception {
        String source = create();
        var body = Map.of("name", "权限数据集", "dataSourceId", source, "sql", "SELECT 1 AS amount");
        String id = (String)
                ((Map<?, ?>) exchange(HttpMethod.POST, "/datasets", body).get("data")).get("id");
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, VIEWER, List.of("dataset:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, VIEWER);
        assertThat(exchange(HttpMethod.GET, "/datasets/" + id, null).get("code"))
                .isEqualTo("ok");
        assertThat(exchange(HttpMethod.GET, "/datasets", null).get("code")).isEqualTo("ok");
        assertThat(exchange(HttpMethod.POST, "/datasets", body).get("code")).isEqualTo("auth.forbidden");
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, body).get("code"))
                .isEqualTo("auth.forbidden");
        assertThat(exchange(HttpMethod.DELETE, "/datasets/" + id, null).get("code"))
                .isEqualTo("auth.forbidden");
        assertThat(preview(source, "SELECT 1").get("code")).isEqualTo("auth.forbidden");
        assertThat(exchange(HttpMethod.GET, "/datasets/sources", null).get("code"))
                .isEqualTo("auth.forbidden");
        UUID other = UUID.fromString("019a0110-0000-7000-8000-0000000000d0");
        TestGrants.publish(redisTemplate, other, TestTokens.MEMBERSHIP_ID, List.of("dataset:view", "dataset:manage"));
        token = TestTokens.forTenant(other);
        assertThat(exchange(HttpMethod.GET, "/datasets/" + id, null).get("code"))
                .isEqualTo("dataset.notFound");
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, body).get("code"))
                .isEqualTo("dataset.notFound");
        assertThat(exchange(HttpMethod.DELETE, "/datasets/" + id, null).get("code"))
                .isEqualTo("dataset.notFound");
        assertThat((List<?>) exchange(HttpMethod.GET, "/datasets", null).get("data"))
                .isEmpty();
        assertThat(preview(source, "SELECT 1").get("code")).isEqualTo("datasource.notFound");
        assertThat(exchange(HttpMethod.POST, "/datasets", body).get("code")).isEqualTo("datasource.notFound");
    }

    @Test
    void savesRevalidateQueriesAndFieldOverridesAndSourcesAreMinimal() throws Exception {
        String source = create();
        var body = new HashMap<String, Object>(
                Map.of("name", "校验数据集", "dataSourceId", source, "sql", "SELECT 1 AS amount"));
        String id = (String)
                ((Map<?, ?>) exchange(HttpMethod.POST, "/datasets", body).get("data")).get("id");
        body.put("sql", "SELECT missing FROM nonexistent_table");
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, body).get("code"))
                .isEqualTo("dataset.queryFailed");
        body.put("sql", "DELETE FROM anything");
        assertThat(exchange(HttpMethod.POST, "/datasets", body).get("code")).isEqualTo("dataset.sqlNotAllowed");
        body.put("sql", "SELECT 1 AS amount");
        body.put("fields", List.of(Map.of("name", "gone", "displayName", "不存在", "role", "MEASURE", "date", false)));
        assertThat(exchange(HttpMethod.PUT, "/datasets/" + id, body).get("code"))
                .isEqualTo("dataset.fieldInvalid");
        var field = Map.of("name", "amount", "displayName", "金额", "role", "MEASURE", "date", false);
        body.put("fields", List.of(field, field));
        assertThat(exchange(HttpMethod.POST, "/datasets", body).get("code")).isEqualTo("dataset.fieldInvalid");
        assertThat(preview(source, "SELECT 1 AS amount, 2 AS amount").get("code"))
                .isEqualTo("dataset.fieldInvalid");
        assertThat(((Map<?, ?>) exchange(HttpMethod.GET, "/datasets/" + id, null)
                                .get("data"))
                        .get("sql"))
                .isEqualTo("SELECT 1 AS amount");
        var api = exchange(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "API",
                        "type",
                        "API",
                        "api",
                        Map.of("url", "http://127.0.0.1:12345/report", "method", "GET", "authType", "NONE")));
        String apiId = (String) ((Map<?, ?>) api.get("data")).get("id");
        assertThat(preview(apiId, "SELECT 1").get("code")).isEqualTo("dataset.sourceNotSupported");
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, OUTSIDER, List.of("dataset:manage"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, OUTSIDER);
        var choices = exchange(HttpMethod.GET, "/datasets/sources", null);
        assertThat(choices.get("code")).isEqualTo("ok");
        assertThat(choices.toString()).doesNotContain("password", "username", external.host(), apiId);
        assertThat(choices.toString()).contains(source);
        assertThat(preview(source, "SELECT 1 AS value").get("code")).isEqualTo("ok");
    }

    private Map<?, ?> preview(String source, String sql) {
        return exchange(HttpMethod.POST, "/datasets/preview", Map.of("dataSourceId", source, "sql", sql));
    }

    private String create() {
        var response = exchange(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "元数据测试",
                        "type",
                        "POSTGRESQL",
                        "database",
                        Map.of(
                                "host",
                                external.host(),
                                "port",
                                external.port(),
                                "database",
                                external.database(),
                                "username",
                                external.username(),
                                "password",
                                external.password(),
                                "params",
                                Map.of("sslmode", "disable"))));
        assertThat(response.get("httpStatus")).isEqualTo(201);
        return (String) ((Map<?, ?>) response.get("data")).get("id");
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
