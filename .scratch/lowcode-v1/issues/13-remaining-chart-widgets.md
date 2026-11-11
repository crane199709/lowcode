# 13: 其余图表组件

**What to build:** 编辑器中能放置并配置基础折线图、堆叠柱状图、饼图（含环形开关）。见规格第 5 节。

**Blocked by:** 10

**Status:** resolved

- [x] 折线图：一个 Dimension（常为日期）+ 一个或多个 Measure
- [x] 堆叠柱状图：两个 Dimension（类别与堆叠）+ 一个 Measure
- [x] 饼图：一个 Dimension + 一个 Measure；环形开关设置内半径
- [x] 各组件属性面板只允许合法的 Field 组合，并给出提示
- [x] 视觉用例覆盖每种组件，走查查看截图


## 页面简报与验收边界

- 设计者在现有大屏工作台选择组件、Dataset 和 Field，主任务是配置合法的折线图、堆叠柱状图或饼图，保存草稿仍为唯一实心主操作。添加组件合并为下拉入口，撤销、重做和网格吸附保持可达，避免 1024 宽度工具栏拥挤。
- 属性区复用现有 Dataset、拖放、聚合、排序、Top N、过滤与刷新控制；显示每种组件需要的维度/度量数量，达到数量上限后不再添加，饼图提供环形开关。组件查询和异常仍局限于自身。
- 验收边界已由需求方确认：Widget HTTP API（真实 PostgreSQL）验证新类型与非法组合；Playwright 操作真实 ECharts SVG，覆盖合法配置、显示结果和两档截图（1440×900、1024×768），逐张查看所有新增/变动 PNG。Browser plugin not available，使用仓库 Playwright，独立端口 5193。
- 与工单 11 协调保留属性文件路径直至日期前端提交合入，再统一泛化；不修改日期查询语义、BAR 已有通用聚合语义或 TEXT/IMAGE。


## Answer

新增 LINE、STACKED_BAR、PIE 三种 Widget。LINE 为一个 Dimension 与一个或多个 Measure；STACKED_BAR 为两个不同的 Dimension（类别、堆叠）与一个 Measure；PIE 为一个 Dimension 与一个 Measure，`config.donut` 必须为 boolean，切换内半径仅重绘，不重复取数。

- 服务端在保存草稿及即时查询两处检查组合，`ScreenDraft.Widget.isQueryWidget()` 识别 BAR/LINE/STACKED_BAR/PIE；`ScreenQueryService.Config` 在原 Dataset、Query、刷新配置外增加可空 donut，HTTP 查询结果仍由 screen.service 自有 `WidgetData/OutputColumn` 投影。BAR 的无维度聚合与其他既有通用查询保持可用。
- `ScreenBarChart.vue` 泛化为 `ScreenChart.vue`，继续使用同一个取数、请求世代、刷新定时器、卸载清理和 ResizeObserver。LINE 使用多个系列并保留 NULL 断点；STACKED_BAR 只做结果透视，缺失组合与 NULL 均保留 null；PIE 直接使用查询返回值，未做第二次隐藏聚合。全零时 `stillShowZeroSum:false`，不会虚构等分扇区。
- `ScreenBarProperties.vue` 泛化为 `ScreenChartProperties.vue`，复用 Dataset、拖放、聚合、过滤、排序、Top N 与刷新。新图表显示合法数量提示，达到上限后禁止继续添加；非数值 Measure 只允许 COUNT/COUNT_DISTINCT。日期控件承接工单 11 的 `ae0e952`，保持其 `dateGrouping?: DAY|MONTH|YEAR` 契约。
- 工具栏使用“添加组件”菜单；文本卡、图片、柱状图与三个新类型从同一入口添加，1024 宽度保持可读。TEXT/IMAGE 渲染及图片地址入口保留。交互测试等待菜单关闭后继续，避免关闭时恢复焦点打断紧接着打开的属性选择器；属性控件先滚入视区。

### 验证与设计走查

- TDD：三种类型首次保存均返回 400，开放后保存/发布/查询成功；三种类型首次页面均缺少对应菜单入口。非数值度量“求和”原可用，收紧后禁用；全零饼图原有两个实际扇区，修正后不存在大扇区。堆叠图 tooltip 中 NULL 与缺失组合显示空值，未变为零。红绿日志 `/private/tmp/lowcode13-{red,green}-*.log`、`lowcode13-red-zero-pie-geometry.log`、`lowcode13-green-edge-charts.log`。
- 完整后端 `./mvnw spotless:apply verify`：124 tests，0 failures/errors/skips，含默认多数据库、架构、SchemaComment、Spotless。日志 `/private/tmp/lowcode13-backend-verify.log`。未改外部连接配置或驱动，未重复 slow-dialects。
- 承接集成分支 c101bf2 后完整 ScreenQueryApiTest 再次通过，13 tests，0 failures/errors/skips，日志 `/private/tmp/lowcode13-backend-after-integration-final.log`。
- 完整 Playwright 正常比对 **106 tests 全过**，日志 `/private/tmp/lowcode13-ui-full-final.log`。其中新增 12 个用例覆盖三个组件实际 SVG、合法组合与保存、环形仅重绘、非数值聚合限制、全零饼图、稀疏/NULL 堆叠以及两宽播放与属性面板；既有 BAR 刷新/旧响应/resize、TEXT、IMAGE 回归继续通过。
- 新增 12 张图表截图，更新工具栏影响的 BAR、文本、图片和空画布 8 张截图，共 20 张均逐张查看。1440×900 与 1024×768 下主操作、菜单入口与属性操作可达，没有重叠或框架错误覆盖层，正常图表 console 无警告/错误。无关页面基线的小像素差异已恢复。
- `pnpm build`（隔离 lint、style token、类型检查、构建）、`pnpm format:check` 与合并后的 `./mvnw spotless:check` 均通过。ECharts 三种图表合并后主要 chunk 约 644 kB，仅有既有的体积提示，未引入新依赖。

### 后续工单接力

- 14：ScreenChart 只负责四种图表，ScreenWidgetContent/ScreenCanvas 的 queryBase、imageBase 透传契约不变；通用查询模型在 widget-query.ts，取数配置更名为 ChartConfig，初始化为 emptyChart(type)。指标卡、明细/汇总表需增加明确类型与 renderer，不在本工单引入 registry。
- 16：四种取数 Widget 的 Dataset 引用仍统一为 config.datasetId，后端可用 Widget.isQueryWidget() 判断；draft/published 均须检查，TEXT/IMAGE 无 Dataset 引用。

未验证真实 Shell/网关、部署环境、移动端、Firefox/WebKit；UI 业务接口使用 mock，实际 PostgreSQL 聚合与 HTTP 校验由容器用例覆盖。
