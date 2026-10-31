import { expect, test } from '@playwright/test'

test('overview renders KPI and health', async ({ page }) => {
  await page.goto('/#/overview')
  await expect(page.locator('h1')).toHaveText('首页')
  await expect(page.getByText('今日请求')).toBeVisible()
  await expect(page.getByText('系统健康')).toBeVisible()
  await expect(page.getByText('最近竞价', { exact: true }).first()).toBeVisible()
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
  await expect(page.locator('h1')).toHaveText('广告管理')
  await expect(page.getByText('广告列表', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建广告' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('heading', { name: '定向条件' })).toBeVisible()
})

test('ads edit saves through PUT', async ({ page }) => {
  await page.goto('/#/ads')
  await page.getByRole('button', { name: '编辑' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('已更新广告', { exact: false })).toBeVisible({ timeout: 10_000 })
})

test('ops page switches between group, DLT and recon tabs', async ({ page }) => {
  await page.goto('/#/ops')
  await expect(page.locator('h1')).toHaveText('运行监控')
  await expect(page.getByText('billing-recon', { exact: true }).first()).toBeVisible()

  await page.getByRole('tab', { name: 'DLT 重放' }).click()
  await expect(page.getByText('批量重放')).toBeVisible()

  await page.getByRole('tab', { name: '预算对账' }).click()
  await expect(page.getByText('立即对账')).toBeVisible()
})

test('data and experiments pages load', async ({ page }) => {
  await page.goto('/#/data')
  await expect(page.locator('h1')).toHaveText('数据查询')
  await expect(page.getByRole('button', { name: '导出 CSV' })).toBeVisible()

  await page.goto('/#/experiments')
  await expect(page.locator('h1')).toHaveText('演示开关')
  await expect(page.getByText('兜底开关')).toBeVisible()
})


test('budget handles a missing adId and recovers', async ({ page }) => {
  await page.goto('/#/budget')
  await page.evaluate(() => localStorage.setItem('bidpulse.console.adId', '999998'))
  await page.reload()
  await expect(page.getByText('没有找到这个广告')).toBeVisible({ timeout: 10_000 })
  await page.getByRole('button', { name: '使用广告 110' }).click()
  await expect(page.getByText('创意标题-110')).toBeVisible()
})

test('help drawer explains the pages', async ({ page }) => {
  await page.goto('/#/overview')
  await page.getByRole('button', { name: '使用说明' }).click()
  await expect(page.getByText('推荐演示顺序')).toBeVisible()
  await expect(page.getByText('模拟竞价：亲手发一次请求')).toBeVisible()
  await page.getByRole('button', { name: '知道了' }).click()
})
