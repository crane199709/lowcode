package io.saasforge.lowcode.datasource.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 外部 SQL 的执行预算；驱动查询超时按整秒计。 */
@ConfigurationProperties("lowcode.datasource.sql")
public record SqlDatasetProperties(
        @DefaultValue("30") int timeoutSeconds,
        @DefaultValue("10000") int maxRows) {
    public SqlDatasetProperties {
        if (timeoutSeconds < 1 || maxRows < 1 || maxRows > 100000) {
            throw new IllegalStateException("SQL timeout must be positive and maxRows must be between 1 and 100000");
        }
    }
}
