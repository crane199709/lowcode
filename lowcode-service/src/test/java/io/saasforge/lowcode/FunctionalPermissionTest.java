package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** 接口按 Permission Code 拦截，以及 lowcode-service 申报的 Resource Manifest。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FunctionalPermissionTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000ee");
    private static final UUID READER = UUID.fromString("019a0110-0000-7000-8000-0000000000e1");
    private static final UUID OUTSIDER = UUID.fromString("019a0110-0000-7000-8000-0000000000e2");

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
    StringRedisTemplate redisTemplate;

    @Test
    void membershipWithThePermissionGets200AndWithoutGets403OnTheSameEndpoint() throws Exception {
        TestGrants.publish(redisTemplate, TENANT, READER, List.of("screen:view"));
        TestGrants.publish(redisTemplate, TENANT, OUTSIDER, List.of());

        var allowed = get("/screens", READER);
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody().get("code")).isEqualTo("ok");

        var denied = get("/screens", OUTSIDER);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(denied.getBody().get("code")).isEqualTo("auth.forbidden");
        assertThat(denied.getBody().get("userTip")).isEqualTo("没有权限执行该操作");

        // 只有 screen:view 的成员不能新建大屏。
        int created = client(READER)
                .post()
                .uri("/screens")
                .body(Map.of("name", "越权"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toBodilessEntity()
                .getStatusCode()
                .value();
        assertThat(created).isEqualTo(403);

        // 未声明权限码的接口只要求已认证。
        assertThat(get("/context", OUTSIDER).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void manifestDeclaresLowcodePermissionsMenusRolesAndTheScreenDataResource() {
        ResourceManifest manifest = ResourceManifestCollector.collect(
                "lowcode-service", "1", "io.saasforge.lowcode", getClass().getClassLoader());

        assertThat(manifest.permissions())
                .containsExactly(
                        new Permission("dataset:manage", "管理数据集", "数据集"),
                        new Permission("dataset:view", "查看数据集", "数据集"),
                        new Permission("datasource:manage", "管理数据源", "数据源"),
                        new Permission("datasource:view", "查看数据源", "数据源"),
                        new Permission("screen:edit", "编辑大屏", "大屏"),
                        new Permission("screen:publish", "发布大屏", "大屏"),
                        new Permission("screen:view", "查看大屏", "大屏"));

        assertThat(manifest.menus()).singleElement().satisfies(group -> {
            assertThat(group.name()).isEqualTo("低代码");
            assertThat(group.children())
                    .extracting(MenuNode::name, MenuNode::app, MenuNode::path, MenuNode::permission)
                    .containsExactly(
                            tuple("数据源", "lowcode", "datasources", "datasource:view"),
                            tuple("数据集", "lowcode", "datasets", "dataset:view"),
                            tuple("大屏", "lowcode", "screens", "screen:view"));
        });

        assertThat(manifest.roles())
                .extracting(RoleTemplate::name, role -> Set.copyOf(role.permissions()))
                .containsExactlyInAnyOrder(
                        tuple(
                                "大屏设计者",
                                Set.of(
                                        "datasource:view",
                                        "datasource:manage",
                                        "dataset:view",
                                        "dataset:manage",
                                        "screen:view",
                                        "screen:edit",
                                        "screen:publish")),
                        tuple("大屏查看者", Set.of("screen:view")));

        assertThat(manifest.dataResources()).singleElement().satisfies(screen -> {
            assertThat(screen.code()).isEqualTo("screen");
            assertThat(screen.attributes())
                    .extracting(DataAttribute::code, DataAttribute::name)
                    .containsExactly(tuple("ownerId", "创建人"), tuple("departmentPath", "所属部门"));
        });
    }

    private ResponseEntity<Map> get(String uri, UUID membership) throws Exception {
        return client(membership)
                .get()
                .uri(uri)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(Map.class);
    }

    private RestClient client(UUID membership) throws Exception {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestTokens.forMembership(TENANT, membership))
                .build();
    }
}
