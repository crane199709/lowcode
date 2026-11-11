import { expect, test, type Page } from '@playwright/test'
import { open, viewports } from './session'

const screenId = '0193a000-0000-7000-8000-000000000021'
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF9sAAAAASUVORK5CYII=',
  'base64',
)
async function mockImages(page: Page) {
  let draft = { designWidth: 1920, designHeight: 1080, backgroundColor: '#101827', widgets: [] }
  let sequence = 0
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/screens'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      const method = route.request().method()
      let data: unknown = draft
      if (path.endsWith('/images/uploads')) {
        const objectId = `0193a000-0000-7000-8000-${String(++sequence).padStart(12, '0')}`
        data = {
          objectId,
          url: `/test-images/${objectId}`,
          headers: { 'Content-Type': 'image/png' },
          expiresAt: new Date(Date.now() + 3600000).toISOString(),
        }
      } else if (path.endsWith('/complete'))
        data = { id: path.split('/').at(-2), filename: '华东经营图片.png' }
      else if (path.endsWith('/download'))
        data = {
          url: `/test-images/${path.split('/').at(-2)}`,
          expiresAt: new Date(Date.now() + 3600000).toISOString(),
        }
      else if (method === 'PUT') draft = route.request().postDataJSON()
      await route.fulfill({
        json: { code: 'ok', httpStatus: path.endsWith('/uploads') ? 201 : 200, data },
      })
    },
  )
  await page.route('**/test-images/*', (route) =>
    route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><defs><linearGradient id="g"><stop stop-color="#164e63"/><stop offset="1" stop-color="#0f172a"/></linearGradient></defs><path fill="url(#g)" d="M0 0h960v540H0z"/><circle cx="720" cy="160" r="110" fill="#22d3ee" opacity=".3"/><path d="M0 460 240 270 400 360 670 160 960 360v180H0z" fill="#0891b2" opacity=".55"/></svg>',
    }),
  )
}

