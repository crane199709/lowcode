package io.saasforge.lowcode.datasource.model;

import java.util.Map;

/**
 * 数据库类 Data Source 的结构化连接字段，JDBC URL 由服务端据此拼装。
 *
 * @param password 明文密码；编辑与测试已保存的数据源时为空表示沿用已保存的密码
 * @param params 白名单内的连接参数
 */
public record ConnectionSettings(
        String host, int port, String database, String username, String password, Map<String, String> params) {
    public ConnectionSettings {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    public ConnectionSettings withPassword(String password) {
        return new ConnectionSettings(host, port, database, username, password, params);
    }

    /** 不输出密码，避免写入日志。 */
    @Override
    public String toString() {
        return "ConnectionSettings[host=" + host + ", port=" + port + ", database=" + database + ", username="
                + username + ", params=" + params + "]";
    }
}
