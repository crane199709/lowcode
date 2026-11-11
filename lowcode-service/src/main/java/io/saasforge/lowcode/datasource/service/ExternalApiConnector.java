package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.config.ApiProperties;
import io.saasforge.lowcode.datasource.model.ApiAuthType;
import io.saasforge.lowcode.datasource.model.ApiConnectionTestResponse;
import io.saasforge.lowcode.datasource.model.ApiMethod;
import io.saasforge.lowcode.datasource.model.ApiSettings;
import io.saasforge.sdk.core.api.ForgeException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 以 HTTP 请求 Tenant 自有的 JSON 接口（ADR-0002）。
 *
 * <p>连接目标用 {@link NetworkGuard} 已检查过的地址：自定义 DNS 解析只返回这些地址，避免检查后再次解析时 DNS
 * 结果改变（DNS rebinding）。重定向一律不跟随，自动重试、Cookie 与压缩都关闭，避免一次测试连接被引到别的地址或读入
 * 无上限的响应体。
 */
@Component
class ExternalApiConnector {
    private static final Logger log = LoggerFactory.getLogger(ExternalApiConnector.class);

    private final ApiProperties properties;

    ExternalApiConnector(ApiProperties properties) {
        this.properties = properties;
    }

    /**
     * 请求接口，HTTP 2xx 即成功。
     *
     * @param addresses 已按禁止网段检查过的解析结果
     * @return 目标接口的状态码
     * @throws ForgeException 重定向、认证失败、超时、响应超限与不可达等情况，带可读错误码
     */
    ApiConnectionTestResponse testConnection(ApiSettings settings, List<InetAddress> addresses) {
        var timeout = Timeout.of(properties.timeout());
        var requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new CheckedDnsResolver(addresses))
                .setDefaultConnectionConfig(
                        ConnectionConfig.custom().setConnectTimeout(timeout).build())
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build()) {
            var request = new HttpUriRequestBase(
                    settings.method().name(), URI.create(settings.url().trim()));
            request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
            // 不声明压缩，响应体大小按实际传输字节判断
            request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
            applyAuth(request, settings);
            if (settings.method() == ApiMethod.POST && hasText(settings.body())) {
                request.setEntity(new StringEntity(settings.body(), ContentType.APPLICATION_JSON));
            }
            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    log.info("API request to {} was redirected with HTTP {}", addresses.get(0), status);
                    throw new ForgeException(DataSourceErrorCode.API_REDIRECT);
                }
                if (status == 401 || status == 403) {
                    log.info("API request to {} failed authentication with HTTP {}", addresses.get(0), status);
                    throw new ForgeException(DataSourceErrorCode.API_AUTH_FAILED);
                }
                if (status < 200 || status >= 300) {
                    log.info("API request to {} returned HTTP {}", addresses.get(0), status);
                    throw new ForgeException(DataSourceErrorCode.API_HTTP_ERROR, status);
                }
                // 测试连接也受响应体上限约束，否则一个超大响应就能拖垮服务
                readBody(response.getEntity());
                return new ApiConnectionTestResponse(status);
            }
        } catch (ForgeException e) {
            throw e;
        } catch (ResponseTooLargeException e) {
            throw new ForgeException(DataSourceErrorCode.API_RESPONSE_TOO_LARGE, properties.maxResponseBytes());
        } catch (IOException e) {
            // 超时、拒绝连接、解析失败、TLS 与读响应失败都合并为同一个原因：区分它们等于告诉调用方端口是否开放
            throw unreachable(e, addresses);
        }
    }

    private static void applyAuth(HttpUriRequestBase request, ApiSettings settings) {
        if (settings.authType() == ApiAuthType.HEADER) {
            request.setHeader(settings.headerName(), settings.headerValue());
        } else if (settings.authType() == ApiAuthType.BASIC) {
            String credentials = settings.username() + ":" + settings.password();
            request.setHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** 读取响应体并计数，Content-Length 已知时先判断，避免把超大响应读进内存。 */
    private void readBody(HttpEntity entity) throws IOException {
        if (entity == null) {
            return;
        }
        int max = properties.maxResponseBytes();
        if (entity.getContentLength() > max) {
            throw new ResponseTooLargeException();
        }
        try (InputStream body = entity.getContent()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new ResponseTooLargeException();
                }
            }
        }
    }

    private static ForgeException unreachable(IOException e, List<InetAddress> addresses) {
        log.info("API endpoint {} is unreachable: {}", addresses.get(0), e.toString());
        throw new ForgeException(DataSourceErrorCode.API_UNREACHABLE);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 只返回调用方已经检查过的地址，不再触发第二次 DNS 解析。 */
    private record CheckedDnsResolver(List<InetAddress> addresses) implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) {
            return addresses.toArray(InetAddress[]::new);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    private static final class ResponseTooLargeException extends IOException {}
}
