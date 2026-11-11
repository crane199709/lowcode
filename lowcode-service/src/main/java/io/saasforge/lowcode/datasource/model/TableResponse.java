package io.saasforge.lowcode.datasource.model;

/** catalog 与 schema 无值时返回空字符串，保留跨库的表标识供后续列查询使用。 */
public record TableResponse(String catalog, String schema, String name) {}
