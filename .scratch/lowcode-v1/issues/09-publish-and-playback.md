# 09: 发布与播放

**What to build:** 持 `screen:publish` 的 Membership 能 Publish Screen；持 `screen:view` 且在其 Data Scope 内的 Membership 能 Playback 已发布快照。见规格第 6 节「草稿与发布」「Playback」、第 8 节。

**Blocked by:** 08

**Status:** ready-for-agent

- [ ] Publish 把草稿冻结为快照并覆盖上一份；未发布过的 Screen 对仅有 `screen:view` 的 Membership 不可见
- [ ] 发布后继续编辑草稿不影响已发布快照
- [ ] Playback 支持等比适配（默认）、铺满拉伸、按宽度适配，可用浏览器全屏
- [ ] Data Resource `screen` 生效：「本人」「本部门」Data Scope 限制可见 Screen
- [ ] Publish 写 Audit Event
- [ ] 测试覆盖草稿与快照分离、Data Scope 过滤；Playback 视觉用例覆盖三种缩放方式
