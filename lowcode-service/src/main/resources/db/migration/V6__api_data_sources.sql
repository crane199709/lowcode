-- API 类 Data Source 的请求配置。数据库类的这些列为空串或 NONE，旧行由 DEFAULT 补上。
ALTER TABLE data_sources
    ADD COLUMN api_url varchar(2048) NOT NULL DEFAULT '',
    ADD COLUMN api_method varchar(8) NOT NULL DEFAULT '',
    ADD COLUMN api_body text NOT NULL DEFAULT '',
    ADD COLUMN api_auth varchar(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN api_header_name varchar(128) NOT NULL DEFAULT '',
    ADD COLUMN api_header_value_encrypted text NOT NULL DEFAULT '';

COMMENT ON COLUMN data_sources.host IS '主机名或 IP，连接前按禁止网段检查解析结果；API 类为空';
COMMENT ON COLUMN data_sources.port IS '端口；API 类为 0';
COMMENT ON COLUMN data_sources.database_name IS '库名，Oracle 为服务名；API 类为空';
COMMENT ON COLUMN data_sources.username IS '连接账号；API 类为 Basic 认证用户名，无认证或自定义请求头时为空';
COMMENT ON COLUMN data_sources.password_encrypted IS '数据库密码或 API Basic 密码的 AES-GCM 密文（Base64），任何接口都不回显';
COMMENT ON COLUMN data_sources.params IS '按类型白名单校验过的连接参数，JSON 对象文本；API 类为 {}';
COMMENT ON COLUMN data_sources.api_url IS 'API 请求地址，只允许 http 或 https；连接前按禁止网段检查主机';
COMMENT ON COLUMN data_sources.api_method IS 'API 请求方法，GET 或 POST';
COMMENT ON COLUMN data_sources.api_body IS 'API 的静态 JSON 请求体，仅 POST 使用';
COMMENT ON COLUMN data_sources.api_auth IS 'API 认证方式：NONE、HEADER 或 BASIC';
COMMENT ON COLUMN data_sources.api_header_name IS '自定义认证请求头名称，仅 HEADER 使用';
COMMENT ON COLUMN data_sources.api_header_value_encrypted IS '自定义认证请求头值的 AES-GCM 密文（Base64），任何接口都不回显';
