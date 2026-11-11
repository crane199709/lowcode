package io.saasforge.lowcode.datasource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

/** 不声明 tenant_id：租户列由 SDK 在 SQL 中注入，业务代码无从读写。 */
@TableName("data_sources")
public class DataSource extends AuditableEntity {
    private UUID id;
    private String name;
    private String type;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    // AES-GCM 密文，明文只在连接时解密，不进入任何响应
    private String passwordEncrypted;
    // 白名单连接参数的 JSON 对象文本
    private String params;
    // API 类：请求地址、方法、静态 JSON 请求体、认证方式与自定义请求头名称
    private String apiUrl;
    private String apiMethod;
    private String apiBody;
    private String apiAuth;
    private String apiHeaderName;
    // AES-GCM 密文；API 自定义请求头的值，明文只在请求时解密
    private String apiHeaderValueEncrypted;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordEncrypted() {
        return passwordEncrypted;
    }

    public void setPasswordEncrypted(String passwordEncrypted) {
        this.passwordEncrypted = passwordEncrypted;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiMethod() {
        return apiMethod;
    }

    public void setApiMethod(String apiMethod) {
        this.apiMethod = apiMethod;
    }

    public String getApiBody() {
        return apiBody;
    }

    public void setApiBody(String apiBody) {
        this.apiBody = apiBody;
    }

    public String getApiAuth() {
        return apiAuth;
    }

    public void setApiAuth(String apiAuth) {
        this.apiAuth = apiAuth;
    }

    public String getApiHeaderName() {
        return apiHeaderName;
    }

    public void setApiHeaderName(String apiHeaderName) {
        this.apiHeaderName = apiHeaderName;
    }

    public String getApiHeaderValueEncrypted() {
        return apiHeaderValueEncrypted;
    }

    public void setApiHeaderValueEncrypted(String apiHeaderValueEncrypted) {
        this.apiHeaderValueEncrypted = apiHeaderValueEncrypted;
    }

    /** 不输出密码密文与请求头值密文。 */
    @Override
    public String toString() {
        return "DataSource[id=" + id + ", name=" + name + ", type=" + type + ", host=" + host + ", port=" + port
                + ", databaseName=" + databaseName + ", apiUrl=" + apiUrl + "]";
    }
}
