package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class ClickHouseDataSourceTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalClickHouse();

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

    @Test
    void connectionProbeExecutesAQueryAndReportsSafeReasons() {
        assertThat(test(request())).isEqualTo("ok");
        var wrong = request();
        database(wrong).put("password", "wrong");
        assertThat(test(wrong)).isEqualTo("datasource.authFailed");
        var missing = request();
        database(missing).put("database", "no_such_db");
        assertThat(test(missing)).isEqualTo("datasource.databaseNotFound");
        var closed = request();
        database(closed).put("port", 1);
        var failure = exchange(HttpMethod.POST, "/data-sources/connection-test", closed);
        assertThat(failure.get("code")).isEqualTo("datasource.unreachable");
        assertThat(failure.toString()).doesNotContain(external.password(), external.username(), "Connection refused");
    }

    @Test
    void registersAndBrowsesExactTableNamesAndPreviewsSqlDatasets() {
        var created = exchange(HttpMethod.POST, "/data-sources", request());
        assertThat(created.get("httpStatus")).isEqualTo(201);
        assertThat(created.toString()).doesNotContain(external.password(), "passwordEncrypted");
        String source = (String) ((Map<?, ?>) created.get("data")).get("id");
        var tables = exchange(HttpMethod.GET, "/data-sources/" + source + "/tables", null);
        assertThat(tables.get("code")).isEqualTo("ok");
        assertThat((List<?>) tables.get("data"))
                .isEqualTo(List.of(Map.of("catalog", "default", "schema", "", "name", "LC_META_ORDER_%")));
        var columns = exchange(
                HttpMethod.GET,
                "/data-sources/" + source + "/columns?catalog=default&schema=&table=LC_META_ORDER_%25",
                null);
        assertThat((List<?>) columns.get("data"))
                .isEqualTo(List.of(
                        Map.of("name", "order_id", "type", "Int64"),
                        Map.of("name", "amount", "type", "Nullable(Decimal(18, 2))")));
        assertThat(exchange(
                                HttpMethod.GET,
                                "/data-sources/" + source + "/columns?catalog=system&schema=&table=users",
                                null)
                        .get("code"))
                .isEqualTo("datasource.tableNotFound");
        String sql =
                "SELECT 12.50 AS amount, 'east' AS region, toDate('2026-11-11') AS day, true AS active, CAST(NULL AS Nullable(Int64)) AS missing";
        var preview = exchange(HttpMethod.POST, "/datasets/preview", Map.of("dataSourceId", source, "sql", sql));
        assertThat(preview.get("code")).isEqualTo("ok");
        var data = (Map<?, ?>) preview.get("data");
        var fields = (List<Map<String, Object>>) data.get("fields");
        assertThat(fields)
                .extracting(f -> f.get("type"))
                .containsExactly("NUMBER", "STRING", "DATE", "BOOLEAN", "NUMBER");
        assertThat((List<?>) data.get("rows"))
                .isEqualTo(java.util.Arrays.asList(java.util.Arrays.asList(12.5, "east", "2026-11-11", true, null)));
        var saved = exchange(
                HttpMethod.POST, "/datasets", Map.of("name", "ClickHouse 日期销售", "dataSourceId", source, "sql", sql));
        assertThat(saved.get("httpStatus")).isEqualTo(201);
        assertThat(exchange(
                                HttpMethod.POST,
                                "/datasets/preview",
                                Map.of(
                                        "dataSourceId",
                                        source,
                                        "sql",
                                        "WITH q AS (SELECT 1 AS n) SELECT * FROM q; -- end"))
                        .get("code"))
                .isEqualTo("ok");
    }

    @Test
    void rejectsMalformedSuccessResponsesAndNeverFollowsRedirects() throws Exception {
        var response = new AtomicReference<>("{\"data\":[{}]}");
        var stolen = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestURI().getPath().equals("/stolen")) {
                stolen.incrementAndGet();
            }
            if (response.get().equals("redirect")) {
                exchange.getResponseHeaders()
                        .set(
                                "Location",
                                "http://localhost:" + server.getAddress().getPort() + "/stolen");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var body = request();
            database(body).put("host", "localhost");
            database(body).put("port", server.getAddress().getPort());
            for (String content : List.of(
                    "{\"data\":[{}]}",
                    "{\"data\":[[]]}",
                    "{}",
                    "null",
                    "not JSON",
                    "{\"data\":[[1]]} failure: secret",
                    "{\"data\":[[1]],\"exception\":\"secret\"}",
                    "redirect")) {
                response.set(content);
                var result = exchange(HttpMethod.POST, "/data-sources/connection-test", body);
                assertThat(result.get("code")).as(content).isEqualTo("datasource.connectionFailed");
                assertThat(result.toString()).doesNotContain("secret", external.password());
            }
            assertThat(stolen).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enforcesReadOnlyTimeoutAndRowBudgets() {
        String source = (String) ((Map<?, ?>)
                        exchange(HttpMethod.POST, "/data-sources", request()).get("data"))
                .get("id");
        var readonly = exchange(
                HttpMethod.POST,
                "/datasets/preview",
                Map.of("dataSourceId", source, "sql", "SELECT getSetting('readonly') AS mode"));
        assertThat(((Map<?, ?>) readonly.get("data")).get("rows")).isEqualTo(List.of(List.of(1)));
        var limited = exchange(
                HttpMethod.POST,
                "/datasets/preview",
                Map.of("dataSourceId", source, "sql", "SELECT number AS amount FROM numbers(5)"));
        var data = (Map<?, ?>) limited.get("data");
        assertThat(data.get("rows")).isEqualTo(List.of(List.of(0), List.of(1), List.of(2)));
        assertThat(data.get("truncated")).isEqualTo(true);
        assertThat(data.get("maxRows")).isEqualTo(3);
        assertThat(exchange(
                                HttpMethod.POST,
                                "/datasets/preview",
                                Map.of("dataSourceId", source, "sql", "SELECT sleep(3) AS wait"))
                        .get("code"))
                .isEqualTo("dataset.queryTimeout");
        assertThat(exchange(
                                HttpMethod.POST,
                                "/datasets/preview",
                                Map.of("dataSourceId", source, "sql", "DROP TABLE anything"))
                        .get("code"))
                .isEqualTo("dataset.sqlNotAllowed");
    }

    @Test
    void responseLimitRejectsDeclaredOversizeBeforeReadingTheBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", response -> {
            // 声明超限后不发送正文，证明客户端在读取或解析正文前拒绝，而非等待查询超时。
            response.sendResponseHeaders(200, 1100000);
            response.close();
        });
        server.start();
        try {
            var body = request();
            database(body).put("host", "localhost");
            database(body).put("port", server.getAddress().getPort());
            assertThat(test(body)).isEqualTo("datasource.connectionFailed");
            String source = (String) ((Map<?, ?>)
                            exchange(HttpMethod.POST, "/data-sources", body).get("data"))
                    .get("id");
            assertThat(exchange(
                                    HttpMethod.POST,
                                    "/datasets/preview",
                                    Map.of("dataSourceId", source, "sql", "SELECT 1 AS n"))
                            .get("code"))
                    .isEqualTo("dataset.queryFailed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnlistedConnectionParametersAndEncodesDatabaseNames() {
        for (var param : List.of(
                Map.of("ssl", "disabled"),
                Map.of("url", "http://localhost"),
                Map.of("follow_redirects", "true"),
                Map.of("sslTrustStore", "/etc/passwd"),
                Map.of("sslMode", "NONE"),
                Map.of("proxyHost", "localhost"),
                Map.of("socketFactory", "anything"))) {
            var body = request();
            database(body).put("params", param);
            assertThat(exchange(HttpMethod.POST, "/data-sources", body).get("code"))
                    .isEqualTo("datasource.paramNotAllowed");
            assertThat(test(body)).isEqualTo("datasource.paramNotAllowed");
        }
        var body = request();
        database(body).put("params", Map.of("ssl", "true"));
        assertThat(exchange(HttpMethod.POST, "/data-sources", body).get("httpStatus"))
                .isEqualTo(201);
        database(body).put("params", Map.of("ssl", "false"));
        database(body).put("database", "default&readonly=0");
        assertThat(test(body)).isEqualTo("datasource.databaseNotFound");
    }

    @Test
    void tlsVerifiesOriginalHostnameWithoutUsingServiceClientKeyStore(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("server.p12");
        var keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-alias",
                        "server",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        storePath.toString(),
                        "-storepass",
                        "changeit",
                        "-keypass",
                        "changeit",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-noprompt")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertThat(keytool.waitFor()).isEqualTo(0);
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storePath)) {
            store.load(input, "changeit".toCharArray());
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        var received = new AtomicInteger();
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            byte[] response = "{\"data\":[[1]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        String previousTrust = System.getProperty("javax.net.ssl.trustStore");
        String previousTrustPassword = System.getProperty("javax.net.ssl.trustStorePassword");
        String previousKey = System.getProperty("javax.net.ssl.keyStore");
        server.start();
        try {
            System.setProperty("javax.net.ssl.trustStore", storePath.toString());
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
            System.setProperty(
                    "javax.net.ssl.keyStore",
                    directory.resolve("service-secret-does-not-exist.p12").toString());
            var body = request();
            database(body).put("host", "localhost");
            database(body).put("port", server.getAddress().getPort());
            database(body).put("params", Map.of("ssl", "true"));
            assertThat(test(body)).isEqualTo("ok");
            assertThat(received).hasValue(1);
            // 同一已信任证书不含 IP SAN；连接检查过的 IP 时仍须校验填写的原始主机。
            database(body).put("host", "127.0.0.1");
            assertThat(test(body)).isEqualTo("datasource.unreachable");
            assertThat(received).hasValue(1);
        } finally {
            restoreProperty("javax.net.ssl.trustStore", previousTrust);
            restoreProperty("javax.net.ssl.trustStorePassword", previousTrustPassword);
            restoreProperty("javax.net.ssl.keyStore", previousKey);
            server.stop(0);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static Map<String, Object> request() {
        var body = new HashMap<String, Object>();
        body.put("name", "ClickHouse 销售明细");
        body.put("type", "CLICKHOUSE");
        body.put(
                "database",
                new HashMap<>(Map.of(
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
                        Map.of())));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> database(Map<String, Object> body) {
        return (Map<String, Object>) body.get("database");
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
        if (body != null) {
            request = request.body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
