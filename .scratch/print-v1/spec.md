# 打印一期：打印模板与渲染规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-14 的设计拷问逐条确认；架构取舍见 [ADR-0003](../../docs/adr/0003-declarative-template-dsl-and-server-side-html.md)、[ADR-0004](../../docs/adr/0004-thermal-labels-print-as-bitmaps.md)。

## 1. 背景与目标

以 icube-base-lowcode 的打印域为参照（**只参考功能，不参考实现**），补齐本仓库缺失的打印能力。参照结论：

| icube-base 打印域能力 | 本仓库处置 |
|---|---|
| 打印模板聚合根（文档型 / 标签型二选一）、DataContract | 采纳概念，模型重做（见 §4、§7） |
| 三层不可变版本（契约版本 + 格式版本 + 发布包） | 简化为单个 Template Revision（§4.4） |
| 受控节点白名单 + 发布前预检 | 采纳，节点集合更收敛（§5） |
| 后端无头 Chromium 渲染 HTML → PDF | 采纳，但 HTML 生成只实现一次、不引 JS 排版阶段（§8、ADR-0003） |
| 标签 mm 绝对坐标 + SVG 编码器 + ZPL/TSPL 指令 | 采纳坐标模型，指令改为位图下发（§10、ADR-0004） |
| 异步渲染任务表（6 张表 + 调度器 + 清理 Job + 幂等键 + 授权快照） | **不采纳**：一期同步渲染，无任务表（§9） |
| PDF 字节入 `bytea` + TTL 清理 Job | **不采纳**：即打即弃，归档才落平台存储（§9.3） |
| 业务对象鉴权、发起人快照、盖章签名密码学 | **不采纳**：本仓库无外部业务系统调用方 |

目标：

- Tenant 能配置 Document Template 与 Label Template，绑定既有 Dataset 取数，发布出可追溯的 Template Revision。
- 有权限的 Membership 能在打印页选模板、填参数、预览、打印或下载 PDF；标签模板还能导出 ZPL/TSPL 指令。
- 打印件的排版在服务端唯一确定：预览与产物同源，不依赖客户端渲染差异。

非目标（二期及以后）：

- 报表域（`report`）与业务配置域（表单 / 列表页 / 元数据驱动），见 [一期规格](../lowcode-v1/spec.md) 与本规格的差距结论。
- 数据集发布快照与修订号绑定；打印模板跟着 Dataset 实时取数，与 Screen 现状一致。
- 打印模板导入导出与环境迁移包。
- 打印任务表、异步渲染、批量排队、重试与幂等键。
- 打印机设备管理、服务端直推打印机、打印机状态回传。
- 正文内的动态页码（`第 X 页 / 共 Y 页` 只允许出现在页眉页脚，见 §8.4）。
- 防伪花纹、密码学电子签名、模板字体上传。
- 模板多版本回滚 UI（只有修订历史与重印，没有"回到第 3 版继续编辑"）。

## 2. 工程形态

- 打印域是 `lowcode-service` 内的新功能包，不新增服务、不新增数据库 schema。
- 包结构与依赖方向：

```text
io.saasforge.lowcode.print
├── template    配置域：模板草稿、修订发布、资源、权限、引用保护
│                依赖平台 SDK（租户、权限、存储）与 datasource 的 service
├── render      引擎：模板快照 + 数据 → HTML 字符串。纯函数，无状态
│                只依赖 JDK；不依赖 Spring、JDBC、租户、Dataset、存储
├── command     引擎：模板快照 + 数据 → 位图与 ZPL/TSPL 指令文本。纯函数
│                只依赖 JDK 与 render 产出的 HTML
└── job         编排：同步渲染入口、Chromium 进程调用、归档、产物读取
                 依赖平台 storage 与 security
```

- 引擎层（`render`、`command`）不认识租户、Dataset、数据库与文件：模板快照、数据、图片字节都由调用方传入。将来整体上移 SaaS Forge 平台只是搬包。
- 用架构规则测试守住这条边界（沿用现有 `ArchitectureTest` 的写法）：`print.render` 与 `print.command` 不得依赖 `..datasource..`、`..screen..`、`..print.template..`、`..print.job..`、`org.springframework..`、`java.sql..`。

