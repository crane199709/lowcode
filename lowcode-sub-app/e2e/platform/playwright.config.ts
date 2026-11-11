import { defineConfig } from '@playwright/test'
import { resolve } from 'node:path'

const platform = process.env.LOWCODE_PLATFORM_ROOT
const run = process.env.LOWCODE_E2E_RUN_DIR
if (!platform || !run) throw new Error('请通过 deploy/e2e/run.sh 启动隔离平台')

export default defineConfig({
  testDir: '.',
  testMatch: '**/acceptance.spec.ts',
  outputDir: resolve(run, 'playwright-results'),
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  reporter: [['list']],
  use: {
    channel: 'chromium',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 1000 },
    baseURL: 'http://127.0.0.1:15174',
    // 原始 trace 可含密码和邀请令牌，不生成，不交付。
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  webServer: [
    ...[
      ['@forge/platform-console', 15173],
      ['@forge/tenant-app', 15174],
    ].map(([app, port]) => ({
      command: `pnpm --filter ${app} preview --host 127.0.0.1 --port ${port} --strictPort`,
      cwd: resolve(platform, 'frontend'),
      url: `http://127.0.0.1:${port}`,
      reuseExistingServer: false,
      env: { FORGE_DEV_GATEWAY: 'http://127.0.0.1:18000' },
    })),
    {
      command: 'pnpm preview --config e2e/platform/vite.config.ts',
      url: 'http://127.0.0.1:15176/remoteEntry.js',
      reuseExistingServer: false,
    },
  ],
})
