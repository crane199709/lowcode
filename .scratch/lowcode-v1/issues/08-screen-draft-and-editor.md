# 08: 大屏草稿与编辑器

**What to build:** 持 `screen:edit` 的 Membership 能新建 Screen、在自由画布上摆放文本卡并保存草稿。见规格第 6 节「画布」「编辑器」。

**Blocked by:** 01

**Status:** resolved

- [x] Screen 列表、新建（Design Resolution 取预设或自定义 640–7680）、修改 Design Resolution（已有 Widget 坐标不换算）、复制整个 Screen、删除
- [x] 编辑器：拖拽、缩放、网格吸附、层级调整、删除、复制 Widget、撤销 / 重做
- [x] 文本卡作为第一个 Widget，可设文字与基本样式
- [x] 背景色可设置（背景图见 15）
- [x] 画布与 Widget 的视觉属性不受 `--forge-*` token 约束；编辑器外围界面遵守规范；lint 误报记为平台反馈
- [x] 保存草稿；Screen 按 Tenant 隔离
- [x] 编辑器 Playwright 用例覆盖拖拽、缩放、撤销重做，视觉用例走查查看截图

## 页面简报与验收边界

- 使用者：持 `screen:edit` 的大屏设计者。列表主任务是找到并打开 Screen；主操作为“新增”，低频的复制、删除在行内。首屏显示名称、Design Resolution 与更新时间，按列表原型排布。
- 编辑器主任务：添加与摆放文本卡并保存草稿；主操作为“保存草稿”。采用独立工作台页面：顶部操作栏，中间缩放后的自由画布，右侧画布/选中 Widget 属性。已有坐标在修改分辨率后保持原值；网格吸附可开关。
- 需求方已授权本工单列表/编辑器意图与 Screen HTTP API、Playwright UI 行为测试边界。HTTP 验证草稿往返、复制、参数约束、Tenant 隔离与权限；UI 验证拖拽/缩放、撤销重做、层级、属性和保存。该边界不覆盖真实 Shell 与生产环境。
- Browser plugin not available：使用仓库 Playwright 工作流，临时配置端口 5188 避免并行工单互扰。按 1440×900 / 1024×768 逐张查看新增基线。

## Answer

完成 Screen 列表、新建/设置/复制/逻辑删除与独立编辑器。文本卡支持文字、字号、文字/填充色、字重、对齐；自由画布按设计像素拖拽/缩放、10 px 网格吸附开关、层级、组件复制/删除、保留 100 个历史状态的撤销重做。修改 Design Resolution 只修改画布宽高，已有 Widget 坐标不换算。后台仍经 SDK 的 Tenant 与 Screen Data Policy 过滤。

### 后续工单的接口指针

- 后端数据模型：[ScreenDraft.java](../../../lowcode-service/src/main/java/io/saasforge/lowcode/screen/model/ScreenDraft.java)；服务：[ScreenService.java](../../../lowcode-service/src/main/java/io/saasforge/lowcode/screen/service/ScreenService.java)；HTTP：[ScreenController.java](../../../lowcode-service/src/main/java/io/saasforge/lowcode/screen/controller/ScreenController.java)。迁移 `V9__screen_drafts.sql` 将 JSON 草稿写入 `screens.draft`（text，沿用 Dataset 的 JSON text 存储方式）。
- 前端公开数据结构：[screen.ts](../../../lowcode-sub-app/src/pages/screen.ts)；编辑器：[ScreenEditorPage.vue](../../../lowcode-sub-app/src/pages/ScreenEditorPage.vue)；相对路由 `screens/:id/edit`，名称 `lowcode:screen-editor`。
- `POST /screens`、`PUT /screens/{id}` 接收 `{name, draft?}`，返回含 `id/name/draft/ownerId/departmentPath/createdAt/updatedAt` 的 Screen。新建省略 draft 时用 1920×1080 空草稿；更新省略 draft 时保留草稿，兼容基础维护调用。
- `GET /screens/{id}/draft`、`PUT /screens/{id}/draft` 均要求 `screen:edit`，请求/响应的 data 直接是 ScreenDraft。`POST /screens/{id}/copy` 要求 `screen:edit`、返回 201 Screen，复制布局配置，名称加“（副本）”，创建归属重新取当前 Membership 与部门。Widget ID 在同一 Screen 内唯一，复制整个 Screen 保留其 Widget ID。
- ScreenDraft：`{designWidth, designHeight, backgroundColor, widgets: [{id,type,x,y,width,height,zIndex,config}]}`。宽高 640–7680；最多 200 个 Widget；坐标 0–7680、尺寸 1–7680、层级 0–10000。目前 `type=TEXT`；`config={text,fontSize,color,backgroundColor,fontWeight,textAlign}`，文字至多 20000 字、字号 8–256、颜色为 6/8 位十六进制、字重 normal/bold、对齐 left/center/right。config 使用 JsonNode，10/15 扩展类型时同步添加服务端校验与前端渲染即可。
- 09 接力时将 `screens.draft` 冻结成已发布快照，复制不带发布状态。目前基础 `GET /screens` 与 `GET /screens/{id}` 仍返回草稿；仅查看已发布内容的可见性与响应隔离由 09 完成。08 的编辑器只通过需 edit 权限的 draft 接口加载。

