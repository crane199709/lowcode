# 05: 浏览表与列

**What to build:** 数据库类 Data Source 详情中能浏览其表与列（名称、类型），辅助编写 Dataset SQL。见规格第 3 节「辅助能力」。

**Blocked by:** 02

**Status:** resolved

- [x] 列出当前账号可见的表（含 schema），选中后列出列名与类型
- [x] 需要 `datasource:view`；对 API 类 Data Source 不提供
- [x] PostgreSQL 有容器测试；其他类型在 03 完成后补用例
- [x] 前端浏览面板可被 06 的 SQL 编辑页复用

## Comments

### 页面简报与验收边界

- 持 `datasource:view` 的成员在数据库类数据源行点击「浏览表」，打开详情抽屉；API 类没有此入口。
- 抽屉显示名称、类型、连接地址、账号，表列浏览作为关联数据展示；选表是主操作，重新加载为次操作。
- 表列表显示 schema 和表名，选中后按数据库列顺序显示列名、类型；长名称可查看完整内容，空值用「—」，提供加载、空列表与错误状态。
- 浏览面板仅接收数据源 ID，独立于抽屉，供 06 复用；切换表或数据源时丢弃过期请求。
- 沿用 03 已确认的 REST API 集成测试与 Playwright 边界；审查以开始提交 `71f564dc9badb840671428b1a505603a37d89adf` 为基准。

### 实现说明

- 新增 `GET /data-sources/{id}/tables` 与 `GET /data-sources/{id}/columns?catalog=…&schema=…&table=…`，均要求 `datasource:view`。先读取当前租户中未删除的数据源，再检查网络并使用已保存的凭据；API 类返回 `datasource.metadataNotSupported`。
- 表标识保留 catalog、schema、name，无值时返回空字符串；MySQL 将库名显示为 schema。元数据按当前数据库账号读取，PostgreSQL 固定启用 `hideUnprivilegedObjects` 并按驱动返回的 schema 集合过滤；可见性采用 JDBC 元数据语义。
- 列查询先核验表在可见集合中，再转义元数据名称模式，按数据库列顺序返回列名与数据库类型；失败沿用既有固定错误文案，不回显驱动错误或密码。
- `DataSourceSchemaBrowser.vue` 只接收 `sourceId`，不依赖详情抽屉或编辑表单。选表、重载、切换数据源与卸载时使过期响应失效；表名按钮支持键盘操作，长名称显示省略号并可悬停查看。

### 设计走查

- 新增表列浏览、有表无列、表/列加载失败、表加载中、空表列表六种状态，在 1440×900、1024×768 下各留一张基线，共 12 张；逐张查看后修正长表名截断缺少省略号的问题。
- 更新原有数据源列表、只读列表、空列表及表单截图，共 20 张：增加「浏览表」入口并调整列宽，保证 1024 宽度下账号、更新时间和全部操作同行可见。表单截图仅列表背景随列宽变化；逐张查看，表单内容未调整。
- Playwright 验证只读成员入口、API 排除、表列选择、重载恢复、空数据、失败提示与 traceId、快速切表旧响应不覆盖当前列；正常流程检查页面 URL、非空首屏、无 Vite 错误覆盖层及无控制台错误。

### 双轴审查与修复

- Standards：修正 Oracle 测试的 `Locale` 全限定类型引用，改用显式导入；复核无遗留规范问题。
- Spec：补齐 PostgreSQL 分区父表的 JDBC 类型；SQL Server 的 schema、表名模式额外转义 `[`。两项均先用真实容器复现，分别避免漏掉分区父表、把 `LC_META_[AB]` 误匹配到 `LC_META_A` 并显示错误列；修复经独立复核通过。

### 验证结果

- `./mvnw verify -Pslow-dialects`：82 个测试全部通过，无跳过；含 PostgreSQL、MySQL、SQL Server、Oracle 真实容器测试，连接重定向回归、架构检查、schema 注释检查与 Spotless。
- `pnpm build`、`pnpm typecheck`、`pnpm format:check` 通过；build 同时执行隔离约定与样式 token 检查。
- `pnpm test:visual`：完整 40 项通过；长名称样式修正后，另运行 `pnpm test:visual data-source-metadata.spec.ts`，12 项交互及最终视觉基线比对全部通过。
- 浏览器环境：本地开发宿主 `http://127.0.0.1:5176/apps/lowcode/datasources`，Chromium，1440×900 与 1024×768。Browser 插件不可用，沿用仓库 Playwright；前端使用 mock API，未做真实 Shell/网关联调。
