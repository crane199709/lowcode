ALTER TABLE datasets ADD COLUMN json_path text;
COMMENT ON COLUMN datasets.json_path IS 'API 数据集定位结果数组的 JSON 路径，SQL 数据集为空';
COMMENT ON COLUMN datasets.sql_query IS '单条只读 SELECT 或 WITH 查询，API 数据集为空字符串';
