import { expect, test, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

test.skip(process.env.OF_AI_MOCK_BROWSER !== '1', 'Only runs against the explicit local Mock overlay.')

const output = process.env.OF_BROWSER_EVIDENCE_DIR || '../.runtime/v110-screenshots'
async function login(page: Page, role: string, feature: string) {
  await page.goto('/')
  await page.getByRole('textbox',{name:'用户名'}).fill('demo_'+role)
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('button',{name:feature,exact:true}).click()
  await mkdir(output,{recursive:true})
}

test('Mock Agent 展示工具、数据库表格和 SQL 图形', async ({page}) => {
  await login(page,'admin','AI 数据助手')
  await page.getByRole('textbox',{name:'问题',exact:true}).fill('按日展示这周的支付销售趋势。')
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await expect(page.getByText(/销售趋势 · MySQL GTID 从库只读/)).toBeVisible()
  await expect(page.getByRole('img',{name:'SQL 统计图 sales'})).toBeVisible()
  await page.screenshot({path:join(output,'mock-agent.png'),animations:'disabled',fullPage:true})
})

test('Mock 客服展示取消规则的真实知识片段和引用', async ({page}) => {
  await login(page,'customer','智能客服')
  await page.getByRole('button',{name:'待支付订单能取消吗？',exact:true}).click()
  await expect(page.getByText(/引用 #\d+ · 订单、支付与取消/)).toBeVisible()
  await page.getByText(/引用 #\d+ · 订单、支付与取消/).click()
  await expect(page.getByText(/当前版本没有退款功能/).first()).toBeVisible()
  await page.screenshot({path:join(output,'mock-rag.png'),animations:'disabled',fullPage:true})
})

test('Mock 商家报告显示可核对的库存指标', async ({page}) => {
  await login(page,'merchant','AI 库存分析')
  await page.getByRole('spinbutton',{name:'商品 ID'}).fill('1')
  await page.getByRole('button',{name:'生成库存报告'}).click()
  await expect(page.getByRole('heading',{name:'补货与库存建议'})).toBeVisible()
  await expect(page.getByText('预计可售天数',{exact:true})).toBeVisible()
  await page.screenshot({path:join(output,'mock-inventory.png'),animations:'disabled',fullPage:true})
})
