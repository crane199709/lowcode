import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'
const screenId = '0193a000-0000-7000-8000-000000000014'
const datasetId = '0193a000-0000-7000-8000-000000000041'
const aggregate = {
  dimensions: [{ field: 'region' }],
  measures: [{ field: 'amount', aggregation: 'SUM' }],
  filters: [],
  sorts: [],
}
const detail = {
  dimensions: [],
  measures: [],
  filters: [],
  sorts: [],
  mode: 'DETAIL',
  fields: ['region', 'amount'],
}
function widget(type: string) {
  return {
    id: 'table',
    type,
    x: 80,
    y: 80,
    width: 1760,
    height: 900,
    zIndex: 0,
    config: {
      datasetId,
      query:
        type === 'DETAIL_TABLE'
          ? detail
          : type === 'INDICATOR'
            ? { ...aggregate, dimensions: [] }
            : aggregate,
      refreshSeconds: 0,
      ...(type === 'INDICATOR'
        ? { title: '本季度含税销售总额', unit: '万元', numberFormat: 'DECIMAL_2' }
        : {}),
    },
  }
}
const draft = {
  designWidth: 1920,
  designHeight: 1080,
  backgroundColor: '#101827',
  widgets: [] as unknown[],
}
const result = {
  columns: [
    { id: 'f0', label: '区域', type: 'STRING' },
    { id: 'f1', label: '销售金额', type: 'NUMBER' },
  ],
  rows: [
    ['华东区域', 138000.5],
    ['华南区域（含港澳业务及重点客户）', 96000],
    [null, null],
  ],
  truncated: false,
  maxRows: 10000,
}
async function mock(page: Page, definition = draft) {
  const queries: unknown[] = [],
    saves: unknown[] = []
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      let data: unknown = definition
      if (path.endsWith('/datasets'))
        data = [
          {
            id: datasetId,
            name: '季度销售明细（区域与含税销售金额）',
            fields: [
              {
                name: 'region',
                displayName: '区域',
                type: 'STRING',
                role: 'DIMENSION',
                date: false,
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
        const body = route.request().postDataJSON()
        queries.push(body)
        const indicator =
          body?.type === 'INDICATOR' ||
          definition.widgets.some((w) => (w as { type: string }).type === 'INDICATOR')
        const summary =
          body?.type === 'SUMMARY_TABLE' ||
          definition.widgets.some((w) => (w as { type: string }).type === 'SUMMARY_TABLE')
        data = indicator
          ? {
              ...result,
              columns: [{ id: 'm0', label: '销售金额 · 求和', type: 'NUMBER' }],
              rows: [[306000.5]],
            }
          : summary
            ? {
                ...result,
                columns: [
                  { id: 'd0', label: '区域', type: 'STRING' },
                  {
                    id: 'm0',
                    label: `销售金额 · ${body?.config.query.measures[0]?.aggregation === 'AVG' ? '平均' : '求和'}`,
                    type: 'NUMBER',
                  },
                ],
              }
            : result
      } else if (path.endsWith('/published'))
        data = {
          id: screenId,
          name: '区域销售经营大屏',
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
async function choose(page: Page, name: string, option: string) {
  const select = page.getByRole('combobox', { name, exact: true })
  await select.scrollIntoViewIfNeeded()
  await select.press('ArrowDown')
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(page.getByRole('option', { name: option, exact: true })).not.toBeVisible()
}
async function add(page: Page, name: string) {
  await page.getByRole('button', { name: '添加组件', exact: true }).click()
  await page.getByRole('menuitem', { name, exact: true }).click()
  await expect(page.getByRole('menuitem', { name, exact: true })).not.toBeVisible()
  await choose(page, `${name}数据集`, '季度销售明细（区域与含税销售金额）')
}
test('明细表选择字段保存原始模式，移除字段重置排序', async ({ page }) => {
  const { saves } = await mock(page)
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await add(page, '明细表')
  await page.getByRole('button', { name: '拖动字段 区域', exact: true }).click()
  await page.getByRole('button', { name: '拖动字段 销售金额', exact: true }).click()
  await expect(page.getByRole('region', { name: '明细表', exact: true })).toContainText('华东区域')
  await choose(page, '排序字段', '销售金额')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    type: 'DETAIL_TABLE',
    config: { query: { ...detail, sorts: [{ column: 1, direction: 'DESC' }] } },
  })
  await page
    .getByRole('group', { name: '明细字段放置区', exact: true })
    .getByRole('button', { name: '关闭此标签' })
    .first()
    .click()
  await expect(page.getByRole('combobox', { name: '排序顺序', exact: true })).toHaveCount(0)
})
test('汇总表配置分组聚合，指标卡限制一个度量并保存显示格式', async ({ page }) => {
  const { saves } = await mock(page)
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  await add(page, '汇总表')
  await page.getByRole('button', { name: '拖动字段 区域', exact: true }).click()
  await page.getByRole('button', { name: '拖动字段 销售金额', exact: true }).click()
  await expect(page.getByRole('region', { name: '汇总表', exact: true })).toContainText('华东区域')
  await choose(page, '度量1聚合', '平均')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect((saves[0] as typeof draft).widgets[0]).toMatchObject({
    type: 'SUMMARY_TABLE',
    config: { query: { measures: [{ field: 'amount', aggregation: 'AVG' }] } },
  })
  await add(page, '指标卡')
  await expect(page.getByRole('group', { name: '维度放置区', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '拖动字段 销售金额', exact: true }).click()
  await expect(page.getByRole('button', { name: '拖动字段 区域', exact: true })).toBeDisabled()
  await expect(
    page.getByRole('group', { name: '度量放置区', exact: true }).locator('.measure'),
  ).toHaveCount(1)
  await page.getByRole('textbox', { name: '指标标题', exact: true }).fill('含税销售总额')
  await page.getByRole('textbox', { name: '指标单位', exact: true }).fill('万元')
  await choose(page, '数字格式', '两位小数')
  await expect(page.getByRole('region', { name: '指标卡', exact: true })).toContainText(
    '306,000.50',
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => saves.length).toBe(2)
  expect((saves[1] as typeof draft).widgets[1]).toMatchObject({
    type: 'INDICATOR',
    config: {
      title: '含税销售总额',
      unit: '万元',
      numberFormat: 'DECIMAL_2',
      query: { dimensions: [], measures: [{ field: 'amount', aggregation: 'SUM' }] },
    },
  })
})
for (const [name, width, height] of viewports) {
  for (const [type, label] of [
    ['DETAIL_TABLE', '明细表'],
    ['SUMMARY_TABLE', '汇总表'],
    ['INDICATOR', '指标卡'],
  ] as const) {
    test(`${label}播放与属性 ${name}`, async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (e) => errors.push(e.message))
      page.on('console', (m) => {
        if (m.type() === 'error' || m.type() === 'warning') errors.push(m.text())
      })
      await page.setViewportSize({ width, height })
      await mock(page, { ...draft, widgets: [widget(type)] })
      await open(page, `screens/${screenId}/play`, ['screen:view', 'screen:edit'])
      await expect(page).toHaveURL(new RegExp(`/screens/${screenId}/play$`))
      await expect(page).toHaveTitle(/lowcode/)
      await expect(page.getByRole('region', { name: label, exact: true })).toContainText(
        type === 'INDICATOR' ? '306,000.50' : '华东区域',
      )
      await expect(page.locator('vite-error-overlay')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`${type.toLowerCase()}-playback-${name}.png`)
      await page.goto(`/apps/lowcode/screens/${screenId}/edit`)
      await page.getByTestId('screen-widget').click()
      await expect(
        page.getByRole('combobox', { name: `${label}数据集`, exact: true }),
      ).toBeVisible()
      if (type === 'SUMMARY_TABLE')
        await page.getByRole('group', { name: '度量放置区', exact: true }).scrollIntoViewIfNeeded()
      if (type === 'DETAIL_TABLE')
        await page
          .getByRole('group', { name: '明细字段放置区', exact: true })
          .scrollIntoViewIfNeeded()
      if (type === 'INDICATOR')
        await page.getByRole('combobox', { name: '数字格式', exact: true }).scrollIntoViewIfNeeded()
      await expect(page).toHaveScreenshot(`${type.toLowerCase()}-editor-${name}.png`)
      expect(errors).toEqual([])
    })
  }
}
for (const [name, width, height] of viewports) {
  test(`明细上万行分页读取后段且明确提示上限 ${name}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await mock(page, { ...draft, widgets: [widget('DETAIL_TABLE')] })
    await page.route('**/published/widgets/table/query', (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: {
            ...result,
            rows: Array.from({ length: 10000 }, (_, i) => [`业务区域${i + 1}`, i]),
            truncated: true,
          },
        },
      }),
    )
    await open(page, `screens/${screenId}/play`, ['screen:view'])
    const table = page.getByRole('region', { name: '明细表', exact: true })
    await expect(table).toContainText('仅显示前 10000 行')
    await expect(table.locator('.el-table__body tr')).toHaveCount(50)
    await table.getByRole('button', { name: /^(下一页|Go to next page)$/ }).click()
    await expect(table).toContainText('业务区域51')
    await expect(table.locator('.el-table__body tr')).toHaveCount(50)
    await expect(page).toHaveScreenshot(`detail-limit-${name}.png`)
  })
}

test('指标格式调整不重新查询，空聚合显示空值', async ({ page }) => {
  const { queries } = await mock(page, { ...draft, widgets: [widget('INDICATOR')] })
  await open(page, `screens/${screenId}/edit`, ['screen:edit'])
  const card = page.getByRole('region', { name: '指标卡', exact: true })
  await expect(card).toContainText('306,000.50')
  await page.getByTestId('screen-widget').click()
  await choose(page, '数字格式', '整数')
  await expect(card).toContainText('306,001')
  await page.getByRole('textbox', { name: '指标单位', exact: true }).fill('元')
  await expect(card).toContainText('元')
  expect(queries.length).toBe(1)
  await page.route(`**/screens/${screenId}/draft/query`, (route) =>
    route.fulfill({ json: { code: 'ok', httpStatus: 200, data: { ...result, rows: [[null]] } } }),
  )
  await choose(page, '度量1聚合', '平均')
  await expect(card).toContainText('—')
})

test('表格播放刷新与卸载停止沿用图表生命周期', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-11-11T10:00:00Z') })
  await page.clock.pauseAt(new Date('2026-11-11T10:00:01Z'))
  const table = widget('DETAIL_TABLE')
  table.config.refreshSeconds = 10
  const { queries } = await mock(page, { ...draft, widgets: [table] })
  await open(page, `screens/${screenId}/play`, ['screen:view'])
  await page.clock.runFor(200)
  await expect(page.getByRole('region', { name: '明细表', exact: true })).toContainText('华东区域')
  await expect(page.getByRole('region', { name: '明细表', exact: true })).toHaveAttribute(
    'aria-busy',
    'false',
  )
  expect(queries.length).toBe(1)
  await page.clock.fastForward(10000)
  await expect.poll(() => queries.length).toBe(2)
  await page.getByRole('button', { name: '返回列表', exact: true }).click()
  await page.clock.fastForward(30000)
  expect(queries.length).toBe(2)
})
