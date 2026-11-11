# 11: 多方言聚合与日期分组

**What to build:** 绑定任一数据库类 Data Source 的 Widget 都能正确聚合，日期 Dimension 能按日 / 月 / 年分组。见规格第 5 节「查询能力」。

**Blocked by:** 03, 10

**Status:** ready-for-agent

- [ ] 分组聚合查询覆盖 MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse
- [ ] 日期 Dimension 按日 / 月 / 年分组，各方言结果一致
- [ ] Top N 在各方言下正确（含 Oracle、SQL Server 的分页写法）
- [ ] 每种方言有容器测试；Oracle 在独立 profile 中运行
