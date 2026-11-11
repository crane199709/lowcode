import { expect, test } from '@playwright/test'
import { open, viewports } from './session'

const item = {
  id: '0193a000-0000-7000-8000-000000000001',
  name: '华东经营分析（季度销售与渠道汇总）',
  type: 'POSTGRESQL',
  host: 'reporting.example.com',
  port: 5432,
  database: 'sales',
  username: 'reader',
  dataSourceName: '经营分析源',
  fields: [],
  updatedAt: '2026-11-11T10:30:00+08:00',
}
for (const [label, width, height] of viewports) {
  test(`数据集引用包含隐藏范围提示 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await page.route(
      (url) => url.pathname.startsWith('/lowcode/datasets'),
      (route) =>
        route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: new URL(route.request().url()).pathname.endsWith('/references')
              ? {
                  references: [
                    { id: 'screen', name: '全国经营驾驶舱（管理层季度业绩与渠道分析）' },
                  ],
                  hiddenReferences: true,
                }
              : [item],
            meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
          },
        }),
    )
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await open(page, 'datasets', ['dataset:view', 'dataset:manage'])
    await page.getByRole('button', { name: '删除', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '删除数据集' })
    await expect(dialog.getByText('全国经营驾驶舱（管理层季度业绩与渠道分析）')).toBeVisible()
    await expect(
      dialog.getByText('其他无权查看的大屏也在引用此数据集，请联系大屏设计者处理。'),
    ).toBeVisible()
    await expect(dialog.getByRole('button', { name: '删除', exact: true })).toBeDisabled()
    expect(errors).toEqual([])
    await expect(page).toHaveScreenshot(`dataset-delete-referenced-${label}.png`)
  })
  test(`确认期间新增引用仍在原弹窗阻止删除 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    let deletes = 0
    await page.route(
      (url) => url.pathname.startsWith('/lowcode/datasets'),
      (route) => {
        const path = new URL(route.request().url()).pathname
        if (route.request().method() === 'DELETE') {
          deletes++
          return route.fulfill({
            status: 409,
            json: {
              code: 'dataset.referenced',
              httpStatus: 409,
              userTip: '数据集正在被以下大屏引用，无法删除：新建区域经营大屏',
              traceId: 'visual-reference',
            },
          })
        }
        return route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: path.endsWith('/references')
              ? {
                  references: deletes ? [{ id: 'new', name: '新建区域经营大屏' }] : [],
                  hiddenReferences: false,
                }
              : [item],
            meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
          },
        })
      },
    )
    await open(page, 'datasets', ['dataset:view', 'dataset:manage'])
    await page.getByRole('button', { name: '删除', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '删除数据集' })
    await expect(dialog.getByText('当前没有引用，删除后将无法在列表中查看。')).toBeVisible()
    await dialog.getByRole('button', { name: '删除', exact: true }).click()
    await expect(dialog.getByText('新建区域经营大屏', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: '删除', exact: true })).toBeDisabled()
    expect(deletes).toBe(1)
    await expect(page).toHaveScreenshot(`dataset-delete-new-reference-${label}.png`)
  })
  test(`无引用资源取消和删除 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    let deleted = false
    await page.route('**/lowcode/data-sources**', (route) => {
      if (route.request().method() === 'DELETE') {
        deleted = true
        return route.fulfill({ json: { code: 'ok', httpStatus: 200 } })
      }
      return route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: new URL(route.request().url()).pathname.endsWith('/references')
            ? { references: [], hiddenReferences: false }
            : deleted
              ? []
              : [item],
          meta: {
            page: {
              number: 1,
              size: 20,
              totalElements: deleted ? 0 : 1,
              totalPages: deleted ? 0 : 1,
            },
          },
        },
      })
    })
    await open(page, 'datasources', ['datasource:view', 'datasource:manage'])
    await page.getByRole('button', { name: '删除', exact: true }).click()
    let dialog = page.getByRole('dialog', { name: '删除数据源' })
    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).not.toBeVisible()
    expect(deleted).toBe(false)
    await page.getByRole('button', { name: '删除', exact: true }).click()
    dialog = page.getByRole('dialog', { name: '删除数据源' })
    await expect(dialog.getByRole('button', { name: '删除', exact: true })).toBeEnabled()
    await dialog.getByRole('button', { name: '删除', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await expect(page.getByText('暂无数据源')).toBeVisible()
    expect(deleted).toBe(true)
  })
  test(`数据源删除确认展示引用 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    let deletes = 0
    await page.route('**/lowcode/data-sources**', (route) => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/references'))
        return route.fulfill({
          json: {
            code: 'ok',
            httpStatus: 200,
            data: {
              references: [{ id: 'dataset', name: '全国区域季度销售与渠道分布（含历史同期数据）' }],
              hiddenReferences: false,
            },
          },
        })
      if (route.request().method() === 'DELETE') deletes++
      return route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: [item],
          meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
        },
      })
    })
    await open(page, 'datasources', ['datasource:view', 'datasource:manage'])
    await page.getByRole('button', { name: '删除', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '删除数据源' })
    await expect(dialog.getByText('全国区域季度销售与渠道分布（含历史同期数据）')).toBeVisible()
    await expect(dialog.getByRole('button', { name: '删除', exact: true })).toBeDisabled()
    expect(deletes).toBe(0)
    await expect(page).toHaveScreenshot(`datasource-delete-referenced-${label}.png`)
  })
}
