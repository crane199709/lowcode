# Lowcode 平台候选升级记录

本次按实际基线升级：原 checkout 为 Lowcode `29dea28aa391078df27111416d4e2d3436367124`，后端父 POM、前端 SDK/UI/kit 原本均为 `0.2.0-SNAPSHOT`；不是 `0.1.0` 生成工程升级。快照阶段升级提交为 `cb974b9d40247596dbd9331f56f482eef58cefb7`，最终坐标在其上以独立分支 `codex/v020-upgrade-lowcode-final` 收尾。业务服务版本 `1.0.0-SNAPSHOT`、Sub-App 版本 `0.1.0` 与 Manifest 版本 `2` 保持各自语义。

固定最终平台候选源码为 `9f61c8a0073c82a900abeff45ce21c5600b6afb3`，坐标 `0.2.0`。候选清单 `candidate.json` 的 SHA-256 为 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`。候选仅供隔离验收，未上传目标 Maven/npm 仓库，也未切换共享环境；`0.2.0` 表示制品坐标，不表示产品正式版。

| 随工程提交的实际最终候选包 | SHA-256                                                            |
| -------------------------- | ------------------------------------------------------------------ |
| SDK                        | `89d39aec9091502911c45b734e6875314bbae7f305076e1a7c6f32a1d75eb041` |
| UI                         | `0b6b43a30a9e557ff4934544a0f656b6d3ede134355204b8ec11e0a795a9d2a8` |
| sub-app-kit                | `77002bcd4cea8f4bdc7b8545ce6979345d2cb06b54ffd785b03c713d0d19f580` |

## 必要变化

- 父 POM、SDK/UI/kit 及 file overrides 对齐 `0.2.0`；三份 tgz 是最终候选的原始 pack 制品，使用 `pnpm update @crane199709/sdk @crane199709/ui @crane199709/sub-app-kit` 更新 lockfile 完整性值，保留已有本地包安装模式。CI 固定候选的直接父实现 `98060f84421179b41b7e96cf123da8e41ba720a8`，运行既有版本同步及检查入口后重建；该固定变换的完整 Git tree 已证明等于候选 `9f61c8a0`，明确区分本地实际制品消费与 CI 源码重建。
- 规范副本直接来自最终候选实际生成工程，合入本版升级清单指针和 Agent 说明，保留本工程自有测试约定。相关后端实现相对快照候选没有变化；前端 SDK/UI 的变化只有版本值，kit 另更新生成说明和规范指针，页面、主题与请求逻辑不变。
- 快照阶段已新增的 V12 复制 storage `upgrade-retention.sql`，本轮 V1—V12 均保持原文。既有 V11 已包含 `purpose`；旧 STORED 文件保留截止仍为空，图片用途 `screen-image` 与已发布/草稿关联不变。
- storage 同时启用 scheduling，引用已有 IAM 地址与服务凭据，不另建凭据，也不混用 Ops Token。现有测试继续隔离 IAM；真实平台验收使用本轮独立 IAM。快照阶段修正的 4 处 NeedBraces 违规保持，不跳过父 POM 门禁。
- 业务源码继续传 `/lowcode/...`，SDK 添加 `/api`，配套宿主剥离后到网关；既有 Vite 配置已无 `apiPrefixes`。独立宿主通过既有 `gateway` 选项接收 `FORGE_DEV_GATEWAY`，两种宿主共用候选统一浅色主题。
- 隔离运行器改为启动配套 `forge-iam`、`forge-platform`、`forge-gateway` 的 `0.2.0` JAR；Lowcode 自身仍使用 `lowcode-service-1.0.0-SNAPSHOT.jar`。`--skip-build` 前分别核对业务实际候选构建与平台运行来源，不向业务消费缓存安装平台源码。

## 本地复现

前端包已提交，可在 `lowcode-sub-app` 执行 `pnpm install --frozen-lockfile`、`pnpm format:check` 和 `pnpm build`，不依赖已退出的临时 registry。

后端实际候选消费需取得完整候选目录并校验清单摘要。给 Maven settings 增加 ID 为 `forge-release`、URL 为候选 `maven-repository` 的 file repository，启用 releases；使用新的 `-Dmaven.repo.local=<独立目录>`。仅复制本机第三方依赖缓存，排除全部 `io/saasforge` 和 `.lastUpdated`；不要先在该缓存执行平台源码 `install`。随后运行：

```sh
cd lowcode-service
./mvnw -s /absolute/path/to/candidate-settings.xml \
  -Dmaven.repo.local=/absolute/path/to/isolated-m2 verify -DforkCount=1
