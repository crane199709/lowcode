ALTER TABLE screens ADD COLUMN published text;
ALTER TABLE screens ADD COLUMN published_name varchar(120);
ALTER TABLE screens ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
COMMENT ON COLUMN screens.published IS '已发布大屏快照，冻结布局与组件配置；再次发布时覆盖';
COMMENT ON COLUMN screens.published_name IS '发布时的大屏名称，避免向查看者泄露尚未发布的改名';
COMMENT ON COLUMN screens.published_at IS '最近一次发布时间，未发布时为 NULL';
