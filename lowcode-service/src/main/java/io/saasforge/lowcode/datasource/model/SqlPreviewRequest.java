package io.saasforge.lowcode.datasource.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SqlPreviewRequest(
        @NotNull UUID dataSourceId,
        @NotBlank @Size(max = 65536) String sql) {}
