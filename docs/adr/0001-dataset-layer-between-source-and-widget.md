# Data Source 与 Widget 之间设 Dataset 层，Widget 只按 Field 取数

Widget 不直接绑定 Data Source 写查询，而是绑定一个 Dataset（一个 Data Source 上的一段 SQL 或一个 API 请求），按 Dataset 推断出的 Field 选择 Dimension、Measure 与聚合方式，由服务端在 Dataset 查询外层生成分组聚合查询。原因是拖放配图、汇总表和统一的过滤排序都需要一个带类型的字段模型；没有这一层，每个 Widget 都得手写聚合 SQL，非 SQL 的 API 数据源也无法与数据库共用同一套配置方式。代价是 Dataset 的修改即时影响所有引用它的 Widget，包括已发布的 Screen。

## Considered Options

- Widget 直接绑定 Data Source 并各写一段查询：实现最简单，但字段与聚合无法可视化配置，同一查询在多个 Widget 间重复，且 API 数据源只能逐个 Widget 处理 JSON。
- 完整语义层（多表关联建模、计算字段、跨源关联）：表达力最强，但一期只验证平台承载能力，不承担 BI 建模的复杂度；当前单源单查询的 Dataset 可在不改 Widget 配置的前提下向它演进。
