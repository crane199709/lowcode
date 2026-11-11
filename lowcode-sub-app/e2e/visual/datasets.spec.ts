import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'

const fields = [
  { name: 'region', displayName: '销售区域', type: 'STRING', role: 'DIMENSION', date: false },
  { name: 'amount', displayName: '销售金额', type: 'NUMBER', role: 'MEASURE', date: false },
]
const dataset = {
  id: '0193a000-0000-7000-8000-000000000011',
  name: '华东大区销售明细（经营分析大屏使用）',
  dataSourceId: '0193a000-0000-7000-8000-000000000001',
  dataSourceName: '华东销售只读数据库',
  sql: 'SELECT region, amount FROM sales',
  fields,
  createdAt: '2026-11-11T09:00:00+08:00',
  updatedAt: '2026-11-11T10:20:30+08:00',
}
const manage = ['dataset:view', 'dataset:manage']
async function mock(page: Page, items = [dataset]) {
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/datasets'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      const method = route.request().method()
      if (path === '/lowcode/datasets/sources') {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: [{ id: dataset.dataSourceId, name: dataset.dataSourceName, type: 'POSTGRESQL' }],
            meta: { page: { number: 1, size: 100, totalElements: 1, totalPages: 1 } },
          },
        })
      } else if (path === '/lowcode/datasets/preview') {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: {
              fields,
              rows: [
                ['华东', 1234.5],
                [null, 0],
              ],
              truncated: false,
              maxRows: 10000,
            },
          },
        })
      } else if (method === 'GET' && path === '/lowcode/datasets') {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: items,
            meta: {
              page: {
                number: 1,
                size: 20,
                totalElements: items.length,
                totalPages: items.length ? 1 : 0,
              },
            },
          },
        })
      } else {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: method === 'POST' ? 201 : 200,
            data: method === 'DELETE' ? null : dataset,
          },
        })
      }
    },
  )
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('数据集列表与编辑预览', async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      await mock(page)
      await open(page, 'datasets', manage)
      await expect(page.getByText(dataset.name, { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-list-${label}.png`)
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await expect(drawer.getByText('请使用只读账号')).toBeVisible()
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      await expect(drawer.getByText('华东', { exact: true })).toBeVisible()
      await expect(drawer.getByRole('table').last().getByText('—', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-edit-preview-${label}.png`)
      await drawer.getByLabel('amount 显示名').fill('订单金额')
      await drawer.getByLabel('amount 日期').check()
      const saved = page.waitForRequest((request) => request.method() === 'PUT')
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      expect((await saved).postDataJSON().fields[1]).toMatchObject({
        name: 'amount',
        displayName: '订单金额',
        date: true,
      })
      await expect(drawer).not.toBeVisible()
      expect(errors).toEqual([])
    })
    test('数据集空列表与新增', async ({ page }) => {
      await mock(page, [])
      await open(page, 'datasets', manage)
      await expect(page.getByText('暂无数据集')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-empty-${label}.png`)
      await page.getByRole('button', { name: '新增', exact: true }).click()
      await expect(page.getByRole('dialog')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-create-${label}.png`)
    })
    test('修改 SQL 后重新推断并保留同名字段配置', async ({ page }) => {
      await mock(page)
      await open(page, 'datasets', manage)
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByLabel('amount 显示名').fill('订单金额')
      await drawer.getByLabel('amount 日期').check()
      await drawer.getByRole('textbox', { name: 'SQL', exact: true }).fill(dataset.sql + ' ')
      const saved = page.waitForRequest((request) => request.method() === 'PUT')
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      expect((await saved).postDataJSON().fields[1]).toMatchObject({
        name: 'amount',
        displayName: '订单金额',
        role: 'MEASURE',
        date: true,
      })
      await expect(drawer).not.toBeVisible()
    })
    test('新增保存与删除', async ({ page }) => {
      await mock(page)
      await open(page, 'datasets', manage)
      await page.getByRole('button', { name: '新增', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('月度销售额')
      await drawer.getByRole('combobox').click()
      await page.getByRole('option', { name: dataset.dataSourceName }).click()
      await drawer
        .getByRole('textbox', { name: 'SQL', exact: true })
        .fill('SELECT region, amount FROM sales')
      const created = page.waitForRequest(
        (request) =>
          request.method() === 'POST' && new URL(request.url()).pathname === '/lowcode/datasets',
      )
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      expect((await created).postDataJSON()).toMatchObject({
        name: '月度销售额',
        dataSourceId: dataset.dataSourceId,
        fields: [],
      })
      await expect(drawer).not.toBeVisible()
      const removed = page.waitForRequest((request) => request.method() === 'DELETE')
      await page.getByRole('button', { name: '删除', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click()
      expect(new URL((await removed).url()).pathname).toBe(`/lowcode/datasets/${dataset.id}`)
    })
    test('预览失败显示错误并可修改 SQL 重试', async ({ page }) => {
      await mock(page)
      await page.route(
        (url) => url.pathname === '/lowcode/datasets/preview',
        (route) =>
          route.fulfill({
            status: 422,
            json: {
              code: 'dataset.queryTimeout',
              httpStatus: 422,
              userTip: '查询超时，请简化 SQL 后重试',
              traceId: 'visual-timeout',
            },
          }),
      )
      await open(page, 'datasets', manage)
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      await expect(drawer.getByText('查询超时，请简化 SQL 后重试')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-preview-error-${label}.png`)
      await drawer.getByRole('textbox', { name: 'SQL', exact: true }).fill('SELECT 1 AS amount')
      await expect(drawer.getByText('查询超时，请简化 SQL 后重试')).toHaveCount(0)
      await expect(drawer.getByLabel('amount 显示名')).toHaveCount(0)
    })
    test('SQL 修改后忽略旧预览结果', async ({ page }) => {
      await mock(page)
      let release!: () => void
      const held = new Promise<void>((resolve) => {
        release = resolve
      })
      let started!: () => void
      const pending = new Promise<void>((resolve) => {
        started = resolve
      })
      await page.route(
        (url) => url.pathname === '/lowcode/datasets/preview',
        async (route) => {
          started()
          await held
          await route.fulfill({
            json: {
              code: 'ok',
              httpStatus: 200,
              data: { fields, rows: [['过期结果', 99]], truncated: false, maxRows: 10000 },
            },
          })
        },
      )
      await open(page, 'datasets', manage)
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      await pending
      await drawer
        .getByRole('textbox', { name: 'SQL', exact: true })
        .fill('SELECT 1 AS current_amount')
      release()
      await expect(drawer.getByRole('button', { name: '预览', exact: true })).toBeEnabled()
      await expect(drawer.getByText('过期结果', { exact: true })).toHaveCount(0)
      await expect(drawer.getByLabel('amount 显示名')).toHaveCount(0)
    })
    test('只读成员查看 SQL 和 Field', async ({ page }) => {
      await mock(page)
      await open(page, 'datasets', ['dataset:view'])
      await expect(page.getByRole('button', { name: '新增', exact: true })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
      await page.getByRole('button', { name: '查看', exact: true }).click()
      await expect(page.getByRole('dialog').getByText(dataset.sql, { exact: true })).toBeVisible()
      await expect(
        page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }),
      ).toHaveCount(0)
      await expect(page).toHaveScreenshot(`datasets-readonly-${label}.png`)
    })
  })
}
