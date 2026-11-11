package io.saasforge.lowcode.datasource.model;

/** API 测试连接的结果：目标接口的 HTTP 状态码，便于确认请求真实到达。 */
public record ApiConnectionTestResponse(int status) {}
