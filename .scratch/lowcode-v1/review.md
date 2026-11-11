# 低代码一期最终审查与修复

审查固定点：`c2d1d231b45c14bcc601f70255fa404a97687c11`。审查 HEAD：`5197b77100ea8d828dd5d82187b39d10c5edb615`，范围为该固定点至 HEAD 的一期实现。两份独立报告分别审查仓库规范与规格符合性，以下保留原始发现及各轴结论；没有将两轴重新合并排名。原报告中的行号属于被审查的 HEAD。

修复分支：`codex/lowcode-v1-review-fixes`，基于上述审查 HEAD 创建独立 managed 工作树；不修改原工作区 `main`。修复只覆盖以下已确认发现。

## Standards 轴：原报告

固定审查范围：`c2d1d231...5197b77`，只读审查；未重跑已通过的重型验证。

硬性规范违规：

- **[P3] 纯 HTTP 投影应放入 `model`。** [ScreenQueryService.java:118](../../lowcode-service/src/main/java/io/saasforge/lowcode/screen/service/ScreenQueryService.java:118) 的 `WidgetData`/`OutputColumn` 与 :122 的 `BindingDataset`/`BindingField` 没有业务行为，直接构成 Controller 响应，却定义在 `service`。违反 [business-service.md:168](../../lowcode-service/docs/standards/business-service.md:168) 的接口模型归属与 :172 的共享模型规则。将这四个现有定义原样移到 `screen.model` 并更新引用即可；不创建镜像。已检查实际架构规则，此移动不触犯跨功能依赖限制。`DeletionReferences` 等领域服务契约不在此项。
- **[P3] 无同名冲突的类型仍使用全限定名。** 违反 [business-service.md:191](../../lowcode-service/docs/standards/business-service.md:191) 的“显式 import 加简单类名”。位置：生产代码 [ExternalClickHouseConnector.java:111](../../lowcode-service/src/main/java/io/saasforge/lowcode/datasource/service/ExternalClickHouseConnector.java:111)、:291；[SqlAggregation.java:204](../../lowcode-service/src/main/java/io/saasforge/lowcode/datasource/service/SqlAggregation.java:204)。测试代码 [ClickHouseDataSourceTest.java:119](../../lowcode-service/src/test/java/io/saasforge/lowcode/ClickHouseDataSourceTest.java:119)；[DialectQueryApiTest.java:190](../../lowcode-service/src/test/java/io/saasforge/lowcode/DialectQueryApiTest.java:190)、:196、:203、:494；[ScreenQueryApiTest.java:236](../../lowcode-service/src/test/java/io/saasforge/lowcode/ScreenQueryApiTest.java:236)、:328；[SharedContainers.java:189](../../lowcode-service/src/test/java/io/saasforge/lowcode/SharedContainers.java:189)。统一导入对应类型即可，属于机械维护问题。

启发式建议：

- **[P3，可能的 Duplicated Code] 编辑与播放重复映射 Widget 样式。** [ScreenEditorPage.vue:295](../../lowcode-sub-app/src/pages/ScreenEditorPage.vue:295) 和 [ScreenCanvas.vue:9](../../lowcode-sub-app/src/pages/ScreenCanvas.vue:9) 均复制 `left: \`${widget.x}px\`` 到 `textAlign: widget.config.textAlign` 的整段映射。此处决定编辑/播放的一致性，增加视觉属性时须同步两处。可在现有 `screen.ts` 提取一个小函数，两处调用；无需引入渲染抽象。这是判断性建议，未发现当前渲染错误。

合计：2 项规范违规、1 项启发式建议；最高 P3，无阻断性规范问题。

## Standards 轴：修复结论

- 两项规范违规均已修复：`WidgetData`、`OutputColumn`、`BindingDataset`、`BindingField` 四个纯 HTTP 投影原样移至 `screen.model`，Service / Controller 改为导入对应类型；JSON 字段及语义保持不变。`PublishedScreen`、`DeletionReferences` 等已有领域契约未纳入此次移动。
- 报告列出的生产与测试类型引用改为显式 import 和简单类名；没有同名冲突需要保留的额外改动。
- 接受重复代码启发式建议：在既有 `screen.ts` 抽取 `widgetStyle`，编辑器与播放画布复用同一映射；没有增加渲染框架或新视觉行为。

### 原 Standards reviewer 定向复核

原报告三项均已解决：

- 四个纯 API 投影已原样移至 `screen.model`，Controller/Service 引用同步，无镜像。
- 原清单全部 FQCN 已改为显式 import 与简单类名。
- Editor/Canvas 共用 `screen.ts` 的 `widgetStyle`，字段映射一致，Canvas 的绝对定位保留。

本次仅静态定向复核，未改源码、未扩展审查、未运行重型测试。

## Spec 轴：原报告

