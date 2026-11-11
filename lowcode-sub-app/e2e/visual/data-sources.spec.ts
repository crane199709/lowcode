import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'

const manage = ['datasource:view', 'datasource:manage']

// 接近真实长度：最长的名称与云数据库主机名，以及常见的短名称
const items = [
  {
    id: '0193a000-0000-7000-8000-000000000001',
    name: '华东大区销售明细库（只读副本，供经营分析大屏使用）',
    type: 'POSTGRESQL',
    host: 'pgm-bp1x7k2m9q3n5r8t.pg.rds.aliyuncs.com',
    port: 5432,
    database: 'sales_east_reporting',
    username: 'bi_readonly',
    params: { sslmode: 'require' },
    createdAt: '2026-11-02T09:15:00+08:00',
    updatedAt: '2026-11-10T18:42:31+08:00',
  },
  {
    id: '0193a000-0000-7000-8000-000000000002',
    name: '仓储库存',
    type: 'POSTGRESQL',
    host: '203.0.113.24',
    port: 15432,
    database: 'wms',
    username: 'report',
    params: { sslmode: 'prefer' },
    createdAt: '2026-10-21T14:03:00+08:00',
    updatedAt: '2026-10-21T14:03:00+08:00',
  },
  {
    id: '0193a000-0000-7000-8000-000000000003',
    name: '会员订单',
    type: 'MYSQL',
    host: 'rm-uf6a1b2c3d4e5f6g7.mysql.rds.aliyuncs.com',
    port: 3306,
    database: 'member_order',
    username: 'bi_reader',
    params: { sslMode: 'REQUIRED', allowPublicKeyRetrieval: 'false' },
    createdAt: '2026-10-18T10:20:00+08:00',
    updatedAt: '2026-10-19T08:05:12+08:00',
  },
  {
    id: '0193a000-0000-7000-8000-000000000004',
    name: '财务总账',
    type: 'SQLSERVER',
    host: 'fin-sql01.corp.example.com',
    port: 1433,
    database: 'GeneralLedger',
    username: 'report_ro',
    params: { encrypt: 'true', trustServerCertificate: 'false' },
    createdAt: '2026-10-12T16:40:00+08:00',
    updatedAt: '2026-10-12T16:40:00+08:00',
  },
  {
    id: '0193a000-0000-7000-8000-000000000005',
    name: 'ERP 生产计划',
    type: 'ORACLE',
    host: '198.51.100.7',
    port: 1521,
    database: 'ERPPROD.example.com',
    username: 'APPS_READ',
    params: {},
    createdAt: '2026-09-30T11:00:00+08:00',
    updatedAt: '2026-10-08T09:30:45+08:00',
  },
]

