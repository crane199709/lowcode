package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

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
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forMembership(tenant, membership))
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
