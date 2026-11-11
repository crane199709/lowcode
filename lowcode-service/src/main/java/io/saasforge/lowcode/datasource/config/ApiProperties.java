package io.saasforge.lowcode.datasource.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 外部 HTTP Data Source（API 与 ClickHouse）的响应约束。超时与响应体上限是控制服务端为租户发起请求的代价，默认值写在 {@link DefaultValue}。
 *
 * @param timeout API 请求连接与等待响应的超时；ClickHouse 使用 SQL 的执行预算
 * @param maxResponseBytes 单个响应的最大字节数，API 与 ClickHouse 的连接测试、元数据与取数共用
 */
@ConfigurationProperties("lowcode.datasource.api")
public record ApiProperties(
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("1048576") int maxResponseBytes) {
    public ApiProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("lowcode.datasource.api.timeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalStateException("lowcode.datasource.api.max-response-bytes must be positive");
        }
    }
}
