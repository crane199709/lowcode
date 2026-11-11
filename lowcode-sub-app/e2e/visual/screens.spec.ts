import { expect, test, type Page } from '@playwright/test'
import { open } from './session'

const initial = {
  id: '0193a000-0000-7000-8000-000000000021',
  name: '华东区域经营分析大屏（季度销售与目标完成情况）',
  draft: { designWidth: 1920, designHeight: 1080, backgroundColor: '#101827', widgets: [] },
  designWidth: 1920,
  designHeight: 1080,
  publishedAt: null,
  updatedAt: '2026-11-11T10:20:30+08:00',
}
const permissions = ['screen:view', 'screen:edit']
async function mock(page: Page, items: unknown[] = [initial]) {
  let screen = structuredClone(initial)
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      const method = route.request().method()
      if (method === 'GET' && path === '/lowcode/screens') {
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
        if (method === 'PUT' && path.endsWith('/draft'))
          screen.draft = route.request().postDataJSON()
        if (method === 'POST' && path === '/lowcode/screens')
          screen = { ...screen, ...route.request().postDataJSON() }
        await route.fulfill({
          json: {
            code: 'ok',
            httpStatus: method === 'POST' ? 201 : 200,
            data: method === 'DELETE' ? null : path.endsWith('/draft') ? screen.draft : screen,
          },
        })
      }
    },
  )
}

test('设计者能添加文本卡、拖拽缩放并撤销重做后保存草稿', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await mock(page)
  await open(page, 'screens', permissions)
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByRole('button', { name: '添加文本卡', exact: true }).click()
  await expect(page.getByLabel('文字内容')).toHaveValue('文本卡')
  await page.getByLabel('文字内容').fill('华东季度经营分析')
  await page.getByLabel('文字内容').blur()
  const widget = page.getByTestId('screen-widget').first()
  const box = (await widget.boundingBox())!
  await page.mouse.move(box.x + 20, box.y + 20)
  await page.mouse.down()
  await page.mouse.move(box.x + 60, box.y + 60, { steps: 5 })
  await page.mouse.up()
  await expect(page.getByLabel('X 坐标', { exact: true })).not.toHaveValue('80')
  const movedX = await page.getByLabel('X 坐标', { exact: true }).inputValue()
  expect(Number(movedX) % 10).toBe(0)
  const resize = (await page.getByTestId('widget-resize').boundingBox())!
  await page.mouse.move(resize.x + resize.width / 2, resize.y + resize.height / 2)
  await page.mouse.down()
  await page.mouse.move(resize.x + 50, resize.y + 30, { steps: 5 })
  await page.mouse.up()
  const resizedWidth = await page.getByLabel('组件宽度', { exact: true }).inputValue()
  expect(Number(resizedWidth)).toBeGreaterThan(320)
  expect(Number(resizedWidth) % 10).toBe(0)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.getByLabel('组件宽度', { exact: true })).toHaveValue('320')
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await expect(page.getByLabel('组件宽度', { exact: true })).toHaveValue(resizedWidth)
  const saved = page.waitForRequest(
    (request) => request.method() === 'PUT' && request.url().endsWith('/draft'),
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const draft = (await saved).postDataJSON()
  expect(draft.widgets[0]).toMatchObject({
    x: Number(movedX),
    width: Number(resizedWidth),
    config: { text: '华东季度经营分析' },
  })
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByTestId('screen-widget').first()).toHaveText('华东季度经营分析')
  expect(errors).toEqual([])
})

