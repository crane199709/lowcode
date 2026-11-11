# 11: 多方言聚合与日期分组

**What to build:** 绑定任一数据库类 Data Source 的 Widget 都能正确聚合，日期 Dimension 能按日 / 月 / 年分组。见规格第 5 节「查询能力」。

**Blocked by:** 03, 10, 18

**Status:** ready-for-agent

- [ ] 分组聚合查询覆盖 MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse
- [ ] 日期 Dimension 按日 / 月 / 年分组，各方言结果一致
- [ ] Top N 在各方言下正确（含 Oracle、SQL Server 的分页写法）
- [ ] 每种方言有容器测试；Oracle 在独立 profile 中运行

## Comments

### 依赖补正（2026-11-11）

ClickHouse 连接实现已从 03 拆为 18；本工单的 ClickHouse 聚合需沿用 18 的 HTTP 查询入口，因此显式加入依赖，避免只等待 03、10 就开始多方言实现。17 的全链路验收同步补齐 18 依赖。
