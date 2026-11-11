# 02: 登记 PostgreSQL 数据源

**What to build:** 持 `datasource:manage` 的 Membership 能在数据源页登记、编辑、删除一个 PostgreSQL Data Source 并测试连接；持 `datasource:view` 的只能查看。这是第一个打穿前后端的切片，确立连接安全约束。见规格第 3 节、ADR-0002。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 只收结构化字段（主机、端口、库名、账号、密码、白名单连接参数），服务端拼装 URL；请求带非白名单参数被拒绝
- [ ] 密码加密保存，任何接口不回显；编辑时留空表示不修改
- [ ] 禁止网段与加密密钥为必配项，漏配启动失败；主机解析结果落入禁止网段时拒绝保存与测试连接
- [ ] 测试连接返回成功或可读的失败原因，不暴露端口是否开放等探测细节
- [ ] Data Source 按 Tenant 隔离；删除为逻辑删除（引用保护见 16）
- [ ] 新增、修改、删除写 Audit Event
- [ ] 后端用 Testcontainers PostgreSQL 做真实连接测试，覆盖禁止网段、参数白名单、密码不回显与权限
- [ ] 前端列表与表单按 `docs/standards/sub-app.md` 三步完成，视觉用例覆盖有数据与空列表，走查查看截图
