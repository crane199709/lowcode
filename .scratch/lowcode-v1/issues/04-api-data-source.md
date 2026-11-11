# 04: API 数据源

**What to build:** 能登记一个 HTTP JSON 接口作为 Data Source 并测试连接。见规格第 3 节「API 类」、ADR-0002。

**Blocked by:** 02

**Status:** resolved

- [x] 支持 GET、POST（静态 JSON 请求体）
- [x] 认证：无、自定义请求头、Basic；敏感头部值与密码加密保存且不回显
- [x] 禁止网段同样生效；不跟随重定向；有超时与响应体大小上限
- [x] 测试连接返回状态与可读错误
- [x] 用本地 mock HTTP 服务测试认证方式、重定向拒绝、超时与超限

## Comments

### 页面简报（数据源列表与表单中的 API 类型）

- **谁在用、主任务**：持 `datasource:manage` 的成员把租户自有的 HTTP JSON 接口登记为数据源，并用「测试连接」确认地址、方法与认证配置可用；持 `datasource:view` 的成员在列表中看到类型与接口地址。
- **主操作**：沿用 02 的「新增」与抽屉；API 类型与数据库类型共用一个列表，类型选择追加 `API`，表单按类型切换字段。
- **一眼可见**：列表的类型列显示 `API`，连接地址列显示接口 URL，账号列显示 Basic 用户名，无账号时显示 `—`。
- **表单载体**：仍使用 `el-drawer`，API 类型下依次为名称、类型、接口地址、请求方法、POST 时的 JSON 请求体、认证方式，以及按认证方式出现的请求头名称/值或 Basic 账号/密码。请求头值与密码在编辑时留空表示不修改。
- **原型**：列表 `PageHeader` → `PageToolbar` → `ListPage`；API 表单项与数据库表单项共用同一抽屉。

### 设计走查

`e2e/visual/data-sources.spec.ts` 新增「新增 API 数据源」与「编辑 API 数据源」两个状态，1440、1024 两档，共 4 张新基线；列表、只读列表与已有抽屉状态的背景列表加入一条 API 数据源后重新生成。逐张查看 20 张数据源截图后改了两处：

- 类型从数据库切到 API 时，`el-form` 默认会在规则变化时立刻校验新字段，出现一片红色必填提示；给表单加 `:validate-on-rule-change="false"`，切换类型不再提前报错。
- 1024 宽度的 API 新增表单比抽屉高，截图时滚到顶部，保证名称与接口地址可见；请求头值位于下方，滚动后可见。
- 长接口地址在单行输入框内横向滚动，列表中以省略号收起，未撑破布局。

### 实现说明

