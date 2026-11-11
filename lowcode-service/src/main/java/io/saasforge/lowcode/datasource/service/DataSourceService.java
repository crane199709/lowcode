package io.saasforge.lowcode.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.lowcode.datasource.entity.DataSource;
import io.saasforge.lowcode.datasource.mapper.DataSourceMapper;
import io.saasforge.lowcode.datasource.model.ApiAuthType;
import io.saasforge.lowcode.datasource.model.ApiConnectionTestResponse;
import io.saasforge.lowcode.datasource.model.ApiMethod;
import io.saasforge.lowcode.datasource.model.ApiSettings;
import io.saasforge.lowcode.datasource.model.ColumnResponse;
import io.saasforge.lowcode.datasource.model.ConnectionSettings;
import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.lowcode.datasource.model.TableResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data Source 的登记与测试连接。租户隔离由 SDK 注入 SQL；连接安全按 ADR-0002：数据库只收结构化字段与白名单参数，
 * API 只收 http/https 请求配置；保存与连接前都按禁止网段检查主机，密码与请求头值加密保存、不出现在任何返回值中。
 */
@Service
public class DataSourceService {
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};
    // 这些请求头会改变请求的解析方式或连接目标，交给 HttpClient 与实体生成逻辑管理
    private static final Set<String> RESERVED_HEADERS =
            Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade");

    private final DataSourceMapper dataSourceMapper;
    private final NetworkGuard networkGuard;
    private final SecretCipher secretCipher;
    private final ExternalJdbcConnector connector;
    private final ExternalApiConnector apiConnector;
    private final JsonMapper jsonMapper;

    DataSourceService(
            DataSourceMapper dataSourceMapper,
            NetworkGuard networkGuard,
            SecretCipher secretCipher,
            ExternalJdbcConnector connector,
            ExternalApiConnector apiConnector,
            JsonMapper jsonMapper) {
        this.dataSourceMapper = dataSourceMapper;
        this.networkGuard = networkGuard;
        this.secretCipher = secretCipher;
        this.connector = connector;
        this.apiConnector = apiConnector;
        this.jsonMapper = jsonMapper;
    }

    public DataSource createDatabase(String name, DataSourceType type, ConnectionSettings settings) {
        if (settings == null) {
            throw databaseRequired();
        }
        if (!settings.hasPassword()) {
            throw passwordRequired();
        }
        return create(name, type, settings, null);
    }

    public DataSource createApi(String name, ApiSettings settings) {
        return create(name, DataSourceType.API, null, settings);
    }

    private DataSource create(String name, DataSourceType type, ConnectionSettings database, ApiSettings api) {
        var dataSource = toEntity(name, type, database, api);
        dataSourceMapper.insert(dataSource);
        // 创建时间由数据库生成，重新读取才能返回与之后查询一致的值
        return get(dataSource.getId());
    }

    /** 密码为空表示不修改：实体字段为 null 时 updateById 不更新该列。 */
    public DataSource updateDatabase(UUID id, String name, DataSourceType type, ConnectionSettings settings) {
        if (settings == null) {
            throw databaseRequired();
        }
        DataSource saved = get(id);
        requirePasswordIfServerChanged(saved, settings);
        return update(id, name, type, settings, null);
    }

    /** 请求头值或 Basic 密码为空表示沿用已保存的值，但只允许在同一来源与同一认证方式下沿用。 */
    public DataSource updateApi(UUID id, String name, ApiSettings settings) {
        DataSource saved = get(id);
        return update(id, name, DataSourceType.API, null, resolveApiSecret(saved, settings));
    }

    private DataSource update(UUID id, String name, DataSourceType type, ConnectionSettings database, ApiSettings api) {
        var dataSource = toEntity(name, type, database, api);
        dataSource.setId(id);
        if (dataSourceMapper.updateById(dataSource) == 0) {
            throw notFound(id);
        }
        return get(id);
    }

    public void delete(UUID id) {
        if (dataSourceMapper.deleteById(id) == 0) {
            throw notFound(id);
        }
    }

    public DataSource get(UUID id) {
        DataSource dataSource = dataSourceMapper.selectById(id);
        if (dataSource == null) {
            throw notFound(id);
        }
        return dataSource;
    }

    public List<DataSource> list(PageQuery query) {
        return dataSourceMapper.selectPage(query.size(), query.offset());
    }

    public List<TableResponse> tables(UUID id) {
        DataSource saved = get(id);
        DataSourceType type = DataSourceType.valueOf(saved.getType());
        ConnectionSettings settings = metadataSettings(saved, type);
        return connector.tables(type, metadataAddress(settings), settings);
    }

    public List<ColumnResponse> columns(UUID id, TableResponse table) {
        DataSource saved = get(id);
        DataSourceType type = DataSourceType.valueOf(saved.getType());
        ConnectionSettings settings = metadataSettings(saved, type);
        return connector.columns(type, metadataAddress(settings), settings, table);
    }

    private ConnectionSettings metadataSettings(DataSource saved, DataSourceType type) {
        if (type == DataSourceType.API) throw new ForgeException(DataSourceErrorCode.METADATA_NOT_SUPPORTED);
        var settings = new ConnectionSettings(
                saved.getHost(),
                saved.getPort(),
                saved.getDatabaseName(),
                saved.getUsername(),
                secretCipher.decrypt(saved.getPasswordEncrypted()),
                paramsOf(saved));
        checkConnectionFields(type, settings);
        return settings;
    }

    private InetAddress metadataAddress(ConnectionSettings settings) {
        List<InetAddress> addresses = networkGuard.resolve(settings.host());
        if (addresses.isEmpty()) throw unreachable();
        return addresses.get(0);
    }

    public long count() {
        return dataSourceMapper.selectCount(new LambdaQueryWrapper<>());
    }

    public Map<String, String> paramsOf(DataSource dataSource) {
        return jsonMapper.readValue(dataSource.getParams(), PARAMS);
    }

    /** 测试尚未保存的数据库连接，成功时正常返回，失败时抛出可读原因的错误码。 */
    public void testConnection(DataSourceType type, ConnectionSettings settings) {
        if (settings == null) {
            throw databaseRequired();
        }
        if (!settings.hasPassword()) {
            throw passwordRequired();
        }
        connect(type, settings);
    }

    /** 测试编辑中的已保存数据库：密码为空时用已保存的密码，以便不重新输入密码也能验证其他字段的修改。 */
    public void testConnection(UUID id, DataSourceType type, ConnectionSettings settings) {
        if (settings == null) {
            throw databaseRequired();
        }
        DataSource saved = get(id);
        requirePasswordIfServerChanged(saved, settings);
        connect(
                type,
                settings.hasPassword()
                        ? settings
                        : settings.withPassword(secretCipher.decrypt(saved.getPasswordEncrypted())));
    }

    /** 测试尚未保存的 API 请求。 */
    public ApiConnectionTestResponse testApiConnection(ApiSettings settings) {
        return connectApi(settings);
    }

    /** 测试编辑中的已保存 API 请求：请求头值或密码为空时用已保存的值。 */
    public ApiConnectionTestResponse testApiConnection(UUID id, ApiSettings settings) {
        DataSource saved = get(id);
        return connectApi(resolveApiSecret(saved, settings));
    }

    private void connect(DataSourceType type, ConnectionSettings settings) {
        checkConnectionFields(type, settings);
        List<InetAddress> addresses = networkGuard.resolve(settings.host());
        // 与连接失败返回完全相同的错误，不区分主机能否解析
        if (addresses.isEmpty()) {
            throw unreachable();
        }
        connector.testConnection(type, addresses.get(0), settings);
    }

    private ApiConnectionTestResponse connectApi(ApiSettings settings) {
        URI uri = validateApi(settings);
        List<InetAddress> addresses = networkGuard.resolve(uri.getHost());
        if (addresses.isEmpty()) {
            throw new ForgeException(DataSourceErrorCode.API_UNREACHABLE);
        }
        // 保存与校验都用 trim 后的地址，请求也必须用同一份，避免首尾空格在 URI.create 处抛未处理的异常
        return apiConnector.testConnection(settings.withUrl(uri.toString()), addresses);
    }

    private DataSource toEntity(String name, DataSourceType type, ConnectionSettings database, ApiSettings api) {
        var dataSource = new DataSource();
        dataSource.setName(name);
        dataSource.setType(type.name());
        if (type == DataSourceType.API) {
            fillApi(dataSource, api);
        } else {
            fillDatabase(dataSource, type, database);
        }
        return dataSource;
    }

    /** API 类行：数据库字段写成空值或默认值，认证凭据只写对应的列。 */
    private void fillApi(DataSource dataSource, ApiSettings api) {
        URI uri = validateApi(api);
        // 暂时无法解析的主机允许保存，连接时会再次解析并检查
        networkGuard.resolve(uri.getHost());
        dataSource.setHost("");
        dataSource.setPort(0);
        dataSource.setDatabaseName("");
        dataSource.setUsername(api.authType() == ApiAuthType.BASIC ? api.username() : "");
        dataSource.setParams("{}");
        dataSource.setApiUrl(api.url().trim());
        dataSource.setApiMethod(api.method().name());
        dataSource.setApiBody(api.body() == null ? "" : api.body());
        dataSource.setApiAuth(api.authType().name());
        dataSource.setApiHeaderName(api.authType() == ApiAuthType.HEADER ? api.headerName() : "");
        dataSource.setApiHeaderValueEncrypted(api.authType() == ApiAuthType.HEADER ? encrypt(api.headerValue()) : "");
        dataSource.setPasswordEncrypted(api.authType() == ApiAuthType.BASIC ? encrypt(api.password()) : "");
    }

    /** 数据库类行：API 字段写成空值或 NONE，避免切换类型后残留上一类数据源的凭据。 */
    private void fillDatabase(DataSource dataSource, DataSourceType type, ConnectionSettings database) {
        checkConnectionFields(type, database);
        // 暂时无法解析的主机允许保存，连接时会再次解析并检查
        networkGuard.resolve(database.host());
        dataSource.setHost(database.host());
        dataSource.setPort(database.port());
        dataSource.setDatabaseName(database.database());
        dataSource.setUsername(database.username());
        dataSource.setParams(jsonMapper.writeValueAsString(database.params()));
        dataSource.setPasswordEncrypted(database.hasPassword() ? secretCipher.encrypt(database.password()) : null);
        dataSource.setApiUrl("");
        dataSource.setApiMethod("");
        dataSource.setApiBody("");
        dataSource.setApiAuth(ApiAuthType.NONE.name());
        dataSource.setApiHeaderName("");
        dataSource.setApiHeaderValueEncrypted("");
    }

    /** 密码为空时清除旧密文，非空时加密。 */
    private String encrypt(String value) {
        return hasText(value) ? secretCipher.encrypt(value) : "";
    }

    private static void checkConnectionFields(DataSourceType type, ConnectionSettings settings) {
        if (!type.acceptsDatabase(settings.database())) {
            throw ForgeException.withErrorMessage(
                    DataSourceErrorCode.DATABASE_INVALID, "Database name is not allowed for " + type);
        }
        settings.params().forEach((param, value) -> {
            if (!type.allows(param, value)) {
                throw ForgeException.withErrorMessage(
                        DataSourceErrorCode.PARAM_NOT_ALLOWED,
                        "Connection param " + param + "=" + value + " is not allowed for " + type,
                        param);
            }
        });
    }

    /**
     * 校验 API 请求配置。请求头值与 Basic 密码在这里必须已填写（编辑时先由 {@link #resolveApiSecret} 沿用保存值），
     * 请求地址只接受 http/https，且不能带用户信息或片段。
     */
    private URI validateApi(ApiSettings api) {
        if (api == null || api.method() == null || api.authType() == null || !hasText(api.url())) {
            throw apiRequired();
        }
        URI uri = parseUrl(api.url());
        String body = api.body() == null ? "" : api.body();
        if (api.method() == ApiMethod.GET && hasText(body)) {
            throw apiBodyNotAllowed();
        }
        if (api.method() == ApiMethod.POST && hasText(body)) {
            try {
                jsonMapper.readTree(body);
            } catch (JacksonException e) {
                throw apiBodyInvalid();
            }
        }
        switch (api.authType()) {
            case NONE -> {}
            case HEADER -> {
                if (!hasText(api.headerName()) || !hasText(api.headerValue())) {
                    throw apiAuthRequired();
                }
                if (RESERVED_HEADERS.contains(api.headerName().toLowerCase(Locale.ROOT))) {
                    throw apiHeaderNotAllowed();
                }
            }
            case BASIC -> {
                if (!hasText(api.username()) || !hasText(api.password())) {
                    throw apiAuthRequired();
                }
            }
        }
        return uri;
    }

    private static URI parseUrl(String raw) {
        String value = raw.trim();
        if (value.isEmpty()
                || value.length() > 2048
                || value.chars().anyMatch(Character::isWhitespace)
                || value.indexOf('\\') >= 0) {
            throw apiUrlInvalid();
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw apiUrlInvalid();
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw apiUrlInvalid();
        }
        return uri;
    }

    /**
     * 沿用已保存的请求头值或 Basic 密码。改动接口来源（协议、主机、端口）或认证标识（请求头名称、账号）后必须重新填写，
     * 否则改一下地址就能让服务端把已保存的凭据发给另一个接口。与数据库密码沿用规则同一考虑。
     */
    private ApiSettings resolveApiSecret(DataSource saved, ApiSettings settings) {
        if (settings == null) {
            throw apiRequired();
        }
        boolean sameAuth = DataSourceType.API.name().equals(saved.getType())
                && saved.getApiAuth() != null
                && settings.authType() != null
                && settings.authType().name().equals(saved.getApiAuth());
        if (settings.authType() == ApiAuthType.HEADER && !settings.hasHeaderValue()) {
            if (sameAuth
                    && sameApiOrigin(saved, settings)
                    && settings.headerName() != null
                    && settings.headerName().equals(saved.getApiHeaderName())
                    && hasText(saved.getApiHeaderValueEncrypted())) {
                return settings.withHeaderValue(secretCipher.decrypt(saved.getApiHeaderValueEncrypted()));
            }
            throw apiAuthRequired();
        }
        if (settings.authType() == ApiAuthType.BASIC && !settings.hasPassword()) {
            if (sameAuth
                    && sameApiOrigin(saved, settings)
                    && settings.username() != null
                    && settings.username().equals(saved.getUsername())
                    && hasText(saved.getPasswordEncrypted())) {
                return settings.withPassword(secretCipher.decrypt(saved.getPasswordEncrypted()));
            }
            throw apiAuthRequired();
        }
        return settings;
    }

    private static boolean sameApiOrigin(DataSource saved, ApiSettings settings) {
        try {
            URI savedUri = URI.create(saved.getApiUrl());
            URI uri = URI.create(settings.url().trim());
            return savedUri.getScheme().equalsIgnoreCase(uri.getScheme())
                    && savedUri.getHost().equalsIgnoreCase(uri.getHost())
                    && effectivePort(savedUri) == effectivePort(uri);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 沿用已保存的密码时，服务器与账号必须与保存时一致：否则改一下主机就能让服务端把已保存的密码发给自己控制的服务器
     * （如要求明文认证的 PostgreSQL），绕过「密码不回显」。改了库名仍是同一台服务器，无需重新填写。
     */
    private static void requirePasswordIfServerChanged(DataSource saved, ConnectionSettings settings) {
        if (settings.hasPassword()) {
            return;
        }
        if (DataSourceType.API.name().equals(saved.getType())
                || !saved.getHost().equals(settings.host())
                || saved.getPort() != settings.port()
                || !saved.getUsername().equals(settings.username())) {
            throw passwordRequired();
        }
    }

    static ForgeException unreachable() {
        return ForgeException.withErrorMessage(DataSourceErrorCode.UNREACHABLE, "Data source is unreachable");
    }

    private static ForgeException passwordRequired() {
        return ForgeException.withErrorMessage(DataSourceErrorCode.PASSWORD_REQUIRED, "Password is required");
    }

    private static ForgeException apiRequired() {
        return ForgeException.withErrorMessage(DataSourceErrorCode.API_REQUIRED, "API request settings are required");
    }

    private static ForgeException apiUrlInvalid() {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.API_URL_INVALID, "API URL must be an absolute http or https URL");
    }

    private static ForgeException apiBodyInvalid() {
        return ForgeException.withErrorMessage(DataSourceErrorCode.API_BODY_INVALID, "API body must be valid JSON");
    }

    private static ForgeException apiBodyNotAllowed() {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.API_BODY_NOT_ALLOWED, "GET request must not have a body");
    }

    private static ForgeException apiAuthRequired() {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.API_AUTH_REQUIRED, "Credentials are required for the selected API authentication");
    }

    private static ForgeException apiHeaderNotAllowed() {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.API_HEADER_NOT_ALLOWED, "Header name is reserved for the HTTP client");
    }

    private static ForgeException databaseRequired() {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.DATABASE_REQUIRED, "Database connection settings are required");
    }

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.NOT_FOUND, "Data source " + id + " not found in current tenant");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
