# 03: 补齐其余数据库类型

**What to build:** 除 PostgreSQL 外，还能登记并测试连接 MySQL、SQL Server、Oracle 类 Data Source。见规格第 3 节。ClickHouse 经确认拆至 [18](18-clickhouse-data-source.md)。

**Blocked by:** 02

**Status:** resolved

- [x] 表单按类型显示对应字段（Oracle 为服务名），各类型有自己的连接参数白名单
- [x] MySQL、SQL Server 的真实连接测试随默认 `verify` 运行
- [x] Oracle 的连接测试放在独立 profile（如 `-Pslow-dialects`），`AGENTS.md` 写明运行方式
- [x] 各类型的 URL 拼装拒绝已知危险参数（如 MySQL `allowLoadLocalInfile`）

## Comments

### ClickHouse 拆出

2026-11-11 继续实现时，经用户确认沿用现有拆分：ClickHouse 留在工单 18，本工单验收 MySQL、SQL Server、Oracle。工单 18 记录了 HTTP 驱动的重定向与延迟建连风险，须单独确定满足 ADR-0002 的连接方式；本工单不开放 ClickHouse 类型，也不引入其驱动。规格第 3、9 节中的 ClickHouse 目标仍由 18 完成。

### 页面简报

- **谁在用、主任务**：持 `datasource:manage` 的成员登记和测试三种新增数据库类型，查看成员继续浏览类型与连接地址。
- **主操作**：沿用现有数据源列表与抽屉，保存为主操作，测试连接与取消为次操作。
- **字段位置**：名称后增加类型选择；Oracle 将库名标签改为服务名；账号密码后仅显示所选类型的连接参数。切换类型重置参数，默认端口随类型切换，手填端口保留。
- **原型**：保持 02 的列表与抽屉结构，连接结果显示在表单下方并滚动到可见位置。

### 验收边界

经用户确认，使用现有 REST API 集成测试验证真实数据库连接、登记与危险参数拒绝，使用 Playwright 验证类型切换和表单交互；不直接测试私有 URL 拼装方法。

### 实现说明

- 增加 MySQL、SQL Server、Oracle 类型、对应驱动与参数白名单。MySQL 的库名经编码传入 URL，SQL Server 的库名仅经属性传入，Oracle 的服务名限制为字母、数字、点、下划线和连字符；拒绝驱动参数及描述符注入。
- MySQL 固定关闭本地文件读取、反序列化及 JVM 客户端证书库回退。后者经真实 TLS 连接先复现读取本机证书文件失败，再验证修复后连接成功。
- SQL Server 使用固定 SocketFactory 核验连接目标的 IP 与端口，关闭可能绕过工厂的并行寻址。Oracle 使用每连接的 hostname resolver，只允许首次解析到已检查的 IP，固定关闭代理远程解析。两类连接不支持服务端重定向（包括同 IP 换端口），需要路由的集群应提供可直连的数据库地址。
- Oracle 的网络 I/O、EOF 和未知主机错误统一归为“无法连接”；认证失败、库或服务名不存在仍分别返回可读原因，不输出驱动错误或实际目标地址。
- 表单按类型重置白名单参数、切换默认端口并保留手填端口；Oracle 使用服务名标签。测试结果滚动到可见区域，字段或类型变化及关闭抽屉会使未完成的旧测试结果失效。
- `DataSourceDialectTest` 覆盖真实 MySQL / SQL Server 连接、登记、参数白名单、库名注入和服务凭据隔离；`OracleDataSourceTest` 在 `slow-dialects` profile 验证真实 Oracle；默认运行的 `DataSourceRedirectTest` 用协议模拟服务驱动真实 SQL Server / Oracle JDBC，验证禁止 IPv6 地址和同 IP 换端口的目标均未收到连接。

### 设计走查

沿用 02 的列表和抽屉，新增 MySQL、SQL Server、Oracle 的 1440×900、1024×768 状态；多类型列表、只读列表、通用新增和错误状态一并更新，共 14 张新增或变化的基线已逐张查看。类型、连接参数与服务名标签均正确，长主机名和名称未撑破布局，1024 宽度下操作与结果可见。接口由 Playwright mock；真实数据库连接由后端容器测试验收。

### 最终验证（2026-11-11）

- 后端 `mvn verify`：52 项全部通过，默认包括 MySQL、SQL Server 真实连接及两类驱动的协议重定向回归，不运行 Oracle 容器测试。
- 后端 `mvn verify -Pslow-dialects -DforkCount=1`：58 项全部通过，额外包含 Oracle 容器的 6 项真实连接、登记与参数拒绝测试。单 JVM 运行以控制本机测试资源占用。
- 前端 `pnpm build`（含隔离检查、样式检查与类型检查）、`pnpm test:visual`：全部通过，24 项覆盖 1440、1024 两档。独立 Playwright 检查的页面地址、标题、非空内容、类型切换与端口 / 服务名联动均正确，无框架错误遮罩，控制台 error / warning 均为零。环境为本机 `http://127.0.0.1:5176/apps/lowcode/datasources`；Browser 插件不可用，使用仓库已有 Playwright。
- 规范审查：无发现。规格审查发现服务端重定向可绕过禁止网段检查，修复后复查无剩余问题；安全回归先验证目标实际收到连接，再验证修复后目标零连接。
- 验证范围为本地容器与 mock API 的前端宿主；平台网关和真实租户端到端验收由工单 17 完成。ClickHouse 由工单 18 完成。
