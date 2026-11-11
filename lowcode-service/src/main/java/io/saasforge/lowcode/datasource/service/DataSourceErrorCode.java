package io.saasforge.lowcode.datasource.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum DataSourceErrorCode implements ErrorCode {
    /** 其他租户、已删除与不存在的数据源同样返回本错误，不泄露它是否存在。 */
    NOT_FOUND("datasource.notFound", 404),
    TABLE_NOT_FOUND("datasource.tableNotFound", 404),
    METADATA_NOT_SUPPORTED("datasource.metadataNotSupported", 400),
    PARAM_NOT_ALLOWED("datasource.paramNotAllowed", 400),
    DATABASE_INVALID("datasource.databaseInvalid", 400),
    DATABASE_REQUIRED("datasource.databaseRequired", 400),
    PASSWORD_REQUIRED("datasource.passwordRequired", 400),
    API_REQUIRED("datasource.apiRequired", 400),
    API_URL_INVALID("datasource.apiUrlInvalid", 400),
    API_BODY_INVALID("datasource.apiBodyInvalid", 400),
    API_BODY_NOT_ALLOWED("datasource.apiBodyNotAllowed", 400),
    API_AUTH_REQUIRED("datasource.apiAuthRequired", 400),
    API_HEADER_NOT_ALLOWED("datasource.apiHeaderNotAllowed", 400),
    HOST_BLOCKED("datasource.hostBlocked", 400),
    /**
     * 主机无法解析、拒绝连接、超时与非数据库端口都归为本错误：区分它们等于告诉调用方端口是否开放，可被用来探测网络。
     */
    UNREACHABLE("datasource.unreachable", 422),
    AUTH_FAILED("datasource.authFailed", 422),
    DATABASE_NOT_FOUND("datasource.databaseNotFound", 422),
    CONNECTION_FAILED("datasource.connectionFailed", 422),
    /** 接口主动返回 3xx：不跟随重定向，否则租户可把请求引向被禁止的地址。 */
    API_REDIRECT("datasource.apiRedirect", 422),
    /** 响应体超过配置上限，避免一次请求耗尽服务内存。 */
    API_RESPONSE_TOO_LARGE("datasource.apiResponseTooLarge", 422),
    API_UNREACHABLE("datasource.apiUnreachable", 422),
    API_AUTH_FAILED("datasource.apiAuthFailed", 422),
    /** 目标接口返回其他非 2xx 状态；状态码本身是该接口返回的，不涉及平台网络细节。 */
    API_HTTP_ERROR("datasource.apiHttpError", 422);

    private final String code;
    private final int httpStatus;

    DataSourceErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
