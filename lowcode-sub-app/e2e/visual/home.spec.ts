import { expect, test, type Page } from '@playwright/test'

const viewports = [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const

// 接近真实长度的数据：含最长标题与大额金额，检查长文本不撑破布局；字段与 archetype 的 OrderResponse 一致。
const orders = [
  {
    id: 'o1',
    title: '华东大区 2026 年第四季度办公设备集中采购订单（含笔记本电脑、显示器与会议室终端）',
    amount: 1286400.5,
    createdAt: '2026-11-08T09:30:00Z',
  },
  { id: 'o2', title: '办公用品补货', amount: 860, createdAt: '2026-11-07T02:15:00Z' },
  {
    id: 'o3',
    title: '生产环境服务器扩容',
    amount: 45200,
    createdAt: '2026-11-06T13:45:00Z',
  },
  {
    id: 'o4',
    title: '年度软件许可续费',
    amount: 98000,
    createdAt: '2026-11-05T08:00:00Z',
  },
  {
    id: 'o5',
    title: '华南区门店装修物料',
    amount: 23650.75,
    createdAt: '2026-11-03T06:20:00Z',
  },
]

/** 以已登录成员身份打开首页；`items` 为订单接口返回的条目。 */
async function open(page: Page, items: unknown[]) {
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
  const ok = (data: unknown, meta?: unknown) => ({
    json: { code: 'ok', httpStatus: 200, data, meta, traceId: 'visual' },
  })
  await page.route(
    (url) => url.pathname.startsWith('/iam/'),
    (route) => route.fulfill(ok({ permissions: [], menus: [], subApps: [], layout: null })),
  )
  // 只拦截接口：页面地址 /apps/lowcode/... 不以 /lowcode/ 开头。
  await page.route(
    (url) => url.pathname.startsWith('/lowcode/'),
    (route) =>
      route.fulfill(
        ok(items, {
          page: { number: 1, size: 20, totalElements: items.length, totalPages: 1 },
        }),
      ),
  )
  await page.goto('/apps/lowcode/home')
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('订单列表', async ({ page }) => {
      await open(page, orders)
      await expect(page.getByTestId('order-title')).toHaveCount(orders.length)
      await expect(page).toHaveScreenshot(`home-${label}.png`)
    })

    test('空列表', async ({ page }) => {
      await open(page, [])
      await expect(page.getByText('暂无订单')).toBeVisible()
      await expect(page).toHaveScreenshot(`home-empty-${label}.png`)
    })
  })
}
