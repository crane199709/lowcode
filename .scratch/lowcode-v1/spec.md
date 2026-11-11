# 低代码一期：数据源与大屏规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准，平台术语以 SaaS Forge 平台定义为准。本规格的决策经 2026-11-11 的设计拷问逐条确认；架构取舍见 [ADR-0001](../../docs/adr/0001-dataset-layer-between-source-and-widget.md)、[ADR-0002](../../docs/adr/0002-structured-connection-and-blocked-networks.md)。

## 1. 背景与目标

以 icube-base-lowcode 的功能清单为参照（只参考功能，不参考实现），在 SaaS Forge 上搭一个真实规模的业务服务，同时验证平台脚手架（`forge-service-archetype`、`sub-app-kit`）、SDK 与接入指南能否支撑复杂业务。

目标：

- Tenant 能登记自有 Data Source，在其上定义 Dataset，用 Widget 搭建并 Publish Screen，有权限的 Membership 能 Playback。
- 开发中暴露的平台问题（脚手架、SDK、指南）回到平台仓库 `.scratch/lowcode-validation/issues/` 开工单，附证据与复现方式；本仓库工单只放 lowcode 自身的工作。

非目标（二期及以后）：

- 数据源：DB2、达梦、TiDB、Doris、MongoDB、Elasticsearch、Hive。
- Widget：仪表盘、组合图、横向柱状图、南丁格尔玫瑰图、雷达图、矩形树图、散点图、漏斗图、地图、气泡地图。
- 报表配置、匿名分享链接、Widget 联动 / 下钻 / 全局筛选器、查询结果缓存、计算字段与同环比、Screen 版本历史与回滚、多表关联建模与跨源关联。

## 2. 工程形态

- 一个仓库两个子工程：`lowcode-service`（Business Service，`serviceName=lowcode-service`，网关前缀 `/lowcode`）与 `lowcode-sub-app`（Sub-App，应用编码 `lowcode`）。
- 后端两个功能包：`datasource`（Data Source 与 Dataset）、`screen`（Screen 与 Widget）。`screen` 只经 `datasource` 的 `service` 层取数。
- 生成工程自带的订单示例在实现首个功能时移除，Manifest、权限码、Data Resource、菜单、角色模板替换为本服务的声明。
- 前端图表使用 ECharts，由 Sub-App 自行打包，不进入共享依赖。

## 3. Data Source

### 类型

一期：MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse、API（HTTP JSON）。

### 连接与安全

- 数据库类只填结构化字段：主机、端口、库名（Oracle 为服务名）、账号、密码，以及按类型白名单列出的少量连接参数；JDBC URL 由服务端拼装，不接受用户填写的 URL。
- 服务配置必配的禁止网段列表（平台内网 CIDR），漏配启动失败。连接前解析域名，任一解析结果落入禁止网段即拒绝。API 同样适用，且不跟随重定向。
- 密码与 API 敏感头部值加密保存，任何接口都不回显；编辑时留空表示不修改。加密密钥为必配项。
- Data Source 只属于登记它的 Tenant。

### API 类

- 方法 GET、POST；请求体为静态 JSON。
- 认证：无、自定义请求头（如 `Authorization: Bearer …`）、Basic。
- 以 JSON 路径定位结果数组；不分页；有超时与响应体大小上限。

### 辅助能力

- 测试连接：保存前后均可执行，返回成功或可读的失败原因（不泄露内网探测细节，如端口是否开放）。
- 数据库类可浏览表与列，辅助编写 Dataset 的 SQL。

## 4. Dataset

- 一个 Dataset 是「一个 Data Source + 一段 SQL」或「一个 API 类 Data Source 的请求 + JSON 路径」；不支持多表建模与跨源关联。
- SQL 只接受单条 `SELECT` / `WITH`，执行时连接只读，有查询超时（默认 30 秒）与最大行数（明细默认 1 万行）；界面提示使用只读账号。平台不承诺拦截全部写操作，租户账号权限是最终防线。
- 保存时执行一次预览查询推断 Field：SQL 取结果集元数据，API 取样本数据推断类型。数值类型默认为 Measure，其余为 Dimension。用户可改显示名、切换 Dimension / Measure、把 Field 标为日期。
- API 类 Dataset 无法下推查询，聚合在服务端内存中完成，受最大行数约束。

## 5. Widget

### 一期类型

| 分组 | Widget | 取数 |
|---|---|---|
| 表格 | 明细表、汇总表 | 是 |
| 指标 | 指标卡、文本卡 | 指标卡是，文本卡否 |
| 趋势 | 基础折线图 | 是 |
| 比较 | 基础柱状图、堆叠柱状图 | 是 |
| 分布 | 饼图（含环形开关） | 是 |
| 其他 | 图片 | 否 |

### 查询能力

取数类 Widget 选择一个 Dataset，配置：

- Dimension 与 Measure 的拖放；Measure 聚合：求和、平均、计数、去重计数、最大、最小。
- 日期 Dimension 按日 / 月 / 年分组（各数据库方言分别实现）。
- 排序与 Top N。
- 静态过滤：Field + 运算符（等于、包含于、区间）+ 取值。
- 自动刷新间隔：关闭或不少于 10 秒。

