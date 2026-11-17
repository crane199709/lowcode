import { expect, test } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const run = process.env.LOWCODE_E2E_RUN_DIR!
const credentials = JSON.parse(readFileSync(resolve(run, 'credentials.json'), 'utf8'))

test('独立开发宿主真实登录并读取 Shell 流程创建的大屏', async ({ page }) => {
  const errors: string[] = []
  let authenticated = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (authenticated && ['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  await page.goto('/')
  await expect(page).toHaveTitle('lowcode Sub-App 开发宿主')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('lowcode-designer')
  await page.getByLabel('密码', { exact: true }).fill(credentials.memberPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出', exact: true })).toBeVisible()
  authenticated = true
  const screens = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/lowcode/screens' && response.ok(),
  )
  await page.goto('/apps/lowcode/screens')
  expect((await screens).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '大屏', exact: true })).toBeVisible()
  await expect(page.getByText('真实销售验收大屏', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('dialog').getByLabel('名称', { exact: true }).fill('独立宿主验收大屏')
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/lowcode/screens' &&
      response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '创建并编辑', exact: true }).click()
  expect((await created).status()).toBe(201)
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeVisible()
  await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  const theme = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    return {
      primary: style.getPropertyValue('--el-color-primary').trim().toLowerCase(),
      background: getComputedStyle(document.body).backgroundColor,
    }
  })
  expect(theme.primary).toBe('#4f46e5')
  expect(theme.background).toBe('rgb(248, 250, 252)')
  expect(errors).toEqual([])
  await page.screenshot({ path: resolve(run, 'dev-host-screen.png'), animations: 'disabled' })
  writeFileSync(
    resolve(run, 'dev-host-result.json'),
    JSON.stringify({ theme, errors, realHttp: true }, null, 2),
  )
})
