# 01: 清理示例，声明 lowcode 资源

**What to build:** 生成工程不再带订单示例，平台看到的是 lowcode 自己的 Resource Manifest：租户授予权限后能在 Shell 看到「低代码」分组下的数据源、数据集、大屏三个菜单，点开是各自的空列表页。这是后续切片的准备工单。见规格第 2、8 节。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 移除订单、分类、通知示例的代码、mapper、迁移、消息与测试；SDK 必需表的迁移保留，`SchemaCommentTest` 通过
- [x] Manifest 声明 Permission Code `datasource:view`、`datasource:manage`、`dataset:view`、`dataset:manage`、`screen:view`、`screen:edit`、`screen:publish`
- [x] Manifest 声明 Data Resource `screen`（资源属性：创建人、所属部门），角色模板「大屏设计者」「大屏查看者」，菜单分组「低代码」及三个指向 Sub-App `lowcode` 的叶子
- [x] 原示例覆盖的通用测试（租户隔离、功能权限、Data Policy、架构）改用一个最小的 lowcode 资源继续覆盖，不因删除示例而丢失
- [x] Sub-App 去掉示例首页，提供三个空列表页路由，按列表原型 `PageHeader → PageToolbar → ListPage` 搭好空状态
- [x] 后端 `verify`、前端 `build` 通过

## Comments

### 页面简报（数据源、数据集、大屏列表页）

- **谁在用、主任务**：持对应查看权限的成员，浏览本租户的数据源、数据集或大屏。本工单只搭页面骨架，接口由 02、06、08 接入。
- **主操作**：「新增」属于后续工单的功能，现在放按钮点了没有反应，因此 `PageToolbar` 暂时留空，接入新增流程时放进 `#actions` 插槽。
- **一眼可见**：名称、更新时间两列，空状态分别显示「暂无数据源」「暂无数据集」「暂无大屏」和「共 0 条」。其他列随各自工单的接口补充。
- **原型**：列表 `PageHeader` → `PageToolbar` → `ListPage`。

### 设计走查

`e2e/visual/list-pages.spec.ts` 覆盖三个页面的空列表，各有 1440、1024 两档宽度，共 6 张截图。`pnpm test:visual:update` 生成基线后，逐张用 Read 查看：

- 标题、表头、空状态和分页左右边缘对齐，间距来自组件内置 token。
- 表头和「共 0 条」不折行，1024 宽度下可读。
- 没有说明性灰色小字，也没有裸编码。

页面还没有数据，所以接近真实长度的数据、空值和最长值暂时没有用例，由接入接口的工单补充。随后 `pnpm build`（含 `lint`、`lint:style`、`vue-tsc`）和 `pnpm test:visual` 都通过。

### 实现说明

- **示例清理**：删除 `order`、`category`、`notification` 三个功能包及其 mapper XML、迁移（原 V2、V3、V5、V6、V7）、`OrderApiTest`，以及 `order.notFound`、`attachment.*` 文案。原 V4 中的 `order_notifications` 段已去掉。SDK 必需表的迁移重新编号为 `V2__messaging.sql`、`V3__dead_event.sql`。
- **最小资源**：`screen` 功能包包含 `V4__screens.sql`（标准列，`owner_id`、`department_path`，RLS ENABLE + FORCE）和 `/screens` 增删改查接口，接口只带名称字段，查看要求 `screen:view`，写操作要求 `screen:edit`。修改和删除接口是为了让测试覆盖跨租户、越权的写操作。
- **测试迁移**：租户隔离、功能权限、Data Policy 测试改用 `/screens`；`OrderApiTest` 改为 `ScreenApiTest`。Data Policy 里的自定义条件原来按金额过滤，改为「本部门及下级，但不是本人创建」。`FunctionalPermissionTest` 断言清单中的权限码、菜单、角色模板和 Data Resource。
- **Manifest**：菜单叶子用 `actions` 声明页面操作的权限码；`forge.manifest.version` 从 1 升到 2，因为同一版本的清单内容不可变。
- **Sub-App**：`HomePage` 删除，新增 `src/pages/` 下三个列表页，路由路径与菜单叶子的 `path` 一致；开发宿主首页改为 `datasources`。
- **验证**：后端 `mvn verify` 17 个测试全部通过，前端 `pnpm build`、`pnpm test:visual` 通过。

### 遗留

- 迁移重新编号后，跑过旧迁移的本地库会在 Flyway 校验时失败，需要重建；不过删除订单迁移本身也会造成同样的问题。
- `/screens` 只有名称字段，工单 08 在此基础上补设计分辨率、复制等功能，并接入前端大屏列表。
- `screens.owner_id`（Membership）对应资源属性「创建人」，与标准列 `created_by`（Identity）含义不同，08 实现时要沿用 `owner_id`。
- 生成工程自带的 `/context`、`/greeting` 示例接口不属于订单示例，`/context` 还被租户隔离测试使用，所以保留。
- 「低代码」菜单在真实 Shell 与 IAM 上的显示还没验证，留到 17 端到端验收。
