# 14: 表格与指标类组件

**What to build:** 编辑器中能放置并配置明细表、汇总表、指标卡。见规格第 5 节。

**Blocked by:** 10

**Status:** resolved

- [x] 明细表：选择若干 Field，不聚合，受明细最大行数约束并提示
- [x] 汇总表：Dimension 分组 + Measure 聚合
- [x] 指标卡：一个 Measure 的聚合值，可设标题、单位、数字格式
- [x] 视觉用例覆盖每种组件，走查查看截图

## 页面简报与验收边界

- 设计者在大屏工作台添加明细表、汇总表、指标卡，选择 Dataset 并配置 Field；查看者在已发布快照播放。保存草稿仍为唯一实心主操作，组件入口用辅助按钮且在 1024 宽度可达。
- 明细表显示原始所选 Field，提供过滤、排序、Top N、刷新及明确的行数上限状态；汇总表显示 Dimension 与聚合 Measure；指标卡配置一个聚合 Measure、标题、单位和数字格式，单位仅为显示标签。
- 沿用需求方已确认的 Widget HTTP + 真实 PostgreSQL / 本地 HTTP API，以及实际 Playwright UI / 两档截图边界。Browser plugin not available，使用已有 Playwright，端口 5194，1440×900、1024×768；逐张查看修改的基线。
- 使用 implement-spec、tdd 与 frontend-testing-debugging 工作流。先完成后端公开查询切片，待共享图表组件合并后复用其刷新与旧响应隔离逻辑。

## Answer

- 三类取数组件为 `DETAIL_TABLE`、`SUMMARY_TABLE`、`INDICATOR`，进入现有“添加组件”菜单。汇总表要求至少一个 Dimension 和一个 Measure；指标卡要求零 Dimension、一个 Measure，标题不超过 200 字、单位不超过 40 字，数字格式 `AUTO/INTEGER/DECIMAL_2`。单位只显示标签，不做数值转换。
- `DatasetQueryService.Query` 在原五个参数后追加 `mode` 与 `fields`。缺失/null 的 mode 保持 `AGGREGATE`，保留五参数构造器及 `Dimension.dateGrouping`。`DETAIL` 要求 1–64 个不同的当前 Field，维度与度量必须为空；聚合模式不允许混入 fields。列为按选择顺序的 `f0...`，保留当前 Field 显示名和类型；聚合继续使用原 `d0.../m0...` 契约。
- SQL 明细只在既有统一 `SqlAggregation` 添加原始字段选择与排序别名，不生成 GROUP BY；复用五方言源 SQL 组合、引用、参数绑定、过滤、NULLS LAST、Top N、固定目标连接、只读事务、超时与输出行数限制。相同排序值以其余所选字段稳定决胜，保留重复明细行。
- API 明细按当前 Field 名取值，执行过滤、输出排序和 Top N；输入达到最大行数仍返回 `truncated/maxRows` 明确信号。API 聚合仍在任何过滤/Top N 前拒绝不完整原始输入，不能显示部分总数。当前 Field 缺失与失效排序/查询组合继续由服务器拒绝。
- 已发布查询继续仅从 Tenant / Data Scope 内的服务端快照提取 Dataset 和 Query，客户端 URL 的 datasetId 不覆盖快照；草稿预览保持 edit 权限。三类组件由 `isQueryWidget()` 识别，兼容工单 16 的统一引用扫描。
- 新增小型 `useWidgetData` 复用真实图表取数生命周期：配置变化与卸载清理定时器、忽略旧响应，播放在请求完成后刷新，样式/单位/数字格式与布局变化不重新查询。表格用 Element Plus 与每页 50 行切片，10000 行不会同时创建 DOM，NULL 显示“—”，数字右对齐、长文本溢出提示，上限有可读状态。

## 验证记录

- TDD 红灯：`/private/tmp/lowcode14-red-api-detail.log`（DETAIL_TABLE 原返回 400）、`lowcode14-red-pg-detail.log`（旧 SQL 路径无法执行明细）、`lowcode14-red-unified-detail.log`（统一编译器明确返回 dataset.queryFailed）、`lowcode14-red-ui.log`（首次明细配置入口尚未实现）。实现后公开 API + PostgreSQL + Architecture 28 项通过，日志 `/private/tmp/lowcode14-pg-api-green.log`。
- 新增后端公开 HTTP 断言覆盖原始重复行、所选列顺序、SQL / API 上限信号、静态过滤、排序与 Top N、绑定注入值、当前字段不存在、汇总总数、指标单值及非法组合；同一明细契约由五方言测试类继承。没有测试私有 SQL 文本或私有聚合器。
- 前端新增 12 个实际 Playwright 用例，覆盖三类配置保存、字段移除清除排序、指标单度量限制、单位只显示、数字格式变化不重查、NULL、刷新/卸载、10000 行分页后段与 50 行 DOM。完整 118 项通过（集成日期测试前），日志 `/private/tmp/lowcode14-ui-full.log`。同步日期测试后发现其普通柱图测试误用未定义 date，已仅修正两处数据集名称；相关 23 项已全部通过，日志 `/private/tmp/lowcode14-ui-after-sync-final.log`。汇总表列标题夹具同步真实聚合列 schema 后，两档播放与属性截图重生成并逐张验看，2 项正常回归通过，日志 `/private/tmp/lowcode14-ui-summary-columns.log`。
- 新增 14 张基线：三类组件的播放/属性各两档，以及明细上限/分页两档。均逐张查看；属性面板滚动后可达，1024 宽主操作与菜单清晰，长文本/空值/数字对齐正确。根据实际截图调大画布内分页数字与点击区域，并重新生成、逐张查看两张上限基线。已有图表/图片/编辑器基线无需修改。
- Browser plugin not available，使用仓库 Playwright + Chromium，`http://127.0.0.1:5194`，1440×900 与 1024×768。正常页面无空白、框架错误覆盖层或相关 console warning/error，交互均有可见状态断言。

- 完整 `./mvnw verify -Pslow-dialects -DforkCount=1`：185 tests，0 failures/errors/skips；五方言各 8 项公开 Widget HTTP（含本工单的同一 DETAIL 契约），Oracle 实际连接、全部既有数据源回归、ArchitectureTest、SchemaCommentTest、Spotless 全过。日志 `/private/tmp/lowcode14-backend-full-slow.log`。
- 同步集成分支 `ded4b3698b0545188414d50b7d051c86326264b1` 后 `pnpm build`、`pnpm format:check` 通过，包含隔离 lint、style token、类型检查；最新测试夹具格式检查也通过。日志 `/private/tmp/lowcode14-ui-build-after-sync.log`、`lowcode14-ui-format-complete.log`。构建仅保留 ECharts 相关 chunk 体积提示。
- 本工单使用真实后端 HTTP / 真实外部数据库 / 本地 HTTP API；页面接口使用视觉夹具。真实平台网关、注册与套餐开通、用户登录全链路由工单 17 验收，不在本工单重复声明为已完成。
