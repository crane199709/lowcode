# 03: 补齐其余数据库类型

**What to build:** 除 PostgreSQL 外，还能登记并测试连接 MySQL、SQL Server、ClickHouse、Oracle 类 Data Source。见规格第 3 节。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 表单按类型显示对应字段（Oracle 为服务名），各类型有自己的连接参数白名单
- [ ] MySQL、SQL Server、ClickHouse 的真实连接测试随默认 `verify` 运行
- [ ] Oracle 的连接测试放在独立 profile（如 `-Pslow-dialects`），`AGENTS.md` 写明运行方式
- [ ] 各类型的 URL 拼装拒绝已知危险参数（如 MySQL `allowLoadLocalInfile`）
