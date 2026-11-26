import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

test.skip(!process.env.ASSISTANT_E2E_BASE_URL, 'Start StudyReplyE2E to supply the application and local mailboxes')

async function login(page: Page, address: string) {
  await page.goto(address)
  await page.getByLabel('用户名').fill('learner')
  await page.getByLabel('密码').fill(process.env.ASSISTANT_E2E_PASSWORD ?? '')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
}

test('C01 从手机核对原文、保留草稿并发送', async ({ page, context }) => {
  await login(page, '/')
  await page.getByRole('link', { name: '邮件', exact: true }).click()
  await page.getByRole('link', { name: /滑动窗口/ }).click()
  await expect(page.getByText('我们约定用 Java。')).toBeVisible()
  await expect(page.getByRole('radio', { name: '选择来信 Re: 滑动窗口' })).toBeChecked()
  await page.getByRole('button', { name: '拟回复' }).click()
  await page.getByRole('link', { name: /打开草稿/ }).click({ timeout: 20_000 })
  await expect(page.getByLabel('正文')).toHaveValue(/Java.*while/)

  await page.getByRole('link', { name: /查看引用/ }).click()
  await expect(page.getByText('滑动窗口需要 while 反复收缩左边界，直到窗口重新满足约束。').first()).toBeVisible()
  await expect(page.locator('.source-hit')).toBeVisible()
  await page.getByRole('button', { name: '← 返回', exact: true }).click()
  const edited = '我的 Java 示例：窗口超限时反复移动左边界。'
  await page.getByLabel('正文').fill(edited)
  await page.getByRole('button', { name: '保留草稿' }).click()
  await expect(page.getByRole('status').filter({ hasText: '已保存修订 2' })).toBeVisible()
  await page.close()

  const reopened = await context.newPage()
  await reopened.goto('/#/tasks')
  await reopened.getByRole('link', { name: /起草回复/ }).click()
  await reopened.getByRole('link', { name: /打开草稿/ }).click()
  await expect(reopened.getByLabel('正文')).toHaveValue(edited)
  expect(await (await reopened.request.get('/api/sends')).json()).toEqual([])
  await reopened.getByRole('button', { name: '核对并发送' }).click()
  const dialog = reopened.getByRole('dialog', { name: '确认发送' })
  await expect(dialog.getByLabel('确认正文')).toHaveText(edited)
  await expect(dialog.getByText('classmate@studio.test')).toBeVisible()
  await dialog.getByRole('button', { name: '确认发送此修订' }).click()
  await expect(reopened.getByText('已发送', { exact: true })).toBeVisible({ timeout: 20_000 })
  expect(await reopened.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('K04 在桌面保存 PDF 页内位置，从手机查看引用后接着阅读', async ({ page, browser, baseURL }) => {
  const desktop = await browser.newPage({ viewport: { width: 1280, height: 860 } })
  try {
    await login(desktop, baseURL ?? '')
    await desktop.getByRole('link', { name: '资料库', exact: true }).click()
    await desktop.getByRole('article').filter({ hasText: '练习册.pdf' }).getByRole('link', { name: '继续阅读' }).click()
    await expect(desktop.getByText('第 1 / 9 页')).toBeVisible()
    for (let number = 2; number <= 8; number++) {
      await desktop.getByRole('button', { name: '下一页' }).click()
      await expect(desktop.getByText('第 ' + number + ' / 9 页')).toBeVisible()
    }
    const reader = desktop.getByLabel('原文', { exact: true })
    await expect(desktop.getByRole('button', { name: '保存阅读位置' })).toBeEnabled()
    await reader.evaluate(frame => { frame.scrollTop = (frame.scrollHeight - frame.clientHeight) / 2 })
    await desktop.getByRole('button', { name: '保存阅读位置' }).click()
    const id = new URL(desktop.url()).hash.split('/').at(-1)
    await expect.poll(async () => {
      const response = await desktop.request.get(baseURL + '/api/documents/' + id + '/bookmark')
      return (await response.json()).ratio
    }).toBeCloseTo(0.5, 1)

    await login(page, '/')
    await page.getByLabel('你的问题').fill('needle PDF')
    await page.getByRole('button', { name: /提问/ }).click()
    await page.getByRole('link', { name: /打开回答/ }).click()
    await page.getByRole('link', { name: /查看引用/ }).click()
    await expect(page.getByText('第 3 / 9 页')).toBeVisible()
    await page.getByRole('link', { name: '资料库', exact: true }).click()
    await page.getByRole('article').filter({ hasText: '练习册.pdf' }).getByRole('link', { name: '继续阅读' }).click()
    await expect(page.getByText('第 8 / 9 页')).toBeVisible()
    await expect.poll(() => page.getByLabel('原文', { exact: true }).evaluate(frame =>
      frame.scrollTop / Math.max(1, frame.scrollHeight - frame.clientHeight))).toBeCloseTo(0.5, 1)
  } finally {
    await desktop.close()
  }
})
