package io.saasforge.lowcode.datasource.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum DatasetErrorCode implements ErrorCode {
    NOT_FOUND("dataset.notFound", 404),
    SQL_NOT_ALLOWED("dataset.sqlNotAllowed", 400),
    SOURCE_NOT_SUPPORTED("dataset.sourceNotSupported", 400),
    JSON_PATH_INVALID("dataset.jsonPathInvalid", 400),
    API_JSON_INVALID("dataset.apiJsonInvalid", 422),
    API_RESULT_NOT_ARRAY("dataset.apiResultNotArray", 422),
    API_AGGREGATION_TOO_MANY_ROWS("dataset.apiAggregationTooManyRows", 422),
    API_ROWS_INVALID("dataset.apiRowsInvalid", 422),
    QUERY_DEFINITION_INVALID("dataset.queryDefinitionInvalid", 400),
    QUERY_VALUE_INVALID("dataset.queryValueInvalid", 422),
    QUERY_DATE_INVALID("dataset.queryDateInvalid", 422),
    QUERY_INVALID("dataset.queryInvalid", 400),
    FIELD_MISSING("dataset.fieldMissing", 422),
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