## 3. Print Template

- 一个 Print Template 绑定一个 Dataset，二选一为 Document Template 或 Label Template；`type` 创建后不可变更。
- 草稿随聚合根保存，编辑用乐观锁（沿用现有 `version` 列的做法）。
- 模板资源（机构标志、印章、签名、背景图）经 Storage Purpose `print-template-image` 上传，限定 PNG/JPEG，单文件 ≤ 2 MiB；模板快照只保存资源 ID，渲染时由编排层读字节传入引擎。
- 引用保护：Dataset 被打印模板（草稿或任一修订）引用时禁止删除，返回引用清单，沿用现有 `ReferenceProtectionApiTest` 覆盖的模式。

### 3.1 发布与修订

- 发布生成不可变 `print_template_revision`：`template_id`、`revision_no`（同模板内递增）、`snapshot`（JSON）、`schema_version`、`content_digest`、`published_at`、`published_by`、`change_summary`。
- 内容摘要相同不生成新修订（重复发布幂等）。
- 发布前预检，任一失败即拒绝发布并返回可读原因：
  1. 结构校验：节点白名单、坐标范围、必填属性。
  2. 语义校验：绑定字段都在 Dataset 字段里、参数映射到的字段可过滤、图片资源存在。
  3. 样例渲染：用 Dataset 的样例行（含一次空值行）跑一遍引擎，缺字段与类型不符在此拦截。
- 模板删除只允许在"无修订且无草稿内容"或"已下线"时执行；删除时清理其资源与修订。

## 4. Document Template

### 4.1 页面

- 纸张：A3 / A4 / A5 / 自定义（宽高毫米，正数，上限 2000mm），方向横向 / 纵向，四边边距毫米。
- 区域：页眉、页脚、覆盖层、正文。页眉页脚有显示策略：每页 / 仅首页。

### 4.2 正文：区块流

正文是自上而下的区块列表，不支持绝对定位。区块类型（一期白名单，共 11 种）：

| 区块 | 说明 |
|---|---|
| `paragraph` | 段落，内含 `text` / `field` / `datetime` / `pageField` 行内节点，可设对齐、行距、段后距、样式类（正文 / 标题 / 副标题） |
| `list` | 有序 / 无序列表 |
| `divider` | 分隔线 |
| `table` | 声明式表格：`columns[]`（标题、绑定、宽度、对齐、格式化、缺失策略）+ `rows` 可来自数组（`repeatOver`） |
| `keyValueGrid` | 键值栅格：`items[]`（标签 + 绑定），列数可配，用于报告单抬头的患者信息区 |
| `image` | 静态资源图或字段图 |
| `signature` | 签名：图片 + 名称 + 时间三件套 |
| `seal` | 印章：图片 + 名称 |
| `pageBreak` | 强制分页 |
| `spacer` | 可配高度的留白 |
| `richText` | 受限富文本（加粗、斜体、下划线、颜色、字号），不允许内联 HTML 与脚本 |

- 容器嵌套最多 2 层（`paragraph` 内可含行内节点；`table` 单元格内只允许 `text` / `field`），避免 icube 的 4 层嵌套带来的校验与渲染复杂度。
- 表格不做单元格坐标：列由 `columns[]` 定义，行由上到下自动流动；跨页时表头自动重复，行不允许被切分。

### 4.3 数据绑定

- 行内字段用点号路径绑定：`{{patient.name}}`，另支持 `{{total|decimal2}}`、`{{testedOn|date}}` 这类格式化后缀（日期、整数、两位小数、千分位、百分比）。
- `table` 的数组绑定 `repeatOver: "items"`，单元格绑定相对行（`{{name}}`）。
- 可见性条件：`exists` / `equals` / `notEquals` / `in` / `boolean`。不存在更复杂的表达式——需要推算就在 Dataset 里算。
- 缺失策略：默认 `error`（渲染失败，返回哪个路径缺失），逐元素可配 `blank`。

### 4.4 页眉页脚与覆盖层

