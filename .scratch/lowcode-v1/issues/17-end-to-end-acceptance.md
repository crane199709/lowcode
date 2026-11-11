# 17: 本地平台全链路验收

**What to build:** 在本地 SaaS Forge 平台上按规格第 10 节跑通完整业务链路，并把过程中暴露的平台问题记为反馈工单。

**Blocked by:** 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 16

**Status:** ready-for-agent

- [ ] 按平台接入清单完成数据库、Manifest 令牌、Topic、网关路由与 App Registry 登记
- [ ] Operator 向套餐开放 `lowcode` 应用与权限；Tenant 登录看到「低代码」菜单
- [ ] 登记 Data Source → 定义 Dataset → 搭建 Screen 并 Publish，全部在 Shell 内完成
- [ ] 持「大屏查看者」的 Membership 能 Playback；无权限或不在其 Data Scope 内的 Membership 看不到该 Screen
- [ ] Playwright 覆盖上述链路
- [ ] 平台问题写入平台仓库 `.scratch/lowcode-validation/issues/`，附证据与复现；业务工程的临时绕行注明对应工单
