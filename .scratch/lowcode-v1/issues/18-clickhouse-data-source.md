# 18: 登记 ClickHouse 数据源

**What to build:** 能登记并测试连接 ClickHouse 类 Data Source，连接安全与其他数据库类相同（ADR-0002）。从 03 拆出，见 03 的「ClickHouse 拆出」一节。

**Blocked by:** 03

**Status:** needs-triage

- [ ] 先定连接方式：官方 JDBC 驱动（v2，HTTP）会跟随 302 重定向且无法关闭，不能直接使用。候选方案：自写 HTTP 客户端（JDK `HttpClient`，`Redirect.NEVER`，连接检查过的 IP）调 ClickHouse HTTP 接口；或经 MySQL 兼容协议连接
- [ ] 测试连接真正访问服务器（v2 驱动的 `getConnection` 不发请求），失败原因归入现有四类
- [ ] 连接参数白名单；经 IP 连接时 TLS 主机名校验与 SNI 如何处理
- [ ] Testcontainers ClickHouse 真实连接测试随默认 `verify` 运行，含「服务器返回重定向时不跟随」的用例
- [ ] 前端类型选择加入 ClickHouse
- [ ] 11 的分组聚合方言覆盖 ClickHouse 时沿用本工单的连接方式