- **类型与存储**：`DataSourceType` 增加 `API`。`V6__api_data_sources.sql` 为 `data_sources` 增加 `api_url`、`api_method`、`api_body`、`api_auth`、`api_header_name`、`api_header_value_encrypted` 六列，并为表与字段补中文注释；数据库类行的这些列为空值，API 类行把主机、端口、库名写成空值/0，认证方式为 `NONE`，避免切换类型后残留上一类数据源的配置。
- **接口**：沿用 `/data-sources` 的增删改查与两个连接测试接口。请求体的连接配置按类型放在 `database`（`host`、`port`、`database`、`username`、`password`、`params`）或 `api`（`url`、`method`、`body`、`authType`、`headerName`、`headerValue`、Basic 的 `username`/`password`）中，另一项为 null。两类嵌套记录各自带 Bean Validation 注解，响应里的 `errors` 定位到 `database.host`、`api.url` 等字段；未带 `database` 或 `api` 时由 Service 返回 `datasource.databaseRequired` / `datasource.apiRequired`。响应中的 `api` 不含请求头值与密码，Basic 用户名在顶层 `username` 返回以便编辑。
- **请求**：`ExternalApiConnector` 用 Apache HttpClient 5（新增依赖），自定义 `DnsResolver` 只返回 `NetworkGuard` 已检查过的地址，避免检查后再次解析（DNS rebinding）；关闭重定向、自动重试、Cookie、认证缓存与压缩，显式发送 `Accept-Encoding: identity`。GET 不带请求体；POST 的静态 JSON 先用 Jackson 校验再按 `application/json` 发送；自定义请求头名称限制为 token 字符并排除 `Host`、`Content-Length`、`Transfer-Encoding`、`Connection`、`Upgrade`。
- **认证与复用**：无认证不加头；自定义请求头直接作为请求头；Basic 用 `Authorization: Basic Base64(username:password)`。沿用已保存的请求头值或 Basic 密码时，协议、主机、端口、认证方式、请求头名称/账号都必须不变，否则返回 `datasource.apiAuthRequired`；这与数据库密码沿用的考虑一致，防止改地址后把已保存凭据发到别处。
- **错误**：测试连接返回目标接口的 2xx 状态码；3xx 返回 `datasource.apiRedirect`，401/403 返回 `datasource.apiAuthFailed`，其他非 2xx 返回 `datasource.apiHttpError` 并带状态码。解析失败、连接被拒、超时、TLS 失败、读响应失败统一为 `datasource.apiUnreachable`，不区分端口是否开放；响应体超过 `lowcode.datasource.api.max-response-bytes` 返回 `datasource.apiResponseTooLarge`。URL 只接受 http/https，拒绝用户信息、片段、端口 0 与首尾空格。
- **配置**：`lowcode.datasource.api.timeout`（默认 10s）与 `lowcode.datasource.api.max-response-bytes`（默认 1 MiB）由 `ApiProperties` 绑定，默认值只写在 `@DefaultValue`；测试用 500ms/1024 字节覆盖超时与超限路径。超时是连接超时加每次 socket 读超时，没有硬性的整请求截止时间；一个持续慢速吐字节的接口仍可能拖长单次请求，后续取数时再评估是否需要外层截止时间。
- **安全**：请求头值与 Basic 密码走 `SecretCipher`（AES-GCM），响应模型、请求模型、`ApiSettings`、实体与 `toString` 都不输出明文；`DataSourceSecretMapper`（测试专用）直接读表断言两列不是明文。API 数据源同样在保存和连接前检查禁止网段。审计事件经 `toString` 输出，测试断言新增、修改的 Outbox 事件不含请求头值。
- **测试**：`ApiDataSourceTest` 用进程内 `com.sun.net.httpserver.HttpServer` 覆盖无认证/自定义请求头/Basic、GET/POST 与 JSON 请求体、重定向不跟随、超时、Content-Length 与分块响应的超限、401/500/404、连接被拒、禁止网段（保存、修改、两种测试连接）、URL 与请求体校验、凭据沿用边界、密文存储与审计脱敏。`DataSourceApiTest` 增加数据库请求必填字段的字段级 `request.invalid` 用例（如 `database.host`）。`mvn verify` 65 项全部通过；`mvn verify -Pslow-dialects -DforkCount=1` 71 项全部通过，含真实 Oracle 连接。
- **前端**：`data-source.ts` 增加 `API` 类型、API 字段与 `sourceAddress()`；表单按类型切换数据库/API 字段，请求体构造为 `database` 或 `api` 嵌套对象，API 的 URL 做 http/https 与 JSON 请求体校验，测试连接对 API 显示「连接成功（HTTP 200）」。`pnpm build`、`pnpm test:visual`（28 项、20 张数据源基线已逐张查看）、`pnpm format:check` 通过。

### 遗留

- 未做整体请求截止时间（见上）；HTTPS 连接只由 Apache HttpClient 的默认 SNI/主机名校验保障，未加本地 TLS mock 用例。
- 响应体大小上限按传输字节计数，不负责解压后的 JSON 解析成本。
- API 的 JSON 路径定位、结果数组取样与 Field 推断留给 07。
