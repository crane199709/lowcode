package io.saasforge.lowcode.datasource.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DatasetResponse(
        UUID id,
        String name,
        UUID dataSourceId,
        String dataSourceName,
        String sql,
        String jsonPath,
        List<FieldResponse> fields,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