```

`PlatformUpgradeApiTest` 在同一新数据库执行旧 V1—V11、写入已有大屏与真实 S3 图片，再由服务启动执行 V12；通过 HTTP 读取原文件、修改原大屏，并检查跨租户 404。现有图片测试覆盖新上传、用途和权限边界；其余既有权限与数据范围测试继续执行。

真实平台与独立宿主使用 [隔离验收入口](../deploy/e2e/README.md)。调用 `--skip-build` 时须先准备匹配最终候选的三个平台服务、Shell/Console 和当前 Lowcode 构建产物；运行器只使用新建 Compose project，不读取开发 `.env.local`。真实登录、数据源/数据集/大屏读写、S3 图片、发布和查看者权限均由真实服务提供，不能以 mock 视觉用例代替。

## 阶段证据与最终验证

快照阶段候选为 `54115992e00691be57b912e46593709c77c344a1 / 0.2.0-SNAPSHOT`，清单 SHA-256 为 `999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。该阶段后端 185 项、前端 130 项、两个真实宿主各 1 项均通过；38 张更新视觉基线和六张真实截图已逐张查看。NeedBraces、scheduling 必配项、旧库 retention 缺列与 `/api` 路径的红绿证据继续保留在 `/private/tmp/v020-upgrade/06-lowcode/`。

最终候选的输入、源码/包差异、独立 Maven 缓存、消费与构建日志、真实宿主结果和截图放在 `/private/tmp/v020-upgrade/08-lowcode-final/`。候选前端差异已逐文件核对：UI 只改包版本，SDK 只改版本身份值，kit 只改版本、生成说明和规范指针；没有新的页面或主题行为变化，本轮不重复更新 130 项视觉基线。

最终 0.2.0 复验已通过：独立缓存后端 `verify` 185/185，含 10 项升级与图片 HTTP 测试；31 个 Maven JAR/POM、业务运行包内 13 个 Forge 依赖、前端安装目录 77 个文件均逐字节匹配最终候选。前端 frozen 安装、格式、隔离、样式、类型和构建通过。匹配最终源码的 Shell 1/1、独立宿主 1/1 真实浏览器用例通过；六张新截图已逐张查看，独立宿主主色 `#4f46e5`、底色 `rgb(248, 250, 252)`，已登录控制台错误为空，`knownPlatformBaseline=null`。平台三份固定 JAR 和 Shell/Console 构建产物在运行前后摘要一致。

启动前审计曾发现配套目录缺少 Console dist，保留失败日志后在同一重型锁内从固定源码补齐 Shell/Console，恢复三份固定运行 JAR 后再执行真实平台；没有绕过来源检查。

最终执行结果见该目录的 `receipt.json` 与 `receipt.md`。原 checkout 的两处未提交配置修改和未跟踪 `SchedulingConfigTest.java` 保持原样，未搬入升级提交；本轮保留业务主代码和所有已存在迁移。

## 验证边界

Oracle slow-dialect profile 未启用；本轮没有改动外部驱动或连接实现。未运行远程 Actions、未发布目标 Maven/npm 制品、未推送业务分支，未迁移共享或生产环境。目标仓库发布后只读消费属于平台后续发布流程。真实平台的随机凭据、邀请链接和私有原始配置不随 receipt 交付；仅交付无凭据结果与已查看截图。
