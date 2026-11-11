# 16: 引用保护与失效提示

**What to build:** 被引用的 Data Source 或 Dataset 不能删除，用户看到引用它的对象清单；Dataset 修改后失效的 Widget 在 Playback 时显示「字段不存在」。见规格第 6 节「草稿与发布」、第 7 节。

**Blocked by:** 06, 10

**Status:** resolved

- [x] Data Source 被 Dataset 引用时禁止删除，返回引用清单与 Error Code
- [x] Dataset 被任一 Screen 的草稿或已发布快照引用时禁止删除，返回引用清单与 Error Code
- [x] Dataset 的 Field 被删改后，引用它的 Widget 在编辑器与 Playback 中显示「字段不存在」，其余 Widget 正常
- [x] 前端删除确认弹窗展示引用清单

## 页面简报与测试边界

- 设计者在数据源、数据集列表发起低频删除；列表仍以新增为主操作。删除确认弹窗先展示引用对象名称，存在引用时不能提交；提交时服务端再次检查，出现新增引用时在同一弹窗展示失败与最新提示。沿用列表原型和 Element Plus 确认弹窗，不增加页面入口。
- 已获授权的测试边界：公开资源删除/引用 HTTP（真实 PostgreSQL、Redis）、Widget 查询 HTTP，以及 Playwright 编辑器和 Playback。引用保护覆盖同 Tenant 的草稿和发布快照，不受调用者 Screen Data Scope 限制；隐藏引用不返回实际 ID、名称。

## Answer

已完成 Data Source / Dataset 引用保护、删除确认引用清单，以及 Dataset Field 失效时的编辑器与 Playback 局部提示。

### 公开契约与引用隔离

- 新增 `GET /data-sources/{id}/references`（`datasource:manage`）与 `GET /datasets/{id}/references`（`dataset:manage`），响应 `data={references:[{id,name}],hiddenReferences}`，只返回引用对象元数据。对应 DELETE 在事务中再次检查；被引用分别返回 `datasource.referenced` / `dataset.referenced`、HTTP 409，`userTip` 带可公开的引用名称清单。SDK `ForgeException` 不支持结构化错误 data，因此结构化清单由上述 GET 返回，未另建错误信封。
- Data Source 查本功能包 DatasetMapper；Dataset 经本功能包 `DatasetReferences` 接口读取独立 `ScreenDatasetReferences` 服务。后者只依赖自身 Mapper、JSON、权限 API，不依赖 ScreenService 或查询服务，避免功能依赖和 Spring Bean 循环。
- Screen 扫描草稿与已发布快照，按 Screen 去重，使用 `Widget.isQueryWidget()` 覆盖全部七类取数 Widget。专用内部 mapper 方法仅 `@InterceptorIgnore(dataPermission="true")`，始终保留 Tenant 注入与 RLS，并显式排除逻辑删除。
- 引用保护覆盖调用者 Screen Data Scope 外的同 Tenant 大屏；响应另按功能权限与 `DataPermission.permits` 过滤。隐藏对象只返回通用提示，不含实际 ID / 名称；只有查看权限时使用公开的 publishedName，不泄露草稿名称和未发布大屏。无认证系统操作沿用仅受 Tenant 隔离的既有语义，HTTP 仍要求权限。
- 删除继续使用 `deleteById` 逻辑删除。查询、连接配置、密码与请求头值均不进入引用清单。

### 删除与并发引用写入

- 删除在检查引用前锁定目标 Source / Dataset。Dataset create/update 在远程预览结束后的最终短事务中锁定并复查 Source；Screen 新建、全量修改、保存草稿和复制的最终写事务按固定 UUID 顺序锁定并复查 Dataset。
- 保存先取得目标锁时，删除等待提交后重新查引用并返回 409；删除先完成时，后续保存返回 `datasource.notFound` / `dataset.notFound`，不再写入悬空引用。远程 SQL/API 预览不持有最终写入锁。
- Publish 仍原子执行 `published=draft`；草稿保存已保护引用，删除始终同时扫描两份内容，因此发布无需另行引入逆序锁。
- 确定性并发测试只通过外部 API 响应闸门、数据库行锁和系统活动视图安排时序，结果全部从公开 HTTP 断言。活动视图探针位于测试目录，沿用 SchemaCommentMapper 对无 tenant_id 系统视图的例外；业务行锁和生产查询未跳过 Tenant / RLS。

### 字段与页面行为

- 当前 Field 缺失，以及数值聚合所需 Field 改为非 NUMBER、日期分组所需 Field 取消日期能力，都返回 `dataset.fieldMissing` / 422、“字段不存在，请重新配置组件”。纯显示名修改继续更新查询标签，日期值本身无效的既有错误码保留。
- 数据源 / 数据集列表共用删除确认弹窗：先显示引用清单，存在引用时禁用删除；确认期间新增引用时保留弹窗，显示权威 DELETE 错误并重新获取清单。无引用时可取消或成功删除后刷新列表。
- 编辑器与 Playback 均逐 Widget 显示字段失效提示，另一个正常取数 Widget 继续渲染。整合工单 14 后保留 Query mode/fields 与七类取数 Widget 判断。

### 验证与设计走查

- 红灯证据：Data Source / Dataset 原可被引用后删除（200，应为 409），分别见 `/private/tmp/lowcode16-red-source.log`、`lowcode16-red-dataset.log`；同名 NUMBER→STRING 原返回 queryInvalid，见 `lowcode16-red-field.log`；Source 在外部预览期间被删除后原仍保存成功，见 `lowcode16-red-concurrency.log`；草稿写入与 Dataset 删除交错原均成功，见 `lowcode16-red-screen-race.log`；前端原未展示引用清单，见 `lowcode16-red-ui.log`。均先观察失败再实现并复测。
- 最终 `./mvnw verify -DforkCount=1`：181 tests，0 failures / errors / skips，含 PostgreSQL、MySQL、SQL Server、ClickHouse、11 项引用与并发 HTTP 用例、Widget 查询、架构、SchemaComment、Spotless。日志 `/private/tmp/lowcode16-backend-verify-final.log`。未改外部连接器，按规则运行默认 profile；Oracle slow profile 本项未重复运行。
- 最终 `pnpm build`、`pnpm format:check` 通过，含隔离 lint、style token、类型检查。日志 `/private/tmp/lowcode16-ui-build-combined.log`、`lowcode16-ui-format-combined.log`；ECharts 既有打包体积提示仍存在。
- 最终完整 Playwright：130 tests 全过，日志 `/private/tmp/lowcode16-ui-full-combined.log`。测试 URL `http://127.0.0.1:5196`，Chromium，1440×900 / 1024×768。Browser plugin not available，使用仓库 Playwright；接口由 mock 提供，真实计算、权限和并发由后端 HTTP 容器测试验证。
- 新增基线 10 张：`datasource-delete-referenced-*`、`dataset-delete-referenced-*`、`dataset-delete-new-reference-*`、`field-missing-editor-*`、`field-missing-playback-*`。全部逐张查看，覆盖两档引用、隐藏引用、确认期间新引用及正常 / 失效组件并存。走查移除了错误状态下重复的通用引用警告，并重拍复查对应两张。页面身份、非空、无框架覆盖层、无应用 JS / Vue 运行时错误、交互结果通过；409 / 422 是预期错误场景。
- 完整 UI 回归中补齐既有 Dataset 删除 mock 的 references 契约；同步 11 / 14 时修正普通 drag 测试误用超出作用域 date 变量的问题，最终全套正常通过。未验证真实 Shell / 网关、移动端、Firefox 或 WebKit。
