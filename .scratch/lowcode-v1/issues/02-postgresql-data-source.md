# 02: 登记 PostgreSQL 数据源

**What to build:** 持 `datasource:manage` 的 Membership 能在数据源页登记、编辑、删除一个 PostgreSQL Data Source 并测试连接；持 `datasource:view` 的只能查看。这是第一个打穿前后端的切片，确立连接安全约束。见规格第 3 节、ADR-0002。

**Blocked by:** 01

**Status:** resolved

- [x] 只收结构化字段（主机、端口、库名、账号、密码、白名单连接参数），服务端拼装 URL；请求带非白名单参数被拒绝
- [x] 密码加密保存，任何接口不回显；编辑时留空表示不修改
- [x] 禁止网段与加密密钥为必配项，漏配启动失败；主机解析结果落入禁止网段时拒绝保存与测试连接
- [x] 测试连接返回成功或可读的失败原因，不暴露端口是否开放等探测细节
- [x] Data Source 按 Tenant 隔离；删除为逻辑删除（引用保护见 16）
- [x] 新增、修改、删除写 Audit Event
- [x] 后端用 Testcontainers PostgreSQL 做真实连接测试，覆盖禁止网段、参数白名单、密码不回显与权限
- [x] 前端列表与表单按 `docs/standards/sub-app.md` 三步完成，视觉用例覆盖有数据与空列表，走查查看截图

## Comments

### 页面简报（数据源列表与表单）

- **谁在用、主任务**：持 `datasource:view` 的成员浏览本租户的数据源；持 `datasource:manage` 的成员登记、修改、删除数据源，并测试连接。
- **主操作**：「新增」放在 `PageToolbar` 的 `#actions`；编辑、删除是行内 `link` 入口。只读成员看不到新增按钮和操作列，接口仍由后端校验权限。
- **一眼可见**：名称、类型、连接地址（`主机:端口/库名`）、账号、更新时间。SSL 等参数和创建时间收进编辑表单，不在列表展示。
- **表单载体**：共 7 个字段，超过对话框「少于 6 个」的上限，所以用 `el-drawer`（`overlaySize.drawerMd`），顶部标签。页脚依次为「测试连接」（默认按钮）、「取消」、「保存」（主按钮）。测试结果显示在表单下方：成功用 `el-alert`，失败用 `ErrorAlert`，附 traceId。
- **原型**：列表 `PageHeader` → `PageToolbar` → `ListPage`。

### 设计走查

`e2e/visual/data-sources.spec.ts` 覆盖有数据、空列表、只读成员、新增抽屉、编辑时测试连接失败五个状态，各有 1440、1024 两档宽度，共 10 张截图。mock 数据含最长的名称和云数据库主机名。生成后逐张查看，改了两处：

- 1024 宽度下列宽总和超过表格宽度，「更新时间」被固定的操作列遮住。收窄名称、类型、地址、账号的最小宽度后，所有列同行可见，长文本以省略号收起。
- 抽屉里的「类型」只有 PostgreSQL 一个选项，并且是禁用的下拉框，删掉不影响任何操作，所以先去掉，03 增加类型时再放回。

编辑抽屉的密码框始终为空，占位符写「留空表示不修改」。原 `list-pages.spec.ts` 中的数据源空列表用例移入新文件，基线 `datasources-empty-*.png` 改为带「新增」按钮和操作列表头的版本。共享的假会话代码提取到 `e2e/visual/session.ts`。`pnpm build`、`pnpm test:visual` 通过。

### 实现说明

