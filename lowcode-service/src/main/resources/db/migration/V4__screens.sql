-- 大屏是 Data Resource「screen」：owner_id 与 department_path 供 Data Scope「本人」「本部门」「本部门及下级」过滤。
-- 画布、Widget 与发布快照由后续工单补充；tenant_id 由 SDK 注入，RLS 作为第二道防线。
CREATE TABLE screens (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    owner_id uuid,
    department_path ltree,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE screens IS '大屏';
COMMENT ON COLUMN screens.id IS '大屏 ID，UUIDv7';
COMMENT ON COLUMN screens.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN screens.name IS '大屏名称';
COMMENT ON COLUMN screens.owner_id IS '创建大屏的 Membership ID，供「本人」Data Scope 过滤；系统创建时为 NULL';
COMMENT ON COLUMN screens.department_path IS '创建人主部门的路径，供「本部门」「本部门及下级」Data Scope 过滤；创建人无部门时为 NULL';
COMMENT ON COLUMN screens.created_at IS '创建时间';
COMMENT ON COLUMN screens.updated_at IS '最后更新时间';
COMMENT ON COLUMN screens.created_by IS '创建人 ID，系统写入时为 NULL';
COMMENT ON COLUMN screens.updated_by IS '最后更新人 ID，系统写入时为 NULL';
COMMENT ON COLUMN screens.deleted IS '逻辑删除标记，true 表示对常规查询不可见';
CREATE INDEX screens_tenant_id_idx ON screens (tenant_id);
CREATE INDEX screens_owner_id_idx ON screens (owner_id);
-- GiST 索引支持 ltree 的 <@ 子树查询（「本部门及下级」）
CREATE INDEX screens_department_path_idx ON screens USING gist (department_path);

-- FORCE 让表所有者（迁移账号）也受 RLS 约束，否则以迁移账号直连即可绕过隔离。
ALTER TABLE screens ENABLE ROW LEVEL SECURITY;
ALTER TABLE screens FORCE ROW LEVEL SECURITY;

-- 会话变量由 SDK 在每次取连接时设置；未设置或置空时为 NULL，任何行都不可见、不可写。
CREATE POLICY screens_tenant_isolation ON screens
    USING (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid))
    WITH CHECK (tenant_id = CAST(nullif(current_setting('app.tenant_id', true), '') AS uuid));
