import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'

const fields = [
  { name: 'region', displayName: '区域', type: 'STRING', role: 'DIMENSION', date: false },
  { name: 'amount', displayName: '金额', type: 'NUMBER', role: 'MEASURE', date: false },
]
const dataset = {
  id: '0193a000-0000-7000-8000-000000000071',
  name: '各区域实时销售额（API 接口）',
  dataSourceId: '0193a000-0000-7000-8000-000000000072',
  dataSourceName: '集团销售统计接口（每分钟更新）',
  sql: '',
  jsonPath: '$.data.items',
  fields,
  createdAt: '2026-11-11T09:00:00+08:00',
  updatedAt: '2026-11-11T10:20:30+08:00',
}
async function mock(page: Page) {
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/datasets'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      const method = route.request().method()
      if (path.endsWith('/sources')) {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: [{ id: dataset.dataSourceId, name: dataset.dataSourceName, type: 'API' }],
            meta: { page: { number: 1, size: 100, totalElements: 1, totalPages: 1 } },
          },
        })
      } else if (path.endsWith('/preview')) {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: {
              fields,
              rows: [
                ['华东', 12500.5],
                [null, 0],
              ],
              truncated: true,
              maxRows: 10000,
            },
          },
        })
      } else if (method === 'GET' && path === '/lowcode/datasets') {
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: [dataset],
            meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
          },
        })
      } else {
        await route.fulfill({
          json: { code: 'ok', httpStatus: method === 'POST' ? 201 : 200, data: dataset },
        })
      }
    },
  )
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('新增 API 数据集预览并编辑字段后保存', async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      page.on('console', (message) => {
        if (message.type() === 'error') errors.push(message.text())
      })
      await mock(page)
      await open(page, 'datasets', ['dataset:view', 'dataset:manage'])
      await page.getByRole('button', { name: '新增', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('API 销售明细')
      await drawer.getByRole('combobox').click()
      await page.getByRole('option', { name: dataset.dataSourceName }).click()
      await expect(drawer.getByRole('textbox', { name: 'JSON 路径', exact: true })).toBeVisible()
      await expect(drawer.getByText('请使用只读账号')).toHaveCount(0)
      await drawer.getByRole('textbox', { name: 'JSON 路径', exact: true }).fill(dataset.jsonPath)
      await expect(page).toHaveScreenshot(`datasets-create-api-${label}.png`)
      const preview = page.waitForRequest((request) =>
        new URL(request.url()).pathname.endsWith('/preview'),
      )
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      expect((await preview).postDataJSON()).toMatchObject({ jsonPath: '$.data.items', sql: '' })
      await expect(drawer.getByText('华东', { exact: true })).toBeVisible()
      await expect(drawer.getByText('结果超过 10000 行，已截断')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-preview-api-${label}.png`)
      await drawer.getByLabel('amount 显示名').fill('成交金额')
      const saved = page.waitForRequest(
        (request) =>
          request.method() === 'POST' && new URL(request.url()).pathname === '/lowcode/datasets',
      )
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      expect((await saved).postDataJSON().fields[1]).toMatchObject({
        name: 'amount',
        displayName: '成交金额',
        role: 'MEASURE',
      })
      await expect(drawer).not.toBeVisible()
      expect(errors).toEqual([])
    })
    test('编辑 JSON 路径直接保存保留同名字段配置', async ({ page }) => {
      await mock(page)
      await open(page, 'datasets', ['dataset:view', 'dataset:manage'])
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByLabel('amount 显示名').fill('销售金额')
      await drawer.getByLabel('amount 日期').check()
      await drawer
        .getByRole('textbox', { name: 'JSON 路径', exact: true })
        .fill("$['data']['items']")
      const saved = page.waitForRequest((request) => request.method() === 'PUT')
      await drawer.getByRole('button', { name: '保存', exact: true }).click()
      const body = (await saved).postDataJSON()
      expect(body.jsonPath).toBe("$['data']['items']")
      expect(body.fields[1]).toMatchObject({ name: 'amount', displayName: '销售金额', date: true })
      await expect(drawer).not.toBeVisible()
    })
    test('只读成员查看 API 路径与字段', async ({ page }) => {
      await mock(page)
      await open(page, 'datasets', ['dataset:view'])
      await page.getByRole('button', { name: '查看', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await expect(drawer.getByText(dataset.jsonPath, { exact: true })).toBeVisible()
      await expect(drawer.getByText('JSON 路径', { exact: true })).toBeVisible()
      await expect(drawer.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
      await expect(page).toHaveScreenshot(`datasets-readonly-api-${label}.png`)
    })
    test('API 路径错误可修改重试并显示空数组', async ({ page }) => {
      await mock(page)
      await page.route(
        (url) => url.pathname.endsWith('/preview'),
        async (route) => {
          const valid = route.request().postDataJSON().jsonPath === '$.empty'
          await route.fulfill(
            valid
              ? {
                  json: {
                    code: 'ok',
                    httpStatus: 200,
                    data: { fields: [], rows: [], truncated: false, maxRows: 10000 },
                  },
                }
              : {
                  status: 400,
                  json: {
                    code: 'dataset.jsonPathInvalid',
                    httpStatus: 400,
                    userTip: 'JSON 路径无效或未找到结果，请使用以 $ 开头的路径',
                    traceId: 'api-path-error',
                  },
                },
          )
        },
      )
      await open(page, 'datasets', ['dataset:view', 'dataset:manage'])
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      await expect(
        drawer.getByText('JSON 路径无效或未找到结果，请使用以 $ 开头的路径'),
      ).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-error-api-${label}.png`)
      await drawer.getByRole('textbox', { name: 'JSON 路径', exact: true }).fill('$.empty')
      await expect(
        drawer.getByText('JSON 路径无效或未找到结果，请使用以 $ 开头的路径'),
      ).toHaveCount(0)
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      await expect(drawer.getByText('查询未返回数据')).toBeVisible()
      await expect(page).toHaveScreenshot(`datasets-empty-api-${label}.png`)
    })
    test('JSON 路径修改后忽略旧预览结果', async ({ page }) => {
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
        (url) => url.pathname.endsWith('/preview'),
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
      await open(page, 'datasets', ['dataset:view', 'dataset:manage'])
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('button', { name: '预览', exact: true }).click()
      await pending
      await drawer.getByRole('textbox', { name: 'JSON 路径', exact: true }).fill('$.newItems')
      release()
      await expect(drawer.getByRole('button', { name: '预览', exact: true })).toBeEnabled()
      await expect(drawer.getByText('过期结果', { exact: true })).toHaveCount(0)
    })
  })
}
