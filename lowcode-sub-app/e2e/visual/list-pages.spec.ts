import { expect, test, type Page } from '@playwright/test'

const viewports = [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const

// 三个列表页尚未接入接口，只有空状态；接入接口后在各自用例中补充接近真实长度的数据。
const pages = [
  ['datasources', '数据源', '暂无数据源'],
  ['datasets', '数据集', '暂无数据集'],
  ['screens', '大屏', '暂无大屏'],
] as const

/** 以已登录成员身份打开 Sub-App 内的页面。 */
async function open(page: Page, path: string) {
  // 预置假会话：开发宿主读到会话即视为已登录，不访问真实网关。
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'visual-access',
        refreshToken: 'visual-refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '示例科技有限公司',
        identityName: '测试成员',
      }),
    ),
  )
  await page.route(
    (url) => url.pathname.startsWith('/iam/'),
    (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: { permissions: [], menus: [], subApps: [], layout: null },
          traceId: 'visual',
        },
      }),
  )
  await page.goto(`/apps/lowcode/${path}`)
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    for (const [path, title, empty] of pages) {
      test(`${title}空列表`, async ({ page }) => {
        await open(page, path)
        await expect(page.getByRole('heading', { name: title })).toBeVisible()
        await expect(page.getByText(empty)).toBeVisible()
        await expect(page).toHaveScreenshot(`${path}-empty-${label}.png`)
      })
    }
  })
}
