package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.SubjectAttributes;
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
class ScreenQueryApiTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalDataset();
    // 用户SQL是HTTP请求数据；三行预览上限不能截断聚合输入。
    static final String SALES =
            "WITH sales(region, amount, product) AS (VALUES ('east',10.0,'A'),('east',20.0,'A'),('east',NULL,'B'),('west',4.0,'C'),('west',8.0,'D'),('north',7.0,'E')) SELECT * FROM sales; -- trailing";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.database", redis::database);
        r.add("lowcode.datasource.sql.max-rows", () -> 3);
        r.add("lowcode.datasource.sql.timeout-seconds", () -> 1);
        TestTokens.register(r);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    String token;
    Map<String, String> datasetSources = new HashMap<>();

    @BeforeEach
    void grant() throws Exception {
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                TestTokens.MEMBERSHIP_ID,
                List.of("datasource:manage", "dataset:manage", "screen:view", "screen:edit", "screen:publish"));
    }

    @Test
    void dateGroupingRequiresACurrentDateField() {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region", "dateGrouping", "DAY")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, query(List.of(), List.of(measure("amount", "SUM"))));
        assertThat(preview(screen, dataset, q).get("code")).isEqualTo("dataset.fieldMissing");
    }

    @Test
    void publishedBarAggregatesAllRowsWithEveryAggregation() {
        String dataset = dataset(SALES);
        var query = query(
                List.of(Map.of("field", "region")),
                List.of(
                        measure("amount", "SUM"),
                        measure("amount", "AVG"),
                        measure("amount", "COUNT"),
                        measure("product", "COUNT_DISTINCT"),
                        measure("amount", "MAX"),
                        measure("amount", "MIN")));
        query.put("sorts", List.of(Map.of("column", 0, "direction", "ASC")));
        String screen = screen(dataset, query);
        var result = call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null);
        assertThat(result.get("code")).isEqualTo("ok");
        assertThat(rows(result).toString())
                .isEqualTo(
                        "[[east, 30.0, 15.0, 2, 2, 20.0, 10.0], [north, 7.0, 7.0, 1, 1, 7.0, 7.0], [west, 12.0, 6.0, 2, 2, 8.0, 4.0]]");
    }

    @Test
    void lineWidgetSavesPublishesAndQueriesMultipleMeasures() {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM"), measure("amount", "AVG")));
        var definition = new HashMap<>(draft(dataset, q, 0));
        var widget = new HashMap<>((Map<String, Object>) ((List<?>) definition.get("widgets")).get(0));
        widget.put("type", "LINE");
        definition.put("widgets", List.of(widget));
        var created = call(HttpMethod.POST, "/screens", Map.of("name", "销售趋势", "draft", definition));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        String screen = id(created);
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/publish", null)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(rows(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null))
                        .toString())
                .isEqualTo("[[east, 30.0, 15.0], [north, 7.0, 7.0], [west, 12.0, 6.0]]");
    }

    @Test
    void stackedWidgetSavesPublishesAndKeepsSparseGroupsAndNullValues() {
        String dataset = dataset(SALES);
        var q = query(
                List.of(Map.of("field", "region"), Map.of("field", "product")), List.of(measure("amount", "SUM")));
        var definition = new HashMap<>(draft(dataset, q, 0));
        var widget = new HashMap<>((Map<String, Object>) ((List<?>) definition.get("widgets")).get(0));
        widget.put("type", "STACKED_BAR");
        definition.put("widgets", List.of(widget));
        var created = call(HttpMethod.POST, "/screens", Map.of("name", "销售渠道", "draft", definition));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        String screen = id(created);
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/publish", null)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(rows(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null))
                        .toString())
                .isEqualTo("[[east, A, 30.0], [east, B, null], [north, E, 7.0]]");
    }

    @Test
    void pieWidgetQueriesOneMeasureAndPersistsDonutWithoutChangingAggregation() {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "AVG")));
        var definition = new HashMap<>(draft(dataset, q, 0));
        var widget = new HashMap<>((Map<String, Object>) ((List<?>) definition.get("widgets")).get(0));
        widget.put("type", "PIE");
        widget.put("config", Map.of("datasetId", dataset, "query", q, "refreshSeconds", 0, "donut", true));
        definition.put("widgets", List.of(widget));
        var created = call(HttpMethod.POST, "/screens", Map.of("name", "销售分布", "draft", definition));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        String screen = id(created);
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/publish", null)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.GET, "/screens/" + screen + "/published", null)
                        .toString())
                .contains("donut=true");
        assertThat(rows(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null))
                        .toString())
                .isEqualTo("[[east, 15.0], [north, 7.0], [west, 6.0]]");
    }

    @Test
    void chartSpecificInvalidCombinationsCannotBeSavedOrQueried() {
        String dataset = dataset(SALES);
        var valid = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, valid);
        for (String type : List.of("LINE", "STACKED_BAR", "PIE")) {
            for (var q : List.of(
                    query(List.of(), List.of(measure("amount", "SUM"))),
                    query(
                            List.of(Map.of("field", "region"), Map.of("field", "product"), Map.of("field", "amount")),
                            List.of(measure("amount", "SUM"))),
                    query(List.of(Map.of("field", "region")), List.of()))) {
                var definition = new HashMap<>(draft(dataset, q, 0));
                var widget = new HashMap<>((Map<String, Object>) ((List<?>) definition.get("widgets")).get(0));
                widget.put("type", type);
                widget.put("config", Map.of("datasetId", dataset, "query", q, "refreshSeconds", 0, "donut", false));
                definition.put("widgets", List.of(widget));
                assertThat(call(HttpMethod.PUT, "/screens/" + screen + "/draft", definition)
                                .get("httpStatus"))
                        .isEqualTo(400);
                assertThat(call(HttpMethod.POST, "/screens/" + screen + "/draft/query", widget)
                                .get("httpStatus"))
                        .isEqualTo(400);
            }
        }
        for (String type : List.of("STACKED_BAR", "PIE")) {
            var q = query(
                    type.equals("STACKED_BAR")
                            ? List.of(Map.of("field", "region"), Map.of("field", "product"))
                            : List.of(Map.of("field", "region")),
                    List.of(measure("amount", "SUM"), measure("amount", "AVG")));
            var definition = new HashMap<>(draft(dataset, q, 0));
            var widget = new HashMap<>((Map<String, Object>) ((List<?>) definition.get("widgets")).get(0));
            widget.put("type", type);
            widget.put("config", Map.of("datasetId", dataset, "query", q, "refreshSeconds", 0, "donut", false));
            definition.put("widgets", List.of(widget));
            assertThat(call(HttpMethod.PUT, "/screens/" + screen + "/draft", definition)
                            .get("httpStatus"))
                    .isEqualTo(400);
        }
    }

    @Test
    void staticFiltersRunBeforeGroupingAndTopNAppliesAfterSorting() {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM")));
        q.put(
                "filters",
                List.of(
                        Map.of("field", "region", "operator", "IN", "values", List.of("east", "west")),
                        Map.of("field", "amount", "operator", "BETWEEN", "values", List.of("8", "20"))));
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        q.put("topN", 1);
        String screen = screen(dataset, q);
        assertThat(rows(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null))
                        .toString())
                .isEqualTo("[[east, 30.0]]");
        q.put("filters", List.of(Map.of("field", "product", "operator", "EQ", "values", List.of("A"))));
        q.remove("topN");
        assertThat(rows(preview(screen, dataset, q)).toString()).isEqualTo("[[east, 30.0]]");
        q.put("filters", List.of(Map.of("field", "product", "operator", "EQ", "values", List.of("A' OR 1=1 --"))));
        assertThat(rows(preview(screen, dataset, q))).isEmpty();
    }

    @Test
    void emptyAndNullInputsHaveSqlAggregateSemanticsAndNullFiltersAreExplicit() {
        String dataset =
                dataset("SELECT * FROM (VALUES ('east',CAST(NULL AS NUMERIC)),('west',NULL)) sales(region,amount)");
        var q = query(
                List.of(),
                List.of(
                        measure("amount", "SUM"),
                        measure("amount", "AVG"),
                        measure("amount", "COUNT"),
                        measure("amount", "COUNT_DISTINCT"),
                        measure("amount", "MAX"),
                        measure("amount", "MIN")));
        String screen = screen(dataset, q);
        assertThat(rows(preview(screen, dataset, q)).toString()).isEqualTo("[[null, null, 0, 0, null, null]]");
        q.put("filters", List.of(Map.of("field", "region", "operator", "EQ", "values", List.of("missing"))));
        assertThat(rows(preview(screen, dataset, q)).toString()).isEqualTo("[[null, null, 0, 0, null, null]]");
        q.put("dimensions", List.of(Map.of("field", "region")));
        assertThat(rows(preview(screen, dataset, q))).isEmpty();
        q.put("filters", List.of(Map.of("field", "amount", "operator", "EQ", "values", java.util.Arrays.asList((Object)
                null))));
        assertThat(rows(preview(screen, dataset, q)).size()).isEqualTo(2);
    }

    @Test
    void savedQuotedIdentifiersAreSafeAndCurrentFieldChangesImmediatelyInvalidateEveryReference() {
        String dataset = dataset("SELECT 'east' AS \"group name\", 3 AS \"a\"\"b\"");
        var q = query(List.of(Map.of("field", "group name")), List.of(measure("a\"b", "SUM")));
        String screen = screen(dataset, q);
        assertThat(rows(preview(screen, dataset, q)).toString()).isEqualTo("[[east, 3]]");
        for (String place : List.of("dimensions", "measures", "filters")) {
            var bad = new HashMap<>(q);
            bad.put(
                    place,
                    switch (place) {
                        case "dimensions" -> List.of(Map.of("field", "missing"));
                        case "measures" -> List.of(measure("missing", "SUM"));
                        default -> List.of(Map.of("field", "missing", "operator", "EQ", "values", List.of("x")));
                    });
            assertThat(preview(screen, dataset, bad).get("code")).isEqualTo("dataset.fieldMissing");
        }
    }

    @Test
    void editorCatalogNeedsOnlyScreenEditAndFieldEditsAffectPublishedQueriesImmediately() throws Exception {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, q);
        UUID editor = UUID.randomUUID();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, editor, List.of("screen:edit"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, editor);
        var catalog = call(HttpMethod.GET, "/screens/" + screen + "/draft/datasets?page=1&size=100", null);
        assertThat(catalog.get("code")).isEqualTo("ok");
        assertThat(catalog.toString())
                .contains(dataset, "region", "amount")
                .doesNotContain("password", "username", "SELECT", "dataSourceId");
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        var changed = call(
                HttpMethod.PUT,
                "/datasets/" + dataset,
                Map.of(
                        "name",
                        "销售数据集",
                        "dataSourceId",
                        datasetSources.get(dataset),
                        "sql",
                        "SELECT 3 AS renamed, 'east' AS region"));
        assertThat(changed.get("httpStatus")).isEqualTo(200);
        var result = call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null);
        assertThat(result.get("code")).isEqualTo("dataset.fieldMissing");
        assertThat(result.get("userTip")).isEqualTo("字段不存在，请重新配置组件");
    }

    @Test
    void publishedQueriesCannotBypassPermissionTenantScopeOrFrozenWidgetConfig() throws Exception {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, q);
        var changed = new HashMap<>(q);
        changed.put("filters", List.of(Map.of("field", "region", "operator", "EQ", "values", List.of("missing"))));
        call(HttpMethod.PUT, "/screens/" + screen + "/draft", draft(dataset, changed, 0));
        String path = "/screens/" + screen + "/published/widgets/bar/query";
        UUID reader = UUID.fromString("019a0110-0000-7000-8000-0000000000e1");
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, reader, List.of("screen:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, reader);
        assertThat(rows(call(HttpMethod.GET, path + "?datasetId=" + UUID.randomUUID(), null))
                        .size())
                .isEqualTo(3);
        assertThat(preview(screen, dataset, q).get("httpStatus")).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/unknown/query", null)
                        .get("code"))
                .isEqualTo("screen.widgetNotFound");
        UUID denied = UUID.fromString("019a0110-0000-7000-8000-0000000000e2");
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                denied,
                1,
                List.of("screen:view"),
                SubjectAttributes.NONE,
                List.of(DataScope.SELF));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, denied);
        assertThat(call(HttpMethod.GET, path, null).get("httpStatus")).isEqualTo(404);
        UUID deniedDept = UUID.randomUUID();
        token = TestTokens.forMembership(TestTokens.TENANT_ID, deniedDept);
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                deniedDept,
                1,
                List.of("screen:view"),
                new SubjectAttributes(java.util.Set.of("other"), "other", java.util.Set.of(), Map.of(), Map.of()),
                List.of(DataScope.DEPARTMENT));
        assertThat(call(HttpMethod.GET, path, null).get("httpStatus")).isEqualTo(404);
        UUID tenant = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, reader, List.of("screen:view"));
        token = TestTokens.forMembership(tenant, reader);
        assertThat(call(HttpMethod.GET, path, null).get("httpStatus")).isEqualTo(404);
        UUID noView = UUID.randomUUID();
        token = TestTokens.forMembership(TestTokens.TENANT_ID, noView);
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, noView, List.of("screen:edit"));
        assertThat(call(HttpMethod.GET, path, null).get("httpStatus")).isEqualTo(403);
        var unpublished = call(HttpMethod.POST, "/screens", Map.of("name", "未发布", "draft", draft(dataset, q, 0)));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, reader);
        assertThat(call(HttpMethod.GET, "/screens/" + id(unpublished) + "/published/widgets/bar/query", null)
                        .get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void invalidRefreshAndQueryConfigurationAreRejected() {
        String dataset = dataset(SALES);
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, q);
        assertThat(call(HttpMethod.PUT, "/screens/" + screen + "/draft", draft(dataset, new HashMap<>(), 0))
                        .get("httpStatus"))
                .isEqualTo(400);
        for (int interval : List.of(-1, 1, 9)) {
            assertThat(call(HttpMethod.PUT, "/screens/" + screen + "/draft", draft(dataset, q, interval))
                            .get("httpStatus"))
                    .isEqualTo(400);
        }
        for (int interval : List.of(0, 10, 30)) {
            assertThat(call(HttpMethod.PUT, "/screens/" + screen + "/draft", draft(dataset, q, interval))
                            .get("httpStatus"))
                    .isEqualTo(200);
        }
        for (Object filters : List.of(
                List.of(Map.of("field", "amount", "operator", "IN", "values", List.of())),
                List.of(Map.of("field", "amount", "operator", "BETWEEN", "values", List.of("20", "1"))),
                List.of(Map.of("field", "amount", "operator", "EQ", "values", List.of("bad"))))) {
            var invalid = new HashMap<>(q);
            invalid.put("filters", filters);
            assertThat(preview(screen, dataset, invalid).get("code")).isEqualTo("dataset.queryInvalid");
        }
        var invalid = new HashMap<>(q);
        invalid.put("sorts", List.of(Map.of("column", 100, "direction", "ASC")));
        assertThat(preview(screen, dataset, invalid).get("code")).isEqualTo("dataset.queryInvalid");
    }

    @Test
    void datasetJsonOperatorsAndQuestionMarkLiteralsKeepTheirMeaningWithBoundFilters() {
        String dataset = dataset("SELECT CAST('{\"key\":1}' AS jsonb) ? 'key' AS present, '?' AS label, 3 AS amount");
        var q = query(List.of(Map.of("field", "present"), Map.of("field", "label")), List.of(measure("amount", "SUM")));
        q.put("filters", List.of(Map.of("field", "label", "operator", "EQ", "values", List.of("?"))));
        String screen = screen(dataset, q);
        var result = call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/bar/query", null);
        assertThat(result.get("code")).isEqualTo("ok");
        assertThat(rows(result).toString()).isEqualTo("[[true, ?, 3]]");
    }

    Map<?, ?> preview(String screen, String dataset, Map<String, Object> q) {
        Map<?, ?> widget = (Map<?, ?>) ((List<?>) draft(dataset, q, 0).get("widgets")).get(0);
        return call(HttpMethod.POST, "/screens/" + screen + "/draft/query", widget);
    }

    Map<String, Object> query(List<?> dimensions, List<?> measures) {
        return new HashMap<>(
                Map.of("dimensions", dimensions, "measures", measures, "filters", List.of(), "sorts", List.of()));
    }

    Map<String, Object> measure(String field, String aggregation) {
        return Map.of("field", field, "aggregation", aggregation);
    }

    String dataset(String sql) {
        var source = call(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "图表数据源",
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
        assertThat(source.get("httpStatus")).isEqualTo(201);
        var result =
                call(HttpMethod.POST, "/datasets", Map.of("name", "销售数据集", "dataSourceId", id(source), "sql", sql));
        assertThat(result.get("httpStatus")).isEqualTo(201);
        datasetSources.put(id(result), id(source));
        return id(result);
    }

    String screen(String dataset, Map<String, Object> query) {
        var result = call(HttpMethod.POST, "/screens", Map.of("name", "销售大屏", "draft", draft(dataset, query, 0)));
        assertThat(result.get("httpStatus")).isEqualTo(201);
        String id = id(result);
        assertThat(call(HttpMethod.POST, "/screens/" + id + "/publish", null).get("code"))
                .isEqualTo("ok");
        return id;
    }

    Map<String, Object> draft(String dataset, Map<String, Object> query, int refresh) {
        return Map.of(
                "designWidth",
                1920,
                "designHeight",
                1080,
                "backgroundColor",
                "#101827",
                "widgets",
                List.of(Map.of(
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
                        Map.of("datasetId", dataset, "query", query, "refreshSeconds", refresh))));
    }

    String id(Map<?, ?> result) {
        return (String) ((Map<?, ?>) result.get("data")).get("id");
    }

    List<?> rows(Map<?, ?> result) {
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
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
