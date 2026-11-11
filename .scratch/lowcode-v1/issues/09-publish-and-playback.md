# 09: 发布与播放

**What to build:** 持 `screen:publish` 的 Membership 能 Publish Screen；持 `screen:view` 且在其 Data Scope 内的 Membership 能 Playback 已发布快照。见规格第 6 节「草稿与发布」「Playback」、第 8 节。

**Blocked by:** 08

**Status:** resolved

- [x] Publish 把草稿冻结为快照并覆盖上一份；未发布过的 Screen 对仅有 `screen:view` 的 Membership 不可见
- [x] 发布后继续编辑草稿不影响已发布快照
- [x] Playback 支持等比适配（默认）、铺满拉伸、按宽度适配，可用浏览器全屏
- [x] Data Resource `screen` 生效：「本人」「本部门」Data Scope 限制可见 Screen
- [x] Publish 写 Audit Event
- [x] 测试覆盖草稿与快照分离、Data Scope 过滤；Playback 视觉用例覆盖三种缩放方式

## 页面简报与验收边界

- 使用者：持 `screen:publish` 的设计者发布已保存草稿；持 `screen:view` 的查看者播放其 Data Scope 内的已发布 Screen。列表一眼看到设计分辨率、发布状态和更新时间，发布入口位于行操作，覆盖快照前确认。
- Playback 用独立工作台：PageHeader 展示大屏名与返回入口，工具栏提供缩放方式及唯一主操作“全屏”；主区域按设计分辨率展示已发布画布。错误以 ErrorAlert 展示，不回退草稿。
- 需求方已授权 Screen HTTP API 与 Playwright 两个测试边界。HTTP 覆盖发布冻结/重新发布、只查看者草稿隔离、Tenant 与本人/本部门 Data Scope、publish 权限及 Audit Event；UI 覆盖播放入口、三种缩放和全屏，桌面 1440×900 与笔记本 1024×768。真实 Shell/部署不在此边界内。
- Browser plugin not available：按 frontend-testing-debugging 使用仓库 Playwright，临时端口 5189；所有新增与修改 PNG 逐张查看。

## Answer

完成大屏发布与 Playback。发布通过单条受 Tenant/Data Policy 过滤的 SQL 将已保存草稿、名称与发布时间原子冻结，重新发布覆盖上一份；同时维护 `updated_at` 和 `updated_by`（取已验证 JWT 的 Identity `sub`）。复制整个 Screen 只复制草稿，不继承发布状态。

### 后续工单接口

- 迁移 `V10__screen_published_snapshot.sql`：新增 `screens.published`（ScreenDraft JSON text）、`published_name` 与 `published_at`。已发布布局、组件配置、名称和查看者更新时间不随草稿变更；只读列表按发布时间排序，编辑列表按草稿更新时间排序。
- `POST /screens/{id}/publish` 要求 `screen:publish`，返回 `ApiResponse<PublishedScreen>`，并写 `screen.publish` Audit Event（成功和权限失败均有审计）。确认弹窗明确“当前已保存草稿”“覆盖上一份”。
- `GET /screens/{id}/published` 要求 `screen:view`，返回 `{id,name,definition,publishedAt}`；`definition` 是冻结的 ScreenDraft，未发布、其他 Tenant 或 Data Scope 外统一 `screen.notFound` / 404。公开服务入口为 `ScreenService.published(UUID)`；`published(Screen)` 只解析已按范围读取的行，列表不产生逐条重新查询。
- `GET /screens` 与 `GET /screens/{id}`：有 `screen:edit` 时保留草稿维护能力；仅查看者只列已发布 Screen，响应没有 draft，名称/分辨率/更新时间来自快照，包含顶层 `designWidth`/`designHeight`/`publishedAt`。草稿读写、复制仍要求 `screen:edit`。系统异步调用沿用 SDK 的无认证系统任务约定，仍受 Tenant 隔离。
- 前端路由 `screens/:id/play`，名称 `lowcode:screen-playback`，页面 `src/pages/ScreenPlaybackPage.vue`。仅请求 `/published`，不回退草稿；支持等比适配、铺满拉伸、按宽度适配（超高可纵向滚动），容器变化自动重算，浏览器全屏/退出全屏。列表展示发布状态与按权限开放的播放/发布操作。
- 与 15 已约定：本工单保留临时 TEXT 播放渲染，15 合入时改为其 `ScreenCanvas.vue`（props `draft,imageBase`）与图片续签组件；15 按 `published(UUID).definition` 中的冻结引用验证图片访问。

