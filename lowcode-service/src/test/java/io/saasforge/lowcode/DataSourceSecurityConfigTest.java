package io.saasforge.lowcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.lowcode.datasource.config.DataSourceSecurityProperties;
import io.saasforge.lowcode.datasource.service.NetworkGuard;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 禁止网段与加密密钥没有安全的默认值，漏配或写错时启动失败；主机按解析结果而不是字面值检查（ADR-0002）。 */
class DataSourceSecurityConfigTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void startupFailsWithoutBlockedNetworksOrEncryptionKey() {
        runner.withPropertyValues("lowcode.datasource.encryption-key=" + KEY)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("lowcode.datasource.blocked-networks=10.0.0.0/8")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "lowcode.datasource.blocked-networks=10.0.0.0/8", "lowcode.datasource.encryption-key=c2hvcnQ=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "lowcode.datasource.blocked-networks=10.0.0.0/33", "lowcode.datasource.encryption-key=" + KEY)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "lowcode.datasource.blocked-networks=10.0.0.0/8,fd00::/8",
                        "lowcode.datasource.encryption-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(NetworkGuard.class));
    }

    @Test
    void hostNameIsCheckedByWhatItResolvesTo() {
        var guard = new NetworkGuard(new DataSourceSecurityProperties(List.of("127.0.0.0/8", "::1/128"), KEY));
        assertThatThrownBy(() -> guard.resolve("localhost"))
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("datasource.hostBlocked"));
        assertThatThrownBy(() -> guard.resolve("::1")).isInstanceOf(ForgeException.class);
        assertThat(guard.resolve("128.0.0.1")).hasSize(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceSecurityProperties.class)
    @Import(NetworkGuard.class)
    static class Config {}
}
