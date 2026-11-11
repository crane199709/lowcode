import { expect, test } from '@playwright/test'
import { open, viewports } from './session'

// 尚未接入接口的列表页只有空状态；接入接口后移到各自的用例文件，补充接近真实长度的数据。
const pages = [
  ['datasets', '数据集', '暂无数据集'],
  ['screens', '大屏', '暂无大屏'],
] as const

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
