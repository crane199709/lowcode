package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.lowcode.outbox.mapper.AuditOutboxMapper;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.SubjectAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** 请求体用 Bean Validation 声明约束，违反时由 SDK 统一返回 400 {@code request.invalid}，并逐项列出字段。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScreenApiTest {
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
    StringRedisTemplate globalRedis;

    @Autowired
    AuditOutboxMapper auditOutboxMapper;

    @BeforeEach
    void grantScreenPermissions() {
        TestGrants.publishAll(globalRedis, TestTokens.TENANT_ID, TestTokens.MEMBERSHIP_ID);
    }

    @Test
    void invalidRequestBodyIsRejectedWithFieldErrors() throws Exception {
        Map<?, ?> rejected = exchange(HttpMethod.POST, "/screens", Map.of("name", " "));
        assertThat(rejected.get("httpStatus")).isEqualTo(400);
        assertThat(rejected.get("code")).isEqualTo("request.invalid");
        assertThat((List<?>) rejected.get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .containsExactly("name");

        Map<?, ?> created = exchange(HttpMethod.POST, "/screens", Map.of("name", "华东大区销售大屏"));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        String id = (String) ((Map<?, ?>) created.get("data")).get("id");

        Map<?, ?> updated = exchange(HttpMethod.PUT, "/screens/" + id, Map.of("name", "x".repeat(121)));
        assertThat(updated.get("httpStatus")).isEqualTo(400);
        assertThat((List<?>) updated.get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .containsExactly("name");
    }

    @Test
    void screenDraftRoundTripsAndResolutionChangesKeepWidgetCoordinates() throws Exception {
        Map<String, Object> widget = Map.of(
                "id",
                "title",
                "type",
                "TEXT",
                "x",
                120,
                "y",
                80,
                "width",
                320,
                "height",
                100,
                "zIndex",
                0,
                "config",
                Map.of(
                        "text",
                        "华东经营分析",
                        "fontSize",
                        32,
                        "color",
                        "#ffffff",
                        "backgroundColor",
                        "#18243a",
                        "fontWeight",
                        "bold",
                        "textAlign",
                        "left"));
        Map<String, Object> draft = Map.of(
                "designWidth", 1920, "designHeight", 1080, "backgroundColor", "#101827", "widgets", List.of(widget));
        Map<?, ?> created = exchange(HttpMethod.POST, "/screens", Map.of("name", "经营分析", "draft", draft));
        assertThat(created.get("httpStatus")).isEqualTo(201);
        Map<?, ?> screen = (Map<?, ?>) created.get("data");
        assertThat(screen.get("draft")).isEqualTo(draft);
        String id = (String) screen.get("id");
        Map<String, Object> resized = Map.of(
                "designWidth", 1366, "designHeight", 768, "backgroundColor", "#ffffff", "widgets", List.of(widget));
        assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", resized)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchange(HttpMethod.GET, "/screens/" + id + "/draft", null).get("data"))
                .isEqualTo(resized);
        Map<?, ?> copy = (Map<?, ?>)
                exchange(HttpMethod.POST, "/screens/" + id + "/copy", null).get("data");
        assertThat(copy.get("id")).isNotEqualTo(id);
        assertThat(copy.get("name")).isEqualTo("经营分析（副本）");
        assertThat(copy.get("draft")).isEqualTo(resized);
        exchange(HttpMethod.DELETE, "/screens/" + id, null);
        assertThat(exchange(HttpMethod.GET, "/screens/" + id + "/draft", null).get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchange(HttpMethod.GET, "/screens/" + copy.get("id") + "/draft", null)
                        .get("data"))
                .isEqualTo(resized);
    }

    @Test
    void publishFreezesSavedDraftUntilRepublishedAndCopyStartsUnpublished() throws Exception {
        Map<String, Object> first = draftWith(List.of(textWidget("published-title")));
        Map<?, ?> screen = (Map<?, ?>) exchange(HttpMethod.POST, "/screens", Map.of("name", "发布测试", "draft", first))
                .get("data");
        String id = (String) screen.get("id");
        assertThat(exchange(HttpMethod.GET, "/screens/" + id + "/published", null)
                        .get("httpStatus"))
                .isEqualTo(404);
        Map<?, ?> published = (Map<?, ?>)
                exchange(HttpMethod.POST, "/screens/" + id + "/publish", null).get("data");
        assertThat(published.get("definition")).isEqualTo(first);
        assertThat(published.get("publishedAt")).isNotNull();
        Map<String, Object> changed = draftWith(List.of(textWidget("unpublished-change")));
        exchange(HttpMethod.PUT, "/screens/" + id + "/draft", changed);
        assertThat(((Map<?, ?>) exchange(HttpMethod.GET, "/screens/" + id + "/published", null)
                                .get("data"))
                        .get("definition"))
                .isEqualTo(first);
        assertThat(((Map<?, ?>) exchange(HttpMethod.POST, "/screens/" + id + "/publish", null)
                                .get("data"))
                        .get("definition"))
                .isEqualTo(changed);
        Map<?, ?> copy = (Map<?, ?>)
                exchange(HttpMethod.POST, "/screens/" + id + "/copy", null).get("data");
        assertThat(exchange(HttpMethod.GET, "/screens/" + copy.get("id") + "/published", null)
                        .get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void viewerListsAndReadsOnlyPublishedScreensWithoutAnyDraftContent() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID designer = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        TestGrants.publishAll(globalRedis, tenant, designer);
        TestGrants.publish(globalRedis, tenant, viewer, List.of("screen:view"));
        Map<?, ?> published = (Map<?, ?>) exchangeAs(
                        tenant,
                        designer,
                        HttpMethod.POST,
                        "/screens",
                        Map.of("name", "已发布标题", "draft", draftWith(List.of(textWidget("public")))))
                .get("data");
        String id = (String) published.get("id");
        Map<?, ?> draftOnly =
                (Map<?, ?>) exchangeAs(tenant, designer, HttpMethod.POST, "/screens", Map.of("name", "不可见的草稿"))
                        .get("data");
        exchangeAs(tenant, designer, HttpMethod.POST, "/screens/" + id + "/publish", null);
        exchangeAs(
                tenant,
                designer,
                HttpMethod.PUT,
                "/screens/" + id,
                Map.of("name", "未发布名称", "draft", draftWith(List.of(textWidget("secret-draft")))));
        Map<?, ?> listed = exchangeAs(tenant, viewer, HttpMethod.GET, "/screens", null);
        assertThat((List<?>) listed.get("data")).singleElement().satisfies(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            assertThat(row.get("id")).isEqualTo(id);
            assertThat(row.get("name")).isEqualTo("已发布标题");
            assertThat(row.get("draft")).isNull();
            assertThat(row.get("designWidth")).isEqualTo(1920);
        });
        assertThat(((Map<?, ?>) ((Map<?, ?>) listed.get("meta")).get("page")).get("totalElements"))
                .isEqualTo(1);
        Map<?, ?> metadata = (Map<?, ?>) exchangeAs(tenant, viewer, HttpMethod.GET, "/screens/" + id, null)
                .get("data");
        assertThat(metadata.get("draft")).isNull();
        assertThat(metadata.toString()).doesNotContain("secret-draft", "未发布名称");
        assertThat(exchangeAs(tenant, viewer, HttpMethod.GET, "/screens/" + draftOnly.get("id"), null)
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchangeAs(tenant, viewer, HttpMethod.GET, "/screens/" + draftOnly.get("id") + "/published", null)
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchangeAs(tenant, viewer, HttpMethod.GET, "/screens/" + id + "/draft", null)
                        .get("httpStatus"))
                .isEqualTo(403);
        assertThat(exchangeAs(tenant, viewer, HttpMethod.POST, "/screens/" + id + "/publish", null)
                        .get("httpStatus"))
                .isEqualTo(403);
        Map<?, ?> playback = exchangeAs(tenant, viewer, HttpMethod.GET, "/screens/" + id + "/published", null);
        assertThat(playback.get("httpStatus")).isEqualTo(200);
        assertThat(playback.toString()).contains("public").doesNotContain("secret-draft", "未发布名称");
    }

    @Test
    void publishedScreenVisibilityFollowsActiveMembershipSelfAndDepartmentScopes() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        SubjectAttributes east = new SubjectAttributes(Set.of("east"), "east", Set.of(), Map.of(), Map.of());
        SubjectAttributes west = new SubjectAttributes(Set.of("west"), "west", Set.of(), Map.of(), Map.of());
        List<UUID> members = List.of(alice, bob, carol);
        List<String> ids = new ArrayList<>();
        for (UUID member : members) {
            TestGrants.publish(
                    globalRedis,
                    tenant,
                    member,
                    1,
                    TestGrants.ALL_SCREEN_PERMISSIONS,
                    member.equals(carol) ? west : east,
                    List.of(DataScope.ALL));
            Map<?, ?> screen = (Map<?, ?>)
                    exchangeAs(tenant, member, HttpMethod.POST, "/screens", Map.of("name", member.toString()))
                            .get("data");
            String id = (String) screen.get("id");
            ids.add(id);
            assertThat(exchangeAs(tenant, member, HttpMethod.POST, "/screens/" + id + "/publish", null)
                            .get("httpStatus"))
                    .isEqualTo(200);
        }
        record ScopeCase(UUID member, SubjectAttributes subject, List<DataScope> scopes, List<String> visible) {}
        for (ScopeCase scope : List.of(
                new ScopeCase(alice, east, List.of(DataScope.SELF), List.of(ids.get(0))),
                new ScopeCase(bob, east, List.of(DataScope.DEPARTMENT), List.of(ids.get(0), ids.get(1))),
                new ScopeCase(carol, west, List.of(), List.of()))) {
            TestGrants.publish(
                    globalRedis,
                    tenant,
                    scope.member(),
                    2,
                    List.of("screen:view", "screen:publish"),
                    scope.subject(),
                    scope.scopes());
            String token = TestTokens.forMembership(tenant, scope.member(), 2);
            Map<?, ?> list = exchangeWithToken(token, HttpMethod.GET, "/screens", null);
            assertThat((List<?>) list.get("data"))
                    .<String>extracting(row -> (String) ((Map<?, ?>) row).get("id"))
                    .containsExactlyInAnyOrderElementsOf(scope.visible());
            assertThat(((Map<?, ?>) ((Map<?, ?>) list.get("meta")).get("page")).get("totalElements"))
                    .isEqualTo(scope.visible().size());
            for (String id : ids) {
                int expected = scope.visible().contains(id) ? 200 : 404;
                for (String suffix : List.of("", "/published")) {
                    assertThat(exchangeWithToken(token, HttpMethod.GET, "/screens/" + id + suffix, null)
                                    .get("httpStatus"))
                            .isEqualTo(expected);
                }
                assertThat(exchangeWithToken(token, HttpMethod.POST, "/screens/" + id + "/publish", null)
                                .get("httpStatus"))
                        .isEqualTo(expected);
            }
        }
        UUID otherTenant = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        TestGrants.publishAll(globalRedis, otherTenant, outsider);
        for (String id : ids) {
            assertThat(exchangeAs(otherTenant, outsider, HttpMethod.GET, "/screens/" + id + "/published", null)
                            .get("httpStatus"))
                    .isEqualTo(404);
            assertThat(exchangeAs(otherTenant, outsider, HttpMethod.POST, "/screens/" + id + "/publish", null)
                            .get("httpStatus"))
                    .isEqualTo(404);
        }
    }

    @Test
    void draftEditsDoNotChangeViewerMetadataOrPublishedOrdering() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID designer = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        TestGrants.publishAll(globalRedis, tenant, designer);
        TestGrants.publish(globalRedis, tenant, viewer, List.of("screen:view"));
        Map<?, ?> first = (Map<?, ?>) exchangeAs(tenant, designer, HttpMethod.POST, "/screens", Map.of("name", "先发布"))
                .get("data");
        String firstId = (String) first.get("id");
        Map<?, ?> firstPublished =
                (Map<?, ?>) exchangeAs(tenant, designer, HttpMethod.POST, "/screens/" + firstId + "/publish", null)
                        .get("data");
        Map<?, ?> second = (Map<?, ?>) exchangeAs(tenant, designer, HttpMethod.POST, "/screens", Map.of("name", "后发布"))
                .get("data");
        String secondId = (String) second.get("id");
        exchangeAs(tenant, designer, HttpMethod.POST, "/screens/" + secondId + "/publish", null);
        exchangeAs(
                tenant,
                designer,
                HttpMethod.PUT,
                "/screens/" + firstId,
                Map.of(
                        "name",
                        "未发布的改名",
                        "draft",
                        Map.of(
                                "designWidth",
                                3840,
                                "designHeight",
                                2160,
                                "backgroundColor",
                                "#ffffff",
                                "widgets",
                                List.of())));
        List<?> items = (List<?>)
                exchangeAs(tenant, viewer, HttpMethod.GET, "/screens", null).get("data");
        assertThat(items)
                .<String>extracting(row -> (String) ((Map<?, ?>) row).get("id"))
                .containsExactly(secondId, firstId);
        Map<?, ?> metadata = (Map<?, ?>) items.get(1);
        assertThat(metadata.get("name")).isEqualTo("先发布");
        assertThat(metadata.get("designWidth")).isEqualTo(1920);
        assertThat(metadata.get("designHeight")).isEqualTo(1080);
        assertThat(metadata.get("updatedAt")).isEqualTo(firstPublished.get("publishedAt"));
    }

    @Test
    void publishingRequiresPublishPermissionAndWritesAuditEvent() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        TestGrants.publish(globalRedis, tenant, editor, List.of("screen:edit", "screen:view"));
        TestGrants.publish(globalRedis, tenant, publisher, List.of("screen:publish"));
        Map<?, ?> screen = (Map<?, ?>) exchangeAs(tenant, editor, HttpMethod.POST, "/screens", Map.of("name", "审计发布"))
                .get("data");
        String id = (String) screen.get("id");
        assertThat(exchangeAs(tenant, editor, HttpMethod.POST, "/screens/" + id + "/publish", null)
                        .get("httpStatus"))
                .isEqualTo(403);
        assertThat(exchangeAs(tenant, editor, HttpMethod.GET, "/screens/" + id + "/published", null)
                        .get("httpStatus"))
                .isEqualTo(404);
        UUID publisherIdentity = UUID.randomUUID();
        assertThat(exchangeWithToken(
                                TestTokens.forIdentity(tenant, publisher, publisherIdentity),
                                HttpMethod.POST,
                                "/screens/" + id + "/publish",
                                null)
                        .get("httpStatus"))
                .isEqualTo(200);
        JsonMapper json = JsonMapper.builder().build();
        List<Map<?, ?>> events = auditOutboxMapper.selectAuditPayloads().stream()
                .<Map<?, ?>>map(payload -> json.readValue(payload, Map.class))
                .filter(event -> id.equals(event.get("resourceId")))
                .toList();
        assertThat(events)
                .<String>extracting(event -> (String) event.get("action"))
                .containsOnly("screen.publish");
        assertThat(events)
                .<String>extracting(event -> (String) event.get("outcome"))
                .containsExactly("FAILURE", "SUCCESS");
        assertThat(events)
                .<String>extracting(event -> (String) event.get("actorId"))
                .containsExactly(TestTokens.IDENTITY_ID.toString(), publisherIdentity.toString());
        assertThat(events).allSatisfy(event -> {
            assertThat(event.get("resourceType")).isEqualTo("screen");
            assertThat(event.get("tenantId")).isEqualTo(tenant.toString());
        });
    }

    @Test
    void textFontSizeCannotOverflowTheRenderer() throws Exception {
        Map<String, Object> widget = textWidget("title");
        widget.put(
                "config",
                Map.of(
                        "text",
                        "标题",
                        "fontSize",
                        4294967328L,
                        "color",
                        "#ffffff",
                        "backgroundColor",
                        "#18243a",
                        "fontWeight",
                        "normal",
                        "textAlign",
                        "left"));
        Map<?, ?> rejected =
                exchange(HttpMethod.POST, "/screens", Map.of("name", "非法字号", "draft", draftWith(List.of(widget))));
        assertThat(rejected.get("httpStatus")).isEqualTo(400);
        assertThat(rejected.get("code")).isEqualTo("request.invalid");
    }

    @Test
    void draftRejectsInvalidResolutionDuplicateIdsAndUnsupportedWidgetConfiguration() throws Exception {
        for (int invalid : List.of(639, 7681)) {
            var draft = new HashMap<>(draftWith(List.of()));
            draft.put("designWidth", invalid);
            assertThat(exchange(HttpMethod.POST, "/screens", Map.of("name", "无效分辨率", "draft", draft))
                            .get("httpStatus"))
                    .isEqualTo(400);
        }
        assertThat(exchange(
                                HttpMethod.POST,
                                "/screens",
                                Map.of(
                                        "name",
                                        "重复组件",
                                        "draft",
                                        draftWith(List.of(textWidget("same"), textWidget("same")))))
                        .get("httpStatus"))
                .isEqualTo(400);
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
                        "type", "UNKNOWN", "width", 0, "x", -1, "config", Map.of("text", "标题"))
                .entrySet()) {
            var widget = textWidget("invalid");
            widget.put(invalid.getKey(), invalid.getValue());
            assertThat(exchange(
                                    HttpMethod.POST,
                                    "/screens",
                                    Map.of("name", "无效组件", "draft", draftWith(List.of(widget))))
                            .get("httpStatus"))
                    .isEqualTo(400);
        }
        var widgets = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 201; index++) {
            widgets.add(textWidget("text-" + index));
        }
        assertThat(exchange(HttpMethod.POST, "/screens", Map.of("name", "组件过多", "draft", draftWith(widgets)))
                        .get("httpStatus"))
                .isEqualTo(400);
    }

    @Test
    void draftReadSaveAndCopyRespectTenantAndEditPermission() throws Exception {
        Map<?, ?> screen = (Map<?, ?>)
                exchange(HttpMethod.POST, "/screens", Map.of("name", "隔离草稿")).get("data");
        String id = (String) screen.get("id");
        UUID otherTenant = UUID.fromString("019a0110-0000-7000-8000-0000000000dd");
        TestGrants.publishAll(globalRedis, otherTenant, TestTokens.MEMBERSHIP_ID);
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.POST)) {
            String path = "/screens/" + id + (method == HttpMethod.POST ? "/copy" : "/draft");
            assertThat(exchangeAs(otherTenant, method, path, method == HttpMethod.PUT ? draftWith(List.of()) : null)
                            .get("httpStatus"))
                    .isEqualTo(404);
        }
        assertThat(exchange(HttpMethod.GET, "/screens/" + id + "/draft", null).get("httpStatus"))
                .isEqualTo(200);
        UUID reader = UUID.fromString("019a0110-0000-7000-8000-0000000000de");
        TestGrants.publish(globalRedis, TestTokens.TENANT_ID, reader, List.of("screen:view"));
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.POST)) {
            String path = "/screens/" + id + (method == HttpMethod.POST ? "/copy" : "/draft");
            assertThat(exchangeAs(
                                    TestTokens.TENANT_ID,
                                    reader,
                                    method,
                                    path,
                                    method == HttpMethod.PUT ? draftWith(List.of()) : null)
                            .get("httpStatus"))
                    .isEqualTo(403);
        }
    }

    private static Map<String, Object> textWidget(String id) {
        var widget = new HashMap<String, Object>();
        widget.putAll(Map.of(
                "id",
                id,
                "type",
                "TEXT",
                "x",
                120,
                "y",
                80,
                "width",
                320,
                "height",
                100,
                "zIndex",
                0,
                "config",
                Map.of(
                        "text",
                        "标题",
                        "fontSize",
                        32,
                        "color",
                        "#ffffff",
                        "backgroundColor",
                        "#18243a",
                        "fontWeight",
                        "normal",
                        "textAlign",
                        "left")));
        return widget;
    }

    private static Map<String, Object> draftWith(List<?> widgets) {
        return Map.of("designWidth", 1920, "designHeight", 1080, "backgroundColor", "#101827", "widgets", widgets);
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body) throws Exception {
        return exchangeAs(TestTokens.TENANT_ID, method, uri, body);
    }

    private Map<?, ?> exchangeAs(UUID tenant, HttpMethod method, String uri, Object body) throws Exception {
        return exchangeAs(tenant, TestTokens.MEMBERSHIP_ID, method, uri, body);
    }

    private Map<?, ?> exchangeAs(UUID tenant, UUID membership, HttpMethod method, String uri, Object body)
            throws Exception {
        return exchangeWithToken(TestTokens.forMembership(tenant, membership), method, uri, body);
    }

    private Map<?, ?> exchangeWithToken(String token, HttpMethod method, String uri, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
