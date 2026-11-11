package io.saasforge.lowcode.datasource.model;

import java.util.UUID;

public record DatabaseSourceResponse(UUID id, String name, DataSourceType type) {}
