package io.saasforge.lowcode.datasource.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DataSourceSecurityProperties.class, ApiProperties.class, SqlDatasetProperties.class})
class DataSourceConfig {}
