# 15: 图片组件与背景图

**What to build:** 编辑器中能上传图片作为图片 Widget 或 Screen 背景图，Playback 正常显示。见规格第 6 节「画布」。

**Blocked by:** 08

**Status:** resolved

- [x] 声明 Storage Purpose `screen-image`，限定图片类型与大小
- [x] 图片 Widget：上传、替换、填充方式
- [x] Screen 背景图：上传、移除、填充方式
- [x] Playback 能显示已发布快照引用的图片；下载链接时长满足长时间播放（按 Storage Purpose 设定或到期刷新）
- [x] 接入 storage starter 的迁移与必配项按接入指南完成；指南不清或缺失处记为平台反馈

## 页面简报与验收边界

- 使用者为持 `screen:edit` 的设计者：在现有编辑器工具栏添加图片，选中图片后在属性面板替换和调整填充；画布设置内上传、移除背景图并选择填充。主操作仍为“保存草稿”。图片上传完成后才加入草稿，错误保留原图片。
- 播放者只见已发布快照图片，签名链接到期前刷新。沿用现有工作台与播放布局，图片属性仅在适用时显示。
- 已获授权的 TDD 边界：图片上传/完成/下载 HTTP API + 真实对象存储，Playwright 编辑器/播放 UI。Browser plugin not available，使用仓库 Playwright、临时端口 5195；1440×900 和 1024×768 截图逐张走查。

## Answer

图片 Widget 与 Screen 背景图均经 `screen-image` 用途上传。允许 PNG、JPEG、WebP、GIF，扩展名与 MIME 必须配对，最大 10 MiB；确认上传和绑定草稿时再次核对当前 Tenant、已确认状态、用途与媒体类型。图片可替换，背景可移除，均支持完整显示（contain）、覆盖填充（cover）、拉伸（fill）。图片和背景配置参与现有撤销/重做及草稿保存重载；上传失败保留原图。

### 接口与持久化

- 新增 `forge-sdk-storage`，`V11__storage.sql` 原样复制已安装 SDK jar 的当前 schema；包含对象与容量表的中文注释和 Tenant RLS。`application.yml` 的 `FORGE_STORAGE_ENDPOINT/BUCKET/ACCESS_KEY/SECRET_KEY` 均无生产默认值，`.env.example` 已同步浏览器可达地址、预建 bucket、CORS PUT/GET 与请求头要求。普通测试只用专用测试配置初始化客户端，图片测试覆盖为真实 S3；未改写用户 `.env.local` 或加密密钥。
- `POST /screens/{id}/images/uploads` 接收 `{filename,contentType,size}`，返回 SDK UploadTicket；浏览器按票据头直接 PUT，不携带 Tenant Token；`POST /screens/{id}/images/{objectId}/complete` 返回已确认 StoredObject。两者均要求 `screen:edit` 并先校验 Screen 数据范围。
- `GET /screens/{id}/draft/images/{objectId}/download` 要求 `screen:edit`、先校验 Screen 可见范围，再核对当前 Tenant 图片用途；允许预览已确认但尚未保存的图片。
- `GET /screens/{id}/images/{objectId}/download` 要求 `screen:view`，先经 09 的 `ScreenService.published(id)` 读取可见已发布快照，再确认对象确实被该快照的图片 Widget 或背景引用。未发布、数据范围外或仅被草稿引用均返回 404。换图后旧发布快照保持可读，重新发布后切换引用；替换时不删除仍可能被快照引用的旧对象。
- ScreenDraft 新增可选 `backgroundImageObjectId`（UUID）与 `backgroundImageFill`；旧草稿省略字段保持往返兼容。`IMAGE` Widget config 为 `{objectId,fill}`。草稿和发布快照只保存对象 ID，不保存签名地址。
- 前端共享组件：`ScreenCanvas.vue` 的 props 为 `{draft,imageBase}`；编辑器交互包装与播放画布共用 `ScreenWidgetContent.vue`（`{widget,imageBase}`），后续图表可直接增加类型分支。`ScreenImage.vue` 按 DownloadTicket.expiresAt 在到期前续签，失败后 15 秒重试并支持手动重试；用途票据有效期为 1 小时。

### 验证与走查

- TDD 证据：类型/大小首轮因 `request.notFound` 失败（`/tmp/lowcode15-red-api.log`）；上传确认因接口缺失失败（`/tmp/lowcode15-red-complete.log`）；未确认对象绑定先返回 400 而非存储 404（`/tmp/lowcode15-red-bind.log`）；伪装媒体类型原先 code=ok（`/tmp/lowcode15-red-mime2.log`）；发布下载原先 404（`/tmp/lowcode15-red-published.log`）。编辑入口与播放图片渲染也先失败（`/tmp/lowcode15-red-ui.log`、`/tmp/lowcode15-red-playback-ui.log`），对应实现后转绿。
- 后端 `./mvnw spotless:apply verify`：112 tests，0 failures/errors/skips，含 8 项 ScreenImage HTTP 测试、真实 PostgreSQL/Redis/S3、扩展名/MIME/大小、上传确认、下载 PNG 内容、未确认/用途/跨 Tenant 拒绝、只读权限、发布快照引用与本人 Data Scope；架构、SDK schema、表字段注释与 Spotless 全过。日志 `/tmp/lowcode15-verify.log`。最后补充背景与 Widget 使用不同对象的授权断言后，8 项图片测试再次通过（`/tmp/lowcode15-images-final.log`）。
- 前端 `pnpm build`、`pnpm format:check` 通过，含隔离 lint、样式 token lint、类型检查；日志 `/tmp/lowcode15-build-final.log`、`/tmp/lowcode15-format-final.log`。
- 完整 Playwright：85 tests 全过（`/tmp/lowcode15-visual-full.log`），使用等价临时配置 `/tmp/lowcode15-playwright.config.mjs`，只调整端口为 5195 与输出目录到 `/tmp`。图片 5 项 UI 用例覆盖上传、替换、填充、背景移除/撤销、保存重载、失败保留原图、实际 Playback 路由和签名到期前更新；续签使用浏览器受控时钟，在 4 秒票据到期前观察第二次下载及新签名 URL。
- 环境：macOS Chromium，1440×900 / 1024×768，`Browser plugin not available`，按 frontend-testing-debugging 使用仓库 Playwright。页面身份、非空、无框架错误覆盖层、相关 console warning/error、交互状态均通过。
- 8 张 PNG 已逐张查看：新增图片编辑器与播放各两档，更新原空画布/文本编辑器各两档以反映图片入口与背景属性。主操作仍为保存草稿，工具栏首屏可见，长属性通过面板独立滚动可达；原三种播放缩放与全屏用例保持通过。
- 首次 MIME 红测遇单次 Ryuk 连接失败，只清理该次临时 Ryuk 容器后重跑正常；未修改平台容器或测试清理策略。本工单无新增平台 SDK/指南问题。
- 限制：未验证真实 Shell/网关部署及生产 bucket CORS；浏览器接口使用 fixture，后端 HTTP 测试通过真实 S3 兼容容器验证。未覆盖 Firefox/WebKit 或移动端，不以短时钟用例替代生产环境长时间稳定性观察。
