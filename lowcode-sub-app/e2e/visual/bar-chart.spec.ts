import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'
const screenId = '0193a000-0000-7000-8000-000000000031'
const datasetId = '0193a000-0000-7000-8000-000000000041'
const query = {
  dimensions: [{ field: 'region' }],
  measures: [{ field: 'amount', aggregation: 'SUM' }],
  filters: [],
  sorts: [],
}
const widget = {
  id: 'bar',
  type: 'BAR',
  x: 80,
  y: 80,
  width: 1600,
  height: 800,
  zIndex: 0,
  config: { datasetId, query, refreshSeconds: 10 },
}
const draft = {
  designWidth: 1920,
  designHeight: 1080,
  backgroundColor: '#101827',
  widgets: [] as unknown[],
}
const result = {
  columns: [
    { id: 'd0', label: '区域', type: 'STRING' },
    { id: 'm0', label: '销售金额 · 求和', type: 'NUMBER' },
  ],
  rows: [
    ['华东区域', 138000],
    ['华南区域', 96000],
    ['西南区域', 72000],
  ],
  truncated: false,
  maxRows: 10000,
}
async function mock(page: Page, definition = draft, date = false) {
  const queries: unknown[] = []
  const saves: unknown[] = []
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      let data: unknown = definition
      if (path === '/lowcode/screens') data = []
      else if (path.endsWith('/datasets'))
        data = [
          {
            id: datasetId,
            name: date
              ? '日期销售明细（订单发生日期与含税销售金额）'
              : '季度销售明细（区域与含税销售金额）',
            fields: [
              {
                name: 'region',
                displayName: date ? '订单发生日期' : '区域',
                type: 'STRING',
                role: 'DIMENSION',
                date,
              },
              {
                name: 'amount',
                displayName: '销售金额',
                type: 'NUMBER',
                role: 'MEASURE',
                date: false,
              },
            ],
          },
        ]
      else if (path.endsWith('/query')) {
        queries.push(route.request().postDataJSON())
        data = date
          ? {
              ...result,
              columns: [{ id: 'd0', label: '订单发生日期', type: 'STRING' }, result.columns[1]],
              rows: [
                ['2024-02', 138000],
                ['2024-03', 96000],
                ['2025-02', 72000],
              ],
            }
          : result
      } else if (path.endsWith('/published'))
        data = {
          id: screenId,
          name: '区域季度销售大屏',
          definition,
          publishedAt: '2026-11-11T10:20:30+08:00',
        }
      else if (route.request().method() === 'PUT') {
        saves.push(route.request().postDataJSON())
        data = saves.at(-1)
      }
      await route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data,
          meta: { page: { number: 1, size: 100, totalElements: 1, totalPages: 1 } },
        },
      })
    },
  )
  return { queries, saves }
}
test('编辑者拖入维度与度量得到实际柱状图并保存配置', async ({ page }) => {
  const { queries, saves } = await mock(page)
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await page.getByRole('button', { name: '添加组件', exact: true }).click()
  await page.getByRole('menuitem', { name: '柱状图', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '柱状图', exact: true })).not.toBeVisible()
  await page.getByRole('combobox', { name: '柱状图数据集', exact: true }).press('ArrowDown')
  await page
    .getByRole('option', {
      name: '季度销售明细（区域与含税销售金额）',
      exact: true,
    })
    .click()
  await expect(
    page.getByRole('option', {
      name: '季度销售明细（区域与含税销售金额）',
      exact: true,
    }),
  ).not.toBeVisible()
  await page.getByRole('group', { name: '度量放置区', exact: true }).scrollIntoViewIfNeeded()
  await page
    .getByRole('button', { name: '拖动字段 区域', exact: true })
    .dragTo(page.getByRole('group', { name: '维度放置区', exact: true }))
  await expect(page.getByRole('group', { name: '维度放置区', exact: true })).toContainText('区域')
  await page
    .getByRole('button', { name: '拖动字段 销售金额', exact: true })
    .dragTo(page.getByRole('group', { name: '度量放置区', exact: true }))
  await expect(page.getByRole('img', { name: '基础柱状图' }).locator('svg')).toBeVisible()
  await expect(page.getByRole('img', { name: '基础柱状图' })).toContainText('华东区域')
  await expect.poll(() => queries.length).toBe(1)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    type: 'BAR',
    config: { datasetId, query, refreshSeconds: 0 },
  })
})

