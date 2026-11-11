import { expect, test, type Page, type Browser } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const run = process.env.LOWCODE_E2E_RUN_DIR!
const credentials = JSON.parse(readFileSync(resolve(run, 'credentials.json'), 'utf8'))
const permissions = [
  'datasource:view',
  'datasource:manage',
  'dataset:view',
  'dataset:manage',
  'screen:view',
  'screen:edit',
  'screen:publish',
]
const runTag = Date.now().toString(36)
const tenantName = 'Lowcode 隔离验收租户 ' + runTag
const planName = 'Lowcode 全链路套餐 ' + runTag
const consoleOrigin = 'http://127.0.0.1:15173'
const shellOrigin = 'http://127.0.0.1:15174'

type Actor = {
  remoteStatus: () => number
  demoBaseline: () => boolean
  page: Page
  token: () => string
  errors: string[]
  close: () => Promise<void>
}
async function actor(browser: Browser): Promise<Actor> {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  })
  const page = await context.newPage()
  let token = ''
  let remoteStatus = 0
  let demoBaseline = false
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type()))
      errors.push(
        message.text() +
          (message.location().url ? ' [' + new URL(message.location().url).pathname + ']' : ''),
      )
  })
  // 只观察真实 PKCE 交换所得 Token；不写入 Shell 会话存储。
  page.on('response', async (response) => {
    if (new URL(response.url()).pathname === '/demo/context' && response.status() === 403)
      demoBaseline = true
    if (response.url() === 'http://127.0.0.1:15176/remoteEntry.js') remoteStatus = response.status()
    if (
      (response.url().includes('/auth/exchange') || response.url().includes('/auth/refresh')) &&
      response.ok()
    ) {
      const data = await response.json().catch(() => ({}))
      token = data.access_token ?? data.accessToken ?? data.data?.accessToken ?? token
    }
  })
  return {
    page,
    remoteStatus: () => remoteStatus,
    demoBaseline: () => demoBaseline,
    token: () => token,
    errors,
    close: () => context.close(),
  }
}
async function login(user: Actor, account: string, operator = false) {
  const page = user.page
  await page.goto(operator ? consoleOrigin : shellOrigin)
  await page.locator('input[name="account"]').fill(account)
  await page
    .getByLabel('密码', { exact: true })
    .fill(operator ? credentials.operatorPassword : credentials.memberPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (!operator) {
    await expect
      .poll(
        async () =>
          user.token() !== '' ||
          (await page.getByRole('heading', { name: '选择租户' }).isVisible()),
      )
      .toBe(true)
    if (await page.getByRole('heading', { name: '选择租户' }).isVisible())
      await page.getByRole('button', { name: tenantName, exact: true }).click()
  }
  await expect.poll(user.token).not.toBe('')
  await expect(page).toHaveTitle(operator ? 'SaaS Forge 平台控制台' : 'SaaS Forge 租户端')
}
async function api(user: Actor, path: string, method = 'GET', data?: unknown) {
  const response = await user.page.request.fetch('http://127.0.0.1:18000' + path, {
    method,
    headers: { Authorization: `Bearer ${user.token()}` },
    data,
  })
  expect(response.status(), `${method} ${path}`).toBeLessThan(300)
  return response.json()
}
async function accept(user: Actor, contact: string, account: string) {
  let invitation = ''
  await expect
    .poll(
      () => {
        const lines = readFileSync(resolve(run, 'forge-iam.log'), 'utf8').split('\n')
        const line = lines
          .filter((line) => line.includes(contact) && line.includes('invitations/accept'))
          .at(-1)
        invitation =
          line?.match(/http:\/\/127\.0\.0\.1:15174\/invitations\/accept\?[^\s]+/)?.[0] ?? ''
        return !!invitation
      },
      { timeout: 90_000 },
    )
    .toBe(true)
  const preview = user.page.waitForResponse((response) =>
    response.url().endsWith('/auth/invitations/preview'),
  )
  await user.page.goto(invitation)
  const invitationDetails = (await (await preview).json()).data
  if (invitationDetails.requiresCredentials) {
    await user.page.locator('#invitation-account').fill(account)
    await user.page.locator('#invitation-password').fill(credentials.memberPassword)
    await user.page.locator('#invitation-display-name').fill(account)
  }
  await user.page.locator('button[type="submit"]').click()
  await expect(user.page.getByTestId('invitation-accepted')).toBeVisible()
  await login(user, account)
}
async function select(page: Page, locator: ReturnType<Page['getByTestId']>, name: string) {
  const combobox =
    (await locator.getAttribute('role')) === 'combobox' ? locator : locator.getByRole('combobox')
  await expect
    .poll(async () => {
      if ((await combobox.getAttribute('aria-expanded')) !== 'true')
        await combobox.press('ArrowDown')
      return await page.getByRole('option', { name, exact: true }).isVisible()
    })
    .toBe(true)
  await page.getByRole('option', { name, exact: true }).click()
  await page.keyboard.press('Escape')
}
async function capture(page: Page, name: string) {
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  await expect(page.getByText('SaaS Forge', { exact: true }).filter({ visible: true })).toHaveCount(
    1,
  )
  await page.screenshot({
    path: resolve(run, name + '.png'),
    fullPage: false,
    animations: 'disabled',
  })
}

// Browser plugin not available；使用已安装 Playwright。真实 UI -> Gateway -> Nacos lb -> 服务 -> 外库/MQ/S3。
test('Operator 开放应用，普通设计者创建并发布，查看者与范围边界生效', async ({ browser }) => {
  const operator = await actor(browser)
  const owner = await actor(browser)
  const designer = await actor(browser)
  const viewer = await actor(browser)
  const forbidden = await actor(browser)
  const outside = await actor(browser)
  try {
    console.log('stage: operator login')
    await login(operator, credentials.operatorAccount, true)
    const op = operator.page
    console.log('stage: app registry')
    if (
      !(await api(operator, '/platform/sub-apps')).data.some(
        (app: { code: string }) => app.code === 'lowcode',
      )
    ) {
      await op.goto(consoleOrigin + '/sub-apps')
      await op.getByTestId('sub-app-create').click()
      await op.locator('#sub-app-code').fill('lowcode')
      await op.locator('#sub-app-name').fill('低代码')
      await op.locator('#sub-app-version').fill('0.1.0')
      await op.getByTestId('sub-app-env-0').fill('e2e')
      await op.getByTestId('sub-app-entry-0').fill('http://127.0.0.1:15176/remoteEntry.js')
      await op.getByRole('dialog').locator('button[type="submit"]').click()
      await expect(op.getByRole('dialog')).not.toBeVisible()
      expect((await api(operator, '/platform/sub-apps')).data).toEqual(
        expect.arrayContaining([expect.objectContaining({ code: 'lowcode' })]),
      )
    }
    console.log('stage: plan')
    await op.goto(consoleOrigin + '/plans')
    await op.getByTestId('plan-create').click()
    await op.locator('#plan-code').fill('lowcode-e2e-' + runTag)
    await op.locator('#plan-name').fill(planName)
    await select(op, op.getByTestId('plan-sub-apps'), 'lowcode')
    for (const menu of ['datasources', 'datasets', 'screens'])
      await op.getByTestId('menu-lowcode.' + menu).check()
    const planResponse = op.waitForResponse(
      (r) => new URL(r.url()).pathname === '/platform/plans' && r.request().method() === 'POST',
    )
    await op.getByRole('dialog').locator('button[type="submit"]').click()
    const plan = (await (await planResponse).json()).data
    expect(plan.permissions.sort()).toEqual([...permissions].sort())
    console.log('stage: tenant')
    await op.goto(consoleOrigin + '/tenants')
    await op.getByRole('button', { name: '创建租户', exact: true }).click()
    await op.locator('#tenant-name').fill(tenantName)
    await op.locator('#tenant-admin-contact').fill('owner@lowcode-e2e.test')
    await select(op, op.getByTestId('tenant-plan'), planName)
    const tenantResponse = op.waitForResponse(
      (r) => new URL(r.url()).pathname === '/platform/tenants' && r.request().method() === 'POST',
    )
    await op.getByRole('dialog').locator('button[type="submit"]').click()
    const tenant = (await (await tenantResponse).json()).data.tenant
    await expect
      .poll(
        async () =>
          (await api(operator, `/platform/tenants/${tenant.id}`)).data.initializations
            .map((i: { service: string; status: string }) => `${i.service}:${i.status}`)
            .sort(),
        { timeout: 90_000 },
      )
      .toEqual(['forge-iam:succeeded', 'lowcode-service:succeeded'])
    const detail = op.getByRole('dialog')
    await detail.getByRole('button', { name: '刷新', exact: true }).click()
    for (const service of ['forge-iam', 'lowcode-service'])
      await expect(detail.getByRole('row').filter({ hasText: service })).toContainText('成功')
    await detail.getByRole('row').filter({ hasText: 'lowcode-service' }).scrollIntoViewIfNeeded()
    await capture(op, 'operator-tenant-initialized')
    console.log('stage: owner invitation')
    await accept(owner, 'owner@lowcode-e2e.test', 'lowcode-owner')
    for (const path of ['data-sources', 'datasets', 'screens'])
      expect((await api(owner, '/lowcode/' + path)).data).toEqual([])
    const roles = (await api(owner, '/iam/roles')).data
    for (const code of ['screen-designer', 'screen-viewer'])
      expect(roles).toEqual(expect.arrayContaining([expect.objectContaining({ code })]))
    const designerRole = roles.find((r: { code: string }) => r.code === 'screen-designer')
    const viewerRole = roles.find((r: { code: string }) => r.code === 'screen-viewer')
    expect([...designerRole.permissions].sort()).toEqual([...permissions].sort())
    expect(viewerRole.permissions).toEqual(['screen:view'])
    await api(owner, `/iam/roles/${designerRole.id}/data-policies`, 'PUT', {
      policies: [{ resource: 'screen', scope: 'all' }],
    })
    await api(owner, `/iam/roles/${viewerRole.id}/data-policies`, 'PUT', {
      policies: [{ resource: 'screen', scope: 'all' }],
    })
    const deniedRole = (
      await api(owner, '/iam/roles', 'POST', {
        code: 'e2e-forbidden',
        name: '无大屏权限',
        permissions: [],
        dataPolicies: [],
      })
    ).data
    const selfRole = (
      await api(owner, '/iam/roles', 'POST', {
        code: 'e2e-self',
        name: '仅本人范围',
        permissions: ['screen:view'],
        dataPolicies: [{ resource: 'screen', scope: 'self' }],
      })
    ).data
    for (const [user, name, role] of [
      [designer, 'designer', designerRole],
      [viewer, 'viewer', viewerRole],
      [forbidden, 'forbidden', deniedRole],
      [outside, 'outside', selfRole],
    ] as const) {
      await api(owner, '/iam/members/invitations', 'POST', {
        contact: `${name}@lowcode-e2e.test`,
        roleIds: [role.id],
      })
      await accept(user, `${name}@lowcode-e2e.test`, `lowcode-${name}`)
    }
    console.log('stage: designer business UI')
    const page = designer.page
    await expect(page.getByTestId('menu-lowcode.datasources')).toBeVisible()
    await page.getByTestId('menu-lowcode.datasources').click()
    await expect.poll(designer.remoteStatus).toBe(200)
    await expect(page.getByRole('heading', { name: '数据源', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '新增', exact: true }).click()
    const sourceForm = page.getByRole('dialog')
    for (const [label, value] of [
      ['名称', '验收 PostgreSQL 只读数据源'],
      ['主机', '127.0.0.1'],
      ['端口', '15432'],
      ['库名', 'external_fixture'],
      ['账号', credentials.externalUser],
      ['密码', credentials.externalPassword],
    ])
      await sourceForm.getByLabel(label, { exact: true }).fill(value)
    await sourceForm.getByRole('button', { name: '测试连接', exact: true }).click()
    await expect(sourceForm.getByText('连接成功', { exact: true })).toBeVisible()
    const sourceResponse = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/lowcode/data-sources' && r.request().method() === 'POST',
    )
    await sourceForm.getByRole('button', { name: '保存', exact: true }).click()
    const source = (await (await sourceResponse).json()).data
    await expect(sourceForm).not.toBeVisible()
    await page.getByTestId('menu-lowcode.datasets').click()
    await page.getByRole('button', { name: '新增', exact: true }).click()
    const datasetForm = page.getByRole('dialog')
    await datasetForm.getByLabel('名称', { exact: true }).fill('销售验收 Dataset')
    await select(
      page,
      datasetForm.getByRole('combobox', { name: '数据源', exact: true }),
      '验收 PostgreSQL 只读数据源',
    )
    await datasetForm.getByLabel('SQL', { exact: true }).fill('SELECT region, amount FROM sales')
    await datasetForm.getByRole('button', { name: '预览', exact: true }).click()
    await expect(datasetForm.getByText('华南', { exact: true })).toBeVisible()
    await datasetForm.getByLabel('region 显示名').fill('区域')
    await datasetForm.getByLabel('amount 显示名').fill('销售金额')
    const datasetResponse = page.waitForResponse(
      (r) => new URL(r.url()).pathname === '/lowcode/datasets' && r.request().method() === 'POST',
    )
    await datasetForm.getByRole('button', { name: '保存', exact: true }).click()
    const dataset = (await (await datasetResponse).json()).data
    await expect(datasetForm).not.toBeVisible()
    await page.getByTestId('menu-lowcode.screens').click()
    await page.getByRole('button', { name: '新增', exact: true }).click()
    await page.getByRole('dialog').getByLabel('名称', { exact: true }).fill('真实销售验收大屏')
    const screenResponse = page.waitForResponse(
      (r) => new URL(r.url()).pathname === '/lowcode/screens' && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '创建并编辑', exact: true }).click()
    const screen = (await (await screenResponse).json()).data
    await page.getByRole('button', { name: '添加组件', exact: true }).click()
    await page.getByRole('menuitem', { name: '柱状图', exact: true }).click()
    await expect(page.getByRole('menuitem', { name: '柱状图', exact: true })).not.toBeVisible()
    await select(
      page,
      page.getByRole('combobox', { name: '柱状图数据集', exact: true }),
      '销售验收 Dataset',
    )
    await page
      .getByRole('button', { name: '拖动字段 区域', exact: true })
      .dragTo(page.getByRole('group', { name: '维度放置区', exact: true }))
    await page
      .getByRole('button', { name: '拖动字段 销售金额', exact: true })
      .dragTo(page.getByRole('group', { name: '度量放置区', exact: true }))
    await expect(page.getByRole('img', { name: '基础柱状图' })).toContainText('华东')
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMQkFD/DwAB2gFPHkSsgQAAAABJRU5ErkJggg==',
      'base64',
    )
    await page
      .getByLabel('背景图片', { exact: true })
      .setInputFiles({ name: '验收背景.png', mimeType: 'image/png', buffer: png })
    const image = page.getByAltText('大屏背景图')
    await expect(image).toBeVisible()
    await expect.poll(() => image.evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBe(1)
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
    await capture(page, 'designer-real-query-image')
    await page.getByTestId('menu-lowcode.screens').click()
    await page.getByRole('button', { name: '发布', exact: true }).click()
    const publishResponse = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `/lowcode/screens/${screen.id}/publish` &&
        response.request().method() === 'POST',
    )
    await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
    expect((await publishResponse).status()).toBe(200)
    await expect
      .poll(
        async () =>
          (await api(designer, `/lowcode/screens/${screen.id}/published`)).data.publishedAt,
      )
      .toBeTruthy()
    // 公共 API 旁证真实聚合结果，独立 literal 来自外库固定销售样本。
    const published = (await api(viewer, `/lowcode/screens/${screen.id}/published`)).data
    const widget = published.definition.widgets.find((w: { type: string }) => w.type === 'BAR')
    const query = (
      await api(viewer, `/lowcode/screens/${screen.id}/published/widgets/${widget.id}/query`)
    ).data
    expect(query.rows.sort()).toEqual([
      ['华东', 200],
      ['华南', 60],
    ])
    await viewer.page.clock.install()
    const downloadTicket = viewer.page.waitForResponse(
      (r) =>
        r.url().includes('/images/') &&
        !r.url().includes('/draft/') &&
        r.url().endsWith('/download'),
    )
    await viewer.page.getByTestId('menu-lowcode.screens').click()
    await viewer.page.getByRole('button', { name: '播放', exact: true }).click()
    await expect(viewer.page.getByRole('region', { name: '已发布大屏' })).toBeVisible()
    await expect(viewer.page.getByRole('img', { name: '基础柱状图' })).toContainText('华南')
    await expect
      .poll(() =>
        viewer.page
          .getByAltText('大屏背景图')
          .evaluate((img) => (img as HTMLImageElement).naturalWidth),
      )
      .toBe(1)
    await capture(viewer.page, 'viewer-published-playback')
    await viewer.page.setViewportSize({ width: 1024, height: 768 })
    await capture(viewer.page, 'viewer-laptop-playback')
    const ticket = (await (await downloadTicket).json()).data
    const renewal = viewer.page.waitForResponse(
      (r) =>
        r.url().includes('/images/') &&
        !r.url().includes('/draft/') &&
        r.url().endsWith('/download'),
    )
    await viewer.page.clock.fastForward(Math.max(1000, Date.parse(ticket.expiresAt) - Date.now()))
    const renewed = await renewal
    expect(renewed.status()).toBe(200)
    const renewedTicket = (await renewed.json()).data
    await expect
      .poll(
        async () =>
          (await viewer.page.getByAltText('大屏背景图').getAttribute('src')) === renewedTicket.url,
      )
      .toBe(true)
    await expect
      .poll(() =>
        viewer.page
          .getByAltText('大屏背景图')
          .evaluate((img) => (img as HTMLImageElement).naturalWidth),
      )
      .toBe(1)
    await expect(forbidden.page.getByTestId('menu-lowcode.screens')).toHaveCount(0)
    expect(
      (
        await forbidden.page.request.get('http://127.0.0.1:18000/lowcode/screens', {
          headers: { Authorization: `Bearer ${forbidden.token()}` },
        })
      ).status(),
    ).toBe(403)
    expect((await api(outside, '/lowcode/screens')).data).toEqual([])
    for (const path of [`/lowcode/screens/${screen.id}`, `/lowcode/screens/${screen.id}/published`])
      expect(
        (
          await outside.page.request.get('http://127.0.0.1:18000' + path, {
            headers: { Authorization: `Bearer ${outside.token()}` },
          })
        ).status(),
      ).toBe(404)
    await outside.page.getByTestId('menu-lowcode.screens').click()
    await expect(outside.page.getByText('暂无大屏')).toBeVisible()
    await capture(outside.page, 'scope-outside-empty')
    // 删除审计使用无 Dataset 引用的独立 Data Source。
    const unused = (
      await api(designer, '/lowcode/data-sources', 'POST', {
        name: '待删除数据源',
        type: 'POSTGRESQL',
        database: {
          host: '127.0.0.1',
          port: 15432,
          database: 'external_fixture',
          username: credentials.externalUser,
          password: credentials.externalPassword,
          params: { sslmode: 'disable' },
        },
      })
    ).data
    await api(designer, `/lowcode/data-sources/${unused.id}`, 'PUT', {
      name: '待删除数据源已修改',
      type: 'POSTGRESQL',
      database: {
        host: '127.0.0.1',
        port: 15432,
        database: 'external_fixture',
        username: credentials.externalUser,
        params: { sslmode: 'disable' },
      },
    })
    await api(designer, `/lowcode/data-sources/${unused.id}`, 'DELETE')
    await expect
      .poll(
        async () =>
          (await api(owner, `/iam/audit/events?resourceId=${unused.id}`)).data
            .map((e: { action: string }) => e.action)
            .sort(),
        { timeout: 60_000 },
      )
      .toEqual(['datasource.create', 'datasource.delete', 'datasource.update'])
    await expect
      .poll(
        async () =>
          (await api(owner, `/iam/audit/events?resourceId=${screen.id}`)).data.some(
            (e: { action: string }) => e.action === 'screen.publish',
          ),
        { timeout: 60_000 },
      )
      .toBe(true)
    for (const resourceId of [unused.id, screen.id]) {
      const events = (await api(owner, `/iam/audit/events?resourceId=${resourceId}`)).data
      for (const event of events) {
        expect(event).toMatchObject({
          service: 'lowcode-service',
          outcome: 'success',
          actorAccount: 'lowcode-designer',
          resourceId,
        })
        expect(Boolean(event.traceId)).toBe(true)
      }
    }
    for (const path of [`/lowcode/data-sources/${source.id}`, `/lowcode/datasets/${dataset.id}`]) {
      const denied = await page.request.delete('http://127.0.0.1:18000' + path, {
        headers: { Authorization: `Bearer ${designer.token()}` },
      })
      expect(denied.status()).toBe(409)
    }
    // 业务服务自身仍校验 Tenant Realm，不能仅依赖网关。
    expect(
      (
        await page.request.get('http://127.0.0.1:18202/screens', {
          headers: { Authorization: `Bearer ${designer.token()}` },
        })
      ).status(),
    ).toBe(200)
    expect(
      (
        await page.request.get('http://127.0.0.1:18202/screens', {
          headers: { Authorization: `Bearer ${operator.token()}` },
        })
      ).status(),
    ).toBe(401)
    expect((await page.request.get('http://127.0.0.1:18202/screens')).status()).toBe(401)
    const tenantHeader = { Authorization: `Bearer ${designer.token()}` }
    expect(
      (
        await page.request.get('http://127.0.0.1:18000/lowcode/internal/ops', {
          headers: tenantHeader,
        })
      ).status(),
    ).toBe(403)
    expect(
      (
        await page.request.get('http://127.0.0.1:18000/lowcode/screens', {
          headers: { Authorization: `Bearer ${operator.token()}` },
        })
      ).status(),
    ).toBe(401)
    expect((await page.request.get('http://127.0.0.1:18000/lowcode/screens')).status()).toBe(401)
    // 平台反馈 08：只排除已观测到同路径 403 的 Shell 首页示例请求，其他错误保持严格。
    for (const user of [operator, owner, designer, viewer, forbidden, outside])
      expect(
        user.errors.filter(
          (e) =>
            !(
              user.demoBaseline() &&
              e.startsWith('Failed to load resource: the server responded with a status of 403') &&
              e.endsWith('[/demo/context]')
            ),
        ),
      ).toEqual([])
    writeFileSync(
      resolve(run, 'acceptance-result.json'),
      JSON.stringify(
        {
          tenantId: tenant.id,
          screenId: screen.id,
          dataSourceId: source.id,
          datasetId: dataset.id,
          queryRows: query.rows,
          knownPlatformBaseline:
            '08: Shell HomePage /demo/context 403 (exact URL and actual response observed)',
          checks: [
            'realOperatorUI',
            'realTenantShell',
            'realMQProvisioning',
            'realNacosLB',
            'realAuditConsumption',
            'realS3Image',
            'viewerPlayback',
            'noPermission403',
            'selfScopeEmptyAnd404',
            'realmBoundary',
          ],
        },
        null,
        2,
      ),
    )
  } finally {
    await Promise.all(
      [operator, owner, designer, viewer, forbidden, outside].map((user) => user.close()),
    )
  }
})
