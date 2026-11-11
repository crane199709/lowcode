package io.saasforge.lowcode.screen.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ScreenErrorCode implements ErrorCode {
    /** 其他租户、数据范围外与不存在的大屏同样返回本错误，不泄露它是否存在。 */
    NOT_FOUND("screen.notFound", 404),
    WIDGET_NOT_FOUND("screen.widgetNotFound", 404),
    WIDGET_CONFIG_INVALID("screen.widgetConfigInvalid", 400),
    INVALID_IMAGE("screen.invalidImage", 400);

    private final String code;
    private final int httpStatus;

    ScreenErrorCode(String code, int httpStatus) {
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
