package io.saasforge.lowcode.datasource.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;

/** SQL Server 驱动的 SocketFactory SPI：每次 connect（包括服务端路由）均限制到已检查的 IP 与端口。 */
public final class CheckedAddressSocketFactory extends SocketFactory {
    private final InetSocketAddress target;

    /** 由 JDBC 驱动反射构造，参数仅由 ExternalJdbcConnector 生成，不接受租户参数。 */
    public CheckedAddressSocketFactory(String endpoint) {
        int separator = endpoint.lastIndexOf(',');
        target = new InetSocketAddress(
                endpoint.substring(0, separator), Integer.parseInt(endpoint.substring(separator + 1)));
    }

    @Override
    public Socket createSocket() {
        return new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (!target.equals(endpoint)) {
                    throw new IOException("External database redirect is not allowed");
                }
                super.connect(target, timeout);
            }

            @Override
            public void connect(SocketAddress endpoint) throws IOException {
                connect(endpoint, 0);
            }
        };
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    private Socket connect(InetSocketAddress endpoint, InetSocketAddress local) throws IOException {
        Socket socket = createSocket();
        try {
            if (local != null) {
                socket.bind(local);
            }
            socket.connect(endpoint);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }
}
