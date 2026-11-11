# AGENTS.md

本工程是 SaaS Forge 平台的 Sub-App `lowcode`（平台版本 0.2.0-SNAPSHOT）：构建产物 `dist/remoteEntry.js` 由租户 Shell 经 Module Federation 加载，路由挂在 `/apps/lowcode` 下。

## 规则摘要

- 新增或调整页面前读 [Sub-App 页面规范](docs/standards/sub-app.md)，按页面简报、功能实现、设计走查三步完成；走查必须逐张查看截图。
- 列表页用 `PageHeader` → `PageToolbar` → `ListPage` 原型；空值显示 `—`，时间用 `formatDateTime`。
- 样式只用 `--forge-*` token 与 Element Plus 的 `--el-*` 变量，不写间距、字号、圆角和颜色字面量。
- 隔离约定：只在 `src/sub-app.ts` 导出相对路由；不创建 Vue 应用、Router、Pinia、i18n 实例；`<style>` 用 `scoped`；共享依赖只从根入口导入；接口经 `useTenantClient()` 调用，不读写 Shell 的 `forge.*` 会话存储。
- 视觉用例放 `e2e/visual/`：在 `localStorage` 预置假会话，用 `page.route` mock 接口，覆盖接近真实长度的数据、空值与空列表。

## 命令

- `pnpm dev`：独立开发宿主 `http://localhost:5176`，经代理访问本地网关
- `pnpm format`：按平台统一的 Prettier 规则格式化
- `pnpm format:check`：检查代码格式，提交前须通过
- `pnpm lint`：隔离约定检查
- `pnpm lint:style`：样式 token 检查
- `pnpm typecheck`：类型检查
- `pnpm build`：依次执行 lint、lint:style、类型检查，产出 `dist/remoteEntry.js`
- `pnpm test:visual`：视觉回归，与 `e2e/visual/__screenshots__/` 的基线比对
- `pnpm test:visual:update`：生成或更新基线截图，逐张查看后与代码一同提交

## 规范

- [docs/standards/sub-app.md](docs/standards/sub-app.md)：页面流程、原型、组件、交互、样式与走查清单。文件随平台版本复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。
