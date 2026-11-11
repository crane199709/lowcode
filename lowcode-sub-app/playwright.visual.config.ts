import { defineConfig } from '@playwright/test'

// 截图随字体渲染变化：团队固定在同一平台生成与比对基线，首次运行 pnpm test:visual:update 后逐张查看再提交。
export default defineConfig({
  testDir: './e2e/visual',
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  fullyParallel: false,
  workers: 1,
  expect: { toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.005 } },
  use: {
    channel: 'chromium',
    baseURL: 'http://127.0.0.1:5176',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  // 开发宿主即可渲染页面；接口全部由用例 mock，不需要网关与后端。
  webServer: {
    command: 'pnpm dev --host 127.0.0.1',
    url: 'http://127.0.0.1:5176',
    reuseExistingServer: false,
  },
})
