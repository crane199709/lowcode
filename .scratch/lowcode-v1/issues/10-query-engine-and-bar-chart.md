# 10: 取数引擎与基础柱状图

**What to build:** 在编辑器中放一个基础柱状图，绑定 SQL 数据集，拖入 Dimension 与 Measure 即可出图；Playback 时按刷新间隔重新取数。见规格第 5 节、ADR-0001。

**Blocked by:** 06, 09

**Status:** claimed

- [ ] 服务端在 Dataset 查询外层生成分组聚合查询（本工单实现 PostgreSQL 方言）
- [ ] 聚合：求和、平均、计数、去重计数、最大、最小；排序与 Top N
- [ ] 静态过滤：等于、包含于、区间
- [ ] `screen` 功能包只经 `datasource` 的 `service` 层取数，`ArchitectureTest` 通过
- [ ] 柱状图属性面板与 ECharts 渲染；ECharts 由 Sub-App 自行打包
- [ ] 刷新间隔：关闭或不少于 10 秒，Playback 按间隔重新取数
- [ ] 取数接口按 `screen:view` 与 Screen 可见性鉴权，不能借取数接口读取不可见 Screen 的数据
- [ ] 容器测试覆盖各聚合、排序、Top N、过滤的结果正确性
