import { expect, test } from '@playwright/test'

test('overview renders KPI and health', async ({ page }) => {
  await page.goto('/#/overview')
  await expect(page.locator('h1')).toHaveText('概览')
  await expect(page.getByText('今日请求')).toBeVisible()
  await expect(page.getByText('系统健康')).toBeVisible()
  await expect(page.getByText('最近竞价', { exact: true })).toBeVisible()
})

test('playground runs a bid and shows second price', async ({ page }) => {
  await page.goto('/#/playground')
  // 用独立 userId，避免真实后端上一条用例打满频次导致 fall-through
  await page.getByPlaceholder('用于实时频次控制').fill(`e2e-${Date.now()}`)
  await page.getByRole('button', { name: '发起竞价' }).click()
  await expect(page.getByText('竞价成功')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('¥1.01')).toBeVisible()
  await expect(page.getByText('二价计费', { exact: false })).toBeVisible()
})

test('budget page loads budget and frequency', async ({ page }) => {
  await page.goto('/#/budget')
  await expect(page.locator('h1')).toHaveText('预算与频次')
  await expect(page.getByText('当日预算', { exact: true })).toBeVisible()
  await expect(page.getByText('实时频次')).toBeVisible()
  await expect(page.getByText('本小时成交次数')).toBeVisible()
})

test('ads page opens the create dialog', async ({ page }) => {
  await page.goto('/#/ads')
  await expect(page.locator('h1')).toHaveText('广告与投放')
  await expect(page.getByText('广告列表', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建广告' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('heading', { name: '定向条件' })).toBeVisible()
})

test('ops page shows DLT and recon panels', async ({ page }) => {
  await page.goto('/#/ops')
  await expect(page.locator('h1')).toHaveText('异步运维')
  await expect(page.getByText('DLT 消息', { exact: true })).toBeVisible()
  await expect(page.getByText('预算对账', { exact: true })).toBeVisible()
  await expect(page.getByText('billing-recon', { exact: true }).first()).toBeVisible()
})

test('data and experiments pages load', async ({ page }) => {
  await page.goto('/#/data')
  await expect(page.locator('h1')).toHaveText('数据浏览')
  await expect(page.getByText('导出 CSV')).toBeVisible()

  await page.goto('/#/experiments')
  await expect(page.locator('h1')).toHaveText('实验与开关')
  await expect(page.getByText('兜底开关')).toBeVisible()
})