async function mockList(page: Page, data: typeof items) {
  await page.route(
    (url) => url.pathname === '/lowcode/data-sources',
    (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data,
          meta: { page: { number: 1, size: 20, totalElements: data.length, totalPages: data.length ? 1 : 0 } },
          traceId: 'visual',
        },
      }),
  )
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('数据源列表', async ({ page }) => {
      await mockList(page, items)
      await open(page, 'datasources', manage)
      await expect(page.getByText('仓储库存')).toBeVisible()
      await expect(page.getByRole('button', { name: '新增' })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-list-${label}.png`)
    })

    test('数据源空列表', async ({ page }) => {
      await mockList(page, [])
      await open(page, 'datasources', manage)
      await expect(page.getByText('暂无数据源')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-empty-${label}.png`)
    })

    test('只读成员看不到新增与操作列', async ({ page }) => {
      await mockList(page, items)
      await open(page, 'datasources', ['datasource:view'])
      await expect(page.getByText('仓储库存')).toBeVisible()
      await expect(page.getByRole('button', { name: '新增' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '编辑' })).toHaveCount(0)
      await expect(page).toHaveScreenshot(`datasources-readonly-${label}.png`)
    })

    test('新增数据源', async ({ page }) => {
      await mockList(page, items)
      await open(page, 'datasources', manage)
      await page.getByRole('button', { name: '新增' }).click()
      await expect(page.getByRole('dialog', { name: '新增数据源' })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-create-${label}.png`)
    })

    test('新增 MySQL 数据源显示其连接参数', async ({ page }) => {
      await mockList(page, items)
      await open(page, 'datasources', manage)
      await page.getByRole('button', { name: '新增' }).click()
      const drawer = page.getByRole('dialog', { name: '新增数据源' })
      await drawer.getByLabel('类型').click({ force: true })
      await page.getByRole('option', { name: 'MySQL', exact: true }).click()
      await expect(drawer.getByLabel('端口')).toHaveValue('3306')
      await expect(drawer.getByText('允许获取服务器公钥', { exact: false })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-create-mysql-${label}.png`)
    })

    test('编辑 SQL Server 数据源', async ({ page }) => {
      await mockList(page, items)
      await open(page, 'datasources', manage)
      await page.getByRole('row', { name: /财务总账/ }).getByRole('button', { name: '编辑' }).click()
      const drawer = page.getByRole('dialog', { name: '编辑数据源' })
      await expect(drawer.getByLabel('库名')).toHaveValue('GeneralLedger')
      await expect(drawer.getByText('信任服务器证书', { exact: false })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-edit-sqlserver-${label}.png`)
    })

    test('编辑 Oracle 数据源时库名显示为服务名', async ({ page }) => {
      await mockList(page, items)
      await open(page, 'datasources', manage)
      await page.getByRole('row', { name: /ERP 生产计划/ }).getByRole('button', { name: '编辑' }).click()
      const drawer = page.getByRole('dialog', { name: '编辑数据源' })
      await expect(drawer.getByLabel('服务名')).toHaveValue('ERPPROD.example.com')
      await expect(drawer.getByText('SSL')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`datasources-edit-oracle-${label}.png`)
    })

    test('切换类型后测试连接和保存只提交当前类型的参数', async ({ page }) => {
      await mockList(page, items)
      const requests: { method: string; body: unknown }[] = []
      const sourcePath = `/lowcode/data-sources/${items[0]!.id}`
      await page.route(
        (url) => url.pathname === sourcePath || url.pathname === `${sourcePath}/connection-test`,
        (route) => {
          const request = route.request()
          requests.push({ method: request.method(), body: request.postDataJSON() })
          return route.fulfill({ json: { code: 'ok', httpStatus: 200, data: items[0] } })
        },
      )
      await open(page, 'datasources', manage)
      await page.getByRole('button', { name: '编辑' }).first().click()
      const drawer = page.getByRole('dialog', { name: '编辑数据源' })
      await drawer.getByLabel('密码').fill('New-test-password')

      const types = [
        ['MySQL', 'MYSQL', 3306, { sslMode: 'PREFERRED', allowPublicKeyRetrieval: 'false' }],
        ['SQL Server', 'SQLSERVER', 1433, { encrypt: 'true', trustServerCertificate: 'false' }],
        ['Oracle', 'ORACLE', 1521, {}],
        ['PostgreSQL', 'POSTGRESQL', 5432, { sslmode: 'prefer' }],
      ] as const
      for (const [name, type, port, params] of types) {
        await drawer.getByLabel('类型').click({ force: true })
        await page.getByRole('option', { name, exact: true }).click()
        await expect(drawer.getByLabel('端口')).toHaveValue(String(port))
        await drawer.getByRole('button', { name: '测试连接' }).click()
        await expect(drawer.getByText('连接成功', { exact: true })).toBeVisible()
        expect(requests.at(-1)).toMatchObject({ method: 'POST', body: { type, port, params } })
        expect((requests.at(-1)!.body as { params: unknown }).params).toEqual(params)
      }

      // 手填端口不会被类型默认值覆盖，旧类型参数也不会混入保存请求。
      await drawer.getByLabel('端口').fill('11521')
      await drawer.getByLabel('类型').click({ force: true })
      await page.getByRole('option', { name: 'Oracle', exact: true }).click()
      await expect(drawer.getByLabel('端口')).toHaveValue('11521')
      await expect(drawer.getByText('连接成功', { exact: true })).toHaveCount(0)
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      await expect(drawer).not.toBeVisible()
      expect(requests).toHaveLength(5)
      expect(requests.at(-1)).toMatchObject({ method: 'PUT', body: { type: 'ORACLE', port: 11521 } })
      expect((requests.at(-1)!.body as { params: unknown }).params).toEqual({})
    })

    test('测试连接期间切换类型不会显示旧类型的成功结果', async ({ page }) => {
      await mockList(page, items)
      let finish!: () => void
      const pending = new Promise<void>((resolve) => {
        finish = resolve
      })
      await page.route(
        (url) => url.pathname.endsWith('/connection-test'),
        async (route) => {
          await pending
          await route.fulfill({ json: { code: 'ok', httpStatus: 200 } })
        },
      )
      await open(page, 'datasources', manage)
      await page.getByRole('row', { name: /财务总账/ }).getByRole('button', { name: '编辑' }).click()
      const drawer = page.getByRole('dialog', { name: '编辑数据源' })
      const started = page.waitForRequest((request) => request.url().endsWith('/connection-test'))
      await drawer.getByRole('button', { name: '测试连接' }).click()
      await started
      await drawer.getByLabel('类型').click({ force: true })
      await page.getByRole('option', { name: 'Oracle', exact: true }).click()
      finish()
      await expect(drawer.getByRole('button', { name: '测试连接' })).not.toHaveClass(/is-loading/)
      await expect(drawer.getByText('连接成功', { exact: true })).toHaveCount(0)
    })

    test('编辑时测试连接失败', async ({ page }) => {
      await mockList(page, items)
      await page.route(
        (url) => url.pathname.endsWith('/connection-test'),
        (route) =>
          route.fulfill({
            status: 422,
            json: {
              code: 'datasource.unreachable',
              httpStatus: 422,
              errorMessage: 'Connection failed',
              userTip: '无法连接到数据库，请检查主机、端口与网络',
              traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
            },
          }),
      )
      await open(page, 'datasources', manage)
      await page.getByRole('button', { name: '编辑' }).first().click()
      const drawer = page.getByRole('dialog', { name: '编辑数据源' })
      await expect(drawer.getByLabel('密码')).toHaveValue('')
      await drawer.getByRole('button', { name: '测试连接' }).click()
      await expect(drawer.getByText('无法连接到数据库，请检查主机、端口与网络')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-edit-test-failed-${label}.png`)
    })
  })
}
