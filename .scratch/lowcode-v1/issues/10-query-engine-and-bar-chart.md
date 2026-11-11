# 10: 取数引擎与基础柱状图

**What to build:** 在编辑器中放一个基础柱状图，绑定 SQL 数据集，拖入 Dimension 与 Measure 即可出图；Playback 时按刷新间隔重新取数。见规格第 5 节、ADR-0001。

**Blocked by:** 06, 09

**Status:** resolved

- [x] 服务端在 Dataset 查询外层生成分组聚合查询（本工单实现 PostgreSQL 方言）
- [x] 聚合：求和、平均、计数、去重计数、最大、最小；排序与 Top N
- [x] 静态过滤：等于、包含于、区间
- [x] `screen` 功能包只经 `datasource` 的 `service` 层取数，`ArchitectureTest` 通过
- [x] 柱状图属性面板与 ECharts 渲染；ECharts 由 Sub-App 自行打包
- [x] 刷新间隔：关闭或不少于 10 秒，Playback 按间隔重新取数
- [x] 取数接口按 `screen:view` 与 Screen 可见性鉴权，不能借取数接口读取不可见 Screen 的数据
- [x] 容器测试覆盖各聚合、排序、Top N、过滤的结果正确性


## 页面简报与验收边界

- 设计者持 `screen:edit`，在现有大屏工作台添加基础柱状图、选择 Dataset、将 Field 拖入 Dimension / Measure 区并保存草稿；查看者持 `screen:view` 播放可见的已发布 Screen。保存草稿仍是唯一实心主操作，添加组件、字段拖放和属性调整为辅助操作。
- 属性面板沿用现有位置与尺寸区，下接 Dataset、可拖动 Field、维度与度量放置区；聚合、排序、Top N、静态过滤和刷新位于同一可滚动面板。画布即时展示结果，单个组件取数错误只占用该组件。
- 测试边界沿用需求方已授权的 Screen HTTP API + 真实 PostgreSQL 容器，以及 Playwright 页面交互与视觉回归。Browser plugin not available，使用本机 Chromium / `http://127.0.0.1:5190`，桌面 1440×900、笔记本 1024×768。

## Answer

已完成 PostgreSQL 分组聚合与基础柱状图。执行顺序为 Field 过滤、分组聚合、输出排序、Top N；SQL 聚合处理完整 Dataset 结果，不在预览截断后的行上计算。Field 名只从当前保存的 Dataset 校验通过后引用并加双引号转义，过滤值与 Top N 经 PreparedStatement 绑定。继续使用既有固定目标连接、只读事务、回滚、查询超时及结果行数上限。

### 公开契约与后续工单接力

