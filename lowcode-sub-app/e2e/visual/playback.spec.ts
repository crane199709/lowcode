import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'

const id = '0193a000-0000-7000-8000-000000000031'
const definition = {
  designWidth: 1920,
  designHeight: 1080,
  backgroundColor: '#101827',
  widgets: [
    {
      id: 'title',
      type: 'TEXT',
      x: 120,
      y: 100,
      width: 1500,
      height: 160,
      zIndex: 0,
      config: {
        text: '华东区域季度经营分析',
        fontSize: 80,
        color: '#ffffff',
        backgroundColor: '#18243a',
        fontWeight: 'bold',
        textAlign: 'center',
      },
    },
    {
      id: 'metric',
      type: 'TEXT',
      x: 120,
      y: 400,
      width: 720,
      height: 240,
      zIndex: 1,
      config: {
        text: '销售目标完成率\n98.6%',
        fontSize: 64,
        color: '#72ddc2',
        backgroundColor: '#18243a',
        fontWeight: 'normal',
        textAlign: 'center',
      },
    },
    {
      id: 'note',
      type: 'TEXT',
      x: 1000,
      y: 800,
      width: 800,
      height: 180,
      zIndex: 2,
      config: {
        text: '季度业绩持续增长',
        fontSize: 48,
        color: '#ffffff',
        backgroundColor: '#18243a',
        fontWeight: 'normal',
        textAlign: 'left',
      },
    },
  ],
}
const published = {
  id,
  name: '华东区域经营分析大屏（季度销售与目标完成情况）',
  definition,
  publishedAt: '2026-11-11T10:20:30+08:00',
}
async function mock(page: Page) {
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    (route) => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/published') || path.endsWith('/publish'))
        return route.fulfill({ json: { code: 'ok', httpStatus: 200, data: published } })
      return route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: [
            {
              id,
              name: published.name,
              designWidth: 1920,
              designHeight: 1080,
              publishedAt: published.publishedAt,
              updatedAt: published.publishedAt,
            },
          ],
          meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
        },
      })
    },
  )
}

