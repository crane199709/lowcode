package io.saasforge.lowcode.datasource.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record DatasetRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull UUID dataSourceId,
        @Size(max = 65536) String sql,
        @Size(max = 4096) String jsonPath,
        @Size(max = 1000) List<@NotNull @Valid FieldOverrideRequest> fields) {}
