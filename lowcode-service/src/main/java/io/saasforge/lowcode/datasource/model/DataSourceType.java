package io.saasforge.lowcode.datasource.model;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Data Source 类型，及各类型允许租户填写的连接参数与取值（ADR-0002），其余参数一律拒绝。驱动中可读取服务所在机器的文件、
 * 反序列化对象或借用服务凭据的参数（如 MySQL {@code allowLoadLocalInfile}、SQL Server {@code integratedSecurity}）都不在白名单内。
 */
public enum DataSourceType {
    // 不含 verify-ca / verify-full：它们读取服务所在机器上的证书文件
    POSTGRESQL(Map.of("sslmode", Set.of("disable", "allow", "prefer", "require"))),
    // 不含 VERIFY_CA / VERIFY_IDENTITY：连接用的是检查过的 IP，主机名校验无法通过，且需读取服务所在机器的信任库。
    // MySQL 8 默认的 caching_sha2_password 在不用 SSL 时须取服务端公钥，由租户自行决定是否允许
    MYSQL(Map.of(
            "sslMode", Set.of("DISABLED", "PREFERRED", "REQUIRED"),
            "allowPublicKeyRetrieval", Set.of("true", "false"))),
    SQLSERVER(Map.of("encrypt", Set.of("true", "false"), "trustServerCertificate", Set.of("true", "false"))),
    ORACLE(Map.of());

    // Oracle 服务名直接拼进连接描述符，括号、等号等字符可改写描述符（如另指一个地址），只允许服务名本身的字符
    private static final Pattern ORACLE_SERVICE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Map<String, Set<String>> allowedParams;

    DataSourceType(Map<String, Set<String>> allowedParams) {
        this.allowedParams = allowedParams;
    }

    public boolean allows(String param, String value) {
        Set<String> values = allowedParams.get(param);
        return values != null && values.contains(value);
    }

    /** 库名（Oracle 为服务名）能否安全地放进连接地址；其余类型的库名经编码或属性传入，不受字符限制。 */
    public boolean acceptsDatabase(String database) {
        return this != ORACLE || ORACLE_SERVICE_NAME.matcher(database).matches();
    }
}