test('查看者从列表播放已发布快照，三种缩放按容器尺寸生效且可进入和退出全屏', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  await mock(page)
  await open(page, 'screens', ['screen:view'])
  await page.getByRole('button', { name: '播放', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/screens/${id}/play$`))
  await expect(page.getByRole('region', { name: '已发布大屏' })).toBeVisible()
  const stage = page.getByTestId('playback-stage')
  const viewport = page.getByRole('region', { name: '播放画布' })
  const fit = (await stage.boundingBox())!
  const available = (await viewport.boundingBox())!
  expect(fit.width / fit.height).toBeCloseTo(1920 / 1080, 2)
  expect(fit.width).toBeLessThanOrEqual(available.width + 1)
  expect(fit.height).toBeLessThanOrEqual(available.height + 1)
  await page.getByRole('combobox', { name: '缩放方式' }).press('ArrowDown')
  await page.getByRole('option', { name: '铺满拉伸', exact: true }).click()
  await expect
    .poll(async () => Math.abs((await stage.boundingBox())!.height - available.height))
    .toBeLessThan(1)
  await page.getByRole('combobox', { name: '缩放方式' }).press('ArrowDown')
  await page.getByRole('option', { name: '按宽度适配', exact: true }).click()
  await expect
    .poll(async () => Math.abs((await stage.boundingBox())!.width - available.width))
    .toBeLessThan(1)
  await viewport.hover()
  await page.mouse.wheel(0, 1000)
  await expect(page.getByText('季度业绩持续增长', { exact: true })).toBeInViewport()
  await page.getByRole('button', { name: '全屏', exact: true }).click()
  await expect
    .poll(() => page.evaluate(() => document.fullscreenElement?.getAttribute('aria-label')))
    .toBe('已发布大屏')
  await page.getByRole('button', { name: '退出全屏', exact: true }).click()
  await expect.poll(() => page.evaluate(() => document.fullscreenElement === null)).toBe(true)
  expect(errors).toEqual([])
})

test('未发布或无数据范围时只展示错误，不回退读取草稿；无查看权限不请求快照', async ({ page }) => {
  let draftRequests = 0
  let publishedRequests = 0
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/draft')) draftRequests++
      if (path.endsWith('/published')) publishedRequests++
      await route.fulfill({
        status: 404,
        json: {
          code: 'screen.notFound',
          httpStatus: 404,
          userTip: '大屏不存在或不在你的数据范围内',
          traceId: 'playback-not-found',
        },
      })
    },
  )
  await open(page, `screens/${id}/play`, ['screen:view'])
  await expect(page.getByText('大屏不存在或不在你的数据范围内', { exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: '播放画布' })).toHaveCount(0)
  expect(draftRequests).toBe(0)
  expect(publishedRequests).toBe(1)
  await open(page, `screens/${id}/play`, [])
  await expect(page.getByText('没有查看大屏的权限', { exact: true })).toBeVisible()
  expect(publishedRequests).toBe(1)
})

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    test('发布需要确认，失败后可重试且查看者无发布入口', async ({ page }) => {
      await mock(page)
      let attempts = 0
      await page.route(
        (url) => url.pathname.endsWith('/publish'),
        async (route) => {
          attempts++
          if (attempts === 1)
            await route.fulfill({
              status: 503,
              json: {
                code: 'system.unavailable',
                httpStatus: 503,
                userTip: '发布暂不可用，请重试',
                traceId: 'publish-failed',
              },
            })
          else await route.fallback()
        },
      )
      await open(page, 'screens', ['screen:view', 'screen:publish'])
      await page
        .getByRole('region', { name: '大屏', exact: true })
        .getByRole('button', { name: '发布', exact: true })
        .click()
      await expect(page.getByRole('dialog', { name: '发布大屏' })).toBeVisible()
      expect(attempts).toBe(0)
      await expect(page).toHaveScreenshot(`screens-publish-confirm-${label}.png`)
      await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
      await expect(page.getByText('发布暂不可用，请重试', { exact: true })).toBeVisible()
      await page
        .getByRole('region', { name: '大屏', exact: true })
        .getByRole('button', { name: '发布', exact: true })
        .click()
      await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
      await expect.poll(() => attempts).toBe(2)
      await expect(page.getByText('发布暂不可用，请重试', { exact: true })).toHaveCount(0)
      await open(page, 'screens', ['screen:view'])
      await expect(page.getByRole('button', { name: '发布', exact: true })).toHaveCount(0)
    })
    test('快照不存在时显示可读错误与返回入口', async ({ page }) => {
      await mock(page)
      await page.route(
        (url) => url.pathname.endsWith('/published'),
        (route) =>
          route.fulfill({
            status: 404,
            json: {
              code: 'screen.notFound',
              httpStatus: 404,
              userTip: '大屏不存在或不在你的数据范围内',
              traceId: 'playback-not-found',
            },
          }),
      )
      await open(page, `screens/${id}/play`, ['screen:view'])
      await expect(page.getByText('大屏不存在或不在你的数据范围内', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`playback-unavailable-${label}.png`)
      await page.getByRole('button', { name: '返回列表', exact: true }).click()
      await expect(page).toHaveURL(/\/screens$/)
    })
    test('播放三种缩放方式', async ({ page }) => {
      await mock(page)
      await open(page, `screens/${id}/play`, ['screen:view'])
      await expect(page).toHaveTitle('lowcode Sub-App 开发宿主')
      await expect(page.getByText('华东区域季度经营分析', { exact: true })).toBeVisible()
      await expect(page).toHaveScreenshot(`playback-fit-${label}.png`)
      for (const [mode, name] of [
        ['stretch', '铺满拉伸'],
        ['width', '按宽度适配'],
      ] as const) {
        await page.getByRole('combobox', { name: '缩放方式' }).press('ArrowDown')
        await page.getByRole('option', { name, exact: true }).click()
        await expect(page).toHaveScreenshot(`playback-${mode}-${label}.png`)
      }
    })
  })
}
