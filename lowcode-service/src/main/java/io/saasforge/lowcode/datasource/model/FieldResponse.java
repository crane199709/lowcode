package io.saasforge.lowcode.datasource.model;

public record FieldResponse(String name, String displayName, FieldType type, FieldRole role, boolean date) {}