- `datasource.service.DatasetQueryService.query(UUID, Query)` 是跨功能取数入口；`screen` 不依赖 datasource 的 model/entity/mapper。`Query` 包含 `dimensions:[{field}]`、`measures:[{field,aggregation}]`、`filters:[{field,operator,values}]`、`sorts:[{column,direction}]`、可空 `topN`。`column` 为维度后接度量的零基输出索引，同 Field 多次聚合也不歧义；UI 移除轴 Field 或修改聚合时重置排序。
- 聚合为 `SUM/AVG/COUNT/COUNT_DISTINCT/MAX/MIN`。COUNT 对所选 Field 的非空值计数，去重计数同样排除 null；其余聚合忽略 null，无非空输入时返回 null；无 Dimension 的空输入聚合仍返回一行，带 Dimension 的空输入返回空行集。SUM/AVG/MAX/MIN 当前要求 NUMBER，COUNT 两类可用于其他类型。
- 过滤为 `EQ/IN/BETWEEN`；按照当前 Field 类型解析值。EQ(null) 表示 IS NULL，IN 中 null 可匹配空值；IN 不能为空；BETWEEN 为含两端区间，要求两个非空且同类型的有效取值、下限不大于上限。排序 ASC/DESC 均 NULLS LAST，未指定的 Dimension 作为稳定决胜顺序。
- 结果为 `{columns:[{id,label,type}],rows,truncated,maxRows}`；列标识为 `d0…` / `m0…`，行位置与 columns 一致。屏幕 HTTP 使用 `ScreenQueryService.WidgetData/OutputColumn` 自有投影，保持相同语义与明确 schema；ArchitectureTest 不允许 ScreenController 的泛型依赖其他功能包，故在 screen.service 完成必要投影。
- `GET /screens/{id}/published/widgets/{widgetId}/query` 要求 `screen:view`，先读取 Tenant/Data Scope 内的已发布快照，再从服务器快照提取 Widget、Dataset 和 Query。客户端不能指定任意 Dataset 或 Query 覆盖快照；未发布或不可见 Screen 均 404，未知 Widget 为 `screen.widgetNotFound`。
- `POST /screens/{id}/draft/query` 要求 `screen:edit`，body 为编辑中尚未保存的完整 Widget，先校验当前 Screen 可见。`GET /screens/{id}/draft/datasets?page&size` 同样要求 edit 与 Screen 可见，分页返回 Dataset 标识、名称和当前 Field，不返回 SQL、连接或凭据。
- BAR config 为 `{datasetId,query,refreshSeconds}`。ScreenDraft 支持 `TEXT|IMAGE|BAR`，保存时拒绝坏查询形状；刷新仅允许 0 或至少 10 秒。Playback 在上次请求完成后按间隔取数；配置变化/卸载清理定时器并丢弃旧响应，位置与尺寸变化只触发 ECharts resize。ECharts 6.1.0 已由 Sub-App 本地打包，不进入共享依赖。
- 11：可在 Dimension 增加可空的日期分组配置，扩展 DataSourceService.aggregate 与专用连接器；当前仅 PostgreSQL 分支开放，不把本实现直接套到其他方言。原 SQL 由 SqlQueryGuard 保留 SELECT/WITH 只读解析约束，再以合法子查询组合；pgJDBC 的问号运算符仅按语法 token 转义，字符串与引用标识符保持原文（官方约定见 [pgJDBC 查询文档](https://jdbc.postgresql.org/documentation/query/)）。
- 12：DatasetQueryService 已读取当前 Dataset，API 执行可在此分派并传递 jsonPath；需使用完整且未截断的有界 API 输入，沿用本工单的 NULL、过滤、排序与 Top N 语义，不能将预览截断的行当作完整输入。
- 13：`ScreenWidgetContent.vue` / `ScreenCanvas.vue` 透传可选 `queryBase`（编辑 `/draft`、播放 `/published`）；`ScreenBarChart.vue` 已负责取数、刷新、旧响应隔离与 resize，属性模型在 `widget-query.ts` / `ScreenBarProperties.vue`，可按实际新图表组合扩展，无 registry。
- 14：后端允许无 Dimension 的聚合，可直接支持指标值；明细表需显式扩展“不聚合”的 Query 模式，当前 measures 不能为空，不能把明细误做 GROUP BY。
- 16：查询每次读取当前 Field，维度、度量、过滤任一 Field 失效均返回 `dataset.fieldMissing` / 422，文案“字段不存在，请重新配置组件”；前端局部显示该错误，其他 Widget 继续正常渲染。Dataset 引用位置为 BAR 的 `config.datasetId`，同时检查 draft/published 仍属于 16。

### 验证与设计走查

- TDD 记录：首次 BAR 保存因不支持类型返回 400；目录缺失返回 request.notFound；坏查询形状原可保存为 200；PG JSON 问号运算符原返回 queryFailed；页面原缺少“添加柱状图”。均先观察红灯再实现/修复并复测。日志 `/private/tmp/lowcode10-red-aggregate.log`、`lowcode10-red-catalog.log`、`lowcode10-red-invalid-config.log`、`lowcode10-red-json-operator.log`、`lowcode10-red-ui.log`。
- `ScreenQueryApiTest` 8 个 HTTP 用例连接真实 PostgreSQL/Redis，使用字面量结果覆盖全部六种聚合、EQ/IN/BETWEEN、排序/Top N、完整输入不受三行预览上限影响、空输入/全 null、NULL 过滤、特殊引号 Field、绑定注入值、JSON ? 运算符与 ? 字符串；还覆盖当前 Field 修改立即影响已发布查询、字段缺失各引用位置、只 edit 目录、view 独立权限、SELF/DEPARTMENT/Tenant 越界、未发布、未知 Widget、快照不随草稿改变与任意 Dataset 参数绕过尝试、刷新与配置校验。
- 最终 `./mvnw spotless:apply verify -Pslow-dialects`：127 tests，0 failures/errors/skips，含 Oracle、其他既有实际外部数据库、架构、SchemaComment、Spotless。日志 `/private/tmp/lowcode10-backend-verify-final.log`。
- `pnpm build` 与 `pnpm format:check` 通过，含隔离 lint、style token、类型检查。日志 `/private/tmp/lowcode10-ui-build-final.log`、`lowcode10-ui-format-final.log`。ECharts 本地打包使主要 chunk 约 594 kB，构建仅有体积提示。
- 最终完整 Playwright：94 tests 全过，日志 `/private/tmp/lowcode10-ui-full.log`。9 个新增用例验证真实字段 dragTo 出图与保存、聚合/过滤/排序/Top N/最小刷新间隔、10秒刷新边界、关闭刷新、返回列表停止取数、较早响应不覆盖新配置、尺寸修改不触发查询、两档实际 SVG 图表，以及字段失效时其他组件仍显示。
- 页面身份、非空、无框架错误覆盖层、正常图表 console warning/error、交互状态均通过。新增两档播放/编辑/字段失效截图共 6 张，均逐张查看；编辑器入口变化的既有基线 6 张同步刷新并逐张查看，合计走查 12 张，布局与可滚动属性操作可达。精确基线更新后的 Screen/图片相关 13 tests 再次正常比对通过，日志 `/private/tmp/lowcode10-ui-baseline-final.log`。
- 未验证真实 Shell/网关、部署环境、移动端或 Firefox/WebKit；UI 业务接口使用 mock，SQL 计算结果由真实数据库 HTTP 用例验证。多方言、日期分组、API 聚合及其他 Widget 留给后续工单。
