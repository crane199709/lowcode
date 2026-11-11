# 17: 本地平台全链路验收

**What to build:** 在本地 SaaS Forge 平台上按规格第 10 节跑通完整业务链路，并把过程中暴露的平台问题记为反馈工单。

**Blocked by:** 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 16, 18

**Status:** resolved

- [x] 按平台接入清单完成数据库、Manifest 令牌、Topic、网关路由与 App Registry 登记
- [x] Operator 向套餐开放 `lowcode` 应用与权限；Tenant 登录看到「低代码」菜单
- [x] 登记 Data Source → 定义 Dataset → 搭建 Screen 并 Publish，全部在 Shell 内完成
- [x] 持「大屏查看者」的 Membership 能 Playback；无权限或不在其 Data Scope 内的 Membership 看不到该 Screen
- [x] Playwright 覆盖上述链路
- [x] 平台问题写入平台仓库 `.scratch/lowcode-validation/issues/`，附证据与复现；业务工程的临时绕行注明对应工单


## Answer

已实现低代码自有隔离平台 runner 和真实 Operator / Tenant Shell Playwright 用例；既有 Boot 中添加不预置业务对象的 TenantInitializer，真实 MQ 开通由 pending 转为 succeeded。最终从新建空卷和独立凭据的环境单次通过，真实 Nacos lb 路由、Manifest/Ops、套餐开放、普通设计者 SQL→Dataset→图表/图片→发布、viewer Playback、功能权限与 all/self Data Scope、S3 上传/续签、IAM 已消费审计均已验证。

真实共享包版本告警促使本地 tar workaround 升至与 Shell 一致的 0.2.0-SNAPSHOT（平台反馈 01）；Shell 首页固定 Demo 请求已记平台反馈 08，仅对已核实的 `/demo/context` 403 作精确基线处理。未修改平台生产代码或已有用户运行环境。

验收与限制见 [acceptance.md](../acceptance.md)，脱敏截图和数据见 [evidence/17](../evidence/17)。后端完整 verify：181 tests 全过；同版共享包前端 build / format / 130 visual 全过；最终独立平台 Playwright：1 passed（24.4s），已逐张查看 1440/1024 等五张截图并核对本轮进程/容器/卷/端口清理。
