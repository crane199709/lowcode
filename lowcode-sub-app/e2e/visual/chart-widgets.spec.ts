import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'
const screenId = '0193a000-0000-7000-8000-000000000031'
const datasetId = '0193a000-0000-7000-8000-000000000041'
const fields = [
  { name: 'region', displayName: '区域', type: 'STRING', role: 'DIMENSION', date: false },
  { name: 'channel', displayName: '渠道', type: 'STRING', role: 'DIMENSION', date: false },
  { name: 'amount', displayName: '销售金额', type: 'NUMBER', role: 'MEASURE', date: false },
  { name: 'profit', displayName: '毛利润', type: 'NUMBER', role: 'MEASURE', date: false },
]
const lineQuery = {
  dimensions: [{ field: 'region' }],
  measures: [
    { field: 'amount', aggregation: 'SUM' },
    { field: 'profit', aggregation: 'SUM' },
  ],
  filters: [],
  sorts: [],
}
const lineData = {
  columns: [
    { id: 'd0', label: '区域', type: 'STRING' },
    { id: 'm0', label: '销售金额 · 求和', type: 'NUMBER' },
    { id: 'm1', label: '毛利润 · 求和', type: 'NUMBER' },
  ],
  rows: [
    ['华东区域', 138000, 32000],
    ['华南区域', 96000, null],
    ['西南区域', 72000, 15000],
  ],
  truncated: false,
  maxRows: 10000,
}
const draft = {
  designWidth: 1920,
  designHeight: 1080,
  backgroundColor: '#101827',
  widgets: [] as unknown[],
}
function widget(type: string, query = lineQuery, donut = false) {
  return {
    id: 'chart',
    type,
    x: 80,
    y: 80,
    width: 1600,
    height: 800,
    zIndex: 0,
    config: { datasetId, query, refreshSeconds: 0, ...(type === 'PIE' ? { donut } : {}) },
  }
}
async function mock(page: Page, widgets: unknown[] = [], data: unknown = lineData) {
  const queries: unknown[] = [],
    saves: unknown[] = []
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      let response: unknown = { ...draft, widgets }
      if (path.endsWith('/datasets'))
        response = [{ id: datasetId, name: '季度销售与渠道明细', fields }]
      else if (path.endsWith('/query')) {
        queries.push(route.request().postDataJSON())
        response = data
      } else if (path.endsWith('/published'))
        response = {
          id: screenId,
          name: '季度销售趋势与分布',
          definition: { ...draft, widgets },
          publishedAt: '2026-11-11T10:20:30+08:00',
        }
      else if (route.request().method() === 'PUT') {
        saves.push(route.request().postDataJSON())
        response = saves.at(-1)
      }
      await route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: response,
          meta: { page: { number: 1, size: 100, totalElements: 1, totalPages: 1 } },
        },
      })
    },
  )
  return { queries, saves }
}
async function choose(page: Page, name: string, option: string) {
  await page.getByRole('combobox', { name, exact: true }).press('ArrowDown')
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(page.getByRole('option', { name: option, exact: true })).not.toBeVisible()
}
async function add(page: Page, name: string) {
  await page.getByRole('button', { name: '添加组件', exact: true }).click()
  await page.getByRole('menuitem', { name, exact: true }).click()
  await expect(page.getByRole('menuitem', { name, exact: true })).not.toBeVisible()
}
test('折线图限制一个维度并保存多个度量，渲染真实SVG', async ({ page }) => {
  const { queries, saves } = await mock(page)
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await add(page, '折线图')
  await choose(page, '折线图数据集', '季度销售与渠道明细')
  await expect(
    page
      .getByRole('complementary', { name: '属性面板' })
      .getByText('需要 1 个维度和 1–16 个度量', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: '拖动字段 区域', exact: true }).click()
  await expect(page.getByRole('button', { name: '拖动字段 渠道', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '拖动字段 销售金额', exact: true }).click()
  await page.getByRole('button', { name: '拖动字段 毛利润', exact: true }).click()
  const chart = page.getByRole('img', { name: '基础折线图' })
  await expect(chart.locator('svg')).toBeVisible()
  await expect(chart).toContainText('销售金额 · 求和')
  await expect(chart).toContainText('毛利润 · 求和')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    type: 'LINE',
    config: { query: lineQuery },
  })
  expect(queries.length).toBeGreaterThan(0)
})
const stackedQuery = {
  ...lineQuery,
  dimensions: [{ field: 'region' }, { field: 'channel' }],
  measures: [{ field: 'amount', aggregation: 'SUM' }],
}
const stackedData = {
  ...lineData,
  columns: [
    { id: 'd0', label: '区域', type: 'STRING' },
    { id: 'd1', label: '渠道', type: 'STRING' },
    { id: 'm0', label: '销售金额 · 求和', type: 'NUMBER' },
  ],
  rows: [
    ['华东区域', '线下直营', 138000],
    ['华东区域', '线上商城', 42000],
    ['华南区域', '线上商城', 96000],
    ['华南区域', '线下直营', null],
    ['西南区域', '线下直营', 72000],
  ],
}
test('堆叠图限制两个维度一个度量并显示稀疏分组', async ({ page }) => {
  const { saves } = await mock(page, [], stackedData)
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await add(page, '堆叠柱状图')
  await choose(page, '堆叠柱状图数据集', '季度销售与渠道明细')
  await page.getByRole('button', { name: '拖动字段 区域', exact: true }).click()
  await page.getByRole('button', { name: '拖动字段 渠道', exact: true }).click()
  await page.getByRole('button', { name: '拖动字段 销售金额', exact: true }).click()
  await expect(page.getByRole('button', { name: '拖动字段 毛利润', exact: true })).toBeDisabled()
  const chart = page.getByRole('img', { name: '堆叠柱状图' })
  await expect(chart.locator('svg')).toBeVisible()
  await expect(chart).toContainText('线下直营')
  await expect(chart).toContainText('线上商城')
  await expect(chart).toContainText('华南区域')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    type: 'STACKED_BAR',
    config: { query: stackedQuery },
  })
})
const pieQuery = { ...lineQuery, measures: [{ field: 'amount', aggregation: 'AVG' }] }
const pieData = {
  ...lineData,
  columns: lineData.columns
    .slice(0, 2)
    .map((c) => (c.id === 'm0' ? { ...c, label: '销售金额 · 平均' } : c)),
  rows: [
    ['华东区域', 15],
    ['华南区域', 7],
    ['西南区域', 6],
  ],
}
test('饼图限制一个维度一个度量，环形开关只改变显示半径', async ({ page }) => {
  const { queries, saves } = await mock(page, [], pieData)
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await add(page, '饼图')
  await choose(page, '饼图数据集', '季度销售与渠道明细')
  await page.getByRole('button', { name: '拖动字段 区域', exact: true }).click()
  await page.getByRole('button', { name: '拖动字段 销售金额', exact: true }).click()
  await expect(page.getByRole('button', { name: '拖动字段 渠道', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '拖动字段 毛利润', exact: true })).toBeDisabled()
  await choose(page, '度量1聚合', '平均')
  const chart = page.getByRole('img', { name: '饼图' })
  await expect(chart.locator('svg')).toBeVisible()
  await expect(chart).toContainText('华东区域: 15')
  const before = await chart.locator('svg').innerHTML()
  const requests = queries.length
  await page.getByText('环形显示', { exact: true }).click()
  await expect.poll(() => chart.locator('svg').innerHTML()).not.toBe(before)
  expect(queries.length).toBe(requests)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    type: 'PIE',
    config: { donut: true, query: pieQuery },
  })
})
for (const [type, label, q, data] of [
  ['LINE', '基础折线图', lineQuery, lineData],
  ['STACKED_BAR', '堆叠柱状图', stackedQuery, stackedData],
  ['PIE', '饼图', pieQuery, pieData],
] as const) {
  for (const [name, width, height] of viewports) {
    test(`${label}播放与属性面板 ${name}`, async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      page.on('console', (message) => {
        if (['error', 'warning'].includes(message.type())) errors.push(message.text())
      })
      await page.setViewportSize({ width, height })
      await mock(page, [widget(type, q, true)], data)
      await open(page, `screens/${screenId}/play`, ['screen:view', 'screen:edit'])
      await expect(page).toHaveURL(new RegExp(`/screens/${screenId}/play$`))
      await expect(page).toHaveTitle(/lowcode/)
      await expect(page.getByRole('img', { name: label, exact: true }).locator('svg')).toBeVisible()
      await expect(page.locator('vite-error-overlay')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`chart-${type.toLowerCase()}-playback-${name}.png`)
      await page.goto(`/apps/lowcode/screens/${screenId}/edit`)
      await page.getByTestId('screen-widget').click()
      await expect(page.getByRole('heading', { name: label, exact: true })).toBeVisible()
      await expect(page.getByRole('img', { name: label, exact: true }).locator('svg')).toBeVisible()
      await page.getByRole('group', { name: '维度放置区', exact: true }).scrollIntoViewIfNeeded()
      await expect(page).toHaveScreenshot(`chart-${type.toLowerCase()}-editor-${name}.png`)
      expect(errors).toEqual([])
    })
  }
}
test('非数值度量只允许计数与去重计数', async ({ page }) => {
  const counted = { ...lineQuery, measures: [{ field: 'channel', aggregation: 'COUNT' }] }
  await mock(page, [widget('LINE', counted)])
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await page.getByTestId('screen-widget').click()
  await page.getByRole('combobox', { name: '度量1聚合', exact: true }).press('ArrowDown')
  await expect(page.getByRole('option', { name: '求和', exact: true })).toHaveAttribute(
    'aria-disabled',
    'true',
  )
  await expect(page.getByRole('option', { name: '去重计数', exact: true })).not.toHaveAttribute(
    'aria-disabled',
    'true',
  )
})

