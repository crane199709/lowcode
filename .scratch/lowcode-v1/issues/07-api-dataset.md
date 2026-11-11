# 07: API 数据集

**What to build:** 能在 API 类 Data Source 上定义 Dataset：用 JSON 路径定位结果数组，从样本数据推断 Field。见规格第 4 节。

**Blocked by:** 04, 06

**Status:** ready-for-agent

- [ ] JSON 路径定位数组，路径无效或结果不是数组时给出可读错误
- [ ] 从样本推断 Field 类型，默认的 Dimension / Measure 规则与 SQL 数据集一致
- [ ] 复用 06 的 Field 编辑界面
- [ ] 受最大行数约束；用 mock HTTP 服务测试
