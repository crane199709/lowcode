import { expect, test } from '@playwright/test'
import { open, viewports } from './session'

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('ClickHouse 数据源连接参数与保存', async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      page.on('console', (message) => {
        if (['warning', 'error'].includes(message.type())) errors.push(message.text())
      })
      await page.route(
        (url) => url.pathname === '/lowcode/data-sources',
        (route) =>
          route.fulfill({
            json: {
              code: 'ok',
              httpStatus: 200,
              data: [],
              meta: { page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } },
            },
          }),
      )
      await open(page, 'datasources', ['datasource:view', 'datasource:manage'])
      await expect(page).toHaveTitle(/lowcode/i)
      await expect(page.locator('vite-error-overlay')).toHaveCount(0)
      await page.getByRole('button', { name: '新增' }).click()
      const drawer = page.getByRole('dialog', { name: '新增数据源' })
      await drawer.getByLabel('类型').click({ force: true })
      await page.getByRole('option', { name: 'ClickHouse', exact: true }).click()
      await expect(drawer.getByLabel('端口')).toHaveValue('8123')
      await drawer.getByLabel('名称', { exact: true }).fill('客户行为与订单分析')
      await drawer.getByLabel('主机', { exact: true }).fill('analytics.clickhouse.example.com')
      await drawer.getByLabel('库名', { exact: true }).fill('customer_analytics')
      await drawer.getByLabel('账号', { exact: true }).fill('dashboard_reader')
      await drawer.getByLabel('密码', { exact: true }).fill('example-secret')
      await drawer
        .locator('.el-form-item')
        .filter({ hasText: '加密连接' })
        .locator('.el-select__wrapper')
        .click()
      await page.getByRole('option', { name: '加密', exact: true }).click()
      await expect(page).toHaveScreenshot(`datasources-create-clickhouse-${label}.png`)
      await page.route(
        (url) => url.pathname === '/lowcode/data-sources/connection-test',
        async (route) => {
          expect(route.request().postDataJSON()).toMatchObject({
            type: 'CLICKHOUSE',
            database: { port: 8123, params: { ssl: 'true' } },
          })
          await route.fulfill({ json: { code: 'ok', httpStatus: 200 } })
        },
      )
      await drawer.getByRole('button', { name: '测试连接' }).click()
      await expect(drawer.getByText('连接成功', { exact: true })).toBeVisible()
      await page.route(
        (url) => url.pathname === '/lowcode/data-sources' && url.search === '',
        async (route) => {
          if (route.request().method() === 'POST') {
            expect(route.request().postDataJSON()).toMatchObject({
              type: 'CLICKHOUSE',
              database: { params: { ssl: 'true' } },
            })
            await route.fulfill({
              status: 201,
              json: { code: 'ok', httpStatus: 201, data: { id: 'source' } },
            })
          } else {
            await route.fulfill({
              json: {
                code: 'ok',
                httpStatus: 200,
                data: [],
                meta: { page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } },
              },
            })
          }
        },
      )
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      await expect(drawer).not.toBeVisible()
      expect(errors).toEqual([])
    })
  })
}