test('饼图所有值为零时不绘制虚构的等分扇区', async ({ page }) => {
  await mock(page, [widget('PIE', pieQuery)], {
    ...pieData,
    rows: [
      ['华东区域', 0],
      ['华南区域', 0],
      ['西南区域', null],
    ],
  })
  await open(page, `screens/${screenId}/play`, ['screen:view'])
  const chart = page.getByRole('img', { name: '饼图', exact: true })
  await expect(chart.locator('svg')).toBeVisible()
  await expect
    .poll(() =>
      chart.locator('svg path[d*="A"]').evaluateAll(
        (paths) =>
          paths.filter((path) => {
            const bounds = (path as SVGGraphicsElement).getBBox()
            return bounds.width > 50 && bounds.height > 50
          }).length,
      ),
    )
    .toBe(0)
})

test('堆叠图的 NULL 与缺失组合不会显示为零', async ({ page }) => {
  await mock(page, [widget('STACKED_BAR', stackedQuery)], stackedData)
  await open(page, `screens/${screenId}/play`, ['screen:view'])
  const chart = page.getByRole('img', { name: '堆叠柱状图', exact: true })
  await expect(chart.locator('svg')).toBeVisible()
  for (const category of ['华南区域', '西南区域']) {
    const label = await chart.locator('svg text').filter({ hasText: category }).boundingBox()
    const area = await chart.boundingBox()
    await page.mouse.move(label!.x + label!.width / 2, area!.y + area!.height / 2)
    await expect(chart).toContainText(category === '华南区域' ? /线下直营\s*-/ : /线上商城\s*-/)
  }
})
