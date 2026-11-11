import { expect, test, type Page, type Route } from '@playwright/test'
import { open, viewports } from './session'

const source = {
  id: '0193a000-0000-7000-8000-000000000001',
  name: '华东大区销售明细库（经营分析只读副本）',
  type: 'POSTGRESQL',
  host: 'pgm-bp1x7k2m9q3n5r8t.pg.rds.aliyuncs.com',
  port: 5432,
  database: 'sales_east_reporting',
  username: 'bi_readonly',
  params: {},
  api: null,
  createdAt: '2026-11-02T09:15:00+08:00',
  updatedAt: '2026-11-10T18:42:31+08:00',
}
const tables = [
  { catalog: 'sales', schema: 'reporting_east', name: 'monthly_sales_detail_by_region' },
  { catalog: 'sales', schema: 'reporting_west', name: 'monthly_sales_detail_by_region' },
  {
    catalog: '',
    schema: '',
    name: 'monthly_sales_detail_by_region_and_business_department_for_annual_reporting',
  },
]
const columns = [
  { name: 'business_region_name', type: 'varchar' },
  { name: 'total_sales_amount_excluding_tax', type: 'numeric' },
  { name: 'last_updated_at', type: 'timestamp' },
]
const ok = (route: Route, data: unknown) =>
  route.fulfill({ json: { code: 'ok', httpStatus: 200, data } })
const failed = (route: Route) =>
  route.fulfill({
    status: 422,
    json: {
      code: 'datasource.unreachable',
      httpStatus: 422,
      userTip: '无法连接到数据库，请检查主机、端口与网络',
      traceId: 'metadata-trace-20261111',
    },
  })
async function setup(page: Page) {
  await page.route(
    (url) => url.pathname === '/lowcode/data-sources',
    (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: [source],
          meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
        },
      }),
  )
  await page.route('**/tables', (route) => ok(route, tables))
  await page.route('**/columns?*', (route) => ok(route, columns))
}
async function browse(page: Page) {
  await open(page, 'datasources', ['datasource:view'])
  await page.getByRole('button', { name: '浏览表' }).click()
  const drawer = page.getByRole('dialog', { name: '数据源详情' })
  await expect(drawer).toBeVisible()
  return drawer
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test.beforeEach(async ({ page }) => {
      await setup(page)
    })

    test('查看成员浏览表与列', async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      page.on('console', (message) => {
        if (message.type() === 'error') errors.push(message.text())
      })
      const drawer = await browse(page)
      await expect(page).toHaveURL(/\/apps\/lowcode\/datasources$/)
      await expect(page.locator('vite-error-overlay')).toHaveCount(0)
      await drawer.getByRole('button', { name: tables[0].name, exact: true }).first().click()
      await expect(drawer.getByText('business_region_name', { exact: true })).toBeVisible()
      await expect(drawer.getByText('numeric', { exact: true })).toBeVisible()
      await expect(drawer.getByText('—', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-metadata-${label}.png`)
      expect(errors).toEqual([])
    })

    test('表为空', async ({ page }) => {
      await page.route('**/tables', (route) => ok(route, []))
      const drawer = await browse(page)
      await expect(drawer.getByText('当前账号没有可浏览的表')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-metadata-empty-${label}.png`)
    })

    test('表加载失败后可以重试', async ({ page }) => {
      await page.route('**/tables', failed)
      const drawer = await browse(page)
      await expect(drawer.getByText('无法连接到数据库，请检查主机、端口与网络')).toBeVisible()
      await expect(drawer.getByText('metadata-trace-20261111')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-metadata-error-${label}.png`)
      await page.unroute('**/tables', failed)
      await drawer.getByRole('button', { name: '重新加载' }).click()
      await expect(drawer.getByText('reporting_east', { exact: true })).toBeVisible()
    })

    test('列为空与加载失败', async ({ page }) => {
      await page.route('**/columns?*', (route) => ok(route, []))
      const drawer = await browse(page)
      await drawer.getByRole('row').filter({ hasText: 'reporting_east' }).click()
      await expect(drawer.getByText('该表没有可浏览的列')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-metadata-columns-empty-${label}.png`)
      await page.route('**/columns?*', failed)
      await drawer.getByRole('row').filter({ hasText: 'reporting_west' }).click()
      await expect(drawer.getByText('metadata-trace-20261111')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-metadata-columns-error-${label}.png`)
    })

    test('表加载中', async ({ page }) => {
      let complete: (() => Promise<void>) | undefined
      await page.route('**/tables', (route) => {
        complete = () => ok(route, tables)
      })
      const drawer = await browse(page)
      await expect(drawer.locator('.el-skeleton')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasources-metadata-loading-${label}.png`)
      await complete!()
      await expect(drawer.getByText('reporting_east', { exact: true })).toBeVisible()
    })

    test('快速切换表时旧列响应不能覆盖当前表', async ({ page }) => {
      let complete: (() => Promise<void>) | undefined
      await page.route('**/columns?*', (route) => {
        const query = new URL(route.request().url()).searchParams
        if (query.get('schema') === 'reporting_east') {
          complete = () => ok(route, [{ name: 'old_column', type: 'varchar' }])
        } else {
          return ok(route, [{ name: 'west_column', type: 'numeric' }])
        }
      })
      const drawer = await browse(page)
      await drawer.getByRole('row').filter({ hasText: 'reporting_east' }).click()
      await expect(drawer.locator('.el-skeleton')).toBeVisible()
      await drawer.getByRole('row').filter({ hasText: 'reporting_west' }).click()
      await expect(drawer.getByText('west_column', { exact: true })).toBeVisible()
      const response = page.waitForResponse((url) => url.url().includes('schema=reporting_east'))
      await complete!()
      await response
      await expect(drawer.getByText('west_column', { exact: true })).toBeVisible()
      await expect(drawer.getByText('old_column', { exact: true })).toHaveCount(0)
    })
  })
}
