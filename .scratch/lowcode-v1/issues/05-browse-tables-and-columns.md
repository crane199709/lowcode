# 05: 浏览表与列

**What to build:** 数据库类 Data Source 详情中能浏览其表与列（名称、类型），辅助编写 Dataset SQL。见规格第 3 节「辅助能力」。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 列出当前账号可见的表（含 schema），选中后列出列名与类型
- [ ] 需要 `datasource:view`；对 API 类 Data Source 不提供
- [ ] PostgreSQL 有容器测试；其他类型在 03 完成后补用例
- [ ] 前端浏览面板可被 06 的 SQL 编辑页复用
