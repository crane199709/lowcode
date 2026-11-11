package io.saasforge.lowcode.datasource.model;

/** API 数据源的认证方式：无认证、自定义请求头、HTTP Basic。 */
public enum ApiAuthType {
    NONE,
    HEADER,
    BASIC
}
