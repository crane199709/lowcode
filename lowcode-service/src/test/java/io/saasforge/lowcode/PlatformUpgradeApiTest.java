package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 在同一个隔离数据库先执行原有 V1—V11 并写入旧大屏/文件，再由服务启动应用新增迁移。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformUpgradeApiTest {
    static final String SCREEN_ID = "019a0110-0000-7000-8000-000000000101";
    static final String OBJECT_ID = "019a0110-0000-7000-8000-000000000102";
    static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF9sAAAAASUVORK5CYII=");
    static final SharedContainers.Postgres postgres = baseline();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    private static SharedContainers.Postgres baseline() {
        var database = SharedContainers.postgres();
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), "demo_migrator", "demo_migrator")
                .schemas("demo")
                .defaultSchema("demo")
                .createSchemas(false)
                .placeholders(Map.of("serviceSchema", "demo"))
                .locations("classpath:db/migration", "classpath:upgrade")
                .target("11")
                .load()
                .migrate();
        try (var s3 = S3Client.builder()
                .endpointOverride(URI.create(SharedContainers.storageEndpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("storage-test", "storage-test-secret")))
                .build()) {
            s3.putObject(
                    request -> request.bucket("lowcode-test")
                            .key(
                                    "019a0110-0000-7000-8000-000000000001/lowcode-service/019a0110-0000-7000-8000-000000000102")
                            .contentType("image/png"),
                    RequestBody.fromBytes(PNG));
        }
        return database;
    }

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

    @BeforeEach
    void grantEditor() {
        TestGrants.publishAll(globalRedis, TestTokens.TENANT_ID, TestTokens.MEMBERSHIP_ID);
    }

    @Test
    void existingScreenAndImageRemainReadableAndEditableAfterIncrementalMigration() throws Exception {
        var original = exchange(TestTokens.TENANT_ID, HttpMethod.GET, "/screens/" + SCREEN_ID, null);
        assertThat(original.get("httpStatus")).isEqualTo(200);
        assertThat(((Map<?, ?>) original.get("data")).get("name")).isEqualTo("升级前保留大屏");
        var draft = (Map<?, ?>) exchange(TestTokens.TENANT_ID, HttpMethod.GET, "/screens/" + SCREEN_ID + "/draft", null)
                .get("data");
        assertThat(draft.get("backgroundImageObjectId")).isEqualTo(OBJECT_ID);
        var ticket = (Map<?, ?>) exchange(
                        TestTokens.TENANT_ID,
                        HttpMethod.GET,
                        "/screens/" + SCREEN_ID + "/images/" + OBJECT_ID + "/download",
                        null)
                .get("data");
        assertThat(ticket).isNotNull();
        var image = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create((String) ticket.get("url")))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(PNG);
        var changed = exchange(
                TestTokens.TENANT_ID,
                HttpMethod.PUT,
                "/screens/" + SCREEN_ID,
                Map.of("name", "升级后修改大屏", "draft", draft));
        assertThat(changed.get("httpStatus")).isEqualTo(200);
        var updated = (Map<?, ?>) exchange(TestTokens.TENANT_ID, HttpMethod.GET, "/screens/" + SCREEN_ID, null)
                .get("data");
        assertThat(updated.get("name")).isEqualTo("升级后修改大屏");
        assertThat(((Map<?, ?>) updated.get("draft")).get("backgroundImageObjectId"))
                .isEqualTo(OBJECT_ID);
    }

    @Test
    void existingScreenAndImageStayIsolatedFromAnotherTenantAfterUpgrade() throws Exception {
        UUID other = UUID.fromString("019a0110-0000-7000-8000-000000000201");
        TestGrants.publishAll(globalRedis, other, TestTokens.MEMBERSHIP_ID);
        assertThat(exchange(other, HttpMethod.GET, "/screens/" + SCREEN_ID, null)
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchange(other, HttpMethod.GET, "/screens/" + SCREEN_ID + "/images/" + OBJECT_ID + "/download", null)
                        .get("httpStatus"))
                .isEqualTo(404);
    }

    private Map<?, ?> exchange(UUID tenant, HttpMethod method, String path, Object body) throws Exception {
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(path)
                .header("Authorization", "Bearer " + TestTokens.forTenant(tenant))
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
