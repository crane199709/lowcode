package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.SubjectAttributes;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDatasetQueryTest {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    static final AtomicReference<String> BODY = new AtomicReference<>();
    static final HttpServer SERVER = startServer();
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.database", redis::database);
        r.add("lowcode.datasource.sql.max-rows", () -> 8);
        TestTokens.register(r);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    String token;
    final Map<String, String> sources = new HashMap<>();

    @BeforeEach
    void grant() throws Exception {
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                TestTokens.MEMBERSHIP_ID,
                List.of("datasource:manage", "dataset:manage", "screen:view", "screen:edit", "screen:publish"));
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void publishedApiBarUsesTheSameLiteralAggregateResultsAsSql() {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        var q = query(List.of(Map.of("field", "region")), allMeasures());
        String screen = screen(dataset, q);
        var result = published(screen);
        assertThat(result.get("code")).isEqualTo("ok");
        QueryResultAssertions.sales(rows(result));
        var data = (Map<?, ?>) result.get("data");
        assertThat(data.get("truncated")).isEqualTo(false);
        assertThat(((List<?>) data.get("columns")).get(0).toString()).contains("d0", "region");
    }

    @Test
    void rawFiltersPrecedeAggregationAndSortingPrecedesTopN() {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        var q = query(List.of(Map.of("field", "region")), List.of(Map.of("field", "amount", "aggregation", "SUM")));
        q.put(
                "filters",
                List.of(
                        Map.of("field", "region", "operator", "IN", "values", List.of("east", "west")),
                        Map.of("field", "amount", "operator", "BETWEEN", "values", List.of("2.00", "3"))));
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        q.put("topN", 1);
        String screen = screen(dataset, q);
        QueryResultAssertions.rows(rows(published(screen)), new Object[] {"east", 7});
        q.put("filters", List.of(Map.of("field", "amount", "operator", "EQ", "values", List.of("2.000"))));
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {"east", 4});
        q.put("filters", List.of(Map.of("field", "region", "operator", "EQ", "values", Arrays.asList((Object) null))));
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {null, 3});
        q.put("filters", List.of(Map.of("field", "region", "operator", "IN", "values", Arrays.asList("west", null))));
        q.remove("topN");
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {"west", 4}, new Object[] {null, 3});
    }

    @Test
    void oversizedRawInputFailsBeforeFiltersOrTopNCanHideTheTruncation() {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        var q = query(List.of(Map.of("field", "region")), allMeasures());
        q.put("filters", List.of(Map.of("field", "amount", "operator", "EQ", "values", List.of(-1))));
        q.put("topN", 1);
        String screen = screen(dataset, q);
        respond("[{\"amount\":1},{\"amount\":2},{\"amount\":3},{\"amount\":4},"
                + "{\"amount\":5},{\"amount\":6},{\"amount\":7},{\"amount\":8},{\"amount\":9}]");
        var result = published(screen);
        assertThat(result.get("code")).isEqualTo("dataset.apiAggregationTooManyRows");
        assertThat(result.get("httpStatus")).isEqualTo(422);
        assertThat((String) result.get("userTip")).contains("8", "聚合");
        assertThat(result.containsKey("data")).isFalse();
    }

    @Test
    void emptyAndMissingInputsKeepSqlNullAggregateSemantics() {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        var q = query(List.of(), allMeasures());
        String screen = screen(dataset, q);
        respond("[{\"amount\":null,\"region\":\"east\"},{\"region\":\"west\"}]");
        QueryResultAssertions.allNull(rows(published(screen)));
        respond("[]");
        QueryResultAssertions.allNull(rows(published(screen)));
        q.put("dimensions", List.of(Map.of("field", "region")));
        assertThat(rows(preview(screen, dataset, q))).isEmpty();
    }

    @Test
    void markedIsoDatesUseSharedDayMonthYearBucketsAndRejectMalformedValues() {
        String dataset = dataset(QueryResultAssertions.DATES_JSON);
        var saved = call(
                HttpMethod.PUT,
                "/datasets/" + dataset,
                Map.of(
                        "name",
                        "API日期",
                        "dataSourceId",
                        sources.get(dataset),
                        "jsonPath",
                        "$.data.items",
                        "fields",
                        List.of(Map.of("name", "day", "displayName", "销售日", "role", "DIMENSION", "date", true))));
        assertThat(saved.get("code")).isEqualTo("ok");
        var q = query(
                List.of(Map.of("field", "day", "dateGrouping", "DAY")),
                List.of(Map.of("field", "amount", "aggregation", "SUM")));
        String screen = screen(dataset, q);
        QueryResultAssertions.dateDay(rows(published(screen)));
        q.put("dimensions", List.of(Map.of("field", "day", "dateGrouping", "MONTH")));
        QueryResultAssertions.dateMonth(rows(preview(screen, dataset, q)));
        q.put("dimensions", List.of(Map.of("field", "day", "dateGrouping", "YEAR")));
        QueryResultAssertions.dateYear(rows(preview(screen, dataset, q)));
        for (String invalid : List.of("2024-02-30", "2024/02/29", "not-a-date")) {
            respond("[{\"day\":\"" + invalid + "\",\"amount\":1}]");
            var result = published(screen);
            assertThat(result.get("code")).isEqualTo("dataset.queryDateInvalid");
            assertThat(result.get("httpStatus")).isEqualTo(422);
            assertThat((String) result.get("userTip")).contains("ISO", "销售日");
        }
    }

    @Test
    void numericGroupingDistinctAndPrecisionDoNotDependOnDecimalScale() {
        String dataset = dataset("[{\"key\":1,\"amount\":9007199254740993.1},"
                + "{\"key\":1.00,\"amount\":0.2},{\"key\":null,\"amount\":null}]");
        var q = query(List.of(Map.of("field", "key")), allMeasures());
        q.put("sorts", List.of(Map.of("column", 0, "direction", "DESC")));
        String screen = screen(dataset, q);
        QueryResultAssertions.rows(
                rows(published(screen)),
                new Object[] {
                    1,
                    new BigDecimal("9007199254740993.3"),
                    new BigDecimal("4503599627370496.65"),
                    2,
                    2,
                    new BigDecimal("9007199254740993.1"),
                    new BigDecimal("0.2")
                },
                new Object[] {null, null, null, 0, 0, null, null});
        q = query(List.of(), List.of(Map.of("field", "key", "aggregation", "COUNT_DISTINCT")));
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {1});
    }

    @Test
    void changedRuntimeFieldTypesReturnReadableErrorsInsteadOfComparingUnlikeValues() {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        var q = query(List.of(Map.of("field", "region")), allMeasures());
        q.put("filters", List.of(Map.of("field", "amount", "operator", "BETWEEN", "values", List.of(1, 4))));
        String screen = screen(dataset, q);
        respond("[{\"region\":\"east\",\"amount\":\"invalid-number\"}]");
        var result = published(screen);
        assertThat(result.get("code")).isEqualTo("dataset.queryValueInvalid");
        assertThat(result.get("httpStatus")).isEqualTo(422);
        assertThat((String) result.get("userTip")).contains("amount");
    }

    @Test
    void rawFieldsMapByNameAndBothSortDirectionsKeepNullsLastWithStableTies() {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        respond("[{\"amount\":2,\"region\":\"west\"},{\"amount\":2,\"region\":\"east\"},"
                + "{\"region\":\"north\"},{\"amount\":2,\"region\":null},{\"amount\":1,\"region\":\"south\"}]");
        var q = query(List.of(Map.of("field", "region")), List.of(Map.of("field", "amount", "aggregation", "SUM")));
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        String screen = screen(dataset, q);
        QueryResultAssertions.rows(
                rows(published(screen)),
                new Object[] {"east", 2},
                new Object[] {"west", 2},
                new Object[] {null, 2},
                new Object[] {"south", 1},
                new Object[] {"north", null});
        q.put("sorts", List.of(Map.of("column", 1, "direction", "ASC")));
        QueryResultAssertions.rows(
                rows(preview(screen, dataset, q)),
                new Object[] {"south", 1},
                new Object[] {"east", 2},
                new Object[] {"west", 2},
                new Object[] {null, 2},
                new Object[] {"north", null});
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        q.put("topN", 2);
        QueryResultAssertions.rows(
                rows(preview(screen, dataset, q)), new Object[] {"east", 2}, new Object[] {"west", 2});
    }

    @Test
    void apiPublishedQueriesKeepScreenAuthorizationAndCurrentSavedFieldValidation() throws Exception {
        String dataset = dataset(QueryResultAssertions.SALES_JSON);
        var q = query(List.of(Map.of("field", "region")), allMeasures());
        String screen = screen(dataset, q);
        String path = "/screens/" + screen + "/published/widgets/bar/query";
        UUID reader = UUID.randomUUID();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, reader, List.of("screen:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, reader);
        QueryResultAssertions.sales(rows(call(HttpMethod.GET, path + "?datasetId=" + UUID.randomUUID(), null)));
        assertThat(preview(screen, dataset, q).get("httpStatus")).isEqualTo(403);
        for (var scope : List.of(DataScope.SELF, DataScope.DEPARTMENT)) {
            UUID denied = UUID.randomUUID();
            TestGrants.publish(
                    redisTemplate,
                    TestTokens.TENANT_ID,
                    denied,
                    1,
                    List.of("screen:view"),
                    new SubjectAttributes(Set.of("other"), "other", Set.of(), Map.of(), Map.of()),
                    List.of(scope));
            token = TestTokens.forMembership(TestTokens.TENANT_ID, denied);
            assertThat(published(screen).get("httpStatus")).isEqualTo(404);
        }
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, otherTenant, reader, List.of("screen:view"));
        token = TestTokens.forMembership(otherTenant, reader);
        assertThat(published(screen).get("httpStatus")).isEqualTo(404);
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        respond("[{\"region\":\"east\",\"renamed\":1}]");
        var saved = call(
                HttpMethod.PUT,
                "/datasets/" + dataset,
                Map.of("name", "API字段已修改", "dataSourceId", sources.get(dataset), "jsonPath", "$.data.items"));
        assertThat(saved.get("code")).isEqualTo("ok");
        var missing = published(screen);
        assertThat(missing.get("code")).isEqualTo("dataset.fieldMissing");
        assertThat(missing.get("userTip")).isEqualTo("字段不存在，请重新配置组件");
    }

    @Test
    void integerAveragesKeepFractionsAndUseSharedDialectAssertions() {
        String dataset = dataset("[{\"region\":\"east\",\"amount\":1},{\"region\":\"east\",\"amount\":2},"
                + "{\"region\":\"west\",\"amount\":9},{\"region\":\"west\",\"amount\":null}]");
        var q = query(List.of(Map.of("field", "region")), allMeasures());
        String screen = screen(dataset, q);
        QueryResultAssertions.integerAggregates(rows(published(screen)));
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        q.put("topN", 1);
        QueryResultAssertions.integerTopN(rows(preview(screen, dataset, q)));
    }

    List<Map<String, String>> allMeasures() {
        return List.of("SUM", "AVG", "COUNT", "COUNT_DISTINCT", "MAX", "MIN").stream()
                .map(a -> Map.of("field", "amount", "aggregation", a))
                .toList();
    }

    Map<String, Object> query(List<?> dimensions, List<?> measures) {
        return new HashMap<>(
                Map.of("dimensions", dimensions, "measures", measures, "filters", List.of(), "sorts", List.of()));
    }

    String dataset(String json) {
        respond(json);
        var source = call(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "图表接口",
                        "type",
                        "API",
                        "api",
                        Map.of(
                                "url",
                                "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/report",
                                "method",
                                "GET",
                                "authType",
                                "NONE")));
        assertThat(source.get("httpStatus")).isEqualTo(201);
        var result = call(
                HttpMethod.POST,
                "/datasets",
                Map.of("name", "API销售", "dataSourceId", id(source), "jsonPath", "$.data.items"));
        assertThat(result.get("httpStatus")).isEqualTo(201);
        sources.put(id(result), id(source));
        return id(result);
    }

    void respond(String json) {
        BODY.set("{\"data\":{\"items\":" + json + "},\"unrelated\":[{\"amount\":9999}]}");
    }

    String screen(String dataset, Map<String, Object> q) {
        var result = call(HttpMethod.POST, "/screens", Map.of("name", "API销售大屏", "draft", draft(dataset, q)));
        assertThat(result.get("httpStatus")).isEqualTo(201);
        String screen = id(result);
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/publish", null)
                        .get("code"))
                .isEqualTo("ok");
        return screen;
    }

    Map<?, ?> published(String screen) {
        return call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null);
    }

    Map<?, ?> preview(String screen, String dataset, Map<String, Object> q) {
        return call(HttpMethod.POST, "/screens/" + screen + "/draft/query", widget(dataset, q));
    }

    Map<String, Object> widget(String dataset, Map<String, Object> q) {
        return Map.of(
                "id",
                "bar",
                "type",
                "BAR",
                "x",
                0,
                "y",
                0,
                "width",
                800,
                "height",
                600,
                "zIndex",
                0,
                "config",
                Map.of("datasetId", dataset, "query", q, "refreshSeconds", 0));
    }

    Map<String, Object> draft(String dataset, Map<String, Object> q) {
        return Map.of(
                "designWidth",
                1920,
                "designHeight",
                1080,
                "backgroundColor",
                "#101827",
                "widgets",
                List.of(widget(dataset, q)));
    }

    String id(Map<?, ?> result) {
        return (String) ((Map<?, ?>) result.get("data")).get("id");
    }

    List<?> rows(Map<?, ?> result) {
        assertThat(result.get("code")).isEqualTo("ok");
        return (List<?>) ((Map<?, ?>) result.get("data")).get("rows");
    }

    Map<?, ?> call(HttpMethod method, String path, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, response) -> JSON.readValue(response.bodyTo(String.class), Map.class));
    }

    static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/report", exchange -> {
                byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
