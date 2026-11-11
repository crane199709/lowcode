package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.model.ColumnResponse;
import io.saasforge.lowcode.datasource.model.ConnectionSettings;
import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.model.TableResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import oracle.jdbc.pool.OracleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 以原生 JDBC 连接 Tenant 自有的外部数据库。
 *
 * <p>平台架构规则禁止业务代码直接使用 JDBC，因为服务自己的库必须经 MyBatis-Plus 拦截器注入租户隔离；外部数据库不属于平台、
 * 没有租户列，只能直连。ArchitectureTest 经 {@code externalJdbcClasses()} 只对本类放开 JDBC 依赖检查，外部连接都应收在这里
 * （规范见 {@code docs/standards/business-service.md}「禁止」一节的例外）。
 *
 * <p>URL 只含协议、检查过的 IP、端口与编码后的库名，租户参数（已按白名单校验）只经 {@link Properties} 传入；各类型另外固定超时，
 * 并显式关闭可读取服务所在机器文件或借用服务凭据的驱动功能，不依赖驱动默认值（ADR-0002）。
 */
@Component
class ExternalJdbcConnector {
    private static final Logger log = LoggerFactory.getLogger(ExternalJdbcConnector.class);
    // 测试连接是交互操作，不能让请求线程长时间挂起
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 10;

    /**
     * 连接到已通过禁止网段检查的地址，成功即关闭。
     *
     * @throws ForgeException 连接失败，错误码只区分账号密码错误、数据库不存在与其他，不暴露网络细节
     */
    void testConnection(DataSourceType type, InetAddress address, ConnectionSettings settings) {
        withConnection(type, address, settings, connection -> null);
    }

    List<TableResponse> tables(DataSourceType type, InetAddress address, ConnectionSettings settings) {
        return withConnection(type, address, settings, connection -> readTables(connection, type));
    }

