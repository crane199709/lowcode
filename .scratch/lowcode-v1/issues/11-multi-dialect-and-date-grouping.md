# 11: 多方言聚合与日期分组

**What to build:** 绑定任一数据库类 Data Source 的 Widget 都能正确聚合，日期 Dimension 能按日 / 月 / 年分组。见规格第 5 节「查询能力」。

**Blocked by:** 03, 10, 18

**Status:** resolved

- [x] 分组聚合查询覆盖 MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse
- [x] 日期 Dimension 按日 / 月 / 年分组，各方言结果一致
- [x] Top N 在各方言下正确（含 Oracle、SQL Server 的分页写法）
- [x] 每种方言有容器测试；Oracle 在独立 profile 中运行

## Comments

### 依赖补正（2026-11-11）

ClickHouse 连接实现已从 03 拆为 18；本工单的 ClickHouse 聚合需沿用 18 的 HTTP 查询入口，因此显式加入依赖，避免只等待 03、10 就开始多方言实现。17 的全链路验收同步补齐 18 依赖。

### 实现边界与页面简报

沿用已授权的 Screen HTTP API + 各方言真实容器和 Playwright seam。设计者在现有维度放置区的日期 Field 旁选择原值/日/月/年，保持保存草稿为主操作；日期分组是低频轴配置。截图覆盖 1440×900、1024×768。Browser plugin not available，使用仓库 Playwright。


## Answer

已实现五方言分组聚合、日期 Dimension 分组与 Top N。`SqlAggregation` 是两种专用连接器共用的内部 SQL 组合函数，`SqlDateGrouping` 只负责日期表达式；不增加数据提供者框架、缓存或关联能力。JDBC 仍只在既有 allowlist 的 `ExternalJdbcConnector` 中，ClickHouse 沿用固定 DNS、禁止重定向的 HTTP 连接器。

### 日期与共享契约

- 与 12 共用 `Dimension(String field, DateGrouping dateGrouping)`，`DateGrouping=DAY|MONTH|YEAR`，null 表示原值；保留单参数构造器。JSON 为 `{field,dateGrouping?}`，分组输出列为 STRING，标签为 `yyyy-MM-dd` / `yyyy-MM` / `yyyy`，保留 null 分组。
- DATE 与无时区 DATETIME 保留日历值，不猜测其业务时区；带 offset 或类型自带 zone 的时间统一按 UTC 分桶。PostgreSQL/MySQL/Oracle 查询会话固定 UTC，SQL Server 显式 SWITCHOFFSET，ClickHouse 格式化显式 UTC。MySQL DATETIME 不存 offset，真实容器夹具在入库前已按独立已知字面值转换，不能从该类型推断来源时区。
- 日期 Field 可为原生 DATE/DATETIME，或标记为日期的 ISO 字符串。SQL 字符串的共同输入形式为 `yyyy-MM-dd` 或 `yyyy-MM-ddTHH:mm:ss[.fraction][Z|±HH:mm]`；小数秒最多 9 位，日期桶保留原日历日，避免低精度数据库把午夜前小数秒舍入次日。其他格式先在 Dataset SQL 中显式转换。非法日期返回共享的 `dataset.queryDateInvalid` / 422，带 Field 显示名；Top N 也不能掩盖输入中的坏日期。
- PostgreSQL 字符串日期有效性检查采用 PostgreSQL 16+ 的 [`pg_input_is_valid`](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-INFO-VALIDITY)，本工单实际容器为 PostgreSQL 17、MySQL 8.4、SQL Server 2022、Oracle 23、ClickHouse 25.8。未承诺旧数据库版本或各源范围之外的日期值。
- 继续复用 10 的 EQ(null)、IN 含 null、含两端 BETWEEN、计数排除 null、聚合空值、数值 MAX/MIN、ASC/DESC 均空值最后和 Dimension 决胜顺序。SQL Server 提升整数聚合以保留 AVG 小数并避免 int SUM 溢出；ClickHouse 使用 uniqExact 与 OrNull 聚合。数值精度仍受各数据库原生数值类型约束。
- PostgreSQL/MySQL/ClickHouse 使用 LIMIT、Oracle 使用 FETCH FIRST、SQL Server 使用 TOP，过滤与限行均用参数。SQL Server 将源 CTE 放在合法外层，去掉不影响聚合的裸排序；带 TOP/OFFSET/FETCH 的来源限行语义保留。CH 的 UNION 排序使用其原生支持的外层 SELECT，未放宽 SELECT/WITH 守卫。
- 日期控件现位于 13 泛化后的 `ScreenChartProperties.vue`，只对当前标记日期的 Dimension 显示原值/按日/按月/按年。已合入 12、13 的最新集成基线，保留 API 分派和通用图表属性。

### TDD 与验证

- 红灯记录：MySQL 尚不支持取数、日期未分桶、缺少日期控件、Oracle 格式参数必须为文字、SQL Server/Oracle 带时区元数据被归 OTHER、SQL Server UNION 裸排序派生表限制、SQL Server 九位小数秒舍入到次日，均先由公开 HTTP/浏览器结果暴露，再实现并复测。日志分别位于 `/private/tmp/lowcode11-red-mysql.log`、`lowcode11-red-dates.log`、`lowcode11-red-ui.log`、`lowcode11-oracle-debug.log`、`lowcode11-dialects-all.log`、`lowcode11-red-sqlserver-wrap.log`、`lowcode11-red-fraction.log`。
- `DialectQueryApiTest` 的五种真实容器各 7 条 HTTP 契约，共 35 条，使用 12 所有的 `QueryResultAssertions` 字面结果验证六聚合、整数 AVG、去重、NULL/空输入、EQ/IN/BETWEEN、排序/Top N、原生时间与 ISO 字符串的日/月/年、闰日/跨年/null、午夜九位小数秒、+14:00/-13:59 跨 UTC 日界、非法日期及源 WITH/ORDER BY/限行。Oracle 类标记 `slow-dialect`，其他四类由默认 verify 发现。
- 最终 `./mvnw spotless:apply verify -Pslow-dialects -DforkCount=1`：177 tests，0 failures/errors/skips，含协议防重定向、架构及 SchemaComment。日志 `/private/tmp/lowcode11-full-slow-final.log`。
- `pnpm build` 与 `pnpm format:check` 通过，含隔离、style token 与类型检查；完整 Playwright 108 tests 通过。日志 `/private/tmp/lowcode11-ui-build-final.log`、`lowcode11-ui-format-final.log`、`lowcode11-ui-all-final.log`。
- 新增日期交互两档 1440×900 / 1024×768：原值→按月并保存→按年→按日，确认查询请求携带新配置；URL/title、非空页面、无 Vite overlay、console/pageerror 均通过。`date-grouping-desktop.png`、`date-grouping-laptop.png` 使用实际长度的日期 Field 和日期结果，已逐张查看，普通基线比对 2 tests 再次通过。日志 `/private/tmp/lowcode11-ui-dates-qa.log`、`lowcode11-ui-dates-baseline.log`。
- 浏览器为本地 Chromium / `http://127.0.0.1:5191`，Browser plugin not available。未验证真实 Shell/网关部署、移动端、Firefox/WebKit；UI 接口使用 mock，计算结果由真实数据库 HTTP 用例验证。
