# 18: 登记 ClickHouse 数据源

**What to build:** 能登记并测试连接 ClickHouse 类 Data Source，连接安全与其他数据库类相同（ADR-0002）。从 03 拆出，见 03 的「ClickHouse 拆出」一节。

**Blocked by:** 03

**Status:** resolved

- [x] 连接方式：复用现有 Apache HTTP5 客户端能力访问 ClickHouse HTTP；DNS 固定到检查过的 IP，并禁止重定向，不引入 ClickHouse JDBC 驱动。
- [x] 测试连接真正访问服务器（v2 驱动的 `getConnection` 不发请求），失败原因归入现有四类
- [x] 连接参数白名单；经 IP 连接时 TLS 主机名校验与 SNI 如何处理
- [x] Testcontainers ClickHouse 真实连接测试随默认 `verify` 运行，含「服务器返回重定向时不跟随」的用例
- [x] 前端类型选择加入 ClickHouse
- [x] 保留现有 `DataSourceService.previewSql` 查询入口供 11 复用 HTTP 连接；分组聚合方言仍由 11 实现。


## Comments

### 实现与验收边界

沿用已确认的 REST API 与真实容器连接 seam：测试连接须执行 SELECT，登记不回显凭据，真实 ClickHouse 验证错误分类、元数据与 SQL Dataset 预览；本地 HTTP 服务验证禁止重定向。选择现有 Apache HTTP5 的自定义 DNS 方式，连接检查过的 IP，URI 保留原始主机用于 TLS 主机名校验和 SNI；禁用重定向、重试、Cookie 与压缩。参数只开放 ssl=true/false，不开放证书路径、代理或跳过校验。

### 页面简报

数据源管理成员沿用现有抽屉登记 ClickHouse；类型列表增加 ClickHouse，默认 HTTP 端口 8123，账号密码后显示加密连接选项。保存为主操作，测试连接为次操作。其余页面结构沿用现有设计。


### 实现结果

- `CLICKHOUSE` 数据源沿用结构化连接字段、加密存储与网段校验；密码只在 Basic 请求头中发送。连接探测实际执行 `SELECT 1`，认证失败、库不存在、不可达与其他连接失败分别沿用现有错误码。
- HTTP URI 保留原始主机名；Apache DNS resolver 只返回 `NetworkGuard` 已检查的 IP，TLS 证书与 SNI 使用原始主机。TLS 保留证书链与主机名校验，不加载 JVM 客户端证书，不允许配置证书文件、代理或跳过校验；参数仅允许 `ssl=true/false`。
- 浏览表与列通过 `system.tables` / `system.columns`，数据库与表名经 ClickHouse 类型参数传入并精确匹配；先检查同一账号可见表，跨库表标识返回现有 tableNotFound。
- SQL Dataset 复用单条 SELECT/WITH 校验，HTTP 固定 `readonly=1` 与执行超时，外层 LIMIT 限制到配置上限加一行；JSONCompact 元数据推断数值、布尔、日期等 Field，保留空值。HTTP 响应沿用 `lowcode.datasource.api.max-response-bytes`（默认 1 MiB），超限返回固定失败，不将对端正文、SQL 或凭据传给调用方。
- `ClickHouseDataSourceTest` 不带 slow 标签，随默认 verify 运行；真实 ClickHouse 25.8 容器统一由 SharedContainers 启动。HTTP 重定向与 TLS 回归使用本地测试服务，所有断言经 REST 接口观察。

### 设计走查

Browser plugin not available，使用仓库 Playwright，在 `http://127.0.0.1:5178/apps/lowcode/datasources` 独立端口验收。类型选择 → ClickHouse → 加密连接 → 测试连接成功 → 保存请求的类型与参数正确；页面标题、非空内容、框架遮罩与控制台检查通过。1440×900、1024×768 两张新增基线已逐张查看，长主机名、字段、按钮均可读可达；原有界面结构不变。前端接口 mock，真实数据库行为由后端容器验收。

### 技术资料

- [ClickHouse HTTP 接口](https://clickhouse.com/docs/concepts/features/interfaces/http)：结构化请求参数、认证、只读设置以及 HTTP 200 正文可能含执行异常的处理。
- [JSONCompact](https://clickhouse.com/docs/reference/formats/JSON/JSONCompact)：Field 元数据与数组行的返回格式。
- [Apache 自定义 DNS + 默认 TLS 示例](https://github.com/apache/httpcomponents-client/blob/master/httpclient5/src/test/java/org/apache/hc/client5/http/examples/ClientConfiguration.java)：固定连接 IP，同时保留原始 TLS 主机。


### 最终验证（2026-11-11）

- TDD：先验证 ClickHouse 类型未开放的 REST 红灯，再通过真实连接探测；元数据 / Dataset 预览通过对应 REST 红绿循环；畸形成功行先复现 system.error 后改为固定 connectionFailed；合法 JSON 中的 exception 先复现错误地成功，再补拒绝逻辑。重定向目标收到零个请求，失败正文不回显凭据或对端错误。
- `./mvnw verify -Pslow-dialects -DforkCount=1`：最终 102 项全部通过，含 ClickHouse 7 项、Oracle 7 项、MySQL / SQL Server 真实连接、SQL Server / Oracle 协议重定向、架构与表注释检查及 Spotless。此轮编译并执行全部最终代码与新用例。
- 响应超限验证使用本地 HTTP 的固定 Content-Length，分别验证连接探测和 Dataset 预览在读取正文前拒绝超限响应；真实 ClickHouse 单独验证只读、超时与行数限制，避免响应体生成与 1 秒查询超时预算竞争。
- 前端 `pnpm build`、`pnpm format:check`、全部 Playwright 视觉 / 交互用例 64 项均通过。并发验收仅使用临时 5178 端口配置，已移除，标准配置未变。两张 ClickHouse 基线随代码提交。
- 验证使用本地容器与前端 mock API；真实平台网关和租户全链路仍由 17 验收。11 可直接沿用 `DataSourceService.previewSql`，不用再增加 ClickHouse 连接驱动。
