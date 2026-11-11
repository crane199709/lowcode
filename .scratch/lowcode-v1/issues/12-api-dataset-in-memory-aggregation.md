# 12: API 数据集在内存中聚合

**What to build:** 绑定 API 数据集的 Widget 与 SQL 数据集一样支持聚合、日期分组、排序、Top N 与静态过滤。见规格第 4、5 节。

**Blocked by:** 07, 10

**Status:** resolved

- [x] 在服务端内存中完成聚合、分组、排序、过滤，语义与 SQL 方言一致
- [x] 受最大行数约束，超出时给出可读提示而非静默截断
- [x] 与 SQL 方言共用一组结果断言用例

## Answer

已实现 API Dataset 的完整有界内存查询，复用现有 BAR 与公开 Widget 查询接口；不新增前端控件、缓存或独立 HTTP 连接栈。

- `DatasetQueryService` 在校验当前保存的 Field 后，按 Dataset 保存的 JSON 路径选择 API 通路。读取沿用 `DataSourceService.previewDataset`，继承已保存认证、地址检查、固定 DNS 结果、禁止重定向、超时与响应字节上限。
- 原始输入 `truncated=true` 时，在过滤、分组、聚合与 Top N 前直接返回 `dataset.apiAggregationTooManyRows` / 422，提示实际配置的最大行数，不能把部分数据当作完整总数。
- 按 API 实际字段名映射当前保存 Field，忽略原始字段顺序；缺失和显式 NULL 均保留为空。引用 Field 的当前运行类型不兼容时返回 `dataset.queryValueInvalid` / 422，当前 Dataset 已删除或改名的引用 Field 返回既有 `dataset.fieldMissing`。
- 六类聚合和执行顺序沿用工单 10：原始 Field 静态过滤 → 日期/普通维度分组 → 聚合 → 输出排序 → Top N。COUNT(field)/COUNT_DISTINCT 排除 NULL，其他聚合仅接受 NUMBER 并忽略 NULL，空输入或全空得到 NULL；无 Dimension 的空输入仍有一行聚合，有 Dimension 时为空行集。
- API JSON 小数直接解为 BigDecimal，避免先经 Double 丢精度；分组、去重和比较忽略 BigDecimal 的 scale。SUM/MAX/MIN 保留十进制输入精度，AVG 使用 DECIMAL128（34 位有效数字、HALF_EVEN）。EQ(null) 匹配 NULL，IN 可包含 NULL，BETWEEN 含两端。ASC/DESC 都把 NULL 放最后，并以未指定的 Dimension 作稳定决胜顺序。
- 与工单 11 共用 `Dimension(String field, DateGrouping dateGrouping)`；`dateGrouping` 为可空 DAY/MONTH/YEAR，保留单参数构造器与旧 JSON。日期分组要求当前 Field 的 `date` 标记；标为日期的 STRING 接受 ISO 日期/日期时间。带 offset/zone 先转 UTC，无时区值保留自身日历日期，输出 yyyy-MM-dd / yyyy-MM / yyyy；非法值返回 `dataset.queryDateInvalid` / 422，附字段显示名和 ISO 转换提示。

### 验证

- 测试边界沿用用户已确认的 Widget HTTP/公开查询服务、真实 PostgreSQL/Redis 与本地 mock HTTP API；未测试私有聚合器或 SQL 文本生成细节。
- TDD 红灯日志：`/private/tmp/lowcode12-red-date-contract.log`（日期配置被忽略）、`lowcode12-red-aggregate.log`（API 不支持）、`lowcode12-red-filters.log`（过滤和 Top N 未应用）、`lowcode12-red-maxrows.log`（超限仍成功）、`lowcode12-red-empty.log`（无维度空输入缺少聚合行）、`lowcode12-red-dates.log`（未分日期桶）、`lowcode12-red-precision.log`（9007199254740993.1 经 Double 丢精度）、`lowcode12-red-types.log`（类型漂移引发 500）。每个切片均先观察行为失败，再实现并复测。
- `ApiDatasetQueryTest` 新增 10 个公开 HTTP 用例，覆盖全部聚合、整数平均 1.5、NULL/缺失/空输入、BigDecimal 精度及尺度、EQ/IN/BETWEEN、排序和 Top N、原始行超限、字段顺序变化和类型漂移、日期三种粒度及非法日期、同 Tenant SELF/DEPARTMENT 可见性、跨 Tenant、仅 view 禁止草稿查询、任意 datasetId 不覆盖服务器快照、当前 Field 改名立即影响已发布 Widget。
- SQL 方言和 API 直接共用 `QueryResultAssertions.integerAggregates/integerTopN/dateDay/dateMonth/dateYear` 中同一组手工算定的结果；另提供 sales/allNull 与按数值比较的 rows 辅助断言。共享契约提交 `514c3ab`，整数共享期望增量 `91f5ab1`。
- 已有 `ScreenQueryApiTest` 9 项、`ApiDatasetApiTest` 7 项及 `ArchitectureTest` 回归通过；API 10 项全部通过。目标日志 `/private/tmp/lowcode12-targeted-final.log`、`/private/tmp/lowcode12-shared-final.log`。

### 验收边界

- 本工单没有 UI 改动，已有 BAR 的公开 `columns/rows/truncated/maxRows` 结果已通过实际 HTTP 证明，无新增截图。
- 文本按 Java 字符串比较；数据库原生排序规则和 Oracle 空字符串语义不会被本工单改写，不承诺所有来源的本地化文本排序完全一致。

- 完整 `./mvnw verify -DforkCount=1`：131 tests，0 failures/errors/skips，包含默认 PostgreSQL/MySQL/SQL Server/ClickHouse 连接回归、租户与权限隔离、ArchitectureTest、SchemaCommentTest 和 Spotless；日志 `/private/tmp/lowcode12-backend-verify.log`。本工单未修改外部 JDBC/Oracle 连接器，未运行独立 slow-dialects profile。
