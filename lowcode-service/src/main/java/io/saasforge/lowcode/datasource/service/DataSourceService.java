package io.saasforge.lowcode.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.lowcode.datasource.entity.DataSource;
import io.saasforge.lowcode.datasource.mapper.DataSourceMapper;
import io.saasforge.lowcode.datasource.model.ConnectionSettings;
import io.saasforge.lowcode.datasource.model.DataSourceType;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data Source 的登记与测试连接。租户隔离由 SDK 注入 SQL；连接安全按 ADR-0002：只收结构化字段与白名单参数，
 * 保存与连接前都按禁止网段检查主机，密码加密保存、不出现在任何返回值中。
 */
@Service
public class DataSourceService {
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

    private final DataSourceMapper dataSourceMapper;
    private final NetworkGuard networkGuard;
    private final SecretCipher secretCipher;
    private final ExternalJdbcConnector connector;
    private final JsonMapper jsonMapper;

    DataSourceService(
            DataSourceMapper dataSourceMapper,
            NetworkGuard networkGuard,
            SecretCipher secretCipher,
            ExternalJdbcConnector connector,
            JsonMapper jsonMapper) {
        this.dataSourceMapper = dataSourceMapper;
        this.networkGuard = networkGuard;
        this.secretCipher = secretCipher;
        this.connector = connector;
        this.jsonMapper = jsonMapper;
    }

    public DataSource create(String name, DataSourceType type, ConnectionSettings settings) {
        if (!settings.hasPassword()) {
            throw passwordRequired();
        }
        var dataSource = toEntity(name, type, settings);
        dataSourceMapper.insert(dataSource);
        // 创建时间由数据库生成，重新读取才能返回与之后查询一致的值
        return get(dataSource.getId());
    }

    /** 密码为空表示不修改：实体字段为 null 时 updateById 不更新该列。 */
    public DataSource update(UUID id, String name, DataSourceType type, ConnectionSettings settings) {
        requirePasswordIfServerChanged(get(id), settings);
        var dataSource = toEntity(name, type, settings);
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

    public long count() {
        return dataSourceMapper.selectCount(new LambdaQueryWrapper<>());
    }

    public Map<String, String> paramsOf(DataSource dataSource) {
        return jsonMapper.readValue(dataSource.getParams(), PARAMS);
    }

    /** 测试尚未保存的连接，成功时正常返回，失败时抛出可读原因的错误码。 */
    public void testConnection(DataSourceType type, ConnectionSettings settings) {
        if (!settings.hasPassword()) {
            throw passwordRequired();
        }
        connect(type, settings);
    }

    /** 测试编辑中的已保存数据源：密码为空时用已保存的密码，以便不重新输入密码也能验证其他字段的修改。 */
    public void testConnection(UUID id, DataSourceType type, ConnectionSettings settings) {
        DataSource saved = get(id);
        requirePasswordIfServerChanged(saved, settings);
        connect(
                type,
                settings.hasPassword()
                        ? settings
                        : settings.withPassword(secretCipher.decrypt(saved.getPasswordEncrypted())));
    }

    private void connect(DataSourceType type, ConnectionSettings settings) {
        checkParams(type, settings);
        List<InetAddress> addresses = networkGuard.resolve(settings.host());
        // 与连接失败返回完全相同的错误，不区分主机能否解析
        if (addresses.isEmpty()) {
            throw unreachable();
        }
        connector.testPostgres(addresses.get(0), settings);
    }

    private DataSource toEntity(String name, DataSourceType type, ConnectionSettings settings) {
        checkParams(type, settings);
        // 暂时无法解析的主机允许保存，连接时会再次解析并检查
        networkGuard.resolve(settings.host());
        var dataSource = new DataSource();
        dataSource.setName(name);
        dataSource.setType(type.name());
        dataSource.setHost(settings.host());
        dataSource.setPort(settings.port());
        dataSource.setDatabaseName(settings.database());
        dataSource.setUsername(settings.username());
        dataSource.setParams(jsonMapper.writeValueAsString(settings.params()));
        if (settings.hasPassword()) {
            dataSource.setPasswordEncrypted(secretCipher.encrypt(settings.password()));
        }
        return dataSource;
    }

    private static void checkParams(DataSourceType type, ConnectionSettings settings) {
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
     * 沿用已保存的密码时，服务器与账号必须与保存时一致：否则改一下主机就能让服务端把已保存的密码发给自己控制的服务器
     * （如要求明文认证的 PostgreSQL），绕过「密码不回显」。改了库名仍是同一台服务器，无需重新填写。
     */
    private static void requirePasswordIfServerChanged(DataSource saved, ConnectionSettings settings) {
        if (settings.hasPassword()) {
            return;
        }
        if (!saved.getHost().equals(settings.host())
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

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(
                DataSourceErrorCode.NOT_FOUND, "Data source " + id + " not found in current tenant");
    }
}
