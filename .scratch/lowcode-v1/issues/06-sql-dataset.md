# 06: SQL 数据集

**What to build:** 持 `dataset:manage` 的 Membership 能在一个数据库类 Data Source 上写一段 SQL 定义 Dataset，预览结果并调整推断出的 Field。见规格第 4 节、ADR-0001。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 只接受单条 `SELECT` / `WITH`，其他语句与多语句被拒绝并返回 Error Code
- [ ] 执行时连接只读，有查询超时与最大行数；界面提示使用只读账号
- [ ] 保存时执行预览查询，从结果集元数据推断 Field：数值类型默认 Measure，其余 Dimension
- [ ] 可修改 Field 显示名、切换 Dimension / Measure、标记为日期
- [ ] Dataset 的列表、新增、编辑、删除（逻辑删除）；`dataset:view` 只读
- [ ] PostgreSQL 容器测试覆盖只读拦截、超时、行数上限与 Field 推断
