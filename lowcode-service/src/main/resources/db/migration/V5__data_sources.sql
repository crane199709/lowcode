-- Data Source 只做功能权限，不是 Data Resource；tenant_id 由 SDK 注入，RLS 作为第二道防线。
-- 只存结构化连接字段，JDBC URL 在连接时由服务端拼装（ADR-0002）。
CREATE TABLE data_sources (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    type varchar(32) NOT NULL,
    host varchar(253) NOT NULL,
    port integer NOT NULL,
    database_name varchar(128) NOT NULL,
    username varchar(128) NOT NULL,
    password_encrypted text NOT NULL,
    params text NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE data_sources IS '数据源：Tenant 登记的外部数据连接';
COMMENT ON COLUMN data_sources.id IS '数据源 ID，UUIDv7';
COMMENT ON COLUMN data_sources.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN data_sources.name IS '数据源名称';
COMMENT ON COLUMN data_sources.type IS '数据源类型，如 POSTGRESQL';
COMMENT ON COLUMN data_sources.host IS '主机名或 IP，连接前按禁止网段检查解析结果';
COMMENT ON COLUMN data_sources.port IS '端口';
COMMENT ON COLUMN data_sources.database_name IS '库名，Oracle 为服务名';
COMMENT ON COLUMN data_sources.username IS '连接账号';
COMMENT ON COLUMN data_sources.password_encrypted IS '连接密码的 AES-GCM 密文（Base64），任何接口都不回显';
COMMENT ON COLUMN data_sources.params IS '按类型白名单校验过的连接参数，JSON 对象文本';
COMMENT ON COLUMN data_sources.created_at IS '创建时间';
COMMENT ON COLUMN data_sources.updated_at IS '最后更新时间';
COMMENT ON COLUMN data_sources.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN data_sources.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN data_sources.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE INDEX data_sources_tenant_id_idx ON data_sources (tenant_id);

-- FORCE 让表所有者（迁移账号）也受 RLS 约束，否则以迁移账号直连即可绕过隔离。
ALTER TABLE data_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_sources FORCE ROW LEVEL SECURITY;

-- 会话变量由 SDK 在每次取连接时设置；未设置或置空时为 NULL，任何行都不可见、不可写。
CREATE POLICY data_sources_tenant_isolation ON data_sources
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
