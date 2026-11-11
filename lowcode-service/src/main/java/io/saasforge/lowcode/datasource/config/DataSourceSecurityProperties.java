package io.saasforge.lowcode.datasource.config;

import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 代 Tenant 发起外部连接的安全配置（ADR-0002）。两项都随环境变化且没有安全的默认值，漏配或格式错误时启动失败。
 *
 * @param blockedNetworks 禁止连接的网段（CIDR），通常是平台内网；主机任一解析结果落入其中即拒绝
 * @param encryptionKey 加密数据源密码的 AES-256 密钥，Base64 编码的 32 字节
 */
@ConfigurationProperties("lowcode.datasource")
public record DataSourceSecurityProperties(List<String> blockedNetworks, String encryptionKey) {
    public DataSourceSecurityProperties {
        if (blockedNetworks == null || blockedNetworks.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException("lowcode.datasource.blocked-networks must list at least one CIDR");
        }
        blockedNetworks = blockedNetworks.stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .toList();
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("lowcode.datasource.encryption-key is required");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("lowcode.datasource.encryption-key must be Base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("lowcode.datasource.encryption-key must decode to 32 bytes");
        }
    }

    /** 不输出密钥，避免配置绑定失败或调试时写入日志。 */
    @Override
    public String toString() {
        return "DataSourceSecurityProperties[blockedNetworks=" + blockedNetworks + "]";
    }
}
