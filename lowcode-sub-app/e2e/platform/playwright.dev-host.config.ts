import { defineConfig } from '@playwright/test'
import { resolve } from 'node:path'

const run = process.env.LOWCODE_E2E_RUN_DIR
if (!run) throw new Error('请通过 deploy/e2e/run.sh 启动隔离平台')

export default defineConfig({
  testDir: '.',
  testMatch: '**/dev-host.spec.ts',
  outputDir: resolve(run, 'dev-host-results'),
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: [['list']],
  use: {
    channel: 'chromium',
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
    baseURL: 'http://127.0.0.1:15174',
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  // 前一轮 Shell preview 已退出；复用 IAM 已登记的回调源验证独立宿主。
  webServer: {
    command: 'pnpm dev --host 127.0.0.1 --port 15174 --strictPort',
    url: 'http://127.0.0.1:15174',
    reuseExistingServer: false,
    env: { FORGE_DEV_GATEWAY: 'http://127.0.0.1:18000' },
  },
})
