# 01: 清理示例，声明 lowcode 资源

**What to build:** 生成工程不再带订单示例，平台看到的是 lowcode 自己的 Resource Manifest：租户授予权限后能在 Shell 看到「低代码」分组下的数据源、数据集、大屏三个菜单，点开是各自的空列表页。这是后续切片的准备工单。见规格第 2、8 节。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 移除订单、分类、通知示例的代码、mapper、迁移、消息与测试；SDK 必需表的迁移保留，`SchemaCommentTest` 通过
- [ ] Manifest 声明 Permission Code `datasource:view`、`datasource:manage`、`dataset:view`、`dataset:manage`、`screen:view`、`screen:edit`、`screen:publish`
- [ ] Manifest 声明 Data Resource `screen`（资源属性：创建人、所属部门），角色模板「大屏设计者」「大屏查看者」，菜单分组「低代码」及三个指向 Sub-App `lowcode` 的叶子
- [ ] 原示例覆盖的通用测试（租户隔离、功能权限、Data Policy、架构）改用一个最小的 lowcode 资源继续覆盖，不因删除示例而丢失
- [ ] Sub-App 去掉示例首页，提供三个空列表页路由，按列表原型 `PageHeader → PageToolbar → ListPage` 搭好空状态
- [ ] 后端 `verify`、前端 `build` 通过
