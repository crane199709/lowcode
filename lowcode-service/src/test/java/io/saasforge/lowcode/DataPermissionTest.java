package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.SubjectAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 大屏按当前成员的 Data Scope 过滤：不同部门、不同角色组合的成员看到的大屏集合，越权更新与删除，
 * 以及单对象校验与 SQL 过滤结论一致；编辑器保存的自定义条件同样生效。
 *
 * <p>部门树：{@code a}、其下级 {@code a.b} 与另一个顶级部门 {@code c}。每个成员先以「全部」范围、按自己的主部门创建一个大屏，
 * 之后以新版本的明细（等同于 iam 调整角色或组织分配后换发 Token）按不同范围查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataPermissionTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000f0");
    private static final UUID ALICE = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");
    private static final UUID BOB = UUID.fromString("019a0110-0000-7000-8000-0000000000f2");
    private static final UUID CAROL = UUID.fromString("019a0110-0000-7000-8000-0000000000f3");
    private static final UUID DAVE = UUID.fromString("019a0110-0000-7000-8000-0000000000f4");
    private static final UUID ERIN = UUID.fromString("019a0110-0000-7000-8000-0000000000f5");
    private static final Map<UUID, String> NAMES =
            Map.of(ALICE, "alice", BOB, "bob", CAROL, "carol", DAVE, "dave", ERIN, "erin");
    // 编辑器保存的「属于本部门及下级，但不是本人创建」
    private static final String OTHERS_IN_MY_DEPARTMENTS = """
            {"and": [
              {"not": {"attr": "resource.ownerId", "op": "eq", "value": {"ref": "subject.membershipId"}}},
              {"attr": "resource.departmentPath", "op": "under", "value": {"ref": "subject.departmentPaths"}}
            ]}""";
    private static final String VIP_SEES_ALL = """
            {"attr": "subject.attributes.vip", "op": "eq", "value": true}""";
    private static final Map<UUID, SubjectAttributes> DEPARTMENTS = Map.of(
            ALICE, inDepartments("a"),
            BOB, inDepartments("a.b"),
            CAROL, inDepartments("a.b"),
            DAVE, inDepartments("c"),
            ERIN, SubjectAttributes.NONE);

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

    @Autowired
    DataPermission dataPermission;

    // 每次换一个版本发布明细：SDK 按 membership_id + version 缓存明细，同一版本内的覆盖不会立即被读到。
    private static long nextVersion = 10;
    // 容器与应用上下文在各测试间共用，大屏只创建一次；不能用 PER_CLASS 的 @BeforeAll，那会让上下文先于容器启动。
    private static boolean seeded;

    @BeforeEach
    void eachMemberCreatesAScreenInTheirPrimaryDepartment() throws Exception {
        if (seeded) return;
        seeded = true;
        for (UUID member : NAMES.keySet()) {
            String token = publish(member, DEPARTMENTS.get(member), List.of(DataScope.ALL));
            assertThat(status(token, HttpMethod.POST, "/screens", Map.of("name", NAMES.get(member))))
                    .isEqualTo(201);
        }
    }

    @Test
    void membersSeeTheScreensWithinTheUnionOfTheirRolesDataScopes() throws Exception {
        assertVisible(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.SELF), "alice");
        // 「本部门」不含下级部门，「本部门及下级」包含。
        assertVisible(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT), "alice");
        assertVisible(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT_AND_BELOW), "alice", "bob", "carol");
        assertVisible(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.DEPARTMENT), "bob", "carol");
        assertVisible(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL), "alice", "bob", "carol", "dave", "erin");
        // 主部门与兼任部门都计入「本部门」。
        assertVisible(DAVE, inDepartments("c", "a"), List.of(DataScope.DEPARTMENT), "alice", "dave");
        // 两个角色的范围取并集：Erin 自己创建时没有部门，之后被分配到 c，「本人」与「本部门」各得一张。
        assertVisible(ERIN, inDepartments("c"), List.of(DataScope.SELF, DataScope.DEPARTMENT), "dave", "erin");
        // 没有部门的成员按部门的范围看不到任何大屏，按「本人」只看到自己的。
        assertVisible(ERIN, SubjectAttributes.NONE, List.of(DataScope.DEPARTMENT_AND_BELOW));
        // 有查看权限码但没有任何 Data Policy：默认拒绝。
        assertVisible(BOB, DEPARTMENTS.get(BOB), List.of());
    }

    @Test
    void updatesAndDeletesOutsideTheDataScopeAreRejectedAndLeaveTheScreenUntouched() throws Exception {
        Map<String, UUID> ids = screenIds();
        String alice = publish(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.SELF));

        // 范围外的大屏与不存在的大屏一样 404，不泄露其存在。
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            Object body = method == HttpMethod.PUT ? Map.of("name", "越权") : null;
            Map<String, Object> rejected = exchange(alice, method, "/screens/" + ids.get("bob"), body);
            assertThat(rejected.get("code")).isEqualTo("screen.notFound");
            assertThat(rejected.get("httpStatus")).isEqualTo(404);
        }
        assertThat(screen(ids.get("bob")).get("name")).isEqualTo("bob");

        // 范围内照常修改。
        assertThat(status(alice, HttpMethod.PUT, "/screens/" + ids.get("alice"), Map.of("name", "alice 改名")))
                .isEqualTo(200);
        assertThat(screen(ids.get("alice")).get("name")).isEqualTo("alice 改名");
        assertThat(status(alice, HttpMethod.PUT, "/screens/" + ids.get("alice"), Map.of("name", "alice")))
                .isEqualTo(200);
    }

    @Test
    void creatingAScreenOutsideTheOwnDataScopeIsForbidden() throws Exception {
        // 新大屏归属创建者的主部门；没有部门的成员按部门范围创建的大屏自己也看不到，单对象校验直接拒绝。
        String byDepartment = publish(ERIN, SubjectAttributes.NONE, List.of(DataScope.DEPARTMENT));
        Map<String, Object> denied = exchange(byDepartment, HttpMethod.POST, "/screens", Map.of("name", "x"));
        assertThat(denied.get("code")).isEqualTo("auth.forbidden");
        assertThat(denied.get("httpStatus")).isEqualTo(403);

        String withoutPolicy = publish(ERIN, SubjectAttributes.NONE, List.of());
        assertThat(status(withoutPolicy, HttpMethod.POST, "/screens", Map.of("name", "x")))
                .isEqualTo(403);
    }

    @Test
    void singleObjectCheckAgreesWithTheSqlFilterForEveryScreen() throws Exception {
        List<Map<?, ?>> all = list(publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL)));
        record Case(UUID member, SubjectAttributes subject, List<DataScope> scopes) {}
        for (Case c : List.of(
                new Case(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.SELF)),
                new Case(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT)),
                new Case(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT_AND_BELOW)),
                new Case(DAVE, inDepartments("c", "a.b"), List.of(DataScope.SELF, DataScope.DEPARTMENT)),
                new Case(ERIN, SubjectAttributes.NONE, List.of(DataScope.DEPARTMENT_AND_BELOW)),
                new Case(BOB, DEPARTMENTS.get(BOB), List.of()))) {
            long version = nextVersion;
            String token = publish(c.member(), c.subject(), c.scopes());
            Set<Object> bySql =
                    list(token).stream().map(screen -> screen.get("id")).collect(Collectors.toSet());
            Set<Object> inMemory = new HashSet<>();
            asMember(c.member(), version, () -> {
                for (Map<?, ?> screen : all) {
                    var attributes = new HashMap<String, Object>();
                    attributes.put("ownerId", UUID.fromString((String) screen.get("ownerId")));
                    attributes.put("departmentPath", screen.get("departmentPath"));
                    if (dataPermission.permits("screen", attributes)) inMemory.add(screen.get("id"));
                }
            });
            assertThat(inMemory).as("%s %s", NAMES.get(c.member()), c.scopes()).isEqualTo(bySql);
        }
    }

    @Test
    void customConditionsSavedByTheEditorFilterTheListAndAgreeWithTheSingleObjectCheck() throws Exception {
        // 「属于本部门及下级，但不是本人创建」：Alice 在 a，看到下级 a.b 中 Bob 与 Carol 的；Dave 所在的 c 只有自己的。
        assertVisiblePolicies(ALICE, DEPARTMENTS.get(ALICE), List.of(OTHERS_IN_MY_DEPARTMENTS), "bob", "carol");
        assertVisiblePolicies(DAVE, DEPARTMENTS.get(DAVE), List.of(OTHERS_IN_MY_DEPARTMENTS));
        assertVisiblePolicies(ERIN, SubjectAttributes.NONE, List.of(OTHERS_IN_MY_DEPARTMENTS));

        // 引用扩展属性的条件按发布的属性类型加载：VIP 看到全部，非 VIP 只剩另一个角色的条件。
        SubjectAttributes vip = withAttributes(DEPARTMENTS.get(ALICE), Map.of("vip", true));
        SubjectAttributes regular = withAttributes(DEPARTMENTS.get(ALICE), Map.of("vip", false));
        assertVisiblePolicies(
                ALICE, vip, List.of(OTHERS_IN_MY_DEPARTMENTS, VIP_SEES_ALL), "alice", "bob", "carol", "dave", "erin");
        assertVisiblePolicies(ALICE, regular, List.of(OTHERS_IN_MY_DEPARTMENTS, VIP_SEES_ALL), "bob", "carol");
        // 扩展属性定义已删除（明细中没有其类型）：该条策略按拒绝处理，不放行也不报错。
        var untyped = new SubjectAttributes(Set.of("a"), "a", Set.of(), Map.of("vip", true), Map.of());
        assertVisiblePolicies(ALICE, untyped, List.of(OTHERS_IN_MY_DEPARTMENTS, VIP_SEES_ALL), "bob", "carol");

        List<Map<?, ?>> all = list(publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL)));
        for (SubjectAttributes subject : List.of(DEPARTMENTS.get(ALICE), vip, regular, inDepartments("c", "a.b"))) {
            long version = nextVersion;
            String token = publishPolicies(ALICE, subject, List.of(OTHERS_IN_MY_DEPARTMENTS, VIP_SEES_ALL));
            Set<Object> bySql =
                    list(token).stream().map(screen -> screen.get("id")).collect(Collectors.toSet());
            Set<Object> inMemory = new HashSet<>();
            asMember(ALICE, version, () -> {
                for (Map<?, ?> screen : all) {
                    var attributes = new HashMap<String, Object>();
                    attributes.put("ownerId", UUID.fromString((String) screen.get("ownerId")));
                    attributes.put("departmentPath", screen.get("departmentPath"));
                    if (dataPermission.permits("screen", attributes)) inMemory.add(screen.get("id"));
                }
            });
            assertThat(inMemory).as("%s", subject).isEqualTo(bySql);
        }
    }

    private void assertVisible(UUID member, SubjectAttributes subject, List<DataScope> scopes, String... names)
            throws Exception {
        assertVisibleWith(publish(member, subject, scopes), NAMES.get(member) + " " + scopes, names);
    }

    private void assertVisiblePolicies(UUID member, SubjectAttributes subject, List<String> policies, String... names)
            throws Exception {
        assertVisibleWith(publishPolicies(member, subject, policies), NAMES.get(member) + " " + subject, names);
    }

    private void assertVisibleWith(String token, String description, String... names) throws Exception {
        var envelope = exchange(token, HttpMethod.GET, "/screens?size=100", null);
        List<Object> visible = ((List<?>) envelope.get("data"))
                .stream()
                        .<Object>map(screen -> ((Map<?, ?>) screen).get("name"))
                        .toList();
        assertThat(visible).as(description).containsExactlyInAnyOrder((Object[]) names);
        // 总数与列表经同一过滤，分页信息不泄露范围外的数据量。
        assertThat(((Map<?, ?>) ((Map<?, ?>) envelope.get("meta")).get("page")).get("totalElements"))
                .isEqualTo(names.length);
    }

    private Map<String, UUID> screenIds() throws Exception {
        return list(publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL))).stream()
                .filter(screen -> NAMES.containsValue(screen.get("name")))
                .collect(Collectors.toMap(
                        screen -> (String) screen.get("name"), screen -> UUID.fromString((String) screen.get("id"))));
    }

    private Map<?, ?> screen(UUID id) throws Exception {
        String reader = publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL));
        return (Map<?, ?>)
                exchange(reader, HttpMethod.GET, "/screens/" + id, null).get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> list(String token) throws Exception {
        return (List<Map<?, ?>>)
                exchange(token, HttpMethod.GET, "/screens?size=100", null).get("data");
    }

    /** 以新版本发布成员明细（全部大屏权限码与给定的数据范围），返回该版本的 Token。 */
    private String publish(UUID member, SubjectAttributes subject, List<DataScope> scopes) throws Exception {
        long version = nextVersion++;
        TestGrants.publish(redisTemplate, TENANT, member, version, TestGrants.ALL_SCREEN_PERMISSIONS, subject, scopes);
        return TestTokens.forMembership(TENANT, member, version);
    }

    /** 以新版本发布成员明细（全部大屏权限码与给定的大屏策略 JSON），返回该版本的 Token。 */
    private String publishPolicies(UUID member, SubjectAttributes subject, List<String> policies) throws Exception {
        long version = nextVersion++;
        TestGrants.publishPolicies(
                redisTemplate, TENANT, member, version, TestGrants.ALL_SCREEN_PERMISSIONS, subject, policies);
        return TestTokens.forMembership(TENANT, member, version);
    }

    /** 模拟已认证的成员请求线程，供直接调用单对象校验。 */
    private static void asMember(UUID member, long version, Runnable task) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(TestTokens.IDENTITY_ID.toString())
                .claim("tenant_id", TENANT.toString())
                .claim("membership_id", member.toString())
                .claim("ver", version)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            task.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static SubjectAttributes inDepartments(String primary, String... others) {
        var paths = new HashSet<>(List.of(others));
        paths.add(primary);
        return new SubjectAttributes(paths, primary, Set.of(), Map.of(), Map.of());
    }

    private static SubjectAttributes withAttributes(SubjectAttributes subject, Map<String, Object> attributes) {
        return new SubjectAttributes(
                subject.departmentPaths(),
                subject.primaryDepartmentPath(),
                subject.positionIds(),
                attributes,
                Map.of("vip", "boolean"));
    }

    private int status(String token, HttpMethod method, String uri, Object body) throws Exception {
        return (Integer) exchange(token, method, uri, body).get("httpStatus");
    }

    private Map<String, Object> exchange(String token, HttpMethod method, String uri, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, response) -> {
            Map<String, Object> envelope = response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(envelope.get("httpStatus"))
                    .isEqualTo(response.getStatusCode().value());
            return envelope;
        });
    }
}
