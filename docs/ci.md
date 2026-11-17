# 持续集成

仓库的 `main` 推送、面向 `main` 的 Pull Request 和手动触发均运行两个 GitHub Actions 工作流。工作流只读取仓库，不部署应用或发布 SDK。同一工作流与分支的新运行会取消尚未完成的旧运行。

| 工作流                                                        | 环境                                            | 检查与产物                                                                                                    |
| ------------------------------------------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| [Backend quality](../.github/workflows/backend-quality.yml)   | Ubuntu 24.04、Java 17、Docker                   | 构建所需 Forge SDK，执行业务服务 `verify`；失败时保留测试报告                                                 |
| [Frontend quality](../.github/workflows/frontend-quality.yml) | Ubuntu 24.04、Node.js 24、pnpm 12.4.2、Chromium | 检查格式、隔离约定、样式与类型，构建并运行 mock API 交互测试；成功时保留 `dist`，失败时保留浏览器报告与 trace |

报告和构建产物保留 7 天。后端使用 Maven 缓存，前端使用 pnpm store 缓存；安装仍使用 lockfile，不缓存 `node_modules`。

## Forge 候选依赖

本仓库依赖待发布的 `0.2.0` 候选，实际候选源码为 `9f61c8a0073c82a900abeff45ce21c5600b6afb3`。后端 CI checkout 它的精确直接父实现 [`98060f84421179b41b7e96cf123da8e41ba720a8`](https://github.com/crane199709/saas-forge/commit/98060f84421179b41b7e96cf123da8e41ba720a8)，然后在平台根目录执行既有 `version.py set 0.2.0` 和 `version.py check v0.2.0`，再构建所需 SDK 与架构检查制品。已在干净导出中证明该固定变换所得完整 Git tree 与候选源码完全一致，避免要求额外推送独立版本候选分支或标签。

本地后端验收消费候选实际 Maven 制品；CI 使用上述固定来源重建等价源码，不将两者混称为制品下载。前端三份 tarball 则直接来自同一候选的原始 `pnpm pack`，不要求额外配置包仓库密钥；摘要与复现步骤见 [平台升级记录](platform-upgrade.md)。

升级平台时，同步核对后端工作流的源码提交、`lowcode-service/pom.xml`、前端依赖、tarball 摘要和锁文件，确认与实际 Shell 的共享版本匹配，并按候选清单核对实际内容。目标版本制品发布后，再按平台接入指南切换依赖获取方式；当前保留本地覆盖，确保前端安装使用随仓库提交的实际候选。

## 测试范围

- 后端默认运行全部非 `slow-dialect` 测试，含真实 PostgreSQL、MySQL、SQL Server、ClickHouse 连接、租户隔离、功能权限、数据范围、架构规则、表字段注释与 Spotless 检查。设置 `forkCount=1` 控制云端容器与 JVM 的内存占用。
- 需要 Oracle 回归时，在 GitHub Actions 手动运行 **Backend quality** 并勾选 `oracle`，会额外启用 `slow-dialects` profile。
- 前端运行 `e2e/visual/` 的现有交互用例，保留页面状态与请求断言。现有 PNG 基线来自 macOS；Linux CI 显式传入 `--ignore-snapshots`，不比较或更新这些基线。本地 `pnpm test:visual` 继续执行完整截图比对，应在与基线一致的平台上运行。跨环境像素结果可能不同，参见 [Playwright 视觉比对说明](https://playwright.dev/docs/test-snapshots)。
- 两个工作流均不证明真实平台接入已完成。MQ 开通、Nacos 网关、IAM 授权、S3 上传与播放的全链路仍使用 [隔离平台验收入口](../deploy/e2e/README.md)在准备好的本地环境执行。

## 本地复现

先准备 README 中的依赖和匹配版本的 Forge Maven 制品。在仓库根目录执行：

```bash
cd lowcode-service
./mvnw --batch-mode verify -DforkCount=1
```

另开终端，从仓库根目录执行：

```bash
cd lowcode-sub-app
pnpm install --frozen-lockfile
pnpm format:check
pnpm exec prettier --check ../README.md ../README-en.md ../docs/ci.md ../docs/platform-upgrade.md ../.github/workflows/*.yml
pnpm build
pnpm exec playwright install --with-deps chromium
pnpm test:visual --forbid-only --ignore-snapshots --trace=retain-on-failure --reporter=line,html
```