### 验证与视觉走查

- TDD 首轮发布测试先因缺少 publish 接口失败，再绿；仅查看者测试先捕获返回未发布草稿和未发布名称的问题，再绿；播放测试先因缺少播放入口失败，再绿。日志 `/private/tmp/lowcode09-red-publish.log`、`/private/tmp/lowcode09-red-viewer.log`、`/private/tmp/lowcode09-red-ui.log`。
- `ScreenApiTest` 共 10 个 HTTP 用例。新增覆盖草稿/快照分离与重新发布、复制未发布、只读 list/get 不泄露草稿、未发布 404、相同 Identity 的不同 Membership 下 SELF/DEPARTMENT/无策略范围与分页计数、跨 Tenant、publish 独立权限、不同 Identity 的 Audit actor、发布失败与成功 Audit Event，以及草稿编辑不改变只读列表排序/名称/分辨率/更新时间。
- 合入最新 `codex/lowcode-v1-integration` 的 `8c47ddc` 后，`./mvnw verify` 104 tests，0 failures/errors/skips，含 PostgreSQL/Redis、实际 MySQL/SQL Server/ClickHouse、Tenant/Data Scope、架构、SchemaComment、Spotless；日志 `/private/tmp/lowcode09-verify-final.log`。首次完整验证发现系统异步列表误要求 Membership 的回归，修复后该用例和最终全量检查均通过。
- `pnpm build`、`pnpm format:check` 通过，含隔离 lint、style token、类型检查；日志 `/private/tmp/lowcode09-build-final.log`、`/private/tmp/lowcode09-format-final.log`。
- Playwright 使用与仓库配置等价的临时 `/private/tmp/lowcode09-playwright.config.mjs`，只调整端口为 5189、输出到临时目录；最终完整视觉回归 80 tests 全过，日志 `/private/tmp/lowcode09-visual-full.log`。相关 18 tests 在基线更新时通过，日志 `/private/tmp/lowcode09-visual-update.log`。
- `playback.spec.ts` 验证列表→播放路径、尺寸比例、三种缩放、宽度适配的滚动可达、真实浏览器全屏/退出、确认前不发发布请求、失败可重试、查看者没有发布入口、无 view 权限不请求快照、404 不回退草稿与返回列表。
- 环境为 macOS Chromium / `http://127.0.0.1:5189`，1440×900 与 1024×768。Browser plugin not available，使用 frontend-testing-debugging 的 Playwright 路径。页面身份、非空、无框架错误覆盖层、正常播放相关 console warning/error、交互均通过。
- 逐张查看 12 张本工单 PNG：新增三种缩放×两档（6）、快照不可用×两档（2）、发布确认×两档（2），更新列表×两档（2）。检查文字、布局、缩放与可达性；修复宿主未给父元素确定高度造成零高度画布、工具栏插槽错误，并收紧无发布权限时的操作列。用户画布/Widget 样式继续由设计数据决定，外围样式通过 token lint。按宽度适配高于容器时允许纵向滚动。
- 未验证真实 Shell/网关、生产部署、移动端或 Firefox/WebKit；UI API 使用 mock，HTTP 用例连接真实 PostgreSQL/Redis。图片与取数 Widget 分别由 15、10 接力，本工单不冻结 Dataset。
