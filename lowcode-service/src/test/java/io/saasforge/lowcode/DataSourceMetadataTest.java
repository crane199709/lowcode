package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
class DataSourceMetadataTest {
    private static final UUID VIEWER = UUID.fromString("019a0110-0000-7000-8000-0000000000d1");
    private static final UUID OUTSIDER = UUID.fromString("019a0110-0000-7000-8000-0000000000d2");

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalMetadata("POSTGRESQL");

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
    void viewerBrowsesOnlyAccessibleTablesWithTheirSchemas() throws Exception {
        String id = create();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, VIEWER, List.of("datasource:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, VIEWER);
        var response = exchange(HttpMethod.GET, "/data-sources/" + id + "/tables", null);
        assertThat(response.get("code")).isEqualTo("ok");
        assertThat((List<Map<String, Object>>) response.get("data"))
                .contains(
                        Map.of("catalog", "forge", "schema", "browse_meta", "name", "order_%"),
                        Map.of("catalog", "forge", "schema", "browse_other", "name", "order_%"));
        assertThat(response.toString()).doesNotContain("hidden_orders", "browse_locked", external.password());
    }

    @Test
    void viewerCanBrowsePartitionedParentTables() throws Exception {
        String id = create();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, VIEWER, List.of("datasource:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, VIEWER);
        var tables = exchange(HttpMethod.GET, "/data-sources/" + id + "/tables", null);
        assertThat((List<Map<String, Object>>) tables.get("data"))
                .contains(Map.of("catalog", "forge", "schema", "browse_meta", "name", "partitioned_orders"));
        var columns = exchange(
                HttpMethod.GET,
                "/data-sources/" + id + "/columns?catalog=forge&schema=browse_meta&table=partitioned_orders",
                null);
        assertThat(columns.get("code")).isEqualTo("ok");
        assertThat((List<Map<String, Object>>) columns.get("data"))
                .containsExactly(Map.of("name", "order_id", "type", "int4"));
    }

    @Test
    void columnsUseExactSchemaAndTableNamesRatherThanWildcardPatterns() throws Exception {
        String id = create();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, VIEWER, List.of("datasource:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, VIEWER);
        var response = exchange(
                HttpMethod.GET,
                "/data-sources/" + id + "/columns?catalog=forge&schema=browse_meta&table=order_%25",
                null);
        assertThat(response.get("code")).isEqualTo("ok");
        assertThat((List<Map<String, Object>>) response.get("data"))
                .containsExactly(
                        Map.of("name", "order_id", "type", "int4"), Map.of("name", "amount", "type", "numeric"));
        var other = exchange(
                HttpMethod.GET,
                "/data-sources/" + id + "/columns?catalog=forge&schema=browse_other&table=order_%25",
                null);
        assertThat((List<Map<String, Object>>) other.get("data"))
                .containsExactly(Map.of("name", "other_id", "type", "int8"));
    }

    @Test
    void metadataRequiresViewPermissionAndHidesOtherTenantsAndDeletedSources() throws Exception {
        String id = create();
        for (String path : List.of("/tables", "/columns?catalog=forge&schema=browse_meta&table=order_%25")) {
            TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, OUTSIDER, List.of("datasource:manage"));
            token = TestTokens.forMembership(TestTokens.TENANT_ID, OUTSIDER);
            assertThat(exchange(HttpMethod.GET, "/data-sources/" + id + path, null)
                            .get("code"))
                    .isEqualTo("auth.forbidden");
            UUID other = UUID.fromString("019a0110-0000-7000-8000-0000000000d0");
            TestGrants.publish(redisTemplate, other, TestTokens.MEMBERSHIP_ID, List.of("datasource:view"));
            token = TestTokens.forTenant(other);
            assertThat(exchange(HttpMethod.GET, "/data-sources/" + id + path, null)
                            .get("code"))
                    .isEqualTo("datasource.notFound");
            token = TestTokens.forTenant(TestTokens.TENANT_ID);
        }
        grantManage();
        exchange(HttpMethod.DELETE, "/data-sources/" + id, null);
        for (String path : List.of("/tables", "/columns?table=anything")) {
            assertThat(exchange(HttpMethod.GET, "/data-sources/" + id + path, null)
                            .get("code"))
                    .isEqualTo("datasource.notFound");
            assertThat(exchange(HttpMethod.GET, "/data-sources/" + UUID.randomUUID() + path, null)
                            .get("code"))
                    .isEqualTo("datasource.notFound");
        }
    }

    @Test
    void columnsCannotBypassTableVisibilityOrChangeCatalog() {
        String id = create();
        for (String query : List.of(
                "catalog=forge&schema=browse_meta&table=hidden_orders",
                "catalog=forge&schema=browse_locked&table=locked_orders",
                "catalog=other_database&schema=browse_meta&table=order_%25",
                "catalog=forge&schema=browse_meta&table=missing")) {
            assertThat(exchange(HttpMethod.GET, "/data-sources/" + id + "/columns?" + query, null)
                            .get("code"))
                    .isEqualTo("datasource.tableNotFound");
        }
    }

    @Test
    void apiSourcesDoNotSupportMetadata() {
        var response = exchange(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "API",
                        "type",
                        "API",
                        "api",
                        Map.of("url", "http://127.0.0.1:12345/report", "method", "GET", "authType", "NONE")));
        String id = (String) ((Map<?, ?>) response.get("data")).get("id");
        for (String path : List.of("/tables", "/columns?table=anything")) {
            assertThat(exchange(HttpMethod.GET, "/data-sources/" + id + path, null)
                            .get("code"))
                    .isEqualTo("datasource.metadataNotSupported");
        }
    }

    @Test
    void metadataConnectionFailuresNeverExposeDriverDetailsOrPasswords() {
        var response = exchange(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "错误凭据",
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
                                "wrong-password",
                                "params",
                                Map.of("sslmode", "disable"))));
        String id = (String) ((Map<?, ?>) response.get("data")).get("id");
        for (String path : List.of("/tables", "/columns?table=anything")) {
            var failed = exchange(HttpMethod.GET, "/data-sources/" + id + path, null);
            assertThat(failed.get("code")).isEqualTo("datasource.authFailed");
            assertThat(failed.toString())
                    .doesNotContain("wrong-password", external.password(), external.username(), "SQLState");
        }
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
        if (body != null) request = request.body(body);
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
