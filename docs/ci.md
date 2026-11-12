# 持续集成

仓库的 `main` 推送、面向 `main` 的 Pull Request 和手动触发均运行两个 GitHub Actions 工作流。工作流只读取仓库，不部署应用或发布 SDK。同一工作流与分支的新运行会取消尚未完成的旧运行。

| 工作流                                                        | 环境                                            | 检查与产物                                                                                                    |
| ------------------------------------------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| [Backend quality](../.github/workflows/backend-quality.yml)   | Ubuntu 24.04、Java 17、Docker                   | 构建所需 Forge SDK，执行业务服务 `verify`；失败时保留测试报告                                                 |
| [Frontend quality](../.github/workflows/frontend-quality.yml) | Ubuntu 24.04、Node.js 24、pnpm 12.4.2、Chromium | 检查格式、隔离约定、样式与类型，构建并运行 mock API 交互测试；成功时保留 `dist`，失败时保留浏览器报告与 trace |

报告和构建产物保留 7 天。后端使用 Maven 缓存，前端使用 pnpm store 缓存；安装仍使用 lockfile，不缓存 `node_modules`。

## Forge snapshot 依赖

本仓库依赖 `0.2.0-SNAPSHOT`。当前后端工作流从 SaaS Forge 的固定提交 [`3f5ea01d10e18a907914ff82a9f71b3082fe05bf`](https://github.com/crane199709/saas-forge/commit/3f5ea01d10e18a907914ff82a9f71b3082fe05bf) 构建所需 SDK 和架构检查制品，再运行 lowcode 测试。前端直接使用随仓库提交的 `.forge-packages/` tarball。两者均不要求额外配置包仓库密钥。

升级平台时，同步更新后端工作流的源码提交、`lowcode-service/pom.xml`、前端依赖及 snapshot tarball，确认与实际 Shell 的共享版本匹配。采用正式发布的 Maven 制品后，可按平台接入指南切换依赖获取方式。

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
pnpm exec prettier --check ../README.md ../README-en.md ../docs/ci.md ../.github/workflows/*.yml
pnpm build
pnpm exec playwright install --with-deps chromium
pnpm test:visual --forbid-only --ignore-snapshots --trace=retain-on-failure --reporter=line,html
```
