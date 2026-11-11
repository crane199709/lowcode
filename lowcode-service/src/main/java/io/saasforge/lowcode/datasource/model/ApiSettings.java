package io.saasforge.lowcode.datasource.model;

/**
 * API 类 Data Source 的请求配置。URL 由服务端解析并检查主机，不在这里拼装。
 *
 * @param headerValue 自定义请求头的值；编辑与测试已保存的数据源时为空表示沿用已保存的值
 * @param password Basic 认证密码；编辑与测试已保存的数据源时为空表示沿用已保存的密码
 */
public record ApiSettings(
        String url,
        ApiMethod method,
        String body,
        ApiAuthType authType,
        String headerName,
        String headerValue,
        String username,
        String password) {
    public boolean hasHeaderValue() {
        return headerValue != null && !headerValue.isEmpty();
    }

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    public ApiSettings withUrl(String url) {
        return new ApiSettings(url, method, body, authType, headerName, headerValue, username, password);
    }

    public ApiSettings withHeaderValue(String headerValue) {
        return new ApiSettings(url, method, body, authType, headerName, headerValue, username, password);
    }

    public ApiSettings withPassword(String password) {
        return new ApiSettings(url, method, body, authType, headerName, headerValue, username, password);
    }

    /** 不输出请求头值与密码，避免写入日志或审计事件。 */
    @Override
    public String toString() {
        return "ApiSettings[url=" + url + ", method=" + method + ", authType=" + authType + ", headerName=" + headerName
                + ", username=" + username + "]";
    }
}
