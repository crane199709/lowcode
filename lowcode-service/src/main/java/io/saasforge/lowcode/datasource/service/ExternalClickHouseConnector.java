package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.config.ApiProperties;
import io.saasforge.lowcode.datasource.config.SqlDatasetProperties;
import io.saasforge.lowcode.datasource.model.ColumnResponse;
import io.saasforge.lowcode.datasource.model.ConnectionSettings;
import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.model.DatasetPreviewResponse;
import io.saasforge.lowcode.datasource.model.FieldResponse;
import io.saasforge.lowcode.datasource.model.FieldRole;
import io.saasforge.lowcode.datasource.model.FieldType;
import io.saasforge.lowcode.datasource.model.TableResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ClickHouse HTTP 只连接经 NetworkGuard 检查的 IP；URI 保留原始主机，使 TLS 证书校验及 SNI 使用租户填写的主机名。
 * 不跟随重定向，也不使用系统代理、Cookie、重试或服务的客户端证书。凭据只经 Basic 请求头发送，不写入 URI、日志或异常正文。
 */
@Component
class ExternalClickHouseConnector {
    private final ApiProperties httpProperties;
    private final SqlDatasetProperties sqlProperties;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    ExternalClickHouseConnector(ApiProperties httpProperties, SqlDatasetProperties sqlProperties) {
        this.httpProperties = httpProperties;
        this.sqlProperties = sqlProperties;
    }

    void testConnection(InetAddress address, ConnectionSettings settings) {
        var result = execute(address, settings, "SELECT 1 AS connected FORMAT JSONCompact", Map.of(), 5, false);
        if (data(result, 1, false).size() != 1
                || result.path("data").path(0).path(0).asInt(-1) != 1) {
            throw failure(false);
        }
    }

    List<TableResponse> tables(InetAddress address, ConnectionSettings settings) {
        var result = execute(
                address,
                settings,
                "SELECT name FROM system.tables WHERE database = {db:String} ORDER BY name FORMAT JSONCompact",
                Map.of("param_db", settings.database()),
                10,
                false);
        List<TableResponse> tables = new ArrayList<>();
        for (var row : data(result, 1, false)) {
            tables.add(new TableResponse(settings.database(), "", row.get(0).asText()));
        }
        return List.copyOf(tables);
    }

    List<ColumnResponse> columns(InetAddress address, ConnectionSettings settings, TableResponse table) {
        // catalog/schema/table 必须是同一账号可见的表，精确名称通过 ClickHouse 类型参数传递，不拼接租户文本。
        if (!tables(address, settings).contains(table)) {
            throw new ForgeException(DataSourceErrorCode.TABLE_NOT_FOUND);
        }
        var result = execute(
                address,
                settings,
                "SELECT name, type FROM system.columns WHERE database = {db:String} AND table = {table:String} ORDER BY position FORMAT JSONCompact",
                Map.of("param_db", settings.database(), "param_table", table.name()),
                10,
                false);
        List<ColumnResponse> columns = new ArrayList<>();
        for (var row : data(result, 2, false)) {
            columns.add(new ColumnResponse(row.get(0).asText(), row.get(1).asText()));
        }
        return List.copyOf(columns);
    }

    DatasetPreviewResponse preview(InetAddress address, ConnectionSettings settings, String sql) {
        return query(address, settings, sql, Map.of());
    }

    DatasetPreviewResponse aggregate(
            InetAddress address,
            ConnectionSettings settings,
            String sql,
            DatasetQueryService.Query query,
            List<FieldResponse> fields) {
        var prepared = SqlAggregation.build(DataSourceType.CLICKHOUSE, sql, query, fields);
        Map<String, String> parameters = new HashMap<>();
        for (int i = 0; i < prepared.values().size(); i++) {
            parameters.put("param_p" + i, prepared.values().get(i).toString());
        }
        return query(address, settings, prepared.sql(), parameters);
    }

