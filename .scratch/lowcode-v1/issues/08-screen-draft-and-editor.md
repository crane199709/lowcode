# 08: 大屏草稿与编辑器

**What to build:** 持 `screen:edit` 的 Membership 能新建 Screen、在自由画布上摆放文本卡并保存草稿。见规格第 6 节「画布」「编辑器」。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Screen 列表、新建（Design Resolution 取预设或自定义 640–7680）、修改 Design Resolution（已有 Widget 坐标不换算）、复制整个 Screen、删除
- [ ] 编辑器：拖拽、缩放、网格吸附、层级调整、删除、复制 Widget、撤销 / 重做
- [ ] 文本卡作为第一个 Widget，可设文字与基本样式
- [ ] 背景色可设置（背景图见 15）
- [ ] 画布与 Widget 的视觉属性不受 `--forge-*` token 约束；编辑器外围界面遵守规范；lint 误报记为平台反馈
- [ ] 保存草稿；Screen 按 Tenant 隔离
- [ ] 编辑器 Playwright 用例覆盖拖拽、缩放、撤销重做，视觉用例走查查看截图
