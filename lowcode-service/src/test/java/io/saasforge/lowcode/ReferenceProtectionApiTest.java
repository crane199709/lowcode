package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.lowcode.reference.mapper.ReferenceConcurrencyMapper;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.SubjectAttributes;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceProtectionApiTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final SharedContainers.ExternalDatabase external = SharedContainers.externalDataset();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.database", redis::database);
        TestTokens.register(r);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ReferenceConcurrencyMapper concurrency;

    @Autowired
    PlatformTransactionManager transactions;

    String token;

    @BeforeEach
    void grant() throws Exception {
        token = TestTokens.forTenant(TestTokens.TENANT_ID);
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                TestTokens.MEMBERSHIP_ID,
                List.of(
                        "datasource:view",
                        "datasource:manage",
                        "dataset:view",
                        "dataset:manage",
                        "screen:view",
                        "screen:edit",
                        "screen:publish"));
    }

    @Test
    void referencedDataSourceCannotBeDeletedAndReportsDatasetWithoutSql() {
        String source = source();
        String dataset = dataset(source);
        var blocked = call(HttpMethod.DELETE, "/data-sources/" + source, null);
        assertThat(blocked.get("httpStatus")).isEqualTo(409);
        assertThat(blocked.get("code")).isEqualTo("datasource.referenced");
        assertThat(blocked.get("userTip")).asString().contains("区域销售数据集");
        var references = call(HttpMethod.GET, "/data-sources/" + source + "/references", null);
        assertThat(references.toString()).contains(dataset, "区域销售数据集").doesNotContain("SELECT", "password", "username");
        assertThat(call(HttpMethod.GET, "/data-sources/" + source, null).get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.GET, "/datasets/" + dataset, null).get("httpStatus"))
                .isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/data-sources/" + source, null).get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.GET, "/data-sources/" + source, null).get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void datasetReferencedOnlyByDraftCannotBeDeleted() {
        String dataset = dataset(source());
        String screen = screen(dataset, "草稿经营大屏");
        var blocked = call(HttpMethod.DELETE, "/datasets/" + dataset, null);
        assertThat(blocked.get("httpStatus")).isEqualTo(409);
        assertThat(blocked.get("code")).isEqualTo("dataset.referenced");
        assertThat(blocked.get("userTip")).asString().contains("草稿经营大屏");
        var references = call(HttpMethod.GET, "/datasets/" + dataset + "/references", null);
        assertThat(references.toString()).contains(screen, "草稿经营大屏").doesNotContain("SELECT", "config", "datasetId");
        assertThat(call(HttpMethod.GET, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.DELETE, "/screens/" + screen, null).get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("ok");
    }

    @Test
    void publishedReferencesRemainAfterDraftChangesAndBothSnapshotsCountOnce() {
        String dataset = dataset(source());
        String screen = screen(dataset, "已发布经营大屏");
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/publish", null)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(referenceList(dataset)).hasSize(1);
        assertThat(call(HttpMethod.PUT, "/screens/" + screen + "/draft", emptyDraft())
                        .get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("dataset.referenced");
        assertThat(referenceList(dataset)).hasSize(1);
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/publish", null)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(referenceList(dataset)).isEmpty();
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("ok");
    }

    @Test
    void hiddenDataScopeStillBlocksDeletionWithoutDisclosingScreenMetadataAndOtherTenantIsExcluded() throws Exception {
        String dataset = dataset(source());
        String hiddenScreen = screen(dataset, "保密经营大屏名称");
        String tenantOnlyDataset = dataset(source());
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publish(
                redisTemplate,
                otherTenant,
                TestTokens.MEMBERSHIP_ID,
                List.of("datasource:manage", "dataset:manage", "screen:edit"));
        token = TestTokens.forTenant(otherTenant);
        assertThat(call(HttpMethod.POST, "/screens", Map.of("name", "跨租户非法绑定", "draft", draft(tenantOnlyDataset)))
                        .get("httpStatus"))
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/datasets/" + dataset + "/references", null)
                        .get("httpStatus"))
                .isEqualTo(404);
        String otherScreen = screen(dataset(source()), "其他租户机密大屏");
        UUID scoped = UUID.randomUUID();
        TestGrants.publish(
                redisTemplate,
                TestTokens.TENANT_ID,
                scoped,
                1,
                List.of("dataset:manage", "screen:edit", "screen:view"),
                SubjectAttributes.NONE,
                List.of(DataScope.SELF));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, scoped);
        String visible = screen(dataset, "本人经营大屏");
        var references = call(HttpMethod.GET, "/datasets/" + dataset + "/references", null);
        assertThat(referenceList(dataset)).hasSize(1);
        assertThat(references.toString())
                .contains(visible, "本人经营大屏", "hiddenReferences=true")
                .doesNotContain(hiddenScreen, "保密经营大屏名称", otherScreen, "其他租户机密大屏");
        var blocked = call(HttpMethod.DELETE, "/datasets/" + dataset, null);
        assertThat(blocked.get("httpStatus")).isEqualTo(409);
        assertThat(blocked.toString()).contains("其他无权查看的大屏").doesNotContain(hiddenScreen, "保密经营大屏名称");
        call(HttpMethod.DELETE, "/screens/" + visible, null);
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("dataset.referenced");
        assertThat(call(HttpMethod.DELETE, "/datasets/" + tenantOnlyDataset, null)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.GET, "/datasets/" + tenantOnlyDataset + "/references", null)
                        .get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void displayNameChangesKeepQueryWorkingButIncompatibleMeasureBecomesFieldMissing() {
        String source = source();
        String dataset = dataset(source);
        String screen = screen(dataset, "字段即时生效大屏");
        call(HttpMethod.POST, "/screens/" + screen + "/publish", null);
        var renamed = call(
                HttpMethod.PUT,
                "/datasets/" + dataset,
                Map.of(
                        "name",
                        "区域销售数据集",
                        "dataSourceId",
                        source,
                        "sql",
                        "SELECT '华东' AS region, 12 AS amount",
                        "fields",
                        List.of(Map.of("name", "amount", "displayName", "含税销售额", "role", "MEASURE", "date", false))));
        assertThat(renamed.get("code")).isEqualTo("ok");
        assertThat(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/chart/query", null)
                        .toString())
                .contains("含税销售额", "rows=[[华东, 12]]");
        var changed = call(
                HttpMethod.PUT,
                "/datasets/" + dataset,
                Map.of("name", "区域销售数据集", "dataSourceId", source, "sql", "SELECT '华东' AS region, '12' AS amount"));
        assertThat(changed.get("code")).isEqualTo("ok");
        var result = call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/chart/query", null);
        assertThat(result.get("code")).isEqualTo("dataset.fieldMissing");
        assertThat(result.get("userTip")).isEqualTo("字段不存在，请重新配置组件");
        Object widget = ((List<?>) draft(dataset).get("widgets")).get(0);
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/draft/query", widget)
                        .get("code"))
                .isEqualTo("dataset.fieldMissing");
    }

    @Test
    void removingDateCapabilityInvalidatesExistingDateGrouping() {
        String source = source();
        String sql = "SELECT DATE '2026-11-11' AS region, 12 AS amount";
        var saved = call(HttpMethod.POST, "/datasets", Map.of("name", "日期数据集", "dataSourceId", source, "sql", sql));
        String dataset = id(saved);
        var definition = new HashMap<>(draft(dataset));
        var widget = new HashMap<>((Map<String, Object>) ((List<?>) definition.get("widgets")).get(0));
        var config = new HashMap<>((Map<String, Object>) widget.get("config"));
        config.put(
                "query",
                Map.of(
                        "dimensions",
                        List.of(Map.of("field", "region", "dateGrouping", "DAY")),
                        "measures",
                        List.of(Map.of("field", "amount", "aggregation", "SUM")),
                        "filters",
                        List.of(),
                        "sorts",
                        List.of()));
        widget.put("config", config);
        definition.put("widgets", List.of(widget));
        String screen = id(call(HttpMethod.POST, "/screens", Map.of("name", "日期分组大屏", "draft", definition)));
        call(HttpMethod.POST, "/screens/" + screen + "/publish", null);
        assertThat(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/chart/query", null)
                        .get("code"))
                .isEqualTo("ok");
        var changed = call(
                HttpMethod.PUT,
                "/datasets/" + dataset,
                Map.of(
                        "name",
                        "日期数据集",
                        "dataSourceId",
                        source,
                        "sql",
                        sql,
                        "fields",
                        List.of(Map.of("name", "region", "displayName", "日期", "role", "DIMENSION", "date", false))));
        assertThat(changed.get("code")).isEqualTo("ok");
        assertThat(call(HttpMethod.GET, "/screens/" + screen + "/published/widgets/chart/query", null)
                        .get("code"))
                .isEqualTo("dataset.fieldMissing");
        assertThat(call(HttpMethod.POST, "/screens/" + screen + "/draft/query", widget)
                        .get("code"))
                .isEqualTo("dataset.fieldMissing");
    }

    @Test
    void referenceNamesRespectViewOnlyPublishedNamesAndNoScreenPermission() throws Exception {
        String dataset = dataset(source());
        String screen = screen(dataset, "公开发布名称");
        call(HttpMethod.POST, "/screens/" + screen + "/publish", null);
        call(HttpMethod.PUT, "/screens/" + screen, Map.of("name", "新草稿机密名称", "draft", draft(dataset)));
        String unpublished = screen(dataset, "未发布机密名称");
        UUID reader = UUID.randomUUID();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, reader, List.of("dataset:manage", "screen:view"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, reader);
        var visible = call(HttpMethod.GET, "/datasets/" + dataset + "/references", null);
        assertThat(referenceList(dataset)).hasSize(1);
        assertThat(visible.toString())
                .contains(screen, "公开发布名称", "hiddenReferences=true")
                .doesNotContain("新草稿机密名称", "未发布机密名称", unpublished);
        UUID manager = UUID.randomUUID();
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, manager, List.of("dataset:manage"));
        token = TestTokens.forMembership(TestTokens.TENANT_ID, manager);
        var hidden = call(HttpMethod.GET, "/datasets/" + dataset + "/references", null);
        assertThat(referenceList(dataset)).isEmpty();
        assertThat(hidden.toString())
                .contains("hiddenReferences=true")
                .doesNotContain(screen, unpublished, "公开发布名称", "新草稿机密名称");
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("dataset.referenced");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceDeletedDuringRemotePreviewCannotReceiveNewDatasetReference(boolean updating) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rows", exchange -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
                byte[] body = "[{\"region\":\"east\",\"amount\":12}]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String source = id(call(
                    HttpMethod.POST,
                    "/data-sources",
                    Map.of(
                            "name",
                            "预览并发源",
                            "type",
                            "API",
                            "api",
                            Map.of(
                                    "url",
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/rows",
                                    "method",
                                    "GET",
                                    "authType",
                                    "NONE"))));
            String previousSource = updating ? source() : null;
            String previousDataset = updating ? dataset(previousSource) : null;
            var saving = CompletableFuture.supplyAsync(() -> call(
                    updating ? HttpMethod.PUT : HttpMethod.POST,
                    updating ? "/datasets/" + previousDataset : "/datasets",
                    Map.of("name", "预览并发数据集", "dataSourceId", source, "jsonPath", "$")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call(HttpMethod.DELETE, "/data-sources/" + source, null).get("code"))
                    .isEqualTo("ok");
            release.countDown();
            var saved = saving.get(5, TimeUnit.SECONDS);
            assertThat(saved.get("code")).isEqualTo("datasource.notFound");
            assertThat(saved.get("httpStatus")).isEqualTo(404);
            if (updating) {
                assertThat(((Map<?, ?>) call(HttpMethod.GET, "/datasets/" + previousDataset, null)
                                        .get("data"))
                                .get("dataSourceId"))
                        .isEqualTo(previousSource);
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void datasetDeletionWaitsForConcurrentDraftReferenceAndThenRefusesDeletion() throws Exception {
        String dataset = dataset(source());
        String screen = id(call(HttpMethod.POST, "/screens", Map.of("name", "并发草稿", "draft", emptyDraft())));
        var saving = new AtomicReference<CompletableFuture<Map<?, ?>>>();
        var deleting = new AtomicReference<CompletableFuture<Map<?, ?>>>();
        var probe = new TransactionTemplate(transactions);
        probe.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 测试数据库只安排并发时序；是否保留资源与引用保护结果全部通过 HTTP 验证。
        try (var ignored = CurrentTenant.bind(TestTokens.TENANT_ID)) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                concurrency.lockScreen(UUID.fromString(screen));
                saving.set(CompletableFuture.supplyAsync(
                        () -> call(HttpMethod.PUT, "/screens/" + screen + "/draft", draft(dataset))));
                await(() -> probe.execute(probeStatus -> concurrency.waitingScreenWrites()) > 0);
                deleting.set(
                        CompletableFuture.supplyAsync(() -> call(HttpMethod.DELETE, "/datasets/" + dataset, null)));
                await(() ->
                        deleting.get().isDone() || probe.execute(probeStatus -> concurrency.waitingDatasetLocks()) > 0);
            });
        }
        assertThat(saving.get().get(5, TimeUnit.SECONDS).get("code")).isEqualTo("ok");
        var deleted = deleting.get().get(5, TimeUnit.SECONDS);
        assertThat(deleted.get("code")).isEqualTo("dataset.referenced");
        assertThat(deleted.get("httpStatus")).isEqualTo(409);
        assertThat(call(HttpMethod.GET, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("ok");
        assertThat(referenceList(dataset)).hasSize(1);
    }

    void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("并发调度未在五秒内到达数据库等待点");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void deletedDatasetCannotBeReintroducedByCreateOrEitherDraftSaveEndpoint() {
        String dataset = dataset(source());
        String empty = id(call(HttpMethod.POST, "/screens", Map.of("name", "保留原稿", "draft", emptyDraft())));
        assertThat(call(HttpMethod.DELETE, "/datasets/" + dataset, null).get("code"))
                .isEqualTo("ok");
        assertThat(call(HttpMethod.POST, "/screens", Map.of("name", "新大屏", "draft", draft(dataset)))
                        .get("code"))
                .isEqualTo("dataset.notFound");
        assertThat(call(HttpMethod.PUT, "/screens/" + empty, Map.of("name", "修改", "draft", draft(dataset)))
                        .get("code"))
                .isEqualTo("dataset.notFound");
        assertThat(call(HttpMethod.PUT, "/screens/" + empty + "/draft", draft(dataset))
                        .get("code"))
                .isEqualTo("dataset.notFound");
        assertThat(call(HttpMethod.GET, "/screens/" + empty + "/draft", null).toString())
                .contains("widgets=[]");
    }

    List<?> referenceList(String dataset) {
        return (List<?>) ((Map<?, ?>) call(HttpMethod.GET, "/datasets/" + dataset + "/references", null)
                        .get("data"))
                .get("references");
    }

    Map<String, Object> emptyDraft() {
        return Map.of("designWidth", 1920, "designHeight", 1080, "backgroundColor", "#101827", "widgets", List.of());
    }

    String screen(String dataset, String name) {
        var response = call(HttpMethod.POST, "/screens", Map.of("name", name, "draft", draft(dataset)));
        assertThat(response.get("httpStatus")).isEqualTo(201);
        return id(response);
    }

    Map<String, Object> draft(String dataset) {
        return Map.of(
                "designWidth",
                1920,
                "designHeight",
                1080,
                "backgroundColor",
                "#101827",
                "widgets",
                List.of(Map.of(
                        "id",
                        "chart",
                        "type",
                        "LINE",
                        "x",
                        0,
                        "y",
                        0,
                        "width",
                        800,
                        "height",
                        600,
                        "zIndex",
                        0,
                        "config",
                        Map.of(
                                "datasetId",
                                dataset,
                                "query",
                                Map.of(
                                        "dimensions",
                                        List.of(Map.of("field", "region")),
                                        "measures",
                                        List.of(Map.of("field", "amount", "aggregation", "SUM")),
                                        "filters",
                                        List.of(),
                                        "sorts",
                                        List.of()),
                                "refreshSeconds",
                                0))));
    }

    String source() {
        var response = call(
                HttpMethod.POST,
                "/data-sources",
                Map.of(
                        "name",
                        "经营分析源",
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
        return id(response);
    }

    String dataset(String source) {
        var response = call(
                HttpMethod.POST,
                "/datasets",
                Map.of("name", "区域销售数据集", "dataSourceId", source, "sql", "SELECT '华东' AS region, 12 AS amount"));
        assertThat(response.get("httpStatus")).isEqualTo(201);
        return id(response);
    }

    String id(Map<?, ?> response) {
        return (String) ((Map<?, ?>) response.get("data")).get("id");
    }

    Map<?, ?> call(HttpMethod method, String path, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, response) -> response.bodyTo(Map.class));
    }
}
