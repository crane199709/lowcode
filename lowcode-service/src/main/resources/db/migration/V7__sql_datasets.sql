CREATE TABLE datasets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    data_source_id uuid NOT NULL,
    sql_query text NOT NULL,
    fields text NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE datasets IS '数据集：一个数据源上的查询及推断出的 Field';
COMMENT ON COLUMN datasets.id IS '数据集 ID，UUIDv7';
COMMENT ON COLUMN datasets.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN datasets.name IS '数据集名称';
COMMENT ON COLUMN datasets.data_source_id IS '所属数据源 ID';
COMMENT ON COLUMN datasets.sql_query IS '单条只读 SELECT 或 WITH 查询';
COMMENT ON COLUMN datasets.fields IS 'Field 类型及显示配置，JSON 数组文本';
COMMENT ON COLUMN datasets.created_at IS '创建时间';
COMMENT ON COLUMN datasets.updated_at IS '最后更新时间';
COMMENT ON COLUMN datasets.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN datasets.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN datasets.deleted IS '逻辑删除标记';
CREATE INDEX datasets_tenant_id_idx ON datasets (tenant_id);
ALTER TABLE datasets ENABLE ROW LEVEL SECURITY;
ALTER TABLE datasets FORCE ROW LEVEL SECURITY;
CREATE POLICY datasets_tenant_isolation ON datasets
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
