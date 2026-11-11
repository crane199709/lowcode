package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * MySQL、SQL Server 类 Data Source 的登记与测试连接，以 Testcontainers 容器充当 Tenant 自有的外部数据库做真实连接。
 * 连接安全的通用规则（禁止网段、密码不回显、权限）与类型无关，由 {@link DataSourceApiTest} 覆盖；Oracle 见
 * {@link OracleDataSourceTest}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSourceDialectTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    // 在类加载时启动：首次拉取镜像可能耗时很久，放到用例中启动会让用例开始前签发的令牌过期
    static final SharedContainers.ExternalDatabase mysql = SharedContainers.externalMysql();
    static final SharedContainers.ExternalDatabase sqlServer = SharedContainers.externalSqlServer();

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

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "SQLSERVER"})
    void connectionTestSucceedsOrReturnsAReadableReason(String type) {
        assertThat(test(request(type))).isEqualTo("ok");

        var wrongPassword = request(type);
        wrongPassword.put("password", "wrong-Passw0rd");
        assertThat(test(wrongPassword)).isEqualTo("datasource.authFailed");

        var missingDatabase = request(type);
        missingDatabase.put("database", "no_such_db");
        assertThat(test(missingDatabase)).isEqualTo("datasource.databaseNotFound");

        var closedPort = request(type);
        closedPort.put("port", 1);
        assertThat(test(closedPort)).isEqualTo("datasource.unreachable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "SQLSERVER"})
    void registersEachTypeWithoutEchoingThePassword(String type) {
        Map<?, ?> created = exchange(HttpMethod.POST, "/data-sources", request(type));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        assertThat((Map<Object, Object>) created.get("data"))
                .containsEntry("type", type)
                .doesNotContainKey("password");
        assertThat(created.toString()).doesNotContain(external(type).password());
    }

    @Test
    void mysqlConnectionDoesNotUseTheServiceClientKeyStore(@TempDir Path directory) {
        String previous = System.getProperty("javax.net.ssl.keyStore");
        // 不存在的证书文件使驱动只要尝试读取服务的客户端证书就连接失败；租户的密码连接不应受它影响。
        System.setProperty(
                "javax.net.ssl.keyStore",
                directory.resolve("service-client.p12").toString());
        try {
            var body = request("MYSQL");
            body.put("params", Map.of("sslMode", "REQUIRED"));
            assertThat(test(body)).isEqualTo("ok");
        } finally {
            if (previous == null) {
                System.clearProperty("javax.net.ssl.keyStore");
            } else {
                System.setProperty("javax.net.ssl.keyStore", previous);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "MYSQL, sslMode, REQUIRED",
        "MYSQL, allowPublicKeyRetrieval, true",
        "SQLSERVER, encrypt, true",
        "SQLSERVER, trustServerCertificate, true",
    })
    void acceptsWhitelistedParams(String type, String param, String value) {
        var body = request(type);
        body.put("params", Map.of(param, value));
        assertThat(exchange(HttpMethod.POST, "/data-sources", body).get("httpStatus"))
                .isEqualTo(201);
    }

    @ParameterizedTest
    @CsvSource({"MYSQL, test?allowLoadLocalInfile=true", "SQLSERVER, master;databaseName=master"})
    void databaseNameCannotInjectConnectionProperties(String type, String database) {
        var body = request(type);
        body.put("database", database);
        // 若把库名中的分隔符当作 URL 参数，会连上原本存在的 test / master；整体作为库名则找不到该库。
        assertThat(test(body)).isEqualTo("datasource.databaseNotFound");
    }

    /** 驱动参数可被用来读取服务所在机器的文件、反序列化或借用服务的凭据（ADR-0002）。 */
    @ParameterizedTest
    @CsvSource({
        "MYSQL, allowLoadLocalInfile, true",
        "MYSQL, allowUrlInLocalInfile, true",
        "MYSQL, autoDeserialize, true",
        "MYSQL, fallbackToSystemKeyStore, true",
        "MYSQL, allowLoadLocalInfileInPath, /tmp",
        "MYSQL, sslMode, VERIFY_IDENTITY",
        "SQLSERVER, integratedSecurity, true",
        "SQLSERVER, trustStore, /etc/passwd",
        "POSTGRESQL, socketFactory, x",
    })
    void rejectsDangerousParams(String type, String param, String value) {
        var body = request(type);
        body.put("params", Map.of(param, value));
        assertThat(exchange(HttpMethod.POST, "/data-sources", body).get("code"))
                .isEqualTo("datasource.paramNotAllowed");
        assertThat(test(body)).isEqualTo("datasource.paramNotAllowed");
    }

    private static SharedContainers.ExternalDatabase external(String type) {
        return switch (type) {
            case "MYSQL" -> mysql;
            case "SQLSERVER" -> sqlServer;
            case "POSTGRESQL" -> SharedContainers.externalPostgres();
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static Map<String, Object> request(String type) {
        var external = external(type);
        var body = new HashMap<String, Object>();
        body.put("name", type + " 外部库");
        body.put("type", type);
        body.put("host", external.host());
        body.put("port", external.port());
        body.put("database", external.database());
        body.put("username", external.username());
        body.put("password", external.password());
        // MySQL 8 的 caching_sha2_password 在不用 SSL 时须取服务端公钥；SQL Server 容器为自签证书
        body.put(
                "params",
                switch (type) {
                    case "MYSQL" -> Map.of("sslMode", "DISABLED", "allowPublicKeyRetrieval", "true");
                    case "SQLSERVER" -> Map.of("encrypt", "false");
                    default -> Map.of();
                });
        return body;
    }

    private Object test(Map<String, Object> body) {
        return exchange(HttpMethod.POST, "/data-sources/connection-test", body).get("code");
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body) {
        return RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> response.bodyTo(Map.class));
    }
}
