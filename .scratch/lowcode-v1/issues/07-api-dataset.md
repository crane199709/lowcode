# 07: API 数据集

**What to build:** 能在 API 类 Data Source 上定义 Dataset：用 JSON 路径定位结果数组，从样本数据推断 Field。见规格第 4 节。

**Blocked by:** 04, 06

**Status:** resolved

- [x] JSON 路径定位数组，路径无效或结果不是数组时给出可读错误
- [x] 从样本推断 Field 类型，默认的 Dimension / Measure 规则与 SQL 数据集一致
- [x] 复用 06 的 Field 编辑界面
- [x] 受最大行数约束；用 mock HTTP 服务测试

## Comments

### 页面简报

- 持 `dataset:manage` 的成员在既有数据集抽屉选择 API 数据源、填写 JSON 路径，预览并编辑 Field 后保存；`dataset:view` 成员只读查看路径与字段。
- 沿用数据集列表和大抽屉。API 类型在原 SQL 输入位置显示 JSON 路径输入，隐藏数据库只读账号提示；预览、保存和 Field 表格共用 06 的实现。
- 测试沿用 06 已确认的 HTTP API 与 Playwright 边界；外部 API 使用工单指定的本地 mock HTTP 服务。

### 实现说明

- `/datasets/sources` 扩展为数据库与 API 的最小选项列表，仅返回 id、name、type。`/datasets/preview`、创建、修改请求支持 `jsonPath`；数据库类型必须填写 SQL，API 类型必须填写 JSON 路径，不能同时提交两种查询。
- `V8__api_datasets.sql` 为数据集增加 `json_path`。SQL 数据集保持原查询；API 数据集的 `sql_query` 为空字符串。修改类型时清除旧路径；保存均重新请求和推断字段，再应用同名显示配置，拒绝不存在的 Field。
- 使用 JsonPath 解析以 `$` 开头的路径，支持根数组、点路径、括号字段名与数组下标。路径无效/不存在、结果非数组、行非对象、无效 JSON 分别返回可读错误；不返回外部响应正文或解析异常。响应 JSON 严格检查尾随内容。
- 对截断范围内的对象样本按首次出现顺序取字段并合并类型；NULL/缺失不覆盖已知类型，数值默认 Measure，其余 Dimension。不同非空类型合并为 OTHER，嵌套对象/数组按 JSON 文本返回；字符串保持 STRING，可在既有 Field 编辑器标记日期。空数组预览返回空字段/空行。
- API 与 SQL 共用 `lowcode.datasource.sql.max-rows`（默认 10000），超出时截断并返回 `truncated`。HTTP 请求复用 04 的已保存 GET/POST、静态请求体与认证，继续检查禁止网段和固定 DNS 结果，拒绝重定向，使用同一超时与响应字节上限。
- API 的查询无法下推；本工单实现取数和字段模型，Widget 内存聚合在后续 Widget 查询工单完成。

### 设计走查

- 新增 `api-datasets.spec.ts`，覆盖新增预览与字段保存、路径修改保留字段、只读详情、错误后重试与空数组、路径修改后忽略旧预览。
- 桌面 1440×900、笔记本 1024×768 共 10 张新增基线逐张查看：新增、预览截断、只读、路径错误、空数组各两张；空值为 `—`、数值右对齐，长名称、字段表格、底部操作均可读可达。
- Browser 插件不可用，使用仓库 Playwright 开发宿主（`http://127.0.0.1:5176`）与 mock 接口验证；未做真实 Shell / 网关联调。

### 验证与代码审查

- 后端 `./mvnw spotless:apply verify`：88 项测试全部通过，含 API mock HTTP、SQL 回归、MySQL/PostgreSQL/SQL Server/ClickHouse、租户权限、架构与表字段注释检查；格式检查通过。
- 前端 `pnpm build`（含隔离 lint、样式 lint、类型检查）、`pnpm format:check`、`pnpm test:visual` 62 项全部通过；其中 API 数据集新增 10 项交互用例、10 张基线，原基线未修改。
- 初次 `./mvnw verify -Pslow-dialects`：88 项通过，Oracle 容器在等待就绪日志时超时，7 项 Oracle 用例未执行成功；随后 `./mvnw test -Pslow-dialects -Dtest=OracleDataSourceTest -DforkCount=1` 单独重试，7 项全部通过。

#### Standards

发现 1 处可能的重复代码：数据集预览与连接测试各自准备 API 地址。已用同类私有 `prepareApi` 与 `ApiTarget` 合并，并经原审查者复核；剩余硬性规范问题 0，smell 0。

#### Spec

独立审查确认本工单的 JSON 路径、字段推断、Field 编辑、行数限制和 mock HTTP 测试均实现；规格偏差与额外范围问题 0。
