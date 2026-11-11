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
