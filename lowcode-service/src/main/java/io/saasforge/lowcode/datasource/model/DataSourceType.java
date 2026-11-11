package io.saasforge.lowcode.datasource.model;

import java.util.Map;
import java.util.Set;

/** Data Source 类型，及各类型允许租户填写的连接参数与取值（ADR-0002），其余参数一律拒绝。 */
public enum DataSourceType {
    // 不含 verify-ca / verify-full：它们读取服务所在机器上的证书文件
    POSTGRESQL(Map.of("sslmode", Set.of("disable", "allow", "prefer", "require")));

    private final Map<String, Set<String>> allowedParams;

    DataSourceType(Map<String, Set<String>> allowedParams) {
        this.allowedParams = allowedParams;
    }

    public boolean allows(String param, String value) {
        Set<String> values = allowedParams.get(param);
        return values != null && values.contains(value);
    }
}
