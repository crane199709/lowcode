package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body) throws Exception {
        return RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(TestTokens.TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> response.bodyTo(Map.class));
    }
}
