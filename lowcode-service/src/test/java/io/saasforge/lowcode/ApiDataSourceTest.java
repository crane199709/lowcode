package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.saasforge.lowcode.datasource.mapper.DataSourceSecretMapper;
import io.saasforge.lowcode.outbox.mapper.AuditOutboxMapper;
import io.saasforge.sdk.core.CurrentTenant;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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

/**
 * API 类 Data Source 的登记、认证与测试连接。用本地 mock HTTP 服务验证请求真的发出、带上了配置的认证头、
 * 重定向不被跟随、超时与响应体上限生效；禁止网段与数据库类共用同一套检查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDataSourceTest {
    private static final UUID MANAGER = TestTokens.MEMBERSHIP_ID;
    private static final String HEADER_SECRET = "header-secret-019a";
    private static final String BASIC_PASSWORD = "basic-secret-019a";
    private static final Map<String, String> HEADERS = new ConcurrentHashMap<>();
    private static final AtomicReference<String> POST_BODY = new AtomicReference<>();
    private static final AtomicInteger TARGET_HITS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();
    private static final int SERVER_PORT = SERVER.getAddress().getPort();

    private static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    private static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("lowcode.datasource.blocked-networks", () -> "169.254.0.0/16,10.0.0.0/8,fd00::/8");
        // 让超时与超限用例跑得快，同时不影响正常请求
        registry.add("lowcode.datasource.api.timeout", () -> "500ms");
        registry.add("lowcode.datasource.api.max-response-bytes", () -> "1024");
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    AuditOutboxMapper auditOutboxMapper;

    @Autowired
    DataSourceSecretMapper dataSourceSecretMapper;

    @BeforeEach
    void setUp() {
        TestGrants.publish(
                redisTemplate, TestTokens.TENANT_ID, MANAGER, List.of("datasource:view", "datasource:manage"));
        HEADERS.clear();
        POST_BODY.set(null);
        TARGET_HITS.set(0);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void registeredApiDataSourceNeverEchoesSecrets() throws Exception {
        var body = request("行情接口");
        body.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", HEADER_SECRET));

        Map<?, ?> created = exchange(HttpMethod.POST, "/data-sources", body);
        assertThat(created.get("httpStatus")).isEqualTo(201);
        Map<String, Object> data = data(created);
        assertThat(data)
                .containsEntry("name", "行情接口")
                .containsEntry("type", "API")
                .doesNotContainKey("password");
        assertThat(data.get("host")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> api = (Map<String, Object>) data.get("api");
        assertThat(api)
                .containsEntry("url", url("/ok"))
                .containsEntry("method", "GET")
                .containsEntry("authType", "HEADER")
                .containsEntry("headerName", "X-Api-Key")
                .doesNotContainKey("headerValue");

        String id = (String) data.get("id");
        assertThat(exchange(HttpMethod.GET, "/data-sources/" + id, null).toString())
                .doesNotContain(HEADER_SECRET)
                .doesNotContain("headerValue");
        assertThat(exchange(HttpMethod.GET, "/data-sources", null).toString())
                .doesNotContain(HEADER_SECRET)
                .doesNotContain("headerValue");
    }

    @Test
    void connectionTestSendsConfiguredAuthenticationAndMethod() throws Exception {
        var none = request("无认证");
        Map<?, ?> noneResult = exchange(HttpMethod.POST, "/data-sources/connection-test", none);
        assertThat(noneResult.get("code")).isEqualTo("ok");
        assertThat(data(noneResult)).containsEntry("status", 200);
        assertThat(HEADERS).isEmpty();

        HEADERS.clear();
        var header = request("请求头认证");
        header.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", HEADER_SECRET));
        assertThat(exchange(HttpMethod.POST, "/data-sources/connection-test", header)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(HEADERS).containsEntry("X-Api-Key", HEADER_SECRET).doesNotContainKey("Authorization");

        HEADERS.clear();
        var basic = request("Basic 认证");
        basic.put("api", api("/ok", "GET", "", "BASIC", "", ""));
        apiOf(basic).put("username", "api-user");
        apiOf(basic).put("password", BASIC_PASSWORD);
        assertThat(exchange(HttpMethod.POST, "/data-sources/connection-test", basic)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(HEADERS)
                .containsEntry(
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                ("api-user:" + BASIC_PASSWORD).getBytes(StandardCharsets.UTF_8)));

        var post = request("POST 请求");
        post.put("api", api("/echo", "POST", "{\"query\":\"x\"}", "NONE", "", ""));
        assertThat(exchange(HttpMethod.POST, "/data-sources/connection-test", post)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(POST_BODY.get()).isEqualTo("{\"query\":\"x\"}");
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        var body = request("重定向");
        body.put("api", api("/redirect", "GET", "", "NONE", "", ""));

        Map<?, ?> result = exchange(HttpMethod.POST, "/data-sources/connection-test", body);
        assertThat(result.get("httpStatus")).isEqualTo(422);
        assertThat(result.get("code")).isEqualTo("datasource.apiRedirect");
        assertThat(result.get("userTip")).isEqualTo("接口返回重定向；数据源不允许跟随重定向");
        assertThat(TARGET_HITS.get()).isZero();
    }

    @Test
    void timeoutAndOversizedResponseAreReadable() throws Exception {
        var slow = request("超时");
        slow.put("api", api("/slow", "GET", "", "NONE", "", ""));
        long started = System.nanoTime();
        Map<?, ?> timeout = exchange(HttpMethod.POST, "/data-sources/connection-test", slow);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        // 超时与端口不可达合并为同一个原因；耗时证明请求在超时后返回，而不是等到了 mock 服务的响应
        assertThat(timeout.get("code")).isEqualTo("datasource.apiUnreachable");
        assertThat(timeout.get("userTip")).isEqualTo("无法连接到接口，请检查地址与网络");
        assertThat(elapsedMillis).isLessThan(1500);

        var large = request("响应过大");
        large.put("api", api("/large", "GET", "", "NONE", "", ""));
        Map<?, ?> oversized = exchange(HttpMethod.POST, "/data-sources/connection-test", large);
        assertThat(oversized.get("code")).isEqualTo("datasource.apiResponseTooLarge");
        assertThat(oversized.get("userTip")).isEqualTo("接口响应体超过 1024 字节上限");

        // 无 Content-Length 时由流式读取计数拦截
        var chunked = request("分块响应过大");
        chunked.put("api", api("/large-chunked", "GET", "", "NONE", "", ""));
        Map<?, ?> chunkedOversized = exchange(HttpMethod.POST, "/data-sources/connection-test", chunked);
        assertThat(chunkedOversized.get("code")).isEqualTo("datasource.apiResponseTooLarge");
    }

    @Test
    void urlIsTrimmedBeforeRequestAndSave() throws Exception {
        var body = request("空格地址");
        var api = api("/ok", "GET", "", "NONE", "", "");
        api.put("url", "  " + url("/ok") + "  ");
        body.put("api", api);

        assertThat(exchange(HttpMethod.POST, "/data-sources/connection-test", body)
                        .get("code"))
                .isEqualTo("ok");

        Map<?, ?> created = exchange(HttpMethod.POST, "/data-sources", body);
        assertThat(created.get("httpStatus")).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> createdApi = (Map<String, Object>) data(created).get("api");
        assertThat(createdApi).containsEntry("url", url("/ok"));
    }

    @Test
    void httpAndNetworkFailuresAreReadable() throws Exception {
        var unauthorized = request("认证失败");
        unauthorized.put("api", api("/unauthorized", "GET", "", "NONE", "", ""));
        Map<?, ?> auth = exchange(HttpMethod.POST, "/data-sources/connection-test", unauthorized);
        assertThat(auth.get("code")).isEqualTo("datasource.apiAuthFailed");
        assertThat(auth.get("userTip")).isEqualTo("接口认证失败，请检查认证方式与凭据");

        var serverError = request("服务端错误");
        serverError.put("api", api("/server-error", "GET", "", "NONE", "", ""));
        Map<?, ?> failed = exchange(HttpMethod.POST, "/data-sources/connection-test", serverError);
        assertThat(failed.get("code")).isEqualTo("datasource.apiHttpError");
        assertThat(failed.get("userTip")).isEqualTo("接口返回 HTTP 500，请检查接口地址与参数");

        var notFound = request("不存在");
        notFound.put("api", api("/not-found", "GET", "", "NONE", "", ""));
        Map<?, ?> missing = exchange(HttpMethod.POST, "/data-sources/connection-test", notFound);
        assertThat(missing.get("code")).isEqualTo("datasource.apiHttpError");
        assertThat(missing.get("userTip")).isEqualTo("接口返回 HTTP 404，请检查接口地址与参数");

        var unreachable = request("不可达");
        var unreachableApi = api("/ok", "GET", "", "NONE", "", "");
        unreachableApi.put("url", "http://127.0.0.1:1/ok");
        unreachable.put("api", unreachableApi);
        Map<?, ?> network = exchange(HttpMethod.POST, "/data-sources/connection-test", unreachable);
        assertThat(network.get("code")).isEqualTo("datasource.apiUnreachable");
        assertThat(network.get("userTip")).isEqualTo("无法连接到接口，请检查地址与网络");
    }

    @Test
    void missingApiFieldsAreReportedAtTheRequestBoundary() throws Exception {
        assertField("api.url", withApi(request("缺地址"), apiWithUrl("")));
        assertField("api.method", withApi(request("缺方法"), api("/ok", null, "", "NONE", "", "")));
        var noAuthType = api("/ok", "GET", "", "NONE", "", "");
        noAuthType.put("authType", null);
        assertField("api.authType", withApi(request("缺认证方式"), noAuthType));
    }

    @Test
    void blockedHostsAreRejectedForSaveAndConnectionTest() throws Exception {
        String id = (String)
                data(exchange(HttpMethod.POST, "/data-sources", request("待修改"))).get("id");
        for (String host : List.of("169.254.169.254", "10.1.2.3", "[fd00::1]", "0.0.0.0")) {
            var body = request("内网");
            body.put("api", apiWithUrl("http://" + host + "/latest/meta-data"));
            for (var call : List.of(
                    exchange(HttpMethod.POST, "/data-sources", body),
                    exchange(HttpMethod.PUT, "/data-sources/" + id, body),
                    exchange(HttpMethod.POST, "/data-sources/connection-test", body),
                    exchange(HttpMethod.POST, "/data-sources/" + id + "/connection-test", body))) {
                assertThat(call.get("httpStatus")).as(host).isEqualTo(400);
                assertThat(call.get("code")).as(host).isEqualTo("datasource.hostBlocked");
            }
        }
        assertThat(((Map<?, ?>) data(exchange(HttpMethod.GET, "/data-sources/" + id, null))
                                .get("api"))
                        .get("url"))
                .isEqualTo(url("/ok"));
    }

    @Test
    void invalidApiConfigurationIsRejected() throws Exception {
        assertRejected(
                "接口地址协议", withApi(request("协议"), apiWithUrl("ftp://example.com/data")), "datasource.apiUrlInvalid");
        assertRejected(
                "带有用户信息",
                withApi(request("用户信息"), apiWithUrl(url("/ok").replace("http://", "http://user:pass@"))),
                "datasource.apiUrlInvalid");
        assertRejected("带片段", withApi(request("片段"), apiWithUrl(url("/ok") + "#section")), "datasource.apiUrlInvalid");
        assertRejected("缺地址", withApi(request("缺地址"), apiWithUrl("")), "request.invalid");
        assertRejected(
                "端口为零", withApi(request("零端口"), apiWithUrl("http://127.0.0.1:0/ok")), "datasource.apiUrlInvalid");
        assertRejected(
                "请求体不是 JSON",
                withApi(request("错误请求体"), api("/echo", "POST", "{", "NONE", "", "")),
                "datasource.apiBodyInvalid");
        assertRejected(
                "GET 带请求体",
                withApi(request("GET 请求体"), api("/echo", "GET", "{}", "NONE", "", "")),
                "datasource.apiBodyNotAllowed");
        assertRejected(
                "请求头缺值",
                withApi(request("缺请求头值"), api("/ok", "GET", "", "HEADER", "X-Api-Key", "")),
                "datasource.apiAuthRequired");
        assertRejected(
                "Basic 缺密码",
                withApi(request("缺密码"), api("/ok", "GET", "", "BASIC", "", "")),
                "datasource.apiAuthRequired");
        assertRejected(
                "保留请求头",
                withApi(request("Host"), api("/ok", "GET", "", "HEADER", "Host", "evil")),
                "datasource.apiHeaderNotAllowed");
        assertRejected("缺方法", withApi(request("缺方法"), api("/ok", null, "", "NONE", "", "")), "request.invalid");
    }

    @Test
    void savedSecretIsReusedOnlyForTheSameOrigin() throws Exception {
        var header = request("请求头复用");
        header.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", HEADER_SECRET));
        String headerId = (String)
                data(exchange(HttpMethod.POST, "/data-sources", header)).get("id");

        var sameHeader = request("请求头复用");
        sameHeader.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", ""));
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + headerId, sameHeader)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(exchange(HttpMethod.POST, "/data-sources/" + headerId + "/connection-test", sameHeader)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(HEADERS).containsEntry("X-Api-Key", HEADER_SECRET);

        var changedPort = request("请求头复用");
        changedPort.put(
                "api",
                apiWithUrl("http://127.0.0.1:" + (SERVER_PORT + 1) + "/ok").withAuth("HEADER", "X-Api-Key", ""));
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + headerId, changedPort)
                        .get("code"))
                .isEqualTo("datasource.apiAuthRequired");

        var changedHeader = request("请求头复用");
        changedHeader.put("api", api("/ok", "GET", "", "HEADER", "X-Other", ""));
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + headerId, changedHeader)
                        .get("code"))
                .isEqualTo("datasource.apiAuthRequired");

        var basic = request("Basic 复用");
        basic.put("api", api("/ok", "GET", "", "BASIC", "", ""));
        apiOf(basic).put("username", "api-user");
        apiOf(basic).put("password", BASIC_PASSWORD);
        String basicId =
                (String) data(exchange(HttpMethod.POST, "/data-sources", basic)).get("id");

        var sameBasic = request("Basic 复用");
        sameBasic.put("api", api("/ok", "GET", "", "BASIC", "", ""));
        apiOf(sameBasic).put("username", "api-user");
        apiOf(sameBasic).put("password", "");
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + basicId, sameBasic)
                        .get("code"))
                .isEqualTo("ok");
        assertThat(exchange(HttpMethod.POST, "/data-sources/" + basicId + "/connection-test", sameBasic)
                        .get("code"))
                .isEqualTo("ok");

        var changedUser = request("Basic 复用");
        changedUser.put("api", api("/ok", "GET", "", "BASIC", "", ""));
        apiOf(changedUser).put("username", "another-user");
        apiOf(changedUser).put("password", "");
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + basicId, changedUser)
                        .get("code"))
                .isEqualTo("datasource.apiAuthRequired");

        var changedScheme = request("Basic 复用");
        changedScheme.put(
                "api", apiWithUrl(url("/ok").replace("http://", "https://")).withAuth("BASIC", "", ""));
        apiOf(changedScheme).put("username", "api-user");
        apiOf(changedScheme).put("password", "");
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + basicId, changedScheme)
                        .get("code"))
                .isEqualTo("datasource.apiAuthRequired");

        var none = request("无认证");
        String noneId =
                (String) data(exchange(HttpMethod.POST, "/data-sources", none)).get("id");
        var toHeader = request("无认证");
        toHeader.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", ""));
        assertThat(exchange(HttpMethod.PUT, "/data-sources/" + noneId, toHeader).get("code"))
                .isEqualTo("datasource.apiAuthRequired");
    }

    @Test
    void secretsAreEncryptedAtRest() throws Exception {
        var header = request("密文请求头");
        header.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", HEADER_SECRET));
        String headerId = (String)
                data(exchange(HttpMethod.POST, "/data-sources", header)).get("id");

        Map<String, Object> headerSecrets = readSecrets(headerId);
        assertThat(secret(headerSecrets, "api_header_value_encrypted"))
                .isNotBlank()
                .isNotEqualTo(HEADER_SECRET)
                .doesNotContain(HEADER_SECRET);

        var basic = request("密文密码");
        basic.put("api", api("/ok", "GET", "", "BASIC", "", ""));
        apiOf(basic).put("username", "api-user");
        apiOf(basic).put("password", BASIC_PASSWORD);
        String basicId =
                (String) data(exchange(HttpMethod.POST, "/data-sources", basic)).get("id");

        Map<String, Object> basicSecrets = readSecrets(basicId);
        assertThat(secret(basicSecrets, "password_encrypted"))
                .isNotBlank()
                .isNotEqualTo(BASIC_PASSWORD)
                .doesNotContain(BASIC_PASSWORD);
    }

    @Test
    void auditEventsDoNotContainApiSecrets() throws Exception {
        var body = request("审计 API");
        body.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", HEADER_SECRET));
        String id =
                (String) data(exchange(HttpMethod.POST, "/data-sources", body)).get("id");

        var updated = request("审计 API");
        updated.put("api", api("/ok", "GET", "", "HEADER", "X-Api-Key", "second-header-secret"));
        exchange(HttpMethod.PUT, "/data-sources/" + id, updated);
        exchange(HttpMethod.DELETE, "/data-sources/" + id, null);

        String events = auditOutboxMapper.selectAuditPayloads().stream()
                .filter(payload -> payload.contains(id))
                .toList()
                .toString();
        assertThat(events).contains("datasource.create").contains("datasource.update");
        assertThat(events).doesNotContain(HEADER_SECRET).doesNotContain("second-header-secret");
    }

    private void assertRejected(String label, Map<String, Object> body, String code) throws Exception {
        Map<?, ?> result = exchange(HttpMethod.POST, "/data-sources", body);
        assertThat(result.get("httpStatus")).as(label).isEqualTo(400);
        assertThat(result.get("code")).as(label).isEqualTo(code);
    }

    private void assertField(String field, Map<String, Object> body) throws Exception {
        Map<?, ?> result = exchange(HttpMethod.POST, "/data-sources", body);
        assertThat(result.get("code")).isEqualTo("request.invalid");
        assertThat((List<?>) result.get("errors"))
                .<Object>extracting(error -> ((Map<?, ?>) error).get("field"))
                .contains(field);
    }

    private Map<String, Object> readSecrets(String id) {
        var holder = new AtomicReference<Map<String, Object>>();
        CurrentTenant.run(
                TestTokens.TENANT_ID, () -> holder.set(dataSourceSecretMapper.selectSecrets(UUID.fromString(id))));
        Map<String, Object> secrets = holder.get();
        assertThat(secrets).isNotNull();
        return secrets;
    }

    private static String secret(Map<String, Object> secrets, String column) {
        return secrets.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(column))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> request(String name) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("type", "API");
        body.put("api", api("/ok", "GET", "", "NONE", "", ""));
        return body;
    }

    private Map<String, Object> withApi(Map<String, Object> body, Map<String, Object> api) {
        body.put("api", api);
        return body;
    }

    private Map<String, Object> api(
            String path, String method, String body, String authType, String headerName, String headerValue) {
        return apiWithUrl(url(path)).withMethod(method, body).withAuth(authType, headerName, headerValue);
    }

    private ApiBody apiWithUrl(String url) {
        var api = new HashMap<String, Object>();
        api.put("url", url);
        api.put("method", "GET");
        api.put("body", "");
        api.put("authType", "NONE");
        api.put("headerName", "");
        api.put("headerValue", "");
        return new ApiBody(api);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + SERVER_PORT + path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("api");
    }

    private Map<String, Object> data(Map<?, ?> response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return data;
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body) throws Exception {
        return exchange(method, uri, body, TestTokens.forTenant(TestTokens.TENANT_ID));
    }

    private Map<?, ?> exchange(HttpMethod method, String uri, Object body, String token) {
        var spec = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.exchange((request, response) -> response.bodyTo(Map.class));
    }

    private static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/ok", exchange -> {
                capture(exchange);
                respond(exchange, 200, "{\"items\":[{\"id\":1}]}");
            });
            server.createContext("/echo", exchange -> {
                capture(exchange);
                POST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"ok\":true}");
            });
            server.createContext("/target", exchange -> {
                TARGET_HITS.incrementAndGet();
                respond(exchange, 200, "{}");
            });
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/slow", exchange -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{}");
            });
            server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(4096)));
            server.createContext("/large-chunked", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var stream = exchange.getResponseBody()) {
                    stream.write("x".repeat(4096).getBytes(StandardCharsets.UTF_8));
                }
            });
            server.createContext("/unauthorized", exchange -> respond(exchange, 401, "{\"error\":\"unauthorized\"}"));
            server.createContext("/server-error", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
            server.createContext("/not-found", exchange -> respond(exchange, 404, "{\"error\":\"missing\"}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void capture(HttpExchange exchange) {
        String apiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
        if (apiKey != null) {
            HEADERS.put("X-Api-Key", apiKey);
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            HEADERS.put("Authorization", authorization);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    /** 让测试里构造 API 请求的链式写法保持可读。 */
    private static final class ApiBody extends HashMap<String, Object> {
        private ApiBody(Map<String, Object> api) {
            super(api);
        }

        private ApiBody withMethod(String method, String body) {
            put("method", method);
            put("body", body);
            return this;
        }

        private ApiBody withAuth(String authType, String headerName, String headerValue) {
            put("authType", authType);
            put("headerName", headerName);
            put("headerValue", headerValue);
            return this;
        }
    }
}
