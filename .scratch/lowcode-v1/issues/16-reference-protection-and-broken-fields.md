# 16: 引用保护与失效提示

**What to build:** 被引用的 Data Source 或 Dataset 不能删除，用户看到引用它的对象清单；Dataset 修改后失效的 Widget 在 Playback 时显示「字段不存在」。见规格第 6 节「草稿与发布」、第 7 节。

**Blocked by:** 06, 10

**Status:** claimed

- [ ] Data Source 被 Dataset 引用时禁止删除，返回引用清单与 Error Code
- [ ] Dataset 被任一 Screen 的草稿或已发布快照引用时禁止删除，返回引用清单与 Error Code
- [ ] Dataset 的 Field 被删改后，引用它的 Widget 在编辑器与 Playback 中显示「字段不存在」，其余 Widget 正常
- [ ] 前端删除确认弹窗展示引用清单

## 页面简报与测试边界

- 设计者在数据源、数据集列表发起低频删除；列表仍以新增为主操作。删除确认弹窗先展示引用对象名称，存在引用时不能提交；提交时服务端再次检查，出现新增引用时在同一弹窗展示失败与最新提示。沿用列表原型和 Element Plus 确认弹窗，不增加页面入口。
- 已获授权的测试边界：公开资源删除/引用 HTTP（真实 PostgreSQL、Redis）、Widget 查询 HTTP，以及 Playwright 编辑器和 Playback。引用保护覆盖同 Tenant 的草稿和发布快照，不受调用者 Screen Data Scope 限制；隐藏引用不返回实际 ID、名称。
