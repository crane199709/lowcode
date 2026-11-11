package io.saasforge.lowcode.datasource.model;

import java.util.List;

public record DatasetPreviewResponse(
        List<FieldResponse> fields, List<List<Object>> rows, boolean truncated, int maxRows) {}
