ALTER TABLE screens ADD COLUMN draft text NOT NULL DEFAULT '{"designWidth":1920,"designHeight":1080,"backgroundColor":"#101827","widgets":[]}';
COMMENT ON COLUMN screens.draft IS '大屏草稿，JSON 格式的设计分辨率、背景与组件布局配置；修改分辨率不换算坐标';
