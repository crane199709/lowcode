package io.saasforge.lowcode.datasource.model;

import java.util.List;

public record SqlPreviewResponse(List<FieldResponse> fields, List<List<Object>> rows, boolean truncated, int maxRows) {}