- 页眉页脚内容同 `paragraph` 与 `image` 的能力子集（无表格），允许 `pageField`：`page`、`total`。
- 覆盖层元素：`background`（资源图，铺满 / 适应）、`watermarkText`（文本、旋转角、透明度、颜色、平铺）、`watermarkImage`（资源图、透明度、旋转角、平铺）。
- **不做防伪花纹**：代码画出的花纹没有印刷工艺支撑，反而给出虚假的安全感；验真要另立产品决定。

## 5. Label Template

- 标签尺寸：宽高毫米（正数，≤ 300mm）；元素按毫米绝对坐标定位（`xMm` / `yMm` / `widthMm` / `heightMm`）。
- 元素类型（一期）：`text`、`field`、`barcode`、`qrcode`、`line`、`rect`、`image`、`datetime`。
- 文本样式：字体（白名单）、字号（pt）、字重、颜色、水平 / 垂直对齐、换行策略（`shrink` 缩放 / `wrap` 换行 / `clip` 裁切）、最大行数。
- 条码：码制 CODE128 / EAN13 / CODE39 / QR；`moduleWidth`（mm）、`barHeight`（mm）、`margin`、是否显示人读字符及其位置。值规范化（去空格、补校验位、长度校验）失败即渲染失败，不静默产出不可扫的码。
- 超过边界的元素在发布预检时拒绝（不裁切），避免"预览看着好、打印少一半"。

## 6. 取数、契约与打印参数

- 取数走现有 `DatasetQueryService`，与 Screen 的 Playback 同一条路径；数据范围、租户隔离、SQL 白名单与超时限制全部继承，打印不另开取数通道。
- **Print Data Contract 不入库、不手写**：由 Dataset 的 Field 列表直接派生（`name` / `displayName` / `FieldType` / `FieldRole` / `date`），供编辑器字段面板与发布预检使用。这是与 icube 的主要差异之一——它要求使用者手写契约，本仓库已有的 Dataset 元数据够用。
- Print Parameter（打印参数）：模板可声明 0..N 个参数，每个参数映射到 Dataset 某个可过滤字段 + 运算符（`eq` / `in` / `between`），带名称、类型、是否必填、默认值。执行打印时参数值作为查询过滤条件注入——复用现有查询的静态过滤能力，不改造 Dataset 模型。
- 数据粒度：一次打印 = Dataset 一次查询结果 → N 份产物。Document Template 里数组行在同一份文档内跨页流动；Label Template 里每行是一张标签（多页 PDF 每页一张，或多条指令）。

## 7. 渲染

### 7.1 唯一排版权威

- `render` 包把模板快照 + 数据 + 图片字节生成一份自包含 HTML（内联 CSS、图片转 `data:` URI、无外部请求）。
- 前端预览就是把这份 HTML 放进 `<iframe>`；服务端产物是同一份 HTML 经无头 Chromium 转 PDF。**排版只实现一次**，预览与产物必然一致。
- 前端编辑器画布就是同一份 HTML 的可编辑映射，不实现第二套排版。

### 7.2 CSS 策略

- 页面用 `@page { size: <w>mm <h>mm; margin: ... }`。
- 跨页表头用 `thead { display: table-header-group }`，行用 `break-inside: avoid`；不引 JS 测量阶段。
- 覆盖层用 `position: fixed`，Chromium 打印时每页重复。
- 样式只允许内联与受控属性，不允许模板注入 `<script>`、`<link>`、`<iframe>` 与事件属性。

### 7.3 字体

- 镜像内置开源中文字体（思源黑体 / 宋体 Noto Sans CJK SC、Noto Serif CJK SC），模板只能从白名单选择字体族与字号，不开放字体上传。
- 引擎只输出字体族名，字体可用性由部署保证；字体缺失导致的排版差异属于部署故障，用启动自检（渲染一次基准 HTML 并比对文本宽度）提前暴露。

### 7.4 已知限制（一期）

- **正文内动态页码不支持**：`page` / `total` 只允许在页眉页脚。正文内页码需要逐页排版测量（icube 的 `DocumentLayoutPhase` 就是为它引入的），一期不做。
- 页眉页脚的实现走 Chromium 的页眉页脚模板，能力限于文本、页码、图片与对齐全——**不能放表格**。这一点在编辑器里就要限制，不能等到渲染才失败。

### 7.5 schemaVersion 与旧修订

