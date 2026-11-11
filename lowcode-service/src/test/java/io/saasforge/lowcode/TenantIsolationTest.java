package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.lowcode.screen.entity.Screen;
import io.saasforge.lowcode.screen.service.ScreenService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.tenant.TenantRedisTemplate;
import io.saasforge.sdk.web.PageQuery;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 两道防线分别验证：经 SDK 的业务接口按 Token 中的 Active Tenant 隔离；绕过 SDK 直接执行 SQL 时 RLS 仍然隔离。
 *
 * <p>绕过场景必须用原生 JDBC 才能模拟「有人不经 MyBatis-Plus 执行 SQL」，因此这里是编码规范中原生 JDBC 禁令的测试例外。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantIsolationTest {
    static final UUID TENANT_A = UUID.fromString("019a0110-0000-7000-8000-0000000000aa");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000000bb");
    static final UUID MEMBERSHIP_A = UUID.fromString("019a0110-0000-7000-8000-0000000000a1");
    static final UUID MEMBERSHIP_B = UUID.fromString("019a0110-0000-7000-8000-0000000000b1");
    static final PageQuery FIRST_PAGE = new PageQuery(1, PageQuery.MAX_SIZE, List.of());

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

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
    DataSource dataSource;

    @Autowired
    ScreenService screenService;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    AsyncTaskExecutor executor;

    @Autowired
    TenantRedisTemplate tenantRedis;

    @Autowired
    StringRedisTemplate globalRedis;

    @BeforeEach
    void grantScreenPermissions() {
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            TestGrants.publishAll(globalRedis, tenant, TestTokens.MEMBERSHIP_ID);
        }
        TestGrants.publishAll(globalRedis, TENANT_A, MEMBERSHIP_A);
        TestGrants.publishAll(globalRedis, TENANT_B, MEMBERSHIP_B);
    }

    @Test
    void tenantTokenCannotReadOrModifyAnotherTenantsScreens() throws Exception {
        UUID screenA = createScreen(TENANT_A, "A 的大屏");
        UUID screenB = createScreen(TENANT_B, "B 的大屏");

        assertThat(screenA.version()).isEqualTo(7);
        List<?> visibleToA = (List<?>) data(call(TENANT_A, HttpMethod.GET, "/screens", null));
        assertThat(visibleToA)
                .<Object>extracting(screen -> ((Map<?, ?>) screen).get("id"))
                .contains(screenA.toString())
                .doesNotContain(screenB.toString());

        Map<String, Object> change = Map.of("name", "被 A 篡改");
        // 跨租户与不存在的大屏响应相同（traceId 除外），不泄露大屏是否存在
        String crossTenant = failure(TENANT_A, HttpMethod.GET, "/screens/" + screenB, null);
        assertThat(crossTenant)
                .isEqualTo(failure(TENANT_A, HttpMethod.GET, "/screens/" + screenB, null))
                .contains("\"code\":\"screen.notFound\"", "\"httpStatus\":404");
        UUID missing = UUID.randomUUID();
        assertThat(failure(TENANT_A, HttpMethod.GET, "/screens/" + missing, null)
                        .replace(missing.toString(), screenB.toString()))
                .isEqualTo(crossTenant);
        assertThat(status(TENANT_A, HttpMethod.PUT, "/screens/" + screenB, change))
                .isEqualTo(404);
        assertThat(status(TENANT_A, HttpMethod.DELETE, "/screens/" + screenB, null))
                .isEqualTo(404);

        Map<?, ?> untouched = (Map<?, ?>) data(call(TENANT_B, HttpMethod.GET, "/screens/" + screenB, null));
        assertThat(untouched.get("name")).isEqualTo("B 的大屏");

        assertThat(status(TENANT_A, HttpMethod.PUT, "/screens/" + screenA, change))
                .isEqualTo(200);
        assertThat(status(TENANT_A, HttpMethod.DELETE, "/screens/" + screenA, null))
                .isEqualTo(200);
        assertThat(status(TENANT_A, HttpMethod.GET, "/screens/" + screenA, null)).isEqualTo(404);
    }

    @Test
    void sameIdentitySeesOnlyTheActiveTenantsScreensAfterSwitching() throws Exception {
        String tokenA = TestTokens.forMembership(TENANT_A, MEMBERSHIP_A);
        String tokenB = TestTokens.forMembership(TENANT_B, MEMBERSHIP_B);
        Map<?, ?> createdA = (Map<?, ?>) data(client(tokenA)
                .post()
                .uri("/screens")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "切换前 A 的大屏"))
                .retrieve());
        Map<?, ?> createdB = (Map<?, ?>) data(client(tokenB)
                .post()
                .uri("/screens")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "切换后 B 的大屏"))
                .retrieve());

        List<?> seenInA = (List<?>) data(client(tokenA).get().uri("/screens").retrieve());
        List<?> seenInB = (List<?>) data(client(tokenB).get().uri("/screens").retrieve());
        assertThat(seenInA)
                .<Object>extracting(screen -> ((Map<?, ?>) screen).get("id"))
                .contains(createdA.get("id"))
                .doesNotContain(createdB.get("id"));
        assertThat(seenInB)
                .<Object>extracting(screen -> ((Map<?, ?>) screen).get("id"))
                .contains(createdB.get("id"))
                .doesNotContain(createdA.get("id"));
        Map<?, ?> contextB =
                (Map<?, ?>) data(client(tokenB).get().uri("/context").retrieve());
        assertThat(contextB.get("identityId")).isEqualTo(TestTokens.IDENTITY_ID.toString());
        assertThat(contextB.get("tenantId")).isEqualTo(TENANT_B.toString());
        assertThat(contextB.get("membershipId")).isEqualTo(MEMBERSHIP_B.toString());
    }

    private RestClient client(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    @Test
    void rlsBlocksSqlThatBypassesTheInterceptorOnApplicationAccount() throws Exception {
        UUID screenB = createScreen(TENANT_B, "B 的大屏");

        try (Connection connection = connectionFor(TENANT_A)) {
            assertThat(currentUser(connection)).isEqualTo("demo_app");
            assertCrossTenantAccessBlocked(connection, screenB);
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT count(*) FROM screens")).isZero();
        }
    }

    @Test
    void rlsAlsoBindsMigratorAccountThatOwnsTheTable() throws Exception {
        UUID screenB = createScreen(TENANT_B, "B 的大屏");

        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), "demo_migrator", "demo_migrator")) {
            assertThat(count(connection, "SELECT count(*) FROM screens")).isZero();
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                statement.setString(1, TENANT_A.toString());
                statement.execute();
            }
            assertCrossTenantAccessBlocked(connection, screenB);
        }
    }

    @Test
    void asyncWorkCarriesSubmittersTenantAndFailsWithoutOne() throws Exception {
        UUID screenA = createScreen(TENANT_A, "A 的大屏");

        var carried = new AtomicReference<Future<List<UUID>>>();
        CurrentTenant.run(
                TENANT_A,
                () -> carried.set(executor.submit(() ->
                        screenService.list(FIRST_PAGE).stream().map(Screen::getId).toList())));
        assertThat(carried.get().get()).contains(screenA);

        // 定时任务等没有提交方上下文的线程与此相同：未声明租户时显式失败，而不是读到空结果或跨租户数据
        Future<?> undeclared = executor.submit(() -> screenService.list(FIRST_PAGE));
        assertThatThrownBy(undeclared::get)
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void redisKeysArePrefixedWithServiceAndTenant() {
        CurrentTenant.run(TENANT_A, () -> tenantRedis.opsForValue().set("greeting", "a"));
        CurrentTenant.run(TENANT_B, () -> tenantRedis.opsForValue().set("greeting", "b"));

        assertThat(globalRedis.opsForValue().get("lowcode-service:" + TENANT_A + ":greeting"))
                .isEqualTo("a");
        assertThat(globalRedis.opsForValue().get("lowcode-service:" + TENANT_B + ":greeting"))
                .isEqualTo("b");
        CurrentTenant.run(TENANT_A, () -> {
            assertThat(tenantRedis.opsForValue().get("greeting")).isEqualTo("a");
            assertThat(tenantRedis.keys("greet*")).containsExactly("greeting");
        });
        assertThatThrownBy(() -> tenantRedis.opsForValue().get("greeting")).isInstanceOf(IllegalStateException.class);
    }

    private static void assertCrossTenantAccessBlocked(Connection connection, UUID screenOfB) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT count(*) FROM screens WHERE id = ?")) {
            select.setObject(1, screenOfB);
            assertThat(count(select)).isZero();
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE screens SET name = 'x' WHERE id = ?")) {
            update.setObject(1, screenOfB);
            assertThat(update.executeUpdate()).isZero();
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM screens WHERE id = ?")) {
            delete.setObject(1, screenOfB);
            assertThat(delete.executeUpdate()).isZero();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO screens (id, tenant_id, name) VALUES (?, ?, '伪造')")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, TENANT_B);
            assertThatThrownBy(insert::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("42501");
        }
    }

    private Connection connectionFor(UUID tenantId) throws SQLException {
        try (CurrentTenant.Binding ignored = CurrentTenant.bind(tenantId)) {
            return dataSource.getConnection();
        }
    }

    private static String currentUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_user");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return count(statement);
        }
    }

    private static long count(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private UUID createScreen(UUID tenantId, String name) throws Exception {
        Map<?, ?> created =
                (Map<?, ?>) data(call(tenantId, HttpMethod.POST, "/screens", Map.of("name", name)));
        return UUID.fromString((String) created.get("id"));
    }

    private static Object data(RestClient.ResponseSpec response) {
        Map<?, ?> envelope = response.body(Map.class);
        assertThat(envelope.get("code")).isEqualTo("ok");
        return envelope.get("data");
    }

    /** 失败信封去掉 traceId 后的原文，用于比较两次失败是否可区分。 */
    private String failure(UUID tenantId, HttpMethod method, String uri, Object body) throws Exception {
        return request(tenantId, method, uri, body)
                .exchange((req, response) -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))
                .replaceAll("\"traceId\":\"[0-9a-f]+\"", "");
    }

    private RestClient.ResponseSpec call(UUID tenantId, HttpMethod method, String uri, Object body) throws Exception {
        return request(tenantId, method, uri, body).retrieve();
    }

    private int status(UUID tenantId, HttpMethod method, String uri, Object body) throws Exception {
        return request(tenantId, method, uri, body)
                .exchange((req, response) -> response.getStatusCode().value());
    }

    private RestClient.RequestBodySpec request(UUID tenantId, HttpMethod method, String uri, Object body)
            throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(tenantId));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request;
    }
}