for (const [label, width, height] of viewports) {
  test(`图片上传、替换、背景与草稿持久引用 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) errors.push(message.text())
    })
    await mockImages(page)
    await open(page, `screens/${screenId}/edit`, ['screen:view', 'screen:edit'])
    const fileChooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: '添加组件', exact: true }).click()
    await page.getByRole('menuitem', { name: '图片', exact: true }).click()
    await expect(page.getByRole('menuitem', { name: '图片', exact: true })).not.toBeVisible()
    await (await fileChooser).setFiles({ name: '区域图片.png', mimeType: 'image/png', buffer: png })
    const image = page.getByTestId('screen-widget').locator('img')
    await expect(image).toBeVisible()
    const original = await image.getAttribute('src')
    await page
      .getByLabel('替换图片', { exact: true })
      .setInputFiles({ name: '替换区域图片.webp', mimeType: 'image/webp', buffer: png })
    await expect(image).not.toHaveAttribute('src', original!)
    await page.getByRole('combobox', { name: '图片填充', exact: true }).press('ArrowDown')
    await page
      .getByRole('listbox', { name: '图片填充' })
      .getByRole('option', { name: '覆盖填充', exact: true })
      .click()
    await expect(image).toHaveCSS('object-fit', 'cover')
    await page
      .getByLabel('背景图片', { exact: true })
      .setInputFiles({ name: '经营背景.png', mimeType: 'image/png', buffer: png })
    await expect(page.getByAltText('大屏背景图')).toBeVisible()
    await page.getByRole('combobox', { name: '背景填充', exact: true }).press('ArrowDown')
    await page
      .getByRole('listbox', { name: '背景填充' })
      .getByRole('option', { name: '拉伸', exact: true })
      .click()
    await expect(page.getByAltText('大屏背景图')).toHaveCSS('object-fit', 'fill')
    const saved = page.waitForRequest(
      (request) => request.method() === 'PUT' && request.url().endsWith('/draft'),
    )
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    const definition = (await saved).postDataJSON()
    expect(definition).toMatchObject({
      backgroundImageObjectId: '0193a000-0000-7000-8000-000000000003',
      backgroundImageFill: 'fill',
      widgets: [
        {
          type: 'IMAGE',
          config: { objectId: '0193a000-0000-7000-8000-000000000002', fill: 'cover' },
        },
      ],
    })
    expect(JSON.stringify(definition)).not.toContain('test-images')
    await page
      .getByRole('complementary', { name: '属性面板' })
      .evaluate((element) => (element.scrollTop = 0))
    await expect(page).toHaveTitle('lowcode Sub-App 开发宿主')
    await expect(page.getByRole('heading', { name: '大屏编辑器', exact: true })).toBeInViewport()
    await expect(page).toHaveScreenshot(`screen-images-editor-${label}.png`)
    await page.reload()
    await expect(page.getByAltText('大屏背景图')).toBeVisible()
    await expect(page.getByTestId('screen-widget').locator('img')).toHaveCSS('object-fit', 'cover')
    await page.getByRole('button', { name: '移除背景图', exact: true }).click()
    await expect(page.getByAltText('大屏背景图')).toHaveCount(0)
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await expect(page.getByAltText('大屏背景图')).toBeVisible()
    expect(errors).toEqual([])
  })
}

test('上传失败与不支持的文件保留已有图片', async ({ page }) => {
  await mockImages(page)
  await open(page, `screens/${screenId}/edit`, ['screen:view', 'screen:edit'])
  await page
    .getByLabel('添加图片', { exact: true })
    .setInputFiles({ name: '区域图片.png', mimeType: 'image/png', buffer: png })
  const image = page.getByTestId('screen-widget').locator('img')
  await expect(image).toBeVisible()
  const original = await image.getAttribute('src')
  await page
    .getByLabel('替换图片', { exact: true })
    .setInputFiles({ name: 'unsupported.svg', mimeType: 'image/svg+xml', buffer: png })
  await expect(page.getByText('请选择不超过 10 MiB 的 PNG、JPEG、WebP 或 GIF 图片')).toBeVisible()
  await expect(image).toHaveAttribute('src', original!)
  await page.route('**/test-images/*', (route) =>
    route.request().method() === 'PUT' ? route.fulfill({ status: 503 }) : route.fallback(),
  )
  await page
    .getByLabel('替换图片', { exact: true })
    .setInputFiles({ name: '替换图片.png', mimeType: 'image/png', buffer: png })
  await expect(page.getByText('图片上传失败，请重试', { exact: true })).toBeVisible()
  await expect(image).toHaveAttribute('src', original!)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
})

for (const [label, width, height] of viewports) {
  test(`已发布图片播放并在签名到期前刷新 ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await page.clock.install()
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) errors.push(message.text())
    })
    await mockImages(page)
    const objectId = '0193a000-0000-7000-8000-000000000002'
    let downloads = 0
    await page.route('**/published', (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: {
            id: screenId,
            name: '华东区域经营分析',
            publishedAt: '2026-11-11T10:20:30+08:00',
            definition: {
              designWidth: 1920,
              designHeight: 1080,
              backgroundColor: '#101827',
              backgroundImageObjectId: '0193a000-0000-7000-8000-000000000003',
              backgroundImageFill: 'cover',
              widgets: [
                {
                  id: 'image',
                  type: 'IMAGE',
                  x: 120,
                  y: 160,
                  width: 800,
                  height: 500,
                  zIndex: 0,
                  config: { objectId, fill: 'contain' },
                },
              ],
            },
          },
        },
      }),
    )
    await page.route(`**/images/${objectId}/download`, (route) => {
      downloads++
      return route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: {
            url: `/test-images/${objectId}?signature=${downloads}`,
            expiresAt: new Date(Date.now() + 4000).toISOString(),
          },
        },
      })
    })
    await open(page, `screens/${screenId}/play`, ['screen:view'])
    await expect(page.getByAltText('图片组件')).toBeVisible()
    await expect(page.getByAltText('大屏背景图')).toBeVisible()
    await expect(page.getByAltText('图片组件')).toHaveCSS('object-fit', 'contain')
    await expect(page.getByAltText('大屏背景图')).toHaveCSS('object-fit', 'cover')
    await page.clock.fastForward(3700)
    await expect.poll(() => downloads).toBeGreaterThanOrEqual(2)
    await expect(page.getByAltText('图片组件')).toHaveAttribute('src', /signature=2/)
    await expect(page).toHaveTitle('lowcode Sub-App 开发宿主')
    await expect(page.getByRole('heading', { name: '华东区域经营分析', exact: true })).toBeVisible()
    await expect(page).toHaveScreenshot(`screen-images-playback-${label}.png`)
    expect(errors).toEqual([])
  })
}
