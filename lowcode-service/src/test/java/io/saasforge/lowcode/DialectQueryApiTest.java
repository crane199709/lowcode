package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
abstract class DialectQueryApiTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.database", redis::database);
        r.add("lowcode.datasource.sql.max-rows", () -> 20);
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

    abstract String type();

    @Test
    void allAggregatesAndTopN() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        String numeric =
                type().equals("CLICKHOUSE") ? "Nullable(Int32)" : type().equals("MYSQL") ? "SIGNED" : "INTEGER";
        String dataset = dataset("SELECT 'east' AS region, 1 AS amount" + suffix + " UNION ALL SELECT 'east', 2"
                + suffix + " UNION ALL SELECT 'west', 9" + suffix + " UNION ALL SELECT 'west', CAST(NULL AS "
                + numeric + ")" + suffix);
        var q = query(
                List.of(Map.of("field", "region")),
                List.of(
                        measure("amount", "SUM"),
                        measure("amount", "AVG"),
                        measure("amount", "COUNT"),
                        measure("amount", "COUNT_DISTINCT"),
                        measure("amount", "MAX"),
                        measure("amount", "MIN")));
        String screen = screen(dataset, q);
        QueryResultAssertions.integerAggregates(rows(preview(screen, dataset, q)));
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        q.put("topN", 1);
        QueryResultAssertions.integerTopN(rows(preview(screen, dataset, q)));
    }

    @Test
    void isoDateStringsUseCanonicalUtcBuckets() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        String text = type().equals("SQLSERVER")
                ? "VARCHAR(100)"
                : type().equals("ORACLE")
                        ? "VARCHAR2(100)"
                        : type().equals("CLICKHOUSE")
                                ? "String"
                                : type().equals("MYSQL") ? "CHAR(100)" : "VARCHAR(100)";
        String sql = "SELECT CAST('2024-02-29' AS " + text + ") AS day, 1 AS amount" + suffix
                + " UNION ALL SELECT '2024-03-01',2"
                + suffix + " UNION ALL SELECT '2025-02-01',4" + suffix + " UNION ALL SELECT NULL,8"
                + suffix + " UNION ALL SELECT '2025-01-01T00:30:00+01:00',16" + suffix
                + " UNION ALL SELECT '2024-02-29T23:30:00',32"
                + suffix;
        sql = normalize(sql);
        String dataset = dataset(sql);
        var fields = List.of(Map.of("name", "day", "displayName", "日期", "role", "DIMENSION", "date", true));
        assertThat(call(
                                HttpMethod.PUT,
                                "/datasets/" + dataset,
                                Map.of(
                                        "name",
                                        "ISO日期",
                                        "dataSourceId",
                                        datasetSources.get(dataset),
                                        "sql",
                                        sql,
                                        "fields",
                                        fields))
                        .get("code"))
                .isEqualTo("ok");
        var q = query(List.of(Map.of("field", "day", "dateGrouping", "DAY")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, q);
        var result = preview(screen, dataset, q);
        assertThat(result.get("code")).isEqualTo("ok");
        QueryResultAssertions.dateDay(rows(result));
        q.put("dimensions", List.of(Map.of("field", "day", "dateGrouping", "MONTH")));
        result = preview(screen, dataset, q);
        assertThat(result.get("code")).isEqualTo("ok");
        QueryResultAssertions.dateMonth(rows(result));
        q.put("dimensions", List.of(Map.of("field", "day", "dateGrouping", "YEAR")));
        result = preview(screen, dataset, q);
        assertThat(result.get("code")).isEqualTo("ok");
        QueryResultAssertions.dateYear(rows(result));
    }

    @Test
    void nativeDateTimesUseTheSameUtcBuckets() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        String sql = normalize("SELECT " + nativeDate("2024-02-29T00:00:00+00:00") + " AS day,1 AS amount" + suffix
                + " UNION ALL SELECT "
                + nativeDate("2024-03-01T00:00:00+00:00") + ",2" + suffix + " UNION ALL SELECT "
                + nativeDate("2025-02-01T00:00:00+00:00") + ",4" + suffix + " UNION ALL SELECT NULL,8"
                + suffix + " UNION ALL SELECT "
                + nativeDate("2025-01-01T00:30:00+01:00") + ",16" + suffix + " UNION ALL SELECT "
                + nativeDate("2024-02-29T23:30:00+00:00") + ",32" + suffix);
        String dataset = dataset(sql);
        var q = query(List.of(Map.of("field", "day", "dateGrouping", "DAY")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, q);
        QueryResultAssertions.dateDay(rows(preview(screen, dataset, q)));
        q.put("dimensions", List.of(Map.of("field", "day", "dateGrouping", "MONTH")));
        QueryResultAssertions.dateMonth(rows(preview(screen, dataset, q)));
        q.put("dimensions", List.of(Map.of("field", "day", "dateGrouping", "YEAR")));
        QueryResultAssertions.dateYear(rows(preview(screen, dataset, q)));
    }

    String nativeDate(String value) {
        return switch (type()) {
            case "POSTGRESQL" -> "CAST('" + value + "' AS timestamptz)";
            case "SQLSERVER" -> "CAST('" + value + "' AS datetimeoffset)";
            case "ORACLE" -> "TO_TIMESTAMP_TZ('" + value + "','YYYY-MM-DD\"T\"HH24:MI:SSTZH:TZM')";
            case "CLICKHOUSE" -> "parseDateTime64BestEffort('" + value + "',9,'UTC')";
            // MySQL DATETIME 不保存 offset，独立夹具在进入 Dataset 前已按 UTC 存储。
            case "MYSQL" ->
                "CAST('"
                        + (value.equals("2025-01-01T00:30:00+01:00")
                                ? "2024-12-31 23:30:00"
                                : value.substring(0, 19).replace('T', ' '))
                        + "' AS DATETIME)";
            default -> throw new IllegalArgumentException();
        };
    }

    @Test
    void nullsFiltersAndEmptyInputsMatchSharedResults() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        String sql = "SELECT 'east' AS region,1.0 AS amount" + suffix + " UNION ALL SELECT 'east',2.0"
                + suffix + " UNION ALL SELECT 'east',2.00" + suffix + " UNION ALL SELECT 'east',3.0"
                + suffix + " UNION ALL SELECT 'east',NULL" + suffix + " UNION ALL SELECT 'west',4.0"
                + suffix + " UNION ALL SELECT NULL,3.0" + suffix;
        String dataset = dataset(sql);
        var q = query(
                List.of(Map.of("field", "region")),
                List.of(
                        measure("amount", "SUM"),
                        measure("amount", "AVG"),
                        measure("amount", "COUNT"),
                        measure("amount", "COUNT_DISTINCT"),
                        measure("amount", "MAX"),
                        measure("amount", "MIN")));
        String screen = screen(dataset, q);
        QueryResultAssertions.sales(rows(preview(screen, dataset, q)));
        q.put("sorts", List.of(Map.of("column", 0, "direction", "DESC")));
        q.put("topN", 1);
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {"west", 4, 4, 1, 1, 4, 4});
        q.remove("topN");
        q.put("sorts", List.of());
        q.put("filters", List.of(Map.of("field", "region", "operator", "EQ", "values", java.util.Arrays.asList((Object)
                null))));
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {null, 3, 3, 1, 1, 3, 3});
        q.put(
                "filters",
                List.of(
                        Map.of("field", "region", "operator", "IN", "values", java.util.Arrays.asList("west", null)),
                        Map.of("field", "amount", "operator", "BETWEEN", "values", List.of(3, 4))));
        QueryResultAssertions.rows(
                rows(preview(screen, dataset, q)),
                new Object[] {"west", 4, 4, 1, 1, 4, 4},
                new Object[] {null, 3, 3, 1, 1, 3, 3});
        q.put("dimensions", List.of());
        q.put("filters", List.of(Map.of("field", "amount", "operator", "EQ", "values", java.util.Arrays.asList((Object)
                null))));
        QueryResultAssertions.allNull(rows(preview(screen, dataset, q)));
        q.put("filters", List.of(Map.of("field", "region", "operator", "EQ", "values", List.of("missing' OR 1=1 --"))));
        QueryResultAssertions.allNull(rows(preview(screen, dataset, q)));
    }

    @Test
    void invalidIsoDateCannotBeHiddenByTopN() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        for (String invalid : List.of("not-a-date", "2023-02-29")) {
            String sql = normalize("SELECT '2024-02-29' AS day,99 AS amount" + suffix + " UNION ALL SELECT '" + invalid
                    + "',1" + suffix);
            String dataset = dataset(sql);
            markDate(dataset, sql);
            var q = query(List.of(Map.of("field", "day", "dateGrouping", "DAY")), List.of(measure("amount", "SUM")));
            q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
            q.put("topN", 1);
            String screen = screen(dataset, q);
            var result = preview(screen, dataset, q);
            assertThat(result.get("code")).isEqualTo("dataset.queryDateInvalid");
            assertThat(result.get("userTip").toString()).contains("ISO");
        }
    }

    @Test
    void isoFractionAndExtremeOffsetsStayOnTheirUtcDays() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        String sql = normalize("SELECT '2024-02-29T23:59:59.999999999+00:00' AS day,1 AS amount" + suffix
                + " UNION ALL SELECT '2025-01-01T00:15:00+14:00',2"
                + suffix + " UNION ALL SELECT '2024-12-31T23:45:00-13:59',4"
                + suffix);
        String dataset = dataset(sql);
        markDate(dataset, sql);
        var q = query(List.of(Map.of("field", "day", "dateGrouping", "DAY")), List.of(measure("amount", "SUM")));
        String screen = screen(dataset, q);
        QueryResultAssertions.rows(
                rows(preview(screen, dataset, q)),
                new Object[] {"2024-02-29", 1},
                new Object[] {"2024-12-31", 2},
                new Object[] {"2025-01-01", 4});
    }

    @Test
    void sourceCtesOrderingAndRowLimitsKeepTheirMeaning() {
        String suffix = type().equals("ORACLE") ? " FROM dual" : "";
        String source = normalize("SELECT 'east' AS region,1 AS amount" + suffix + " UNION ALL SELECT 'east',2" + suffix
                + " UNION ALL SELECT 'west',9" + suffix);
        String ordered = " ORDER BY " + quote("amount") + " DESC";
        String dataset = dataset("WITH sales AS (" + source + ") SELECT * FROM sales" + ordered + ";");
        var q = query(List.of(Map.of("field", "region")), List.of(measure("amount", "SUM")));
        q.put("sorts", List.of(Map.of("column", 1, "direction", "DESC")));
        q.put("topN", 1);
        String screen = screen(dataset, q);
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {"west", 9});
        dataset = dataset(
                type().equals("CLICKHOUSE")
                        ? "SELECT * FROM (" + source + ") ordered_source" + ordered
                        : source + ordered);
        q = query(List.of(), List.of(measure("amount", "SUM")));
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {12});
        String limited = type().equals("SQLSERVER")
                ? "SELECT TOP (2) * FROM (" + source + ") source_rows" + ordered
                : "SELECT * FROM (" + source + ") source_rows" + ordered
                        + (type().equals("ORACLE") ? " FETCH FIRST 2 ROWS ONLY" : " LIMIT 2");
        dataset = dataset(limited);
        QueryResultAssertions.rows(rows(preview(screen, dataset, q)), new Object[] {11});
    }

    String quote(String field) {
        return switch (type()) {
            case "MYSQL", "CLICKHOUSE" -> "`" + field.replace("`", "``") + "`";
            case "SQLSERVER" -> "[" + field.replace("]", "]]") + "]";
            default -> "\"" + field.replace("\"", "\"\"") + "\"";
        };
    }

    String normalize(String sql) {
        return type().equals("ORACLE")
                ? sql.replace("AS region", "AS \"region\"")
                        .replace("AS amount", "AS \"amount\"")
                        .replace("AS day", "AS \"day\"")
                : sql;
    }

    void markDate(String dataset, String sql) {
        assertThat(call(
                                HttpMethod.PUT,
                                "/datasets/" + dataset,
                                Map.of(
                                        "name",
                                        "ISO日期",
                                        "dataSourceId",
                                        datasetSources.get(dataset),
                                        "sql",
                                        sql,
                                        "fields",
                                        List.of(Map.of(
                                                "name",
                                                "day",
                                                "displayName",
                                                "日期",
                                                "role",
                                                "DIMENSION",
                                                "date",
                                                true))))
                        .get("code"))
                .isEqualTo("ok");
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
        if (type().equals("ORACLE")) {
            sql = sql.replace("AS region", "AS \"region\"")
                    .replace("AS amount", "AS \"amount\"")
                    .replace("AS day", "AS \"day\"");
        }
        var external = switch (type()) {
            case "POSTGRESQL" -> SharedContainers.externalPostgres();
            case "MYSQL" -> SharedContainers.externalMysql();
            case "SQLSERVER" -> SharedContainers.externalSqlServer();
            case "ORACLE" -> SharedContainers.externalOracle();
            case "CLICKHOUSE" -> SharedContainers.externalClickHouse();
            default -> throw new IllegalArgumentException();
        };
        var source = call(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "图表数据源",
                        "type",
                        type(),
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
                                switch (type()) {
                                    case "POSTGRESQL" -> Map.of("sslmode", "disable");
                                    case "MYSQL" -> Map.of();
                                    case "SQLSERVER" -> Map.of("encrypt", "false");
                                    case "CLICKHOUSE" -> Map.of("ssl", "false");
                                    default -> Map.of();
                                })));
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
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}

class PostgresQueryApiTest extends DialectQueryApiTest {
    String type() {
        return "POSTGRESQL";
    }
}

class MysqlQueryApiTest extends DialectQueryApiTest {
    String type() {
        return "MYSQL";
    }
}

class SqlServerQueryApiTest extends DialectQueryApiTest {
    String type() {
        return "SQLSERVER";
    }
}

class ClickHouseQueryApiTest extends DialectQueryApiTest {
    String type() {
        return "CLICKHOUSE";
    }
}

@org.junit.jupiter.api.Tag("slow-dialect")
class OracleQueryApiTest extends DialectQueryApiTest {
    String type() {
        return "ORACLE";
    }
}
