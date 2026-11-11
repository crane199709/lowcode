package io.saasforge.lowcode.datasource.model;

import java.util.UUID;

public record DatasetSourceResponse(UUID id, String name, DataSourceType type) {}
