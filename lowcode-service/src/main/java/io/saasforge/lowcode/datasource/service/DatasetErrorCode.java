package io.saasforge.lowcode.datasource.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum DatasetErrorCode implements ErrorCode {
    NOT_FOUND("dataset.notFound", 404),
    SQL_NOT_ALLOWED("dataset.sqlNotAllowed", 400),
    SOURCE_NOT_SUPPORTED("dataset.sourceNotSupported", 400),
    FIELD_INVALID("dataset.fieldInvalid", 400),
    QUERY_TIMEOUT("dataset.queryTimeout", 422),
    QUERY_FAILED("dataset.queryFailed", 422);
    private final String code;
    private final int httpStatus;

    DatasetErrorCode(String code, int httpStatus) {
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
