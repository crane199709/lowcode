package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.config.DataSourceSecurityProperties;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 按禁止网段检查 Data Source 的主机（ADR-0002）。连接时用这里解析并检查过的地址，而不是再交给驱动解析主机名：
 * 两次解析之间 DNS 结果可能改变（DNS rebinding），检查就会失效。
 */
@Component
public class NetworkGuard {
    private final List<Cidr> blocked;

    public NetworkGuard(DataSourceSecurityProperties properties) {
        this.blocked = properties.blockedNetworks().stream().map(Cidr::parse).toList();
    }

    /**
     * 解析主机的全部地址并检查。
     *
     * @return 解析结果，主机无法解析时为空列表
     * @throws ForgeException 任一地址落入禁止网段时为 {@code datasource.hostBlocked}
     */
    public List<InetAddress> resolve(String host) {
        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return List.of();
        }
        for (InetAddress address : addresses) {
            // 0.0.0.0 与 :: 在多数系统上连到本机，不能依赖配置是否列出
            if (address.isAnyLocalAddress() || blocked.stream().anyMatch(cidr -> cidr.contains(address))) {
                throw ForgeException.withErrorMessage(
                        DataSourceErrorCode.HOST_BLOCKED, "Host " + host + " resolves into a blocked network");
            }
        }
        return addresses;
    }

    private record Cidr(byte[] network, int prefix) {
        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            String address = slash < 0 ? cidr : cidr.substring(0, slash);
            // 只接受地址字面量：配置里写主机名会在启动时触发 DNS 查询，且结果随时间变化
            if (!address.matches("[0-9A-Fa-f:.]+")) {
                throw new IllegalStateException("Invalid blocked network " + cidr);
            }
            byte[] network;
            try {
                network = InetAddress.getByName(address).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid blocked network " + cidr, e);
            }
            int prefix;
            try {
                prefix = slash < 0 ? network.length * 8 : Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid blocked network " + cidr, e);
            }
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalStateException("Invalid blocked network " + cidr);
            }
            return new Cidr(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest);
            return (bytes[full] & mask) == (network[full] & mask);
        }
    }
}
