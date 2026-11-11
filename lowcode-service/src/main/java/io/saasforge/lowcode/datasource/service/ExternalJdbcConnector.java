package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.ConnectionSettings;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 以原生 JDBC 连接 Tenant 自有的外部数据库。
 *
 * <p>平台架构规则禁止业务代码直接使用 JDBC，因为服务自己的库必须经 MyBatis-Plus 拦截器注入租户隔离；外部数据库不属于平台、
 * 没有租户列，只能直连。ArchitectureTest 经 {@code externalJdbcClasses()} 只对本类放开 JDBC 依赖检查，外部连接都应收在这里
 * （规范见 {@code docs/standards/business-service.md}「禁止」一节的例外）。
 */
@Component
class ExternalJdbcConnector {
    private static final Logger log = LoggerFactory.getLogger(ExternalJdbcConnector.class);
    // 秒；测试连接是交互操作，不能让请求线程长时间挂起
    private static final String CONNECT_TIMEOUT = "5";
    private static final String LOGIN_TIMEOUT = "10";
    private static final String SOCKET_TIMEOUT = "10";

    /**
     * 连接到已通过禁止网段检查的地址，成功即关闭。
     *
     * @throws ForgeException 连接失败，错误码只区分账号密码错误、数据库不存在与其他，不暴露网络细节
     */
    void testPostgres(InetAddress address, ConnectionSettings settings) {
        String host = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        // 库名经 URL 编码，防止以 ? / 等字符在 URL 中注入参数；其余参数只经 Properties 传入
        String url = "jdbc:postgresql://" + host + ":" + settings.port() + "/"
                + URLEncoder.encode(settings.database(), StandardCharsets.UTF_8);
        var properties = new Properties();
        properties.putAll(settings.params());
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("connectTimeout", CONNECT_TIMEOUT);
        properties.setProperty("loginTimeout", LOGIN_TIMEOUT);
        properties.setProperty("socketTimeout", SOCKET_TIMEOUT);
        // 不尝试 Kerberos：服务所在环境的凭据不能被用来连接租户的库
        properties.setProperty("gssEncMode", "disable");
        // 默认的 LibPQFactory 会把服务所在机器 ~/.postgresql 下的客户端证书发给对方；白名单不含 verify-*，无需校验服务端证书
        properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        try (Connection ignored = DriverManager.getConnection(url, properties)) {
            // 能建立连接即成功
        } catch (SQLException e) {
            throw translate(e, address);
        }
    }

    /**
     * 4xx 的 errorMessage 会原样返回给调用方，因此只写固定文案；地址与 SQLState 只进日志，否则可凭它们区分端口拒绝、
     * 超时与非数据库端口，用来探测网络。
     */
    private static ForgeException translate(SQLException e, InetAddress address) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        log.info("External connection to {} failed with SQLState {}: {}", address.getHostAddress(), state, e.getMessage());
        if (state.startsWith("28")) {
            return ForgeException.withErrorMessage(DataSourceErrorCode.AUTH_FAILED, "Authentication failed");
        }
        if (state.equals("3D000")) {
            return ForgeException.withErrorMessage(DataSourceErrorCode.DATABASE_NOT_FOUND, "Database not found");
        }
        if (state.startsWith("08")) {
            return DataSourceService.unreachable();
        }
        return ForgeException.withErrorMessage(DataSourceErrorCode.CONNECTION_FAILED, "Connection failed");
    }
}
