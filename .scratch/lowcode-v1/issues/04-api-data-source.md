# 04: API 数据源

**What to build:** 能登记一个 HTTP JSON 接口作为 Data Source 并测试连接。见规格第 3 节「API 类」、ADR-0002。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 支持 GET、POST（静态 JSON 请求体）
- [ ] 认证：无、自定义请求头、Basic；敏感头部值与密码加密保存且不回显
- [ ] 禁止网段同样生效；不跟随重定向；有超时与响应体大小上限
- [ ] 测试连接返回状态与可读错误
- [ ] 用本地 mock HTTP 服务测试认证方式、重定向拒绝、超时与超限
