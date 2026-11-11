package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Oracle 类 Data Source 的测试连接。Oracle 容器启动需要一分钟以上，因此打上 {@code slow-dialect} 标签，默认 {@code verify}
 * 不运行，用 {@code mvn verify -Pslow-dialects} 运行。
 */
@Tag("slow-dialect")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OracleDataSourceTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalMetadata("ORACLE");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
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
                List.of("datasource:view", "datasource:manage"));
    }

    @Test
    void connectionTestSucceedsOrReturnsAReadableReason() {
        assertThat(test(request())).isEqualTo("ok");

        var wrongPassword = request();
        databaseOf(wrongPassword).put("password", "wrong-Passw0rd");
        assertThat(test(wrongPassword)).isEqualTo("datasource.authFailed");

        var missingService = request();
        databaseOf(missingService).put("database", "no_such_service");
        assertThat(test(missingService)).isEqualTo("datasource.databaseNotFound");

        var closedPort = request();
        databaseOf(closedPort).put("port", 1);
        assertThat(test(closedPort)).isEqualTo("datasource.unreachable");
    }

    @Test
    void registersWithoutEchoingThePassword() {
        Map<?, ?> created = exchange(HttpMethod.POST, "/data-sources", request());
        assertThat(created.get("httpStatus")).isEqualTo(201);
        assertThat((Map<Object, Object>) created.get("data"))
                .containsEntry("type", "ORACLE")
                .doesNotContainKey("password");
        assertThat(created.toString()).doesNotContain(external.password());
    }

    /** 服务名直接拼进连接描述符，只能是服务名字符，否则可借括号或分隔符改写描述符。 */
    @ParameterizedTest
    @ValueSource(strings = {"freepdb1)(ADDRESS=(HOST=10.0.0.1))", "freepdb1?oracle.net.wallet_location=x", "a b"})
    void rejectsServiceNamesThatCouldRewriteTheDescriptor(String serviceName) {
        var body = request();
        databaseOf(body).put("database", serviceName);
        assertThat(exchange(HttpMethod.POST, "/data-sources", body).get("code"))
                .isEqualTo("datasource.databaseInvalid");
        assertThat(test(body)).isEqualTo("datasource.databaseInvalid");
    }

    @Test
    void rejectsDriverParams() {
        var body = request();
        databaseOf(body).put("params", Map.of("oracle.net.wallet_location", "/tmp"));
        assertThat(test(body)).isEqualTo("datasource.paramNotAllowed");
    }

    private static Map<String, Object> request() {
        var body = new HashMap<String, Object>();
        body.put("name", "Oracle 外部库");
        body.put("type", "ORACLE");
        var database = new HashMap<String, Object>();
        database.put("host", external.host());
        database.put("port", external.port());
        database.put("database", external.database());
        database.put("username", external.username());
        database.put("password", external.password());
        database.put("params", Map.of());
        body.put("database", database);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> databaseOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("database");
    }

    @Test
    void browsesTablesAndColumnsUsingExactNames() {
        String id = (String) ((Map<?, ?>)
                        exchange(HttpMethod.POST, "/data-sources", request()).get("data"))
                .get("id");
        var tables = exchange(HttpMethod.GET, "/data-sources/" + id + "/tables", null);
        assertThat(tables.get("code")).isEqualTo("ok");
        assertThat((List<Map<String, Object>>) tables.get("data"))
                .contains(Map.of(
                        "catalog",
                        "",
                        "schema",
                        external.username().toUpperCase(Locale.ROOT),
                        "name",
                        "LC_META_ORDER_%"));
        var columns = exchange(
                HttpMethod.GET,
                "/data-sources/" + id + "/columns?schema=" + external.username().toUpperCase(Locale.ROOT)
                        + "&table=LC_META_ORDER_%25",
                null);
        assertThat(columns.get("code")).isEqualTo("ok");
        assertThat((List<Map<String, Object>>) columns.get("data"))
                .containsExactly(
                        Map.of("name", "ORDER_ID", "type", "NUMBER"), Map.of("name", "AMOUNT", "type", "NUMBER"));
    }

    private Object test(Map<String, Object> body) {
        return exchange(HttpMethod.POST, "/data-sources/connection-test", body).get("code");
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(URI.create("http://localhost:" + port + uri))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) request = request.body(body);
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
