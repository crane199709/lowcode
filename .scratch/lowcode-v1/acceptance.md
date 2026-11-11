# 票据 17：隔离平台全链路验收

2026-11-11，结论：通过。最终从全新空卷、独立数据库账号和每轮生成的密钥启动真实平台，单次完成完整链路；运行结束后已核对自有 Java 进程、Compose project、卷和 13 个监听端口全部清理。

## 实现与环境

- 生产改动仅在既有 `LowcodeApplication` 声明空 `TenantInitializer`：业务对象仍由 Tenant 创建，SDK 真实消费 TenantCreated 并回报 succeeded，不增加第三个业务功能包。
- 低代码自有入口为 `deploy/e2e/run.sh`，浏览器用例为 `lowcode-sub-app/e2e/platform/acceptance.spec.ts`。不运行平台发布或模板生成流水线，不修改平台生产源码。
- 平台源码：`/Users/liuhuan/workspace/coding/saas-forge`。独立服务为 IAM 18101、platform 18102、lowcode 18202、gateway 18000；控制台 15173、Tenant Shell 15174、真实 Federation remote 15176。
- 依赖为 PostgreSQL 17（15432）、Redis 8.8.1（16379）、RocketMQ 5.3.2 Proxy（18081）、Silo S3（19000）、Nacos 3.1.1（HTTP 18848 / gRPC 19848）。全部使用新 project / 新卷，宿主映射仅 loopback。
- Browser plugin not available，使用现有 Playwright Chromium；视口 1440×1000 与 1024×768，中文、Asia/Shanghai。最终五张截图均已逐张查看。截图先等待唯一可见 Shell 品牌，再禁用过渡动画；早期 resize 瞬态的重复 header 未作为交付证据。
- 旧 0.1.0 tarball 在真实 Shell 中触发 sdk/ui singleton 版本不满足告警。按平台反馈 01，已从当前源码构建并定向打包 sdk/ui/sub-app-kit 为 0.2.0-SNAPSHOT，更新依赖、overrides 与 lockfile；保留 `singleton: true`、`import: false` 与精确版本要求。

## 已验证行为

| 项目 | 实际结果 |
| --- | --- |
| 数据库接入 | lowcode_app / lowcode_migrator 均非 superuser、非 BYPASSRLS；schema 属于 migrator，Flyway installed_by 为 migrator；准备脚本第二次成功；运行账号建表和读取 IAM schema 被拒 |
| Manifest / Ops | IAM 自动接收真实低代码 Manifest，目录含 7 个权限码；错误 Manifest token 401；直连 Ops 正确专属 token 200、错误 token 401；Manifest 与 Ops 密钥独立 |
| Nacos / 网关 | 两个自有 dataId 发布后精确读回并记录摘要；四个服务真实注册为 127.0.0.1 和准确端口、健康；导入的网关 dataId 使用 lb://，无静态直连兜底 |
| 公开路由 | 匿名网关 JWKS 与 OpenAPI JSON 可读，OpenAPI servers 含 http://127.0.0.1:18000/lowcode；无 Token 业务请求 401、Platform Token 401；有效 Tenant Token 访问 gateway internal 路径 403 |
| 服务自身认证 | 直连 lowcode 的 Tenant Token 请求 200；Platform Token 和匿名请求 401，认证不只依赖网关 |
| Operator UI | 控制台真实密码登录与 PKCE；首次登记 lowcode App Registry；新建套餐并在权限树勾选 7 项权限；通过 UI 创建租户 |
| 真实开通 | 新租户经 RocketMQ 使 IAM、lowcode 两项均 succeeded；UI 刷新后两行显示成功；UI 创建前 Data Source / Dataset / Screen 三类列表均为 0 |
| 普通成员与角色 | Owner 仅用于夹具角色/成员设置；真实模板 screen-designer 的 7 项权限、screen-viewer 的 screen:view 均核对；显式配置 screen all / self 范围；四个普通身份经真实邀请接受和登录 |
| 设计者 UI | Shell 内实际登记 PostgreSQL 只读 Data Source，测试连接成功；保存 Dataset，真实预览与 Field 显示名调整；创建 Screen、拖入维度与度量形成柱状图、保存草稿并发布 |
| 查询与图片 | 外部只读样本库独立于业务 schema；实际聚合结果为华东 200、华南 60；真实浏览器预签名 PUT、S3 CORS、上传确认与已发布图片加载成功 |
| 图片续签 | 控制浏览器时钟提前触发真实下载票据续签，续签 HTTP 200，图片使用新票据且可解码；未修改服务器时钟、未 mock HTTP 或 S3 |
| Playback 与边界 | 普通 viewer 实际播放图表和图片；无 screen:view 的普通成员无菜单且 HTTP 403；self 范围成员列表为空、单对象和发布快照 404，UI 显示暂无大屏 |
| 引用与审计 | 引用中的 Data Source / Dataset 删除均 409；另建无引用 Data Source 经真实 API 创建、修改、删除；IAM 已消费对应 3 项审计和 screen.publish，均 success、actorAccount=lowcode-designer、service=lowcode-service、traceId 非空 |
| 浏览器健康 | 业务页面不空白、无框架覆盖层、真实 remoteEntry 200；无共享版本、CORS、资源 404、Vue 注入或其他低代码运行错误；平台首页已知 403 见下文 |