服务端在 Dataset 查询外层生成分组聚合查询，按 Data Source 类型使用对应方言。

## 6. Screen

### 画布

- 自由画布：Widget 以 Design Resolution 下的像素坐标绝对定位，可叠放。
- Design Resolution 取预设（1920×1080、2560×1440、3840×2160、1366×768）或自定义宽高（640–7680）；创建后可修改，已有 Widget 坐标不换算。
- Playback 缩放方式：等比适配（默认）、铺满拉伸、按宽度适配。
- 背景色与背景图；图片 Widget 与背景图经 Storage Purpose `screen-image` 上传，限定图片类型与大小。

### 编辑器

拖拽、缩放、网格吸附、层级调整、删除、复制 Widget、撤销 / 重做、复制整个 Screen。不做多选、编组、对齐辅助线、缩略图、导入导出。

### 草稿与发布

- 编辑器保存草稿；Publish 把草稿冻结为已发布快照，覆盖上一份，不保留历史。
- 快照冻结布局与 Widget 配置，不冻结 Dataset：Dataset 修改即时影响已发布 Screen，失效 Widget 在 Playback 时显示「字段不存在」。
- 查看者只看到已发布快照；未发布过的 Screen 对查看者不可见。

### Playback

已登录、有权限的 Membership 在 Shell 内打开 Playback 页，可用浏览器全屏 API 隐藏外壳；各 Widget 按自身刷新间隔重新取数。

### 视觉约束

编辑器外围界面（列表、属性面板、工具栏）遵守 `docs/standards/sub-app.md`；画布与 Widget 的视觉属性是用户数据，不受 `--forge-*` token 约束。lint 若误报，记为平台反馈。

## 7. 删除与引用

- Data Source 被 Dataset 引用、Dataset 被 Screen（草稿或已发布快照）引用时禁止删除，返回引用清单与对应 Error Code。
- 删除一律逻辑删除。

## 8. 权限、审计与开通

- Permission Code：`datasource:view`、`datasource:manage`、`dataset:view`、`dataset:manage`、`screen:view`、`screen:edit`、`screen:publish`。
- Data Resource `screen`，资源属性：创建人、所属部门，使「本人」「本部门」等 Data Scope 可限制可见的 Screen。Data Source 与 Dataset 只做功能权限。
- 角色模板：「大屏设计者」（全部权限码）、「大屏查看者」（`screen:view`）。
- 菜单：分组「低代码」下设「数据源」「数据集」「大屏」三个叶子，均指向 Sub-App `lowcode`。
- Audit Event：Data Source 新增 / 修改 / 删除，Screen 发布。
- Provisioning 不预置任何 Data Source、Dataset 或 Screen。

## 9. 测试

- 后端：Testcontainers 对 MySQL、PostgreSQL、SQL Server、ClickHouse 做真实连接与方言测试，默认 `verify` 运行；Oracle 放独立 profile（如 `-Pslow-dialects`）。API 类用本地 mock HTTP 服务。安全约束（禁止网段、URL 拼装、只读 SQL）各有用例。
- 前端：编辑器与 Playback 页的 Playwright 用例与视觉回归，按 `docs/standards/sub-app.md` 的三步流程走查，走查必须查看截图。

## 10. 验收

本地平台全链路跑通：

1. Operator 在 App Registry 登记 `lowcode`，向套餐开放应用与权限。
2. Tenant 登录看到「低代码」菜单。
3. 登记 Data Source → 定义 Dataset → 搭建 Screen 并 Publish。
4. 持「大屏查看者」的 Membership 能 Playback；无权限或不在其 Data Scope 内的 Membership 看不到该 Screen。

## 11. 已知临时措施

- `lowcode-sub-app/pnpm-workspace.yaml` 用 `overrides` 指向 `.forge-packages/` 中按平台源码打包的 `@crane199709/*@0.2.0-SNAPSHOT`，因包含当前脚手架规范的正式版本尚未发布；必须与实际 Shell 的共享版本一致（平台反馈 01）。平台发布新版本后删除 overrides 与 `.forge-packages/`。

## 12. 实现与验收结论

2026-11-11，一期 [18 个实施工单](issues/) 均已 resolved，范围内能力已实现；[验收记录](acceptance.md) 保留票据 17 的原始真实链路与最终审查修复后的独立复验。最终默认后端回归 183 项、前端视觉 / 交互 130 项及新建隔离平台真实链路 1 项均通过。

[最终审查记录](review.md) 分别保留 Standards / Spec 两轴原报告、修复及原 reviewer 定向复核。P1 Dataset UUID 大小写引用保护已按公开 HTTP 红绿验证修复；纯 API 投影归属、显式 import 和编辑 / 播放样式重复也已关闭。已知平台反馈、临时 snapshot 包、Oracle / 移动端等验证边界以验收记录为准，不扩大一期范围。
