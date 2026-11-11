package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.saasforge.lowcode.outbox.mapper.AuditOutboxMapper;
import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL Data Source 的登记与测试连接：以共享容器充当 Tenant 自有的外部数据库做真实连接。
 *
 * <p>禁止网段配置为链路本地与一个私有网段，不含容器所在的本机地址，否则真实连接也会被拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSourceApiTest {
    private static final UUID MANAGER = TestTokens.MEMBERSHIP_ID;
    private static final UUID VIEWER = UUID.fromString("019a0110-0000-7000-8000-0000000000d1");
    private static final UUID OUTSIDER = UUID.fromString("019a0110-0000-7000-8000-0000000000d2");
    private static final UUID OTHER_TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000d0");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalPostgres();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("lowcode.datasource.blocked-networks", () -> "169.254.0.0/16,10.0.0.0/8,fd00::/8");
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    AuditOutboxMapper auditOutboxMapper;

    @BeforeEach
    void grantManage() {
        TestGrants.publish(
                redisTemplate, TestTokens.TENANT_ID, MANAGER, List.of("datasource:view", "datasource:manage"));
    }

    @Test
    void registeredDataSourceNeverEchoesThePassword() throws Exception {
        Map<?, ?> created = exchange(HttpMethod.POST, "/data-sources", request("华东销售库"));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) created.get("data");
        assertThat(data)
                .containsEntry("name", "华东销售库")
                .containsEntry("type", "POSTGRESQL")
                .containsEntry("host", external.host())
                .containsEntry("port", external.port())
                .containsEntry("database", external.database())
                .containsEntry("username", external.username())
                .containsEntry("params", Map.of("sslmode", "disable"))
                .doesNotContainKey("password");

        String id = (String) data.get("id");
        Map<?, ?> fetched = exchange(HttpMethod.GET, "/data-sources/" + id, null);
        Map<?, ?> listed = exchange(HttpMethod.GET, "/data-sources", null);
        assertThat(fetched.toString()).doesNotContain(external.password()).doesNotContain("password");
        assertThat(listed.toString()).doesNotContain(external.password()).doesNotContain("password");
        assertThat((List<?>) listed.get("data"))
                .<Object>extracting(item -> ((Map<?, ?>) item).get("id"))
                .contains(id);
    }

    @Test
    void connectionTestSucceedsOrReturnsAReadableReason() throws Exception {
        assertThat(exchange(HttpMethod.POST, "/data-sources/connection-test", request("测试"))
                        .get("code"))
                .isEqualTo("ok");

        var wrongPassword = request("测试");
        databaseOf(wrongPassword).put("password", "wrong");
        Map<?, ?> authFailed = exchange(HttpMethod.POST, "/data-sources/connection-test", wrongPassword);
        assertThat(authFailed.get("code")).isEqualTo("datasource.authFailed");
        assertThat(authFailed.get("userTip")).isEqualTo("账号或密码错误");

        var missingDatabase = request("测试");
        databaseOf(missingDatabase).put("database", "no_such_db");
        assertThat(exchange(HttpMethod.POST, "/data-sources/connection-test", missingDatabase)
                        .get("code"))
                .isEqualTo("datasource.databaseNotFound");

        // 关闭的端口与无法解析的主机得到同一个原因，不暴露端口是否开放
        var closedPort = request("测试");
        databaseOf(closedPort).put("port", 1);
        var unknownHost = request("测试");
        databaseOf(unknownHost).put("host", "no-such-host.invalid");
        var responses = new ArrayList<Map<?, ?>>();
        for (var unreachable : List.of(closedPort, unknownHost)) {
            Map<?, ?> failed = exchange(HttpMethod.POST, "/data-sources/connection-test", unreachable);
            assertThat(failed.get("httpStatus")).isEqualTo(422);
            assertThat(failed.get("code")).isEqualTo("datasource.unreachable");
            assertThat(failed.get("userTip")).isEqualTo("无法连接到数据库，请检查主机、端口与网络");
            responses.add(failed);
        }
        // errorMessage 同样不能带解析出的地址或驱动的 SQLState
        assertThat(responses.get(0).get("errorMessage"))
                .isEqualTo(responses.get(1).get("errorMessage"));
    }

    @Test
    void savedPasswordIsNeverSentToAChangedServer() throws Exception {
        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/data-sources", request("防外泄"))
                        .get("data"))
                .get("id");
        // 留空密码却改了主机、端口或账号：可能是把已保存的密码引向自己控制的服务器，必须重新填写密码
        for (var change : List.<Map<String, Object>>of(
                Map.of("host", "203.0.113.10"), Map.of("port", 15432), Map.of("username", "other"))) {
            var body = request("防外泄");
            databaseOf(body).put("password", "");
            databaseOf(body).putAll(change);
            assertThat(exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", body)
                            .get("code"))
                    .as(change.toString())
                    .isEqualTo("datasource.passwordRequired");
            assertThat(exchange(HttpMethod.PUT, "/data-sources/" + id, body).get("code"))
                    .as(change.toString())
                    .isEqualTo("datasource.passwordRequired");
        }
    }

    @Test
    void hostInBlockedNetworkIsRejectedForSaveAndConnectionTest() throws Exception {
        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/data-sources", request("待修改"))
                        .get("data"))
                .get("id");
        // 元数据地址、私有网段、IPv6 私有网段与「任意地址」（多数系统上连到本机）
        for (String host : List.of("169.254.169.254", "10.1.2.3", "fd00::1", "0.0.0.0")) {
            var blocked = request("内网");
            databaseOf(blocked).put("host", host);
            for (var call : List.of(
                    exchange(HttpMethod.POST, "/data-sources", blocked),
                    exchange(HttpMethod.PUT, "/data-sources/" + id, blocked),
                    exchange(HttpMethod.POST, "/data-sources/connection-test", blocked),
                    exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", blocked))) {
                assertThat(call.get("httpStatus")).as(host).isEqualTo(400);
                assertThat(call.get("code")).as(host).isEqualTo("datasource.hostBlocked");
            }
        }
        assertThat(((Map<?, ?>) exchange(HttpMethod.GET, "/data-sources/" + id, null)
                                .get("data"))
                        .get("host"))
                .isEqualTo(external.host());
    }

    @Test
    void connectionParamsOutsideTheWhitelistAreRejected() throws Exception {
        // socketFactory 可让驱动实例化任意类；verify-full 读取服务所在机器的证书文件
        for (Map<String, String> params : List.of(
                Map.of("socketFactory", "org.springframework.context.support.ClassPathXmlApplicationContext"),
                Map.of("sslmode", "verify-full"))) {
            var body = request("参数");
            databaseOf(body).put("params", params);
            for (var call : List.of(
                    exchange(HttpMethod.POST, "/data-sources", body),
                    exchange(HttpMethod.POST, "/data-sources/connection-test", body))) {
                assertThat(call.get("httpStatus")).isEqualTo(400);
                assertThat(call.get("code")).isEqualTo("datasource.paramNotAllowed");
            }
        }
        // 多主机写法与 URL 分隔符不能经主机字段混入拼装的 URL
        var multiHost = request("多主机");
        databaseOf(multiHost).put("host", "a.example.com,10.0.0.1");
        assertThat(exchange(HttpMethod.POST, "/data-sources", multiHost).get("code"))
                .isEqualTo("request.invalid");
    }

    @Test
    void databaseFieldsAreValidatedAtTheRequestBoundary() throws Exception {
        var noHost = request("缺主机");
        databaseOf(noHost).remove("host");
        Map<?, ?> failure = exchange(HttpMethod.POST, "/data-sources", noHost);
        assertThat(failure.get("httpStatus")).isEqualTo(400);
        assertThat(failure.get("code")).isEqualTo("request.invalid");
        assertThat((List<?>) failure.get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .contains("database.host");

        var noPort = request("缺端口");
        databaseOf(noPort).remove("port");
        assertThat((List<?>) exchange(HttpMethod.POST, "/data-sources", noPort).get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .contains("database.port");
    }

    @Test
    void passwordIsRequiredOnCreateAndKeptWhenLeftBlankOnEdit() throws Exception {
        var noPassword = request("无密码");
        databaseOf(noPassword).remove("password");
        assertThat(exchange(HttpMethod.POST, "/data-sources", noPassword).get("code"))
                .isEqualTo("datasource.passwordRequired");

        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/data-sources", request("原名"))
                        .get("data"))
                .get("id");
        var renamed = request("新名");
        databaseOf(renamed).put("password", "");
        Map<?, ?> updated = exchange(HttpMethod.PUT, "/data-sources/" + id, renamed);
        assertThat(((Map<?, ?>) updated.get("data")).get("name")).isEqualTo("新名");

        // 留空的密码沿用已保存的密码，真实连接仍能成功
        assertThat(exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", renamed)
                        .get("code"))
                .isEqualTo("ok");
        var wrongPassword = request("新名");
        databaseOf(wrongPassword).put("password", "wrong");
        exchange(HttpMethod.PUT, "/data-sources/" + id, wrongPassword);
        assertThat(exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", renamed)
                        .get("code"))
                .isEqualTo("datasource.authFailed");
    }

    @Test
    void viewPermissionCanOnlyReadAndManagePermissionIsRequiredToChange() throws Exception {
        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/data-sources", request("只读可见"))
                        .get("data"))
                .get("id");
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, VIEWER, List.of("datasource:view"));
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, OUTSIDER, List.of("screen:view"));
        String viewer = TestTokens.forMembership(TestTokens.TENANT_ID, VIEWER);
        String outsider = TestTokens.forMembership(TestTokens.TENANT_ID, OUTSIDER);

        assertThat(exchange(HttpMethod.GET, "/data-sources", null, viewer).get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(HttpMethod.GET, "/data-sources/" + id, null, viewer).get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(HttpMethod.GET, "/data-sources", null, outsider).get("httpStatus"))
                .isEqualTo(403);
        for (var call : List.of(
                exchange(HttpMethod.POST, "/data-sources", request("越权"), viewer),
                exchange(HttpMethod.PUT, "/data-sources/" + id, request("越权"), viewer),
                exchange(HttpMethod.DELETE, "/data-sources/" + id, null, viewer),
                exchange(HttpMethod.POST, "/data-sources/connection-test", request("越权"), viewer),
                exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", request("越权"), viewer))) {
            assertThat(call.get("httpStatus")).isEqualTo(403);
            assertThat(call.get("code")).isEqualTo("auth.forbidden");
        }
    }

    @Test
    void dataSourceBelongsToItsTenantAndDeleteIsLogical() throws Exception {
        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/data-sources", request("租户内"))
                        .get("data"))
                .get("id");
        TestGrants.publish(redisTemplate, OTHER_TENANT, MANAGER, List.of("datasource:view", "datasource:manage"));
        String other = TestTokens.forTenant(OTHER_TENANT);

        assertThat(exchange(HttpMethod.GET, "/data-sources/" + id, null, other).get("code"))
                .isEqualTo("datasource.notFound");
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + id, request("改名"), other)
                        .get("code"))
                .isEqualTo("datasource.notFound");
        assertThat(exchange(HttpMethod.DELETE, "/data-sources/" + id, null, other)
                        .get("code"))
                .isEqualTo("datasource.notFound");
        assertThat(exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", request("x"), other)
                        .get("code"))
                .isEqualTo("datasource.notFound");
        assertThat((List<?>)
                        exchange(HttpMethod.GET, "/data-sources", null, other).get("data"))
                .isEmpty();

        assertThat(exchange(HttpMethod.DELETE, "/data-sources/" + id, null).get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(HttpMethod.GET, "/data-sources/" + id, null).get("code"))
                .isEqualTo("datasource.notFound");
        assertThat((List<?>) exchange(HttpMethod.GET, "/data-sources", null).get("data"))
                .<Object>extracting(item -> ((Map<?, ?>) item).get("id"))
                .doesNotContain(id);
    }

    @Test
    void createUpdateAndDeleteWriteAuditEvents() throws Exception {
        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/data-sources", request("审计"))
                        .get("data"))
                .get("id");
        exchange(HttpMethod.PUT, "/data-sources/" + id, request("审计改名"));
        exchange(HttpMethod.DELETE, "/data-sources/" + id, null);

        List<Map<?, ?>> events = auditOutboxMapper.selectAuditPayloads().stream()
                .<Map<?, ?>>map(payload -> JSON.readValue(payload, Map.class))
                .filter(event -> id.equals(event.get("resourceId")))
                .toList();
        assertThat(events)
                .extracting(event -> event.get("action"), event -> event.get("outcome"), event -> event.get("tenantId"))
                .containsExactly(
                        tuple("datasource.create", "SUCCESS", TestTokens.TENANT_ID.toString()),
                        tuple("datasource.update", "SUCCESS", TestTokens.TENANT_ID.toString()),
                        tuple("datasource.delete", "SUCCESS", TestTokens.TENANT_ID.toString()));
        assertThat(events.toString()).doesNotContain(external.password());
    }

    private Map<String, Object> request(String name) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("type", "POSTGRESQL");
        var database = new HashMap<String, Object>();
        database.put("host", external.host());
        database.put("port", external.port());
        database.put("database", external.database());
        database.put("username", external.username());
        database.put("password", external.password());
        database.put("params", Map.of("sslmode", "disable"));
        body.put("database", database);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> databaseOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("database");
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body) throws Exception {
        return exchange(method, uri, body, TestTokens.forTenant(TestTokens.TENANT_ID));
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body, String token) {
        var spec = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.exchange((request, response) -> response.bodyTo(Map.class));
    }
}