- 快照自带 `schemaVersion`；渲染器按版本分派（`Map<Integer, Renderer>`），**永久保留旧版本渲染路径**，保证历史修订可重印。
- 新模板只能使用最新版本；旧版本渲染器只修 bug，不加特性。

## 8. 打印执行与产物

- 一期全同步，一个请求内完成取数、渲染、返回。硬上限：Document Template ≤ 50 页、Label Template ≤ 200 张，超限直接拒绝并提示缩小查询范围。
- Document 产物 `application/pdf`；Label 产物：PDF（每页一张，用于普通打印机）或 ZPL/TSPL 指令（用于热敏标签机）。
- 不建任务表、不落库。可选动作「归档」把产物写入平台存储（复用 `forge_storage_object` 与配额），归档件列出并允许手动删除，自动清理二期再做。

## 9. 热敏指令

- 标签一律由服务端渲染成整张 1-bit 位图后下发：ZPL 用 `^GFA`，TSPL 用 `BITMAP`（详见 [ADR-0004](../../docs/adr/0004-thermal-labels-print-as-bitmaps.md)）。
- DPI 203 / 300 / 600，仅影响位图像素尺寸；毫米尺寸是模板的真值。
- 一张标签一条指令块，多张按批返回数组；不做打印机连接、不发送，指令文本交给调用方或前端下载。
- 打包格式：ZPL `^XA ... ^XZ` 逐张；TSPL `SIZE` / `GAP` / `DIRECTION` 等改为逐张设置，`CLS ... PRINT`。

## 10. 编辑器与前端

- 文档编辑器：左侧区块大纲 + 中间页面画布（区块流，可上下移动、插入、删除）+ 右侧属性面板；页眉页脚与覆盖层是独立编辑区。不做自由拖拽定位。
- 标签编辑器：mm 画布，元素拖拽 / 缩放 / 吸附（1mm），属性面板与条码预览。
- 两者共用：字段面板（来自 Dataset）、格式化选择、缺失策略、预览（iframe 加载服务端 HTML）。
- 前端路由：`prints`（列表）、`prints/:id/edit`（编辑器）、`print`（执行页：选模板 → 填参数 → 预览 → 打印 / 下载 / 导出指令）。
- 打印走浏览器 `window.print()` + 预览 iframe（普通打印机）；下载 PDF 走服务端产物。不做静默打印（需要客户端代理）。

## 11. 权限、菜单与数据范围

- Permission Code：`print-template:view`、`print-template:manage`（含编辑、预览、资源上传）、`print-template:publish`。执行打印复用 `print-template:view`，不新造权限。
- Data Resource `print_template`，资源属性：创建人、所属部门，使「本人」「本部门」等 Data Scope 可限制可见模板；SQL 层 RLS 与现有表一致。
- 菜单：打印模板列表（`print-template:view`）、打印执行页（`print-template:view`）。
- 执行打印用当前用户身份取数，因此"能看模板但看不到数据"是预期结果，返回可读的空结果提示。

## 12. 验证

- 引擎单元测试：golden HTML 回归（每类节点一个用例，逐字比对生成的 HTML），跨页表头、覆盖层、格式化、缺失策略各有用例；断言不依赖 Chromium，跑得快。
- 渲染冒烟：真实 Chromium 渲染基准模板，断言 PDF 页数与页面尺寸；Chromium 缺失时跳过并显式告警，不静默通过。
- 指令测试：位图尺寸与 DPI 换算、ZPL/TSPL 指令骨架逐字比对。
- 集成测试：模板 CRUD、发布与修订幂等、预检拦截（缺字段 / 越界元素 / 图片缺失）、引用保护、权限与租户隔离、打印页上限拒绝。
- 架构规则：引擎包不得依赖平台设施（§2）。
- 前端视觉回归：编辑器与打印页各一组，mock API。

## 13. 决策记录

- [ADR-0003：打印模板用声明式 JSON DSL，服务端 HTML 是唯一排版权威](../../docs/adr/0003-declarative-template-dsl-and-server-side-html.md)
- [ADR-0004：热敏标签以整张位图下发 ZPL/TSPL](../../docs/adr/0004-thermal-labels-print-as-bitmaps.md)

实现工单见 [issues/](issues/)，按依赖顺序编号 01–10。