    private DatasetPreviewResponse query(
            InetAddress address, ConnectionSettings settings, String sql, Map<String, String> parameters) {
        var result = execute(
                address,
                settings,
                "SELECT * FROM (\n" + sql + "\n) AS lc_preview LIMIT " + (sqlProperties.maxRows() + 1)
                        + " FORMAT JSONCompact",
                parameters,
                sqlProperties.timeoutSeconds(),
                true);
        if (!result.path("meta").isArray() || result.path("meta").isEmpty()) {
            throw failure(true);
        }
        List<FieldResponse> fields = new ArrayList<>();
        var names = new HashSet<String>();
        for (var column : result.path("meta")) {
            String name = column.path("name").asText();
            if (name.isBlank() || !names.add(name)) {
                throw new ForgeException(DatasetErrorCode.FIELD_INVALID);
            }
            FieldType type = fieldType(column.path("type").asText());
            fields.add(new FieldResponse(
                    name,
                    name,
                    type,
                    type == FieldType.NUMBER ? FieldRole.MEASURE : FieldRole.DIMENSION,
                    type == FieldType.DATE || type == FieldType.DATETIME));
        }
        List<List<Object>> rows = new ArrayList<>();
        var values = data(result, fields.size(), true);
        for (var value : values) {
            if (rows.size() == sqlProperties.maxRows()) {
                break;
            }
            if (!value.isArray() || value.size() != fields.size()) {
                throw failure(true);
            }
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                var cell = value.get(i);
                try {
                    row.add(
                            cell.isNull()
                                    ? null
                                    : switch (fields.get(i).type()) {
                                        case NUMBER -> new BigDecimal(cell.asText());
                                        case BOOLEAN -> cell.asBoolean();
                                        case OTHER -> cell.isTextual() ? cell.asText() : cell.toString();
                                        default -> cell.asText();
                                    });
                } catch (NumberFormatException e) {
                    throw failure(true);
                }
            }
            rows.add(row);
        }
        return new DatasetPreviewResponse(
                List.copyOf(fields), rows, values.size() > sqlProperties.maxRows(), sqlProperties.maxRows());
    }

    private static JsonNode data(JsonNode result, int columns, boolean dataset) {
        if (result == null || !result.path("data").isArray()) {
            throw failure(dataset);
        }
        for (var row : result.path("data")) {
            if (!row.isArray() || row.size() != columns) {
                throw failure(dataset);
            }
        }
        return result.path("data");
    }

    private static FieldType fieldType(String type) {
        while ((type.startsWith("Nullable(") || type.startsWith("LowCardinality(")) && type.endsWith(")")) {
            type = type.substring(type.indexOf('(') + 1, type.length() - 1);
        }
        if (type.matches("(?:U?Int(?:8|16|32|64|128|256)|Float(?:32|64)|Decimal.*)")) {
            return FieldType.NUMBER;
        }
        if (type.equals("Bool")) {
            return FieldType.BOOLEAN;
        }
        if (type.equals("Date") || type.equals("Date32")) {
            return FieldType.DATE;
        }
        if (type.startsWith("DateTime")) {
            return FieldType.DATETIME;
        }
        if (type.equals("String") || type.startsWith("FixedString(") || type.startsWith("Enum")) {
            return FieldType.STRING;
        }
        return FieldType.OTHER;
    }

    private JsonNode execute(
            InetAddress address,
            ConnectionSettings settings,
            String sql,
            Map<String, String> parameters,
            int seconds,
            boolean dataset) {
        var timeout = Timeout.ofSeconds(seconds);
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new CheckedDnsResolver(address))
                // createDefault 不加载 JVM 客户端密钥，保留默认证书链与主机名校验。
                .setTlsSocketStrategy(DefaultClientTlsStrategy.createDefault())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .build())
                .build();
        try (var client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout)
                        .setResponseTimeout(timeout)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build()) {
            var uri = new URIBuilder()
                    .setScheme("true".equals(settings.params().get("ssl")) ? "https" : "http")
                    .setHost(settings.host())
                    .setPort(settings.port())
                    .setPath("/")
                    .addParameter("database", settings.database())
                    .addParameter("readonly", "1")
                    .addParameter("max_execution_time", String.valueOf(seconds))
                    .addParameter("wait_end_of_query", "1");
            parameters.forEach(uri::addParameter);
            var request = new HttpPost(uri.build());
            request.setHeader(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString((settings.username() + ":" + settings.password())
                                            .getBytes(StandardCharsets.UTF_8)));
            request.setHeader("Accept-Encoding", "identity");
            request.setEntity(new StringEntity(sql, ContentType.TEXT_PLAIN));
            return client.execute(request, response -> {
                int status = response.getCode();
                var exception = response.getFirstHeader("X-ClickHouse-Exception-Code");
                String code = exception == null ? "" : exception.getValue();
                if (status == 401 || status == 403 || code.equals("516") || code.equals("192") || code.equals("193")) {
                    throw new ForgeException(DataSourceErrorCode.AUTH_FAILED);
                }
                if (code.equals("81")) {
                    throw new ForgeException(DataSourceErrorCode.DATABASE_NOT_FOUND);
                }
                if (dataset && (code.equals("159") || code.equals("160"))) {
                    throw new ForgeException(DatasetErrorCode.QUERY_TIMEOUT);
                }
                if (status != 200 || !code.isEmpty()) {
                    throw failure(dataset);
                }
                var entity = response.getEntity();
                if (entity == null || entity.getContentLength() > httpProperties.maxResponseBytes()) {
                    throw failure(dataset);
                }
                try (var stream = entity.getContent()) {
                    byte[] bytes = stream.readNBytes(httpProperties.maxResponseBytes() + 1);
                    if (bytes.length > httpProperties.maxResponseBytes()) {
                        throw failure(dataset);
                    }
                    // 200 响应也可能在正文尾部追加执行错误，必须完整解析且拒绝尾随内容。
                    try {
                        var result = json.readTree(bytes);
                        if (result == null || !result.isObject() || result.has("exception")) {
                            throw failure(dataset);
                        }
                        return result;
                    } catch (JacksonException e) {
                        throw failure(dataset);
                    }
                }
            });
        } catch (SocketTimeoutException e) {
            if (dataset) {
                throw new ForgeException(DatasetErrorCode.QUERY_TIMEOUT);
            }
            throw DataSourceService.unreachable();
        } catch (IOException | URISyntaxException e) {
            throw DataSourceService.unreachable();
        }
    }

    private static ForgeException failure(boolean dataset) {
        return new ForgeException(dataset ? DatasetErrorCode.QUERY_FAILED : DataSourceErrorCode.CONNECTION_FAILED);
    }

    private record CheckedDnsResolver(InetAddress address) implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) {
            return new InetAddress[] {address};
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
