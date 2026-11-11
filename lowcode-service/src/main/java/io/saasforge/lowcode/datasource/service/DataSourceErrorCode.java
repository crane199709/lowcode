package io.saasforge.lowcode.datasource.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum DataSourceErrorCode implements ErrorCode {
    /** 其他租户、已删除与不存在的数据源同样返回本错误，不泄露它是否存在。 */
    NOT_FOUND("datasource.notFound", 404),
    PARAM_NOT_ALLOWED("datasource.paramNotAllowed", 400),
    PASSWORD_REQUIRED("datasource.passwordRequired", 400),
    HOST_BLOCKED("datasource.hostBlocked", 400),
    /**
     * 主机无法解析、拒绝连接、超时与非数据库端口都归为本错误：区分它们等于告诉调用方端口是否开放，可被用来探测网络。
     */
    UNREACHABLE("datasource.unreachable", 422),
    AUTH_FAILED("datasource.authFailed", 422),
    DATABASE_NOT_FOUND("datasource.databaseNotFound", 422),
    CONNECTION_FAILED("datasource.connectionFailed", 422);

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
