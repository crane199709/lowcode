package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** 模拟不可信数据库的协议重定向，经公开 API 验证驱动不会连接禁止地址。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSourceRedirectTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("lowcode.datasource.blocked-networks", () -> "::1/128");
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @ParameterizedTest
    @CsvSource({"SQLSERVER, ::1", "ORACLE, ::1", "SQLSERVER, 127.0.0.1", "ORACLE, 127.0.0.1"})
    void serverCannotRedirectTheDriverToAnotherEndpoint(String type, String targetHost) throws Exception {
        TestGrants.publish(redisTemplate, TestTokens.TENANT_ID, TestTokens.MEMBERSHIP_ID, List.of("datasource:manage"));
        try (var target = new ServerSocket(0, 1, InetAddress.getByName(targetHost));
                var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            var redirect = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    if (type.equals("SQLSERVER")) {
                        redirectSqlServer(socket, targetHost, target.getLocalPort());
                    } else {
                        redirectOracle(socket, targetHost, target.getLocalPort());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Map<?, ?> result = RestClient.create("http://localhost:" + port)
                    .post()
                    .uri("/data-sources/connection-test")
                    .header("Authorization", "Bearer " + TestTokens.forTenant(TestTokens.TENANT_ID))
                    .body(Map.of(
                            "name",
                            "不可信数据库",
                            "type",
                            type,
                            "database",
                            Map.of(
                                    "host", "127.0.0.1",
                                    "port", server.getLocalPort(),
                                    "database", "test",
                                    "username", "reader",
                                    "password", "test-password",
                                    "params", type.equals("SQLSERVER") ? Map.of("encrypt", "false") : Map.of())))
                    .exchange((request, response) -> response.bodyTo(Map.class));
            redirect.get(15, TimeUnit.SECONDS);
            target.setSoTimeout(200);
            assertThatThrownBy(() -> {
                        try (Socket ignored = target.accept()) {
                            // 若驱动已经连接，accept 会立即返回；禁止目标不应收到连接，即使没有 SQL 被执行。
                        }
                    })
                    .isInstanceOf(SocketTimeoutException.class);
            assertThat(result.get("code")).isEqualTo("datasource.unreachable");
        }
    }

    private static void redirectSqlServer(Socket socket, String targetHost, int targetPort) throws IOException {
        readPacket(socket.getInputStream(), false);
        // TDS PRELOGIN：ENCRYPTION=NOT_SUP，令测试无需实现 TLS。
        writeTds(socket, HexFormat.of().parseHex("00000b00060100110001ff0f000000000002"));
        readPacket(socket.getInputStream(), false);
        byte[] host = targetHost.getBytes(StandardCharsets.UTF_16LE);
        byte[] loginAck = HexFormat.of().parseHex("ad0a000174000004000f000000aeff");
        var response = ByteBuffer.allocate(loginAck.length + 1 + 2 + 1 + 2 + 5 + host.length + 2 + 13)
                .order(ByteOrder.LITTLE_ENDIAN);
        response.put(loginAck);
        // ENVCHANGE(0xe3), ROUTING(20), 新地址；旧值长度为零。DONE 结束登录响应。
        response.put((byte) 0xe3)
                .putShort((short) (1 + 2 + 5 + host.length + 2))
                .put((byte) 20);
        response.putShort((short) (5 + host.length)).put((byte) 0).putShort((short) targetPort);
        response.putShort((short) targetHost.length()).put(host).putShort((short) 0);
        response.put((byte) 0xfd).putShort((short) 0).putShort((short) 0).putLong(0);
        writeTds(socket, response.array());
    }

    private static void writeTds(Socket socket, byte[] body) throws IOException {
        var packet = ByteBuffer.allocate(body.length + 8);
        packet.put((byte) 4).put((byte) 1).putShort((short) packet.capacity()).putShort((short) 0);
        packet.put((byte) 1).put((byte) 0).put(body);
        socket.getOutputStream().write(packet.array());
        socket.getOutputStream().flush();
    }

    private static void redirectOracle(Socket socket, String targetHost, int targetPort) throws IOException {
        readPacket(socket.getInputStream(), true);
        byte[] address = ("(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=" + targetHost + ")(PORT=" + targetPort
                        + "))(CONNECT_DATA=(SERVICE_NAME=test)))")
                .getBytes(StandardCharsets.US_ASCII);
        var packet = ByteBuffer.allocate(address.length + 10);
        // TNS REDIRECT(type=5)：8 字节头部，2 字节描述符长度，随后是新连接描述符。
        packet.putShort((short) packet.capacity())
                .putShort((short) 0)
                .put((byte) 5)
                .put((byte) 0);
        packet.putShort((short) 0).putShort((short) address.length).put(address);
        socket.getOutputStream().write(packet.array());
        socket.getOutputStream().flush();
    }

    private static void readPacket(InputStream input, boolean oracle) throws IOException {
        byte[] header = input.readNBytes(8);
        if (header.length != 8) {
            throw new IOException("Missing protocol header");
        }
        int length = Short.toUnsignedInt(ByteBuffer.wrap(header).getShort(oracle ? 0 : 2));
        if (input.readNBytes(length - 8).length != length - 8) {
            throw new IOException("Incomplete protocol packet");
        }
    }
}
