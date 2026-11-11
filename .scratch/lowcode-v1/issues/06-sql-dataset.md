# 06: SQL 数据集

**What to build:** 持 `dataset:manage` 的 Membership 能在一个数据库类 Data Source 上写一段 SQL 定义 Dataset，预览结果并调整推断出的 Field。见规格第 4 节、ADR-0001。

**Blocked by:** 02

**Status:** resolved

- [x] 只接受单条 `SELECT` / `WITH`，其他语句与多语句被拒绝并返回 Error Code
- [x] 执行时连接只读，有查询超时与最大行数；界面提示使用只读账号
- [x] 保存时执行预览查询，从结果集元数据推断 Field：数值类型默认 Measure，其余 Dimension
- [x] 可修改 Field 显示名、切换 Dimension / Measure、标记为日期
- [x] Dataset 的列表、新增、编辑、删除（逻辑删除）；`dataset:view` 只读
- [x] PostgreSQL 容器测试覆盖只读拦截、超时、行数上限与 Field 推断

## Comments

### 页面简报（已由用户确认）

- 持 `dataset:view` 的 Membership 浏览本租户的数据集与 SQL / Field；持 `dataset:manage` 的 Membership 新增、编辑、预览、删除。
- 列表沿用 `PageHeader` → `PageToolbar` → `ListPage`，一眼可见名称、数据源、字段数、更新时间；新增是主操作，查看、编辑、删除为行内入口。
- SQL 与 Field 配置使用大抽屉；顶部提示使用只读账号，底部为预览、取消、保存。只读成员查看定义，不提供预览或修改入口。
- 测试边界经用户确认：HTTP API + PostgreSQL 容器、Playwright 页面交互与视觉回归。

### 实现与验证

- 新增 `/datasets` 的分页列表、详情、创建、修改与逻辑删除，以及 `/datasets/preview`；管理权限可经 `/datasets/sources` 获取当前租户的数据库数据源标识与名称，不返回连接与凭据。
- SQL 经 JSqlParser 验证只有一条 SELECT / WITH，再按语法节点排除写语句（含 CTE 内写入、SELECT INTO）；字符串与注释中的关键字和分号不影响验证。
- 执行复用 `ExternalJdbcConnector` 的禁止网段检查及固定目标连接，使用只读事务、查询超时与行数限制。`lowcode.datasource.sql.timeout-seconds` 默认 30，`max-rows` 默认 10000；原有连接探测的 10 秒读取预算不提前中断 SQL 查询。
- 每次保存均重新执行预览，零行结果同样按元数据推断类型；数值默认度量，其余默认维度。可调整显示名、用途与日期标记；重复名称、未知 Field 配置、失败查询均拒绝保存。
- PostgreSQL 容器用具备写权限的账号调用 SELECT 内的写函数，验证只读事务拦截、数据未变；同时覆盖超时、行数上限、NULL、Field 推断与调整、权限、租户隔离和删除。
- `./mvnw spotless:apply verify -Pslow-dialects`：88 项测试全部通过（含 Oracle、架构、表与字段注释检查）；显式导入调整后相关测试 7 项通过。
- `pnpm build`（含 lint、样式 lint、类型检查）与 `pnpm format:check` 通过；`pnpm test:visual` 全量 52 项通过。
- 前端数据集交互用例 14 项通过，覆盖新增保存、编辑预览与 Field 保存、删除、只读详情、错误提示及 SQL 修改后忽略旧响应；1440×900 / 1024×768 两档 12 张基线截图逐张走查。
- API 类 Dataset 属于工单 07；删除引用保护属于工单 16。本次未执行真实 Shell / 网关端到端联调，页面测试 mock 业务接口，后端独立使用真实数据库容器。

### 代码审查与修复

- **Standards**：发现 1 处规范问题（单 Controller 专用请求模型的位置）和 1 处轻量 smell（重复设置读取超时），均已修复，独立复核无剩余问题。
- **Spec**：发现 2 处行为问题，均补回归后修复并独立复核：合法别名 `AS lock/update` 被词法黑名单误拒，改为遍历语法节点；SQL 调整后已有 Field 配置被清空，改为缓存覆盖、重新推断后按同名恢复，直接保存时先刷新失效模型。
- SQL 回归另覆盖 INSERT / UPDATE / DELETE CTE、WITH 后 VALUES、子查询中的 SELECT INTO；页面回归验证仅改 SQL 空白后直接保存仍保留显示名与日期标记。
