# Lowcode 平台候选升级记录

本次按实际基线升级：Lowcode `29dea28aa391078df27111416d4e2d3436367124`，分支 `codex/v020-upgrade-lowcode`。后端父 POM、前端 SDK/UI/kit 原本均为 `0.2.0-SNAPSHOT`，前端依赖通过已提交的 `.forge-packages/` 覆盖；不是 `0.1.0` 生成工程升级。业务服务版本 `1.0.0-SNAPSHOT`、Sub-App 版本 `0.1.0` 与 Manifest 版本 `2` 保持各自语义。

固定平台候选源码为 `54115992e00691be57b912e46593709c77c344a1`，坐标 `0.2.0-SNAPSHOT`。候选仅供隔离验收，未上传目标 Maven/npm 仓库，也未切换共享环境。平台候选清单 `candidate.json` 的 SHA-256 为 `999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。

| 随工程提交的实际候选包 | SHA-256                                                            |
| ---------------------- | ------------------------------------------------------------------ |
| SDK                    | `db3b4496a9691ca718b19e7dc95a9055ff242b63b6622c35a52a2cf024cee890` |
| UI                     | `bf5f7c012df8e86e1528b2b0435f9a8a4ad933bea9c13f280d8fdbba58bc653c` |
| sub-app-kit            | `a9c964e4aad3e18fb288051e823f7715ef894f51f5e1f4ed882a39a45863cc3d` |

## 必要变化

- 保留现有本地 tarball 安装模式，三份包替换为同一固定候选，用 `pnpm update @crane199709/sdk @crane199709/ui @crane199709/sub-app-kit` 重新读取 tarball 并更新 lockfile 完整性值，再核对安装目录内容；仅覆盖同名快照后普通 install 可能继续使用旧缓存。共享依赖精确版本沿用候选表，不引入 workspace 链接。CI 后端重建来源同步固定到同一提交，明确区分“本地实际制品消费”和“CI 固定源码重建”。
- 现有 V11 已包含 `purpose`，无需重复执行用途迁移。仅新增 V12，复制候选 storage JAR 中 `upgrade-retention.sql`，补齐保留截止时间与失效时间；已有 STORED 文件截止仍为空，继续永久保留。V1—V11 保持原文，图片用途 `screen-image` 与已发布/草稿关联不变。
- storage 同时启用 scheduling；为已有配置增加 IAM 地址和服务凭据的引用，不另建凭据，也不混用 Ops Token。测试配置继续覆盖 Manifest 地址以隔离 IAM；真实平台验收使用本轮独立 IAM。
- 按新版父 POM 修正现有测试的 4 处无大括号 `if`，不跳过 NeedBraces 或依赖门禁。规范副本直接来自候选实际生成工程，保留本工程 AGENTS 的业务测试约定。
- 独立宿主通过 Vite 的 `gateway` 选项接收 `FORGE_DEV_GATEWAY`。kit 不自动读取 Shell 使用的同名环境变量；隔离验收显式设为本轮网关，常规未设置时仍沿用 kit 默认地址。
- 业务源码继续传 `/lowcode/...`，SDK 添加 `/api`，配套宿主剥离后到网关；独立宿主与 Shell 均使用候选统一浅色主题。既有 `vite.config.ts` 已无 `apiPrefixes`，无需再删除。平台浏览器用例只将浏览器网络观察路径同步为 `/api/...`，网关直连路径不改。

## 本地复现

前端包已提交，可直接在 `lowcode-sub-app` 执行 `pnpm install --frozen-lockfile`、`pnpm format:check` 和 `pnpm build`，不依赖已退出的临时 registry。

后端实际候选消费需取得平台完整候选目录并校验清单摘要。给 Maven settings 增加 ID 为 `forge-release`、URL 为候选 `maven-repository` 的 file repository，并启用 snapshots；使用新的 `-Dmaven.repo.local=<独立目录>`。仅复制本机第三方依赖缓存，排除全部 `io/saasforge` 和 `.lastUpdated`；不要先在该缓存执行平台源码 `install`。随后运行：

```sh
cd lowcode-service
./mvnw -s /absolute/path/to/candidate-settings.xml \
  -Dmaven.repo.local=/absolute/path/to/isolated-m2 verify
```

`PlatformUpgradeApiTest` 在同一新数据库执行旧 V1—V11、写入已有大屏与真实 S3 图片，再由服务启动执行 V12；通过 HTTP 读取原文件、修改原大屏，并检查跨租户 404。现有图片测试覆盖新上传、用途和权限边界；其余既有权限与数据范围测试继续执行。

真实平台与独立宿主使用 [隔离验收入口](../deploy/e2e/README.md)。调用 `--skip-build` 时须先准备匹配候选的平台服务、Shell/Console 和当前 Lowcode 构建产物；运行器仅使用新建 Compose project，不读取开发 `.env.local`。浏览器真实登录、创建数据源/数据集/大屏、S3 图片、发布与查看者权限均由真实服务提供，不能以 mock 视觉用例代替。

## 已完成验证

- 独立候选缓存执行后端完整 `verify -DforkCount=1`：185 个测试通过，零失败、错误或跳过。Oracle slow-dialect profile 未启用。
- 前端格式、隔离、样式、类型与构建检查通过；完整 130 个行为用例通过，同轮更新的 38 张视觉基线已逐张查看。开发代理配置调整后重新检查并构建，生产 dist 逐文件零差异。
- 同一轮新建隔离平台中，Shell 和独立宿主各 1/1 浏览器用例通过：真实登录、业务与图片读写、MQ 开通与审计消费、Nacos 路由、查看者播放/续签、无权限与数据范围拒绝均符合预期。独立宿主主色 `#4f46e5`、底色 `rgb(248, 250, 252)`，已登录阶段无控制台错误。最终六张真实截图和 1440/1024 两档视觉基线均已查看。
- 本轮没有触发旧 `/api/demo/context` 的 403 例外；结果中的 `knownPlatformBaseline` 为 null。

## 验证边界

本次保留原 checkout 的两处未提交配置修改和未跟踪 `SchedulingConfigTest.java`，没有搬入升级提交。升级分支的 scheduling 配置由候选实际启动失败独立确认后实现。

原始日志、候选输入摘要、基线迁移摘要和截图统一留在平台任务证据目录 `/private/tmp/v020-upgrade/06-lowcode/`。完成结果由平台工单 06 的验收记录对应到本仓库最终提交。共享或生产环境未迁移；目标仓库发布后消费复验属于平台后续发布流程。
