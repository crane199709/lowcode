package io.saasforge.lowcode.datasource.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FieldOverrideRequest(
        @NotBlank String name,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull FieldRole role,
        boolean date) {}