for (const [label, width, height] of [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('大屏列表、新增与自定义设计分辨率', async ({ page }) => {
      await mock(page, [
        initial,
        { ...initial, id: 'empty-time', name: '待配置的业务大屏', updatedAt: null },
      ])
      await open(page, 'screens', permissions)
      await expect(page).toHaveTitle('lowcode Sub-App 开发宿主')
      await expect(page.getByRole('heading', { name: '大屏', exact: true })).toBeVisible()
      await expect(page.getByText('—', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`screens-list-${label}.png`)
      await page.getByRole('button', { name: '新增', exact: true }).click()
      const dialog = page.getByRole('dialog')
      await expect(page).toHaveScreenshot(`screens-create-${label}.png`)
      await dialog.getByRole('textbox', { name: '名称', exact: true }).fill('季度经营分析')
      await dialog.getByRole('combobox', { name: '设计分辨率' }).press('ArrowDown')
      await page.getByRole('option', { name: '自定义', exact: true }).click()
      await dialog.getByLabel('画布宽度').fill('1440')
      await dialog.getByLabel('画布高度').fill('900')
      const created = page.waitForRequest(
        (request) =>
          request.method() === 'POST' && new URL(request.url()).pathname === '/lowcode/screens',
      )
      await dialog.getByRole('button', { name: '创建并编辑', exact: true }).click()
      expect((await created).postDataJSON()).toMatchObject({
        name: '季度经营分析',
        draft: { designWidth: 1440, designHeight: 900, widgets: [] },
      })
      await expect(page.getByRole('region', { name: '大屏画布' })).toBeVisible()
      await expect(page.getByText('1440×900', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`screens-editor-empty-${label}.png`)
    })
    test('文字样式、分辨率保持坐标、层级与组件复制删除', async ({ page }) => {
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      page.on('console', (message) => {
        if (message.type() === 'error' || message.type() === 'warning') errors.push(message.text())
      })
      await mock(page)
      await open(page, `screens/${initial.id}/edit`, permissions)
      await page.getByRole('button', { name: '添加文本卡', exact: true }).click()
      await page.getByLabel('文字内容').fill('华东季度经营分析')
      await page.getByLabel('文字内容').blur()
      await page.getByLabel('X 坐标', { exact: true }).fill('120')
      await page.getByLabel('X 坐标', { exact: true }).blur()
      await page.getByLabel('组件宽度').fill('640')
      await page.getByLabel('组件宽度').blur()
      await page.getByRole('combobox', { name: '设计分辨率' }).press('ArrowDown')
      await page.getByRole('option', { name: '1366×768', exact: true }).click()
      await expect(page.getByLabel('X 坐标', { exact: true })).toHaveValue('120')
      await page.getByRole('combobox', { name: '字重' }).press('ArrowDown')
      await page.getByRole('option', { name: '加粗', exact: true }).click()
      await page.getByRole('button', { name: '复制组件', exact: true }).click()
      await page.getByLabel('文字内容').fill('销售目标完成率 98.6%')
      await page.getByLabel('文字内容').blur()
      await page.getByLabel('Y 坐标', { exact: true }).fill('240')
      await page.getByLabel('Y 坐标', { exact: true }).blur()
      await page.getByRole('button', { name: '下移一层', exact: true }).click()
      await expect(page.getByTestId('screen-widget').first()).toHaveCSS('z-index', '0')
      await page.getByRole('button', { name: '上移一层', exact: true }).click()
      const saved = page.waitForRequest((request) => request.method() === 'PUT')
      await page.getByRole('button', { name: '保存草稿', exact: true }).click()
      const draft = (await saved).postDataJSON()
      expect(draft.widgets).toHaveLength(2)
      expect(new Set(draft.widgets.map((widget: { id: string }) => widget.id)).size).toBe(2)
      expect(draft.widgets.map((widget: { zIndex: number }) => widget.zIndex)).toEqual([0, 1])
      expect(draft.widgets[0]).toMatchObject({
        x: 120,
        width: 640,
        config: { text: '华东季度经营分析', fontWeight: 'bold' },
      })
      await page
        .getByRole('complementary', { name: '属性面板' })
        .evaluate((element) => (element.scrollTop = 0))
      await expect(page.getByRole('heading', { name: '大屏编辑器', exact: true })).toBeInViewport()
      await expect(page).toHaveScreenshot(`screens-editor-text-${label}.png`)
      await page.getByRole('button', { name: '删除组件', exact: true }).click()
      await expect(page.getByTestId('screen-widget')).toHaveCount(1)
      await page.getByRole('button', { name: '撤销', exact: true }).click()
      await expect(page.getByTestId('screen-widget')).toHaveCount(2)
      expect(errors).toEqual([])
    })
    test('只读列表', async ({ page }) => {
      await mock(page, [{ ...initial, draft: undefined, publishedAt: '2026-11-11T10:20:30+08:00' }])
      await open(page, 'screens', ['screen:view'])
      await expect(page.getByText(initial.name, { exact: true })).toBeVisible()
      for (const name of ['新增', '编辑', '复制', '删除'])
        await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
      await expect(page).toHaveScreenshot(`screens-readonly-${label}.png`)
    })
  })
}

test('列表设置、复制、删除与保存失败重试', async ({ page }) => {
  await mock(page)
  await open(page, 'screens', permissions)
  await page.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByRole('textbox', { name: '名称', exact: true }).fill('新的大屏名称')
  const updated = page.waitForRequest((request) => request.method() === 'PUT')
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click()
  expect((await updated).postDataJSON()).toMatchObject({
    name: '新的大屏名称',
    draft: initial.draft,
  })
  const copied = page.waitForRequest((request) => request.url().endsWith('/copy'))
  await page.getByRole('button', { name: '复制', exact: true }).click()
  expect((await copied).method()).toBe('POST')
  await page.getByRole('button', { name: '删除', exact: true }).click()
  const deleted = page.waitForRequest((request) => request.method() === 'DELETE')
  await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click()
  expect((await deleted).url()).toContain(initial.id)
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByRole('button', { name: '添加文本卡', exact: true }).click()
  let failed = false
  await page.route(
    (url) => url.pathname.endsWith('/draft'),
    async (route) => {
      if (route.request().method() === 'PUT' && !failed) {
        failed = true
        await route.fulfill({
          status: 503,
          json: {
            code: 'system.unavailable',
            httpStatus: 503,
            userTip: '保存暂不可用，请重试',
            traceId: 'screen-save-failed',
          },
        })
      } else await route.fallback()
    },
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('保存暂不可用，请重试', { exact: true })).toBeVisible()
  await expect(page.getByTestId('screen-widget')).toHaveCount(1)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
})