- **接口**：`/data-sources` 增删改查，以及 `POST /data-sources/connection-test`（未保存）和 `POST /data-sources/{id}/connection-test`（编辑中，密码留空时用已保存的密码）。查看要求 `datasource:view`，其余要求 `datasource:manage`。新增、修改、删除用 `@AuditLog` 写 `datasource.create/update/delete`。
- **结构化连接**：请求只有主机、端口、库名、账号、密码、`params`。主机只允许主机名或 IP 字面量的字符，排除多主机写法中的逗号和 URL 分隔符。`params` 按 `DataSourceType` 白名单逐项校验，PostgreSQL 只开放 `sslmode` 的 `disable/allow/prefer/require`，不含读取本机证书文件的 `verify-*`；其余参数一律以 `datasource.paramNotAllowed` 拒绝。URL 由服务端拼装，库名经 URL 编码，参数只经 `Properties` 传入，另外固定连接超时，并关闭 GSS。
- **禁止网段**：`lowcode.datasource.blocked-networks`（CIDR，支持 IPv6）与 `lowcode.datasource.encryption-key`（Base64 编码的 32 字节 AES 密钥）没有默认值，漏配或格式错误时启动失败。主机的全部解析结果都要检查，`0.0.0.0` 和 `::` 不依赖配置，一律拒绝。连接时使用检查过的 IP 而不是主机名，避免 DNS rebinding。暂时无法解析的主机允许保存，连接时再检查。
- **失败原因**：只分为账号或密码错误、数据库不存在、无法连接（无法解析、端口拒绝、超时、非数据库端口都归入这一类）、其他连接失败四种，HTTP 422，不暴露端口是否开放。
- **密码**：AES-GCM 加密存入 `password_encrypted`，响应模型中没有密码字段。请求模型、`ConnectionSettings`、实体和配置类都覆写了 `toString`。
- **存储**：`V5__data_sources.sql`（标准列，RLS ENABLE + FORCE）。`params` 用 `text` 存 JSON：jsonb 需要自定义 TypeHandler，而 TypeHandler 依赖 `java.sql`，同样会触发架构规则。删除走 `AuditableEntity` 的逻辑删除。
- **JDBC 豁免**：平台架构规则禁止业务代码依赖 JDBC，也没有豁免机制。经确认，外部连接只放在 `ExternalJdbcConnector` 一个类里，`ArchitectureTest` 改为逐条执行共享规则，只对这个类跳过 `persistence()`。平台反馈：saas-forge-v2 `.scratch/lowcode-validation/issues/06-external-jdbc-exemption.md`。
- **审查后修复**：
  - 沿用已保存的密码时，主机、端口和账号必须与保存时一致，否则返回 `datasource.passwordRequired`。原实现中，持 manage 的成员只要改掉主机，就能让服务端把已保存的密码发给自己控制的服务器；修改和测试连接都受此约束。
  - 4xx 响应的 `errorMessage` 会原样返回，所以改为固定文案，解析出的地址和 SQLState 只写日志；无法解析与连接失败的响应完全一致。
  - 固定 `sslfactory=NonValidatingFactory`，避免驱动把服务所在机器的 `~/.postgresql` 客户端证书发给对方。
  - 前端修改任一字段后清除「连接成功」提示。
- **测试**：`DataSourceApiTest` 用共享 PostgreSQL 容器中的普通账号充当租户的外部库，覆盖真实连接成功与各类失败、禁止网段（含 IPv6 与 `0.0.0.0`，涉及保存、修改、两种测试连接）、参数白名单、密码不回显与留空不修改、权限、租户隔离、逻辑删除，以及从 Outbox 读取 Audit Event。`DataSourceSecurityConfigTest` 覆盖漏配启动失败和域名解析结果检查。`mvn verify` 28 个测试全部通过。

### 遗留

- 引用保护（被 Dataset 引用时禁止删除）留给 16。
- `ArchitectureTest` 不再继承 `ForgeArchitectureTest`，平台新增的共享规则需要手动同步，平台提供豁免后恢复继承。
- 回环地址只有配进禁止网段才会被拦截，程序没有固定拒绝，因为测试要经本机端口连接容器。部署时禁止网段应包含 `127.0.0.0/8`、`::1/128` 和平台内网；NAT64 等转换前缀同样靠配置。
- `SharedContainers.externalPostgres()` 用 psql 内联 SQL 创建外部库账号，沿用该类原有的建库写法。它准备的是账号而不是测试数据，`@Sql` 只能作用在应用自己的库上，这里用不了。
- 本机 DNS 若有 fake-IP 代理（如解析到 198.18.0.0/15），不存在的域名也会解析成功，测试中「无法解析」实际走的是「连接失败」分支，两者返回同一个错误码，结论不受影响。