- [P1] Dataset UUID 大小写可绕过删除引用保护。规格 `.scratch/lowcode-v1/spec.md:114` 要求“Dataset 被 Screen（草稿或已发布快照）引用时禁止删除，返回引用清单与对应 Error Code”。`lowcode-service/src/main/java/io/saasforge/lowcode/screen/service/ScreenDatasetReferences.java:59-61` 把规范化小写 `datasetId.toString()` 与配置中的原始文本作大小写敏感比较，但 `screen/model/ScreenDraft.java:96` 接受大写 UUID，保存路径保留原始 JsonNode，`ScreenQueryService.Config` 及引用写锁均按 UUID 正常解析。触发：把已有 Dataset 的 UUID 转成大写，保存至取数 Widget 的 `config.datasetId`（再发布亦可），随后查询 references 并删除该 Dataset。扫描漏掉草稿及快照，DELETE 不返回 409，留下不能再取数的大屏。最小修复：扫描时将配置 ID 解析为 UUID 后比较，或在写入边界统一规范化；增加大写 UUID 的草稿、已发布引用删除回归。

本轮未发现其他可证实的缺失或越界功能。图片 Widget 的 objectId 经 `ScreenDraft.imageObjectIds()` 转为 UUID，发布下载也按 UUID 集合比较，不存在同类漏洞。审查只读，未重跑重型测试；以上结论来自固定 HEAD 5197b771 的完整引用写入、保存、扫描与删除路径。

## Spec 轴：修复结论

P1 已修复：引用扫描把 Widget 中的 `datasetId` 解析成 UUID 后按值比较，接受边界与保存的原始配置均保持不变；图片引用路径没有同类缺陷，未修改。

新增 `ReferenceProtectionApiTest.uppercaseDatasetReferencesBlockDeletionInDraftAndPublishedSnapshot`，沿用已确认的公开 HTTP / 真实 PostgreSQL、Redis seam。两个参数分别验证未发布草稿与发布后清空草稿的独立快照引用；均检查 GET references 包含准确 Screen 元数据，以及 DELETE 返回 409 / `dataset.referenced`。

- 红灯：两个场景各出现三项行为断言失败：references 为 `[]`，DELETE 为 200 / `ok`。日志 `/private/tmp/lowcode-v1-review-red.log`。
- 最小修复后相同两项测试全过，DELETE 均为 409。日志 `/private/tmp/lowcode-v1-review-green.log`。

### 原 Spec reviewer 定向复核

原 P1 已解决：引用扫描改为 UUID 值比较。新增公开 HTTP 回归分别覆盖仅草稿引用，以及发布后清空草稿、仅快照引用；均断言引用清单和 409/dataset.referenced。已核对红绿日志及 183 项全过记录，未另跑重型测试。

## 最终回归与真实链路

- 后端 `./mvnw -o spotless:apply verify -DforkCount=1`：**183 tests，0 failures / errors / skips，BUILD SUCCESS**，含真实 PostgreSQL / MySQL / SQL Server / ClickHouse、ArchitectureTest、SchemaCommentTest 与 Spotless；日志 `/private/tmp/lowcode-v1-review-verify.log`。移动前的旧嵌套投影 class 已不在当前构建产物中。本轮未变更 SQL 方言或外部连接行为，未重复 Oracle slow profile。
- 前端使用自己的 `node_modules`，经 `pnpm install --offline --frozen-lockfile` 安装；未更改 lockfile、snapshot tarball 或依赖版本。`pnpm build`、`pnpm format:check`、完整 `pnpm test:visual` **130 passed（3.4m）**。日志 `/private/tmp/lowcode-v1-review-{install,build,format,visual}.log`；既有 PNG 基线无变更，ECharts 既有大 chunk 提示仍存在。
- 使用上述当前构建产物运行 `./deploy/e2e/run.sh --skip-build --platform-root /Users/liuhuan/workspace/coding/saas-forge`，新建独立 project `lowcode-platform-3t7ih3dc` / 空卷 / 本轮凭据，真实平台链路 **1 passed（24.1s）**。入口日志 `/private/tmp/lowcode-v1-review-platform.log`，私有运行证据 `/private/tmp/lowcode-platform-3t7ih3dc`。
- 五张新截图均逐张查看：初始化两项成功，设计者真实图表/图片，viewer 1440 / 1024 Playback，以及本人范围空列表；两档 Playback 页头唯一。范围外空态截图处于 Element Plus loading mask 淡出瞬态，HTTP / UI 空态和 scope 断言均通过，原 17 的清晰 PNG 及全部基线保持不变；没有将历史 PNG 伪装成本轮截图。其余四张本轮截图与原证据语义一致。
- 仅精确排除已实际观察到的 Shell `/demo/context` 403 平台基线（反馈 08）；其他相关 console / pageerror 检查继续严格执行。没有修改用户平台源码、原角色、Nacos、凭据或 `.env.local`。
- 自动清理后再次核验：本轮 Compose project 的容器和卷、owned Java 进程及 13 个监听端口均无残留；脱敏核验 `/private/tmp/lowcode-platform-3t7ih3dc/review-cleanup.json`。原始凭据、邀请链接及日志只留私有临时目录。
- 两轴全部原发现已修复并经各自原 reviewer 定向复核；规格的 18 个实施工单均为 resolved。整体实现及边界见 [spec.md](spec.md)，历史验收与本轮追加复验见 [acceptance.md](acceptance.md)。
