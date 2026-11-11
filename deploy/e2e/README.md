# Lowcode 真实平台验收

在低代码仓库根目录执行：

```bash
./deploy/e2e/run.sh --platform-root /absolute/path/to/saas-forge
```

要求 Docker、Java 17（通过 `JAVA_HOME` 选择）、pnpm、Python 3、curl 和 macOS 自带 `htpasswd`，两个前端 workspace 已按各自 lockfile 安装依赖、Playwright Chromium 已安装。默认仅构建 IAM、platform、gateway 及其必要依赖、两个 Shell 应用和 lowcode，不执行平台发布或模板生成流水线。刚从当前源码构建过全部相关产物时可加 `--skip-build`。

运行器预检固定隔离端口 15432、16379、18081、19000、18848、19848、18101、18102、18202、18000、15173、15174、15176；冲突直接失败，不停止已有服务。每轮新建独立 Compose project、新数据卷、数据库账号和密钥；Java 仅接收明确列出的环境变量。只引导首个 Operator 与外部只读样本库，不预置租户、套餐、应用、业务对象或权限缓存。租户和业务数据经真实平台 UI/API 创建，MQ 开通/审计、Nacos 注册和导入的 `lb://` 路由、S3 上传均保持开启。

原始日志、临时配置、身份密码和邀请链接仅保存在 `/private/tmp/lowcode-platform-*` 的私有目录，不能直接发布。退出时仅清理本轮记录的 Java PID 和本轮 Compose project/volumes。测试截图不含登录表单和邀请链接；交付证据仍须人工查看后筛选。`--serve` 可在本轮平台就绪后保持夹具供浏览器迭代；在另一终端设置输出的 `LOWCODE_E2E_RUN_DIR` 和 `LOWCODE_PLATFORM_ROOT`，从 `lowcode-sub-app` 执行 `pnpm exec playwright test -c e2e/platform/playwright.config.ts`，结束后向 runner 发送 SIGINT 清理本轮资源。

本环境只验证新建的隔离平台，不证明用户现有运行环境已经接入。本轮明确允许 Data Source 连接自己的 loopback PostgreSQL 只读样本库；禁止网段仍为显式必配。运行器不读取原工程 `.env.local`，不连接已有数据库、Redis、MQ、S3 或 Nacos。

端到端前端配置仅向 `http://127.0.0.1:15174` 开放 remote preview CORS，不改变常规开发配置；Shell 仍提供唯一 Vue/Router/Pinia 实例。Browser plugin not available，浏览器验证使用已有 Playwright Chromium。