async function choose(page: Page, name: string, option: string) {
  await page.getByRole('combobox', { name, exact: true }).press('ArrowDown')
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(page.getByRole('option', { name: option, exact: true })).not.toBeVisible()
}
test('聚合过滤排序Top N与刷新配置可保存，字段变更清除排序', async ({ page }) => {
  const { queries, saves } = await mock(page, { ...draft, widgets: [widget] })
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await page.getByTestId('screen-widget').click()
  await choose(page, '度量1聚合', '平均')
  await choose(page, '排序字段', '销售金额 · 平均')
  await choose(page, '排序顺序', '从小到大')
  await page.getByText('限制条数（Top N）', { exact: true }).click()
  await page.getByRole('spinbutton', { name: 'Top N', exact: true }).fill('2')
  await page.getByRole('button', { name: '添加过滤', exact: true }).click()
  await choose(page, '过滤1运算符', '包含于')
  await page.getByRole('textbox', { name: '过滤1取值', exact: true }).fill('华东区域,华南区域')
  await page.getByRole('textbox', { name: '过滤1取值', exact: true }).press('Tab')
  await page.getByRole('spinbutton', { name: '刷新间隔（秒）', exact: true }).fill('1')
  await page.getByRole('spinbutton', { name: '刷新间隔（秒）', exact: true }).press('Tab')
  await expect(page.getByRole('spinbutton', { name: '刷新间隔（秒）', exact: true })).toHaveValue(
    '10',
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    config: {
      query: {
        measures: [{ field: 'amount', aggregation: 'AVG' }],
        filters: [{ field: 'region', operator: 'IN', values: ['华东区域', '华南区域'] }],
        sorts: [{ column: 1, direction: 'ASC' }],
        topN: 2,
      },
      refreshSeconds: 10,
    },
  })
  await page
    .getByRole('group', { name: '维度放置区', exact: true })
    .getByRole('button', { name: '关闭此标签' })
    .click()
  await expect(page.getByRole('combobox', { name: '排序顺序', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  expect((saves.at(-1) as typeof draft).widgets[0]).toMatchObject({
    config: { query: { sorts: [] } },
  })
})
test('Playback按10秒刷新，返回列表后停止取数', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-11-11T10:00:00Z') })
  await page.clock.pauseAt(new Date('2026-11-11T10:00:01Z'))
  const { queries } = await mock(page, { ...draft, widgets: [widget] })
  await open(page, `screens/${screenId}/play`, ['screen:view'])
  const chart = page.getByRole('img', { name: '基础柱状图' })
  await expect(chart).toBeVisible()
  await page.clock.runFor(200)
  await expect(chart.locator('svg')).toBeVisible()
  await expect(chart).toHaveAttribute('aria-busy', 'false')
  expect(queries.length).toBe(1)
  await page.clock.fastForward(9999)
  expect(queries.length).toBe(1)
  await page.clock.fastForward(1)
  await expect.poll(() => queries.length).toBe(2)
  await page.getByRole('button', { name: '返回列表', exact: true }).click()
  await expect(chart).toHaveCount(0)
  await page.clock.fastForward(30000)
  expect(queries.length).toBe(2)
})
test('关闭刷新仅取数一次', async ({ page }) => {
  await page.clock.install()
  const { queries } = await mock(page, {
    ...draft,
    widgets: [{ ...widget, config: { ...widget.config, refreshSeconds: 0 } }],
  })
  await open(page, `screens/${screenId}/play`, ['screen:view'])
  await expect(page.getByRole('img', { name: '基础柱状图' }).locator('svg')).toBeVisible()
  await page.clock.fastForward(30000)
  expect(queries.length).toBe(1)
})
test('较早查询响应不覆盖新配置，布局调整不触发查询', async ({ page }) => {
  await mock(page, { ...draft, widgets: [widget] })
  let count = 0
  let first: import('@playwright/test').Route | undefined
  await page.route(`**/screens/${screenId}/draft/query`, async (route) => {
    count++
    if (count === 1) {
      first = route
      return
    }
    await route.fulfill({
      json: { code: 'ok', httpStatus: 200, data: { ...result, rows: [['最新区域', 15]] } },
    })
  })
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await expect.poll(() => count).toBe(1)
  await page.getByTestId('screen-widget').click()
  await choose(page, '度量1聚合', '平均')
  await expect(page.getByRole('img', { name: '基础柱状图' })).toContainText('最新区域')
  await first!.fulfill({
    json: { code: 'ok', httpStatus: 200, data: { ...result, rows: [['过期区域', 999]] } },
  })
  await expect(page.getByRole('img', { name: '基础柱状图' })).not.toContainText('过期区域')
  await page.getByRole('spinbutton', { name: '组件宽度', exact: true }).fill('1400')
  await page.getByRole('spinbutton', { name: '组件宽度', exact: true }).press('Tab')
  await page.waitForTimeout(250)
  expect(count).toBe(2)
})
for (const [name, width, height] of viewports) {
  test(`柱状图播放与编辑 ${name}`, async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    page.on('console', (m) => {
      if (m.type() === 'error' || m.type() === 'warning') errors.push(m.text())
    })
    await page.setViewportSize({ width, height })
    await mock(page, { ...draft, widgets: [widget] })
    await open(page, `screens/${screenId}/play`, ['screen:view', 'screen:edit'])
    await expect(page).toHaveURL(new RegExp(`/screens/${screenId}/play$`))
    await expect(page).toHaveTitle(/lowcode/)
    await expect(page.getByRole('img', { name: '基础柱状图' }).locator('svg')).toBeVisible()
    await expect(page).toHaveScreenshot(`bar-playback-${name}.png`)
    await page.goto(`/apps/lowcode/screens/${screenId}/edit`)
    await page.getByTestId('screen-widget').click()
    await expect(page.getByRole('img', { name: '基础柱状图' }).locator('svg')).toBeVisible()
    await page.getByRole('group', { name: '维度放置区', exact: true }).scrollIntoViewIfNeeded()
    await expect(page).toHaveScreenshot(`bar-editor-${name}.png`)
    expect(errors).toEqual([])
  })
  test(`字段不存在仅影响当前组件 ${name}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    const text = {
      id: 'title',
      type: 'TEXT',
      x: 80,
      y: 920,
      width: 1600,
      height: 100,
      zIndex: 1,
      config: {
        text: '其他组件正常展示',
        fontSize: 48,
        color: '#ffffff',
        backgroundColor: '#18243a',
        fontWeight: 'normal',
        textAlign: 'left',
      },
    }
    await mock(page, { ...draft, widgets: [widget, text] })
    await page.route('**/published/widgets/bar/query', (route) =>
      route.fulfill({
        status: 422,
        json: {
          code: 'dataset.fieldMissing',
          httpStatus: 422,
          userTip: '字段不存在，请重新配置组件',
          traceId: 'visual-bar',
        },
      }),
    )
    await open(page, `screens/${screenId}/play`, ['screen:view'])
    await expect(page.getByRole('img', { name: '基础柱状图' })).toContainText(
      '字段不存在，请重新配置组件',
    )
    await expect(page.getByText('其他组件正常展示')).toBeVisible()
    await expect(page).toHaveScreenshot(`bar-field-missing-${name}.png`)
  })
}

for (const [name, width, height] of viewports) {
  test(`日期维度按日月年分组并保存 ${name}`, async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (message.type() === 'error' || message.type() === 'warning') errors.push(message.text())
    })
    await page.setViewportSize({ width, height })
    const state = await mock(page, { ...draft, widgets: [widget] }, true)
    await open(page, `screens/${screenId}/edit`, ['screen:edit'])
    await expect(page).toHaveURL(new RegExp(`/screens/${screenId}/edit$`))
    await expect(page).toHaveTitle(/lowcode/)
    await expect(page.getByRole('region', { name: '大屏编辑器', exact: true })).toBeVisible()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await page.getByTestId('screen-widget').click()
    const grouping = page.getByRole('combobox', { name: '维度1日期分组', exact: true })
    await grouping.press('ArrowDown')
    await page.getByRole('option', { name: '按月', exact: true }).click()
    await expect(page.getByRole('group', { name: '维度放置区', exact: true })).toContainText('按月')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect.poll(() => JSON.stringify(state.saves)).toContain('"dateGrouping":"MONTH"')
    await expect(page).toHaveScreenshot(`date-grouping-${name}.png`)
    await grouping.press('ArrowDown')
    await page.getByRole('option', { name: '按年', exact: true }).click()
    await expect.poll(() => JSON.stringify(state.queries.at(-1))).toContain('"dateGrouping":"YEAR"')
    await grouping.press('ArrowDown')
    await page.getByRole('option', { name: '按日', exact: true }).click()
    await expect.poll(() => JSON.stringify(state.queries.at(-1))).toContain('"dateGrouping":"DAY"')
    expect(errors).toEqual([])
  })
}