## 验证命令与结果

- 平台相关后端定向构建：`./mvnw -o -pl forge-iam,forge-platform,forge-gateway,forge-sdk/forge-sdk-storage -am install -DskipTests`，成功，日志 `/private/tmp/lowcode-v1-platform-backend-build.log`。
- 当前平台 SDK/UI、Tenant Shell、控制台及依赖构建成功，日志 `/private/tmp/lowcode-v1-platform-frontend-build.log`；本轮另行 `pnpm --filter @crane199709/sub-app-kit build` 后定向 pack 三个 snapshot 包，未发布。
- 低代码后端：`./mvnw -o spotless:apply verify`，**181 tests，0 failure / 0 error / 0 skip，BUILD SUCCESS**；日志 `/private/tmp/lowcode-v1-17-verify.log`。
- 更换共享包后：`pnpm build`、`pnpm format:check` 均通过，`pnpm test:visual` **130 passed（3.4m）**；日志 `/private/tmp/lowcode-v1-17-snapshot-{build,format,visual}.log`。ECharts 既有大 chunk 提示仍存在。
- 最终新环境：从仓库根执行 `./deploy/e2e/run.sh --skip-build`，使用上述当前源码工件，最终 **1 passed（24.4s）**；完整入口日志 `/private/tmp/lowcode-v1-17-final-repeat-run.log`，私有运行目录 `/private/tmp/lowcode-platform-nxe1thcd`。首次空环境复跑 `/private/tmp/lowcode-platform-oxjh_q9s` 亦完整通过；交付截图取稳定 capture 后的 nxe1thcd 轮次。
- `python3 -m py_compile deploy/e2e/run.py` 与 `git diff --check` 通过。运行器的大字典仅做格式展开，另经 AST 等价断言确认行为未变。

TDD 红灯来自真实公开接口：缺少初始化器时 IAM succeeded、lowcode pending 持续 90 秒；加入初始化器后从全新环境转绿。脱敏摘录见 [tdd-red.txt](evidence/17/tdd-red.txt)。

## 平台反馈与边界

- 平台反馈 01：`.scratch/lowcode-validation/issues/01-release-version-drift.md` 已追加本轮真实 Federation 告警与同版 snapshot 绕行证据；正式包发布前仍使用 `.forge-packages/`。
- 新平台反馈 08：`.scratch/lowcode-validation/issues/08-tenant-home-assumes-demo.md`。Shell 首页固定请求 `/demo/context`，无 Demo 的隔离平台真实返回 403。验收仅在同一个 actor 实际观察到该路径 HTTP 403，且 console location 精确为 `/demo/context` 时排除这一已知基线；其他 401/403/404、CORS、版本或运行错误仍失败。未追加 Demo 路由或权限，未改平台源码。
- 本次实际全平台业务链路覆盖 PostgreSQL、柱状图和背景图片；默认纳入的 MySQL / SQL Server / ClickHouse 连接及九类 Widget 由本轮 181 后端测试与 130 前端回归覆盖，未声称它们均在真实 Shell 中重走一遍。未重跑 Oracle slow profile：本次未改外部连接或驱动。未验证手机视口或真实等待票据自然过期。
- 原始凭据、邀请链接、Nacos 日志、配置和登录表单证据仅留私有临时目录，未提交。只允许 SQL 引导本轮首个 Operator 和外部只读样本库；没有手工写业务对象、Redis grants/status/IP allowlist、初始化或审计结果。
- 此结论只属于新建隔离平台，不代表用户既有开发环境已完成接入。原工作区 `/Users/liuhuan/workspace/coding/sub/lowcode` 保持 `c2d1d231b45c14bcc601f70255fa404a97687c11` 且工作区干净；现有数据库、服务、角色、Nacos、MQ、S3 与 `.env.local` 未被改动。

## 脱敏证据

[evidence/17](evidence/17) 包含真实聚合结果、Nacos 实例/配置摘要、OpenAPI 前缀、数据库账号身份和最终清理核验。原始私有日志不在其中。

- [平台初始化成功](evidence/17/operator-tenant-initialized.png)
- [普通设计者真实图表与图片](evidence/17/designer-real-query-image.png)
- [查看者 Playback 1440](evidence/17/viewer-published-playback.png)
- [查看者 Playback 1024](evidence/17/viewer-laptop-playback.png)
- [本人范围成员空列表](evidence/17/scope-outside-empty.png)
