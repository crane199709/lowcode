import type { Page } from '@playwright/test'

export const viewports = [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const

/** 以持有给定权限的已登录成员身份打开 Sub-App 内的页面；业务接口由调用方先行 mock。 */
export async function open(page: Page, path: string, permissions: string[] = []) {
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
          data: { permissions, menus: [], subApps: [], layout: null },
          traceId: 'visual',
        },
      }),
  )
  await page.goto(`/apps/lowcode/${path}`)
}
