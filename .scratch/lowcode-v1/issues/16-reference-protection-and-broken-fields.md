# 16: 引用保护与失效提示

**What to build:** 被引用的 Data Source 或 Dataset 不能删除，用户看到引用它的对象清单；Dataset 修改后失效的 Widget 在 Playback 时显示「字段不存在」。见规格第 6 节「草稿与发布」、第 7 节。

**Blocked by:** 06, 10

**Status:** ready-for-agent

- [ ] Data Source 被 Dataset 引用时禁止删除，返回引用清单与 Error Code
- [ ] Dataset 被任一 Screen 的草稿或已发布快照引用时禁止删除，返回引用清单与 Error Code
- [ ] Dataset 的 Field 被删改后，引用它的 Widget 在编辑器与 Playback 中显示「字段不存在」，其余 Widget 正常
- [ ] 前端删除确认弹窗展示引用清单
