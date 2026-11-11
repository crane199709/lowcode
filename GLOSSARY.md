# Lowcode

基于 SaaS Forge 平台的低代码业务服务：Tenant 接入自有数据，用可视化方式搭建数据大屏。平台通用术语（Tenant、Membership、Permission Code、Data Policy、Sub-App 等）以 SaaS Forge 平台的定义为准，本文不重复。

## 数据接入

**Data Source（数据源）**:
Tenant 登记的一个外部数据的连接，分为数据库与 API 两类；只属于登记它的 Tenant。
_Avoid_: 连接、数据连接、库

**Dataset（数据集）**:
在一个 Data Source 上定义的一段查询（SQL 或 API 请求），其结果的列构成一组 Field，是 Widget 取数的唯一来源。
_Avoid_: 数据模型、数据表、视图、查询

**Field（字段）**:
Dataset 查询结果中的一列，带显示名与类型，并被归为 Dimension 或 Measure。
_Avoid_: 列、属性

**Dimension（维度）**:
用于分组、分类或作为坐标轴类别的 Field，如地区、日期。
_Avoid_: 分组字段、类别

**Measure（度量）**:
可被聚合（求和、平均、计数等）后显示为数值的 Field，如金额、数量。
_Avoid_: 指标、数值字段

## 大屏

**Screen（大屏）**:
按固定 Design Resolution 自由摆放若干 Widget 的可视化画面，有草稿与已发布两份内容。
_Avoid_: 仪表板、看板、Dashboard、报表

**Design Resolution（设计分辨率）**:
Screen 画布的宽高像素，Widget 的位置与尺寸都以它为坐标系；播放时整体缩放到实际屏幕。
_Avoid_: 画布大小、屏幕尺寸

**Widget（组件）**:
放在 Screen 上的一个可视单元，如明细表、指标卡、折线图、文本卡、图片；取数类 Widget 绑定一个 Dataset。
_Avoid_: 图表、控件、部件

**Publish（发布）**:
把 Screen 的草稿冻结为一份已发布快照的操作；只有已发布快照对查看者可见，重新发布会覆盖上一份。
_Avoid_: 上线、保存

**Playback（播放）**:
按已发布快照全屏展示 Screen 并按各 Widget 的刷新间隔取数的查看方式。
_Avoid_: 预览、演示、投屏
