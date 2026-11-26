import { expect, test, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

const screenshotDir = process.env.OF_BROWSER_EVIDENCE_DIR || '../evidence/screenshots'

async function login(page:Page, role:string) {
  await page.goto('/')
  await page.getByRole('textbox',{name:'用户名'}).fill('demo_'+role)
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await expect(page.getByRole('navigation',{name:'角色菜单'})).toBeVisible()
}

test('顾客使用实际目录、分类搜索、详情与购物车', async ({page}) => {
  await login(page,'customer')
  await expect(page.getByRole('heading',{name:'商品目录'})).toBeVisible()
  await page.getByRole('textbox',{name:'商品名称'}).fill('分隔收纳盒')
  await page.getByRole('button',{name:'搜索',exact:true}).click()
  const product=page.getByRole('row').filter({hasText:'DEMO-TRAIL'})
  await expect(product).toBeVisible()
  const existing=await page.request.get('/api/cart')
  const previous=(await existing.json()).find((row:{id:number;quantity:number})=>row.id===1)?.quantity ?? 0
  await product.getByRole('button',{name:'商品详情'}).click()
  await expect(page.getByRole('dialog').getByRole('heading',{name:'分隔收纳盒'})).toBeVisible()
  await page.getByRole('button',{name:'保存到购物车'}).click()
  await page.getByRole('button',{name:'我的购物车'}).click()
  await expect(page.getByRole('row').filter({hasText:'分隔收纳盒'})).toBeVisible()
  await expect(page.getByRole('button',{name:'提交订单'})).toBeEnabled()
  if (!previous) await page.getByRole('row').filter({hasText:'分隔收纳盒'}).getByRole('button',{name:'移除'}).click()
  await page.getByRole('button',{name:'我的订单'}).click()
  await expect(page.getByRole('heading',{name:'我的订单'})).toBeVisible()
})

test('商家页面使用从库统计并可查看商品和库存', async ({page}) => {
  await login(page,'merchant')
  await expect(page.getByRole('heading',{name:'销售概览'})).toBeVisible()
  await expect(page.getByText(/从库 72/)).toBeVisible()
  await page.getByRole('button',{name:'商品与库存'}).click()
  await expect(page.getByRole('row').filter({hasText:'DEMO-TRAIL'})).toBeVisible()
  await expect(page.getByRole('button',{name:'调库存'}).first()).toBeVisible()
})

test('管理员查看实时图表、数据库记录和真实实验截图', async ({page}) => {
  await login(page,'admin')
  await mkdir(screenshotDir,{recursive:true})
  await expect(page.getByRole('img',{name:'最近 30 天销售额趋势图'})).toBeVisible()
  await expect(page.getByText(/从库 72/)).toBeVisible()
  await page.screenshot({path:join(screenshotDir,'admin-overview.png'),animations:'disabled'})
  await page.getByRole('button',{name:'运行状态'}).click()
  await expect(page.getByText('复制正常')).toBeVisible()
  await page.screenshot({path:join(screenshotDir,'replica-status.png'),animations:'disabled'})
  await page.getByRole('button',{name:'数据库实验'}).click()
  await page.getByRole('button',{name:'运行 COUNT(*)'}).click()
  await expect(page.getByText('order_items',{exact:true})).toBeVisible({timeout:25000})
  await page.getByRole('button',{name:'索引与慢查询',exact:true}).click()
  const log = page.locator('pre.log')
  await expect(log).toContainText('02-after')
  await log.evaluate(element => {
    const lines=(element.textContent ?? '').split('\n')
    const line=lines.findIndex(text=>text.includes('01-before'))
    element.scrollTop=Math.max(0,line-2)*parseFloat(getComputedStyle(element).lineHeight)
  })
  await log.screenshot({path:join(screenshotDir,'index-comparison.png'),animations:'disabled'})
  await page.getByRole('button',{name:'日志恢复',exact:true}).click()
  await expect(log).toContainText('PASS: 恢复两笔订单')
  await log.evaluate(element => { element.scrollTop=element.scrollHeight })
  await log.screenshot({path:join(screenshotDir,'binlog-recovery.png'),animations:'disabled'})
  await page.getByRole('button',{name:'主从复制',exact:true}).click()
  await expect(log).toContainText('Replica_IO_Running: Yes')
  await log.screenshot({path:join(screenshotDir,'replication-log.png'),animations:'disabled'})
})