    List<ColumnResponse> columns(
            DataSourceType type, InetAddress address, ConnectionSettings settings, TableResponse table) {
        return withConnection(type, address, settings, connection -> {
            // 列元数据未必自行过滤权限；先核验表标识来自同一账号可见的表集合。
            if (!readTables(connection, type).contains(table)) {
                throw new ForgeException(DataSourceErrorCode.TABLE_NOT_FOUND);
            }
            var metadata = connection.getMetaData();
            String escape = metadata.getSearchStringEscape();
            List<ColumnResponse> columns = new ArrayList<>();
            try (var rows = metadata.getColumns(
                    table.catalog().isEmpty() ? null : table.catalog(),
                    type == DataSourceType.MYSQL || table.schema().isEmpty()
                            ? null
                            : pattern(table.schema(), escape, type),
                    pattern(table.name(), escape, type),
                    "%")) {
                while (rows.next()) {
                    columns.add(new ColumnResponse(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME")));
                }
            }
            return List.copyOf(columns);
        });
    }

    private static String pattern(String name, String escape, DataSourceType type) {
        String pattern =
                name.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
        // SQL Server 还把 [ 用作字符组的起始符，其他数据库只需转义标准通配符。
        return type == DataSourceType.SQLSERVER ? pattern.replace("[", escape + "[") : pattern;
    }

    private static List<TableResponse> readTables(Connection connection, DataSourceType type) throws SQLException {
        var metadata = connection.getMetaData();
        Set<String> schemas = new HashSet<>();
        // PostgreSQL 的表权限与 schema USAGE 独立；驱动的 getTables 只检查前者。
        if (type == DataSourceType.POSTGRESQL) {
            try (var rows = metadata.getSchemas()) {
                while (rows.next()) {
                    schemas.add(rows.getString("TABLE_SCHEM"));
                }
            }
        }
        List<TableResponse> tables = new ArrayList<>();
        try (var rows = metadata.getTables(
                connection.getCatalog(), null, "%", new String[] {"TABLE", "VIEW", "PARTITIONED TABLE"})) {
            while (rows.next()) {
                String catalog = text(rows.getString("TABLE_CAT"));
                String schema = text(rows.getString("TABLE_SCHEM"));
                if (type == DataSourceType.POSTGRESQL && !schemas.contains(schema)) {
                    continue;
                }
                tables.add(new TableResponse(
                        catalog, type == DataSourceType.MYSQL ? catalog : schema, rows.getString("TABLE_NAME")));
            }
        }
        tables.sort(Comparator.comparing(TableResponse::schema).thenComparing(TableResponse::name));
        return List.copyOf(tables);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private <T> T withConnection(
            DataSourceType type, InetAddress address, ConnectionSettings settings, JdbcOperation<T> operation) {
        var properties = new Properties();
        properties.putAll(settings.params());
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        String url = switch (type) {
            case POSTGRESQL -> postgres(address, settings, properties);
            case MYSQL -> mysql(address, settings, properties);
            case SQLSERVER -> sqlServer(address, settings, properties);
            case ORACLE -> oracle(address, settings, properties);
            case API -> throw new IllegalArgumentException("API data sources do not use JDBC");
        };
        try (Connection connection = type == DataSourceType.ORACLE
                ? oracleConnection(url, address, properties)
                : DriverManager.getConnection(url, properties)) {
            return operation.run(connection);
        } catch (SQLException e) {
            throw translate(type, e, address);
        }
    }

    @FunctionalInterface
    private interface JdbcOperation<T> {
        T run(Connection connection) throws SQLException;
    }

    private static String postgres(InetAddress address, ConnectionSettings settings, Properties properties) {
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("loginTimeout", String.valueOf(READ_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(READ_TIMEOUT_SECONDS));
        // 不尝试 Kerberos：服务所在环境的凭据不能被用来连接租户的库
        properties.setProperty("gssEncMode", "disable");
        // PostgreSQL 默认元数据会列出没有权限的表，浏览只返回该账号获授权的对象。
        properties.setProperty("hideUnprivilegedObjects", "true");
        // 默认的 LibPQFactory 会把服务所在机器 ~/.postgresql 下的客户端证书发给对方；白名单不含 verify-*，无需校验服务端证书
        properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        // 库名经 URL 编码，防止以 ? / 等字符在 URL 中注入参数
        return "jdbc:postgresql://" + urlHost(address) + ":" + settings.port() + "/" + encode(settings.database());
    }

    private static String mysql(InetAddress address, ConnectionSettings settings, Properties properties) {
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
        properties.setProperty("socketTimeout", String.valueOf(READ_TIMEOUT_SECONDS * 1000));
        // LOAD DATA LOCAL 可让对方服务器读取服务所在机器的任意文件；autoDeserialize 会反序列化对方返回的 Java 对象
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowUrlInLocalInfile", "false");
        properties.setProperty("autoDeserialize", "false");
        // 驱动默认回退到 JVM 的客户端证书库，不能把服务自己的客户端证书用于租户的连接。
        properties.setProperty("fallbackToSystemKeyStore", "false");
        return "jdbc:mysql://" + urlHost(address) + ":" + settings.port() + "/" + encode(settings.database());
    }

    /** 地址与库名只经属性传入：SQL Server 的 URL 以分号分隔属性，拼进 URL 的库名可注入任意属性。 */
    private static String sqlServer(InetAddress address, ConnectionSettings settings, Properties properties) {
        properties.setProperty("serverName", address.getHostAddress());
        properties.setProperty("portNumber", String.valueOf(settings.port()));
        properties.setProperty("databaseName", settings.database());
        // 连接的是 IP，证书按租户填写的主机名校验
        properties.setProperty("hostNameInCertificate", settings.host());
        properties.setProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(READ_TIMEOUT_SECONDS * 1000));
        // 不用服务所在环境的 Windows / Kerberos 凭据登录租户的库
        properties.setProperty("integratedSecurity", "false");
        // TDS 路由可由不可信服务器下发，后续 socket 同样只能连接已检查的目标。
        properties.setProperty("socketFactoryClass", CheckedAddressSocketFactory.class.getName());
        properties.setProperty("socketFactoryConstructorArg", address.getHostAddress() + "," + settings.port());
        // 这两条并行寻址路径可能使用 SocketChannel，绕过 SocketFactory。
        properties.setProperty("multiSubnetFailover", "false");
        properties.setProperty("transparentNetworkIPResolution", "false");
        return "jdbc:sqlserver://";
    }

    /** 服务名已由 {@link DataSourceType#acceptsDatabase} 限定为服务名字符，不能借括号改写连接描述符。 */
    private static String oracle(InetAddress address, ConnectionSettings settings, Properties properties) {
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
        properties.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(READ_TIMEOUT_SECONDS * 1000));
        return "jdbc:oracle:thin:@//" + urlHost(address) + ":" + settings.port() + "/" + settings.database();
    }

    private static Connection oracleConnection(String url, InetAddress address, Properties properties)
            throws SQLException {
        // 强制在本进程解析，防止代理远程 DNS 跳过按连接设置的 resolver。
        properties.setProperty("oracle.net.proxyRemoteDNS", "false");
        var dataSource = new OracleDataSource();
        dataSource.setURL(url);
        dataSource.setConnectionProperties(properties);
        var resolved = new AtomicBoolean();
        return dataSource
                .createConnectionBuilder()
                .hostnameResolver(host -> {
                    // Oracle TNS REDIRECT 会再次解析；即使仍是同一 IP，也可能换端口或连接描述符。
                    if (!resolved.compareAndSet(false, true)
                            || !(host.equals(address.getHostAddress()) || host.equals(urlHost(address)))) {
                        throw new UnknownHostException("External database redirect is not allowed");
                    }
                    return new InetAddress[] {address};
                })
                .build();
    }

    private static String urlHost(InetAddress address) {
        return address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 4xx 的 errorMessage 会原样返回给调用方，因此只写固定文案；地址、SQLState 与厂商错误码只进日志，否则可凭它们区分端口拒绝、
     * 超时与非数据库端口，用来探测网络。
     */
    private static ForgeException translate(DataSourceType type, SQLException e, InetAddress address) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        int code = e.getErrorCode();
        log.info(
                "External {} connection to {} failed with SQLState {} code {}: {}",
                type,
                address.getHostAddress(),
                state,
                code,
                e.getMessage());
        if (state.startsWith("28") || isAuthFailure(type, code)) {
            return ForgeException.withErrorMessage(DataSourceErrorCode.AUTH_FAILED, "Authentication failed");
        }
        if (state.equals("3D000") || isMissingDatabase(type, code)) {
            return ForgeException.withErrorMessage(DataSourceErrorCode.DATABASE_NOT_FOUND, "Database not found");
        }
        // Oracle 对网络 I/O / 读取到 EOF 使用通用 SQLState 72000，不能只靠 08 类识别不可达。
        if (state.startsWith("08")
                || (type == DataSourceType.ORACLE && (code == 17002 || code == 17800 || code == 17868))) {
            return DataSourceService.unreachable();
        }
        return ForgeException.withErrorMessage(DataSourceErrorCode.CONNECTION_FAILED, "Connection failed");
    }

    // 各厂商的错误码：MySQL 1045 / SQL Server 18456 / Oracle ORA-01017 为账号或密码错误
    private static boolean isAuthFailure(DataSourceType type, int code) {
        return switch (type) {
            case MYSQL -> code == 1045;
            case SQLSERVER -> code == 18456;
            case ORACLE -> code == 1017;
            case POSTGRESQL, API -> false;
        };
    }

    // MySQL 1049 / SQL Server 4060 为库不存在；Oracle ORA-12514 为监听器上没有该服务名。
    // MySQL 对无权访问的库返回 1044 而不区分库是否存在，二者合为一类
    private static boolean isMissingDatabase(DataSourceType type, int code) {
        return switch (type) {
            case MYSQL -> code == 1049 || code == 1044;
            case SQLSERVER -> code == 4060;
            case ORACLE -> code == 12514;
            case POSTGRESQL, API -> false;
        };
    }
}
