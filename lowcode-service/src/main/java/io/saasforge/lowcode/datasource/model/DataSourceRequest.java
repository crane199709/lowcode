package io.saasforge.lowcode.datasource.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Data Source 的登记与修改请求。连接配置按类型放在 {@code database} 或 {@code api} 中，另一项为 null；嵌套记录上的
 * Bean Validation 注解随类型生效，响应里的 {@code errors} 能定位到具体字段。
 *
 * @param database 数据库类连接配置；API 类为 null
 * @param api API 类连接配置；数据库类为 null
 */
public record DataSourceRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull DataSourceType type,
        @Valid DatabaseRequest database,
        @Valid ApiRequest api) {
    public ConnectionSettings databaseSettings() {
        return database == null ? null : database.toSettings();
    }

    public ApiSettings apiSettings() {
        return api == null ? null : api.toSettings();
    }

    /** 不输出密码与请求头值，避免请求被记录到日志或审计事件。 */
    @Override
    public String toString() {
        return "DataSourceRequest[name=" + name + ", type=" + type + ", database=" + database + ", api=" + api + "]";
    }

    /**
     * 数据库类连接配置。主机只允许主机名或 IP 字面量的字符，排除 PostgreSQL URL 的多主机写法（逗号）与路径、参数分隔符。
     *
     * @param password 连接密码；新建时必填，编辑时留空表示不修改
     * @param params 按类型白名单校验的连接参数
     */
    public record DatabaseRequest(
            @NotBlank @Size(max = 253) @Pattern(regexp = "[A-Za-z0-9.:\\-]+")
            String host,

            @NotNull @Min(1) @Max(65535) Integer port,
            @NotBlank @Size(max = 128) String database,
            @NotBlank @Size(max = 128) String username,
            @Size(max = 256) String password,
            Map<String, String> params) {
        ConnectionSettings toSettings() {
            return new ConnectionSettings(host, port, database, username, password, params);
        }

        /** 不输出密码。 */
        @Override
        public String toString() {
            return "DatabaseRequest[host=" + host + ", port=" + port + ", database=" + database + ", username="
                    + username + ", params=" + params + "]";
        }
    }

    /**
     * API 请求配置。URL 的协议、主机与路径由 Service 校验；请求头值与 Basic 密码只进不出，编辑时留空表示不修改。
     *
     * @param headerValue 自定义请求头的值；不参与 toString，避免写入日志
     * @param password Basic 密码；数据库类为 null，不参与 toString
     */
    public record ApiRequest(
            @NotBlank @Size(max = 2048) String url,
            @NotNull ApiMethod method,
            @Size(max = 65536) String body,
            @NotNull ApiAuthType authType,

            @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9!#$%&'*+.^_|~-]*")
            String headerName,

            @Size(max = 1024) @Pattern(regexp = "[^\\r\\n]*")
            String headerValue,

            @Size(max = 128) String username,
            @Size(max = 256) String password) {
        ApiSettings toSettings() {
            return new ApiSettings(url, method, body, authType, headerName, headerValue, username, password);
        }

        @Override
        public String toString() {
            return "ApiRequest[url=" + url + ", method=" + method + ", authType=" + authType + ", headerName="
                    + headerName + ", username=" + username + "]";
        }
    }
}
