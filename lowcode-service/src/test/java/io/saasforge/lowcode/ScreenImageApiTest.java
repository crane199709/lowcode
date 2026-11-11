package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.lowcode.screen.service.ScreenImageService;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.SubjectAttributes;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StoragePurpose;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
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

/** 请求体用 Bean Validation 声明约束，违反时由 SDK 统一返回 400 {@code request.invalid}，并逐项列出字段。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScreenImageApiTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.storage.endpoint", SharedContainers::storageEndpoint);
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate globalRedis;

    @Autowired
    ObjectStorage storage;

    @BeforeEach
    void grantScreenPermissions() {
        TestGrants.publishAll(globalRedis, TestTokens.TENANT_ID, TestTokens.MEMBERSHIP_ID);
    }

    @Test
    void imageUploadsAreLimitedToSupportedTypesAndTenMebibytes() throws Exception {
        String id = (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/screens", Map.of("name", "图片大屏"))
                        .get("data"))
                .get("id");
        var rejected = exchange(
                HttpMethod.POST,
                "/screens/" + id + "/images/uploads",
                Map.of("filename", "script.svg", "contentType", "image/svg+xml", "size", 100));
        assertThat(rejected.get("code")).isEqualTo("storage.extensionNotAllowed");
        var oversized = exchange(
                HttpMethod.POST,
                "/screens/" + id + "/images/uploads",
                Map.of("filename", "background.png", "contentType", "image/png", "size", 10485761));
        assertThat(oversized.get("code")).isEqualTo("storage.fileTooLarge");
    }

    @Test
    void uploadRejectsDisguisedNonImageAndMismatchedMediaTypes() throws Exception {
        String id = createScreen();
        for (Map<String, Object> file : List.of(
                Map.<String, Object>of("filename", "image.png", "contentType", "text/html", "size", 100),
                Map.<String, Object>of("filename", "image.jpg", "contentType", "image/png", "size", 100))) {
            assertThat(exchange(HttpMethod.POST, "/screens/" + id + "/images/uploads", file)
                            .get("code"))
                    .isEqualTo("screen.invalidImage");
        }
    }

    @Test
    void completionRechecksStoredImageMediaType() throws Exception {
        String id = createScreen();
        UUID disguised;
        try (var ignored = CurrentTenant.bind(TestTokens.TENANT_ID)) {
            disguised = storage.write(
                            ScreenImageService.PURPOSE,
                            "disguised.png",
                            "text/html",
                            1,
                            new ByteArrayInputStream(new byte[] {1}))
                    .id();
        }
        assertThat(exchange(HttpMethod.POST, "/screens/" + id + "/images/" + disguised + "/complete", null)
                        .get("code"))
                .isEqualTo("screen.invalidImage");
        assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", imageDraft(disguised.toString()))
                        .get("code"))
                .isEqualTo("screen.invalidImage");
    }

    @Test
    void designerCanCompleteAnImageUploadedDirectlyToStorage() throws Exception {
        String id = createScreen();
        Map<?, ?> ticket = upload(id);
        var completed =
                exchange(HttpMethod.POST, "/screens/" + id + "/images/" + ticket.get("objectId") + "/complete", null);
        assertThat(completed.get("httpStatus")).isEqualTo(200);
        assertThat(((Map<?, ?>) completed.get("data")).get("id")).isEqualTo(ticket.get("objectId"));
    }

    @Test
    void draftStoresConfirmedImageIdsAndEditorCanDownloadBeforeSaving() throws Exception {
        String id = createScreen();
        Map<?, ?> ticket = upload(id);
        String objectId = ticket.get("objectId").toString();
        Map<String, Object> draft = imageDraft(objectId);
        assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", draft).get("code"))
                .isEqualTo("storage.objectNotFound");
        exchange(HttpMethod.POST, "/screens/" + id + "/images/" + objectId + "/complete", null);
        Map<?, ?> download =
                (Map<?, ?>) exchange(HttpMethod.GET, "/screens/" + id + "/draft/images/" + objectId + "/download", null)
                        .get("data");
        var response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create((String) download.get("url")))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", draft).get("data"))
                .isEqualTo(draft);
        assertThat(exchange(HttpMethod.GET, "/screens/" + id + "/draft", null).get("data"))
                .isEqualTo(draft);
    }

    @Test
    void imageBindingAndEditorEndpointsRespectTenantPurposeAndEditPermission() throws Exception {
        String id = createScreen();
        Map<?, ?> upload = upload(id);
        String objectId = upload.get("objectId").toString();
        exchange(HttpMethod.POST, "/screens/" + id + "/images/" + objectId + "/complete", null);
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publishAll(globalRedis, otherTenant, TestTokens.MEMBERSHIP_ID);
        String otherId =
                (String) ((Map<?, ?>) exchangeAs(otherTenant, HttpMethod.POST, "/screens", Map.of("name", "其他租户大屏"))
                                .get("data"))
                        .get("id");
        assertThat(exchangeAs(otherTenant, HttpMethod.PUT, "/screens/" + otherId + "/draft", imageDraft(objectId))
                        .get("code"))
                .isEqualTo("storage.objectNotFound");
        assertThat(exchangeAs(
                                otherTenant,
                                HttpMethod.GET,
                                "/screens/" + id + "/draft/images/" + objectId + "/download",
                                null)
                        .get("code"))
                .isEqualTo("screen.notFound");
        UUID foreignPurpose;
        try (var ignored = CurrentTenant.bind(TestTokens.TENANT_ID)) {
            foreignPurpose = storage.write(
                            new StoragePurpose("other-image", Set.of("png"), 1024, Duration.ofMinutes(5)),
                            "other.png",
                            "image/png",
                            1,
                            new ByteArrayInputStream(new byte[] {1}))
                    .id();
        }
        assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", imageDraft(foreignPurpose.toString()))
                        .get("code"))
                .isEqualTo("storage.purposeMismatch");
        UUID viewer = UUID.randomUUID();
        TestGrants.publish(globalRedis, TestTokens.TENANT_ID, viewer, List.of("screen:view"));
        assertThat(exchangeAs(
                                TestTokens.TENANT_ID,
                                viewer,
                                HttpMethod.GET,
                                "/screens/" + id + "/draft/images/" + objectId + "/download",
                                null)
                        .get("httpStatus"))
                .isEqualTo(403);
        assertThat(exchangeAs(
                                TestTokens.TENANT_ID,
                                viewer,
                                HttpMethod.POST,
                                "/screens/" + id + "/images/uploads",
                                Map.of("filename", "a.png", "contentType", "image/png", "size", 10))
                        .get("httpStatus"))
                .isEqualTo(403);
    }

    @Test
    void imageConfigurationRejectsUrlsAndUnknownFillModes() throws Exception {
        String id = createScreen();
        for (String value : List.of("https://example.com/image.png", "not-an-object-id")) {
            assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", imageDraft(value))
                            .get("httpStatus"))
                    .isEqualTo(400);
        }
        var draft = new HashMap<>(imageDraft(UUID.randomUUID().toString()));
        draft.put("backgroundImageFill", "repeat");
        assertThat(exchange(HttpMethod.PUT, "/screens/" + id + "/draft", draft).get("httpStatus"))
                .isEqualTo(400);
    }

    @Test
    void viewersDownloadOnlyImagesFrozenInVisiblePublishedScreens() throws Exception {
        String id = createScreen();
        String original = upload(id).get("objectId").toString();
        exchange(HttpMethod.POST, "/screens/" + id + "/images/" + original + "/complete", null);
        String background = upload(id).get("objectId").toString();
        exchange(HttpMethod.POST, "/screens/" + id + "/images/" + background + "/complete", null);
        var publishedDraft = new HashMap<>(imageDraft(original));
        publishedDraft.put("backgroundImageObjectId", background);
        exchange(HttpMethod.PUT, "/screens/" + id + "/draft", publishedDraft);
        String path = "/screens/" + id + "/images/" + original + "/download";
        assertThat(exchange(HttpMethod.GET, path, null).get("httpStatus")).isEqualTo(404);
        exchange(HttpMethod.POST, "/screens/" + id + "/publish", null);
        String replacement = upload(id).get("objectId").toString();
        exchange(HttpMethod.POST, "/screens/" + id + "/images/" + replacement + "/complete", null);
        exchange(HttpMethod.PUT, "/screens/" + id + "/draft", imageDraft(replacement));
        UUID viewer = UUID.randomUUID();
        TestGrants.publish(globalRedis, TestTokens.TENANT_ID, viewer, List.of("screen:view"));
        assertThat(exchangeAs(TestTokens.TENANT_ID, viewer, HttpMethod.GET, path, null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchangeAs(
                                TestTokens.TENANT_ID,
                                viewer,
                                HttpMethod.GET,
                                "/screens/" + id + "/images/" + background + "/download",
                                null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(exchangeAs(
                                TestTokens.TENANT_ID,
                                viewer,
                                HttpMethod.GET,
                                "/screens/" + id + "/images/" + replacement + "/download",
                                null)
                        .get("httpStatus"))
                .isEqualTo(404);
        UUID scopedViewer = UUID.randomUUID();
        TestGrants.publish(
                globalRedis,
                TestTokens.TENANT_ID,
                scopedViewer,
                1,
                List.of("screen:view"),
                SubjectAttributes.NONE,
                List.of(DataScope.SELF));
        assertThat(exchangeAs(TestTokens.TENANT_ID, scopedViewer, HttpMethod.GET, path, null)
                        .get("code"))
                .isEqualTo("screen.notFound");
        exchange(HttpMethod.POST, "/screens/" + id + "/publish", null);
        assertThat(exchangeAs(TestTokens.TENANT_ID, viewer, HttpMethod.GET, path, null)
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchangeAs(
                                TestTokens.TENANT_ID,
                                viewer,
                                HttpMethod.GET,
                                "/screens/" + id + "/images/" + replacement + "/download",
                                null)
                        .get("httpStatus"))
                .isEqualTo(200);
    }

    private Map<String, Object> imageDraft(String objectId) {
        return Map.of(
                "designWidth",
                1920,
                "designHeight",
                1080,
                "backgroundColor",
                "#101827",
                "backgroundImageObjectId",
                objectId,
                "backgroundImageFill",
                "cover",
                "widgets",
                List.of(Map.of(
                        "id",
                        "picture",
                        "type",
                        "IMAGE",
                        "x",
                        80,
                        "y",
                        80,
                        "width",
                        640,
                        "height",
                        360,
                        "zIndex",
                        0,
                        "config",
                        Map.of("objectId", objectId, "fill", "contain"))));
    }

    private String createScreen() throws Exception {
        return (String) ((Map<?, ?>) exchange(HttpMethod.POST, "/screens", Map.of("name", "图片大屏"))
                        .get("data"))
                .get("id");
    }

    private Map<?, ?> upload(String screenId) throws Exception {
        byte[] png = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF9sAAAAASUVORK5CYII=");
        Map<?, ?> ticket = (Map<?, ?>) exchange(
                        HttpMethod.POST,
                        "/screens/" + screenId + "/images/uploads",
                        Map.of("filename", "区域背景.png", "contentType", "image/png", "size", png.length))
                .get("data");
        var request = HttpRequest.newBuilder(URI.create((String) ticket.get("url")))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(png));
        ((Map<?, ?>) ticket.get("headers")).forEach((name, value) -> request.header(name.toString(), value.toString()));
        assertThat(HttpClient.newHttpClient()
                        .send(request.build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        return ticket;
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