### 验证与走查

- TDD：`ScreenApiTest.screenDraftRoundTripsAndResolutionChangesKeepWidgetCoordinates` 先因响应缺少 draft 失败，再通过；Playwright 首用例先因缺少“编辑”入口失败，再通过。字号整数越界由 HTTP 用例证明原来返回 500，加入 `canConvertToInt` 后按 `request.invalid` 返回 400。日志 `/tmp/lowcode08-red-api.log`、`/tmp/lowcode08-red-ui.log`、`/tmp/lowcode08-red-font.log`。
- `lowcode-service/./mvnw verify`：92 tests，0 failures/errors/skips，含 Tenant、Data Scope、功能权限、参数边界、架构、SchemaComment、真实外部连接/方言与 Spotless；日志 `/tmp/lowcode08-verify.log`。
- 前端完整视觉回归：使用等价临时 Playwright 配置 `/tmp/lowcode08-playwright.config.mjs`，仅把端口改为 5188 并将输出移到 `/tmp`，70 tests 全过；日志 `/tmp/lowcode08-visual-full.log`。`pnpm build`、`pnpm format:check` 均通过（含隔离 lint、stylelint、类型检查）；日志 `/tmp/lowcode08-build-final.log`、`/tmp/lowcode08-format-final.log`。
- [screens.spec.ts](../../../lowcode-sub-app/e2e/visual/screens.spec.ts) 覆盖拖拽、缩放、网格吸附、撤销/重做、保存重载、分辨率保持坐标、样式、层级、组件复制删除、列表维护、只读权限与保存失败重试。浏览器页面身份、非空、无错误覆盖层、相关 console warning/error 均通过。
- 环境：macOS Chromium，1440×900 / 1024×768；`Browser plugin not available`，按 frontend-testing-debugging 使用仓库 Playwright。12 张大屏基线逐张查看：新增 list/create/editor-empty/editor-text/readonly 两档，共 10 张；更新 empty 两档以反映设计分辨率列。修复画布 grid item 的 min-height，工具栏首屏可见，属性面板可独立滚动，长名称不撑破表格，空时间显示“—”。
- 页面外围样式通过平台 token lint；画布与 Widget 的像素、颜色来自用户草稿数据，未发生 lint 误报。
- 未验证真实 Shell/网关部署、移动端与 Firefox/WebKit；本工单的浏览器接口由 mock 提供，后端 HTTP 用例连接真实 PostgreSQL/Redis 容器。

### 平台反馈

已在平台仓库新增 `/Users/liuhuan/workspace/coding/saas-forge/.scratch/lowcode-validation/issues/07-element-plus-input-number-disabled-aria.md`（needs-triage）。Element Plus 2.14.7 的 ElInputNumber 在 disabled true→false 后原生 disabled=false，但 aria-disabled 仍 true；独立最小复现、原生 JSON、Playwright 错误与已查看截图见该工单。当前仅在“自定义”时挂载宽高输入，避免重复信息与该依赖问题；未改平台代码、依赖或强制操作控件。
