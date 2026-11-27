import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

const dir = process.env.OF_BROWSER_EVIDENCE_DIR || '../.runtime/ui-redesign'
const roles: Record<string,string[]> = {
  customer:['商品目录','我的购物车','我的订单','智能客服'],
  merchant:['销售概览','商品与库存','订单处理','AI 库存分析'],
  admin:['平台概览','订单管理','商品管理','用户管理','商家管理','数据库实验','运行状态','AI 数据助手','客服知识库','AI 配置中心'],
}
for (const [role, menus] of Object.entries(roles)) {
  test(`${role} 主要页面和窄屏布局`, async ({page}) => {
    await mkdir(dir,{recursive:true})
    await page.goto('/')
    await page.getByRole('textbox',{name:'用户名'}).fill(`demo_${role}`)
    await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_PASSWORD!)
    await page.getByRole('button',{name:'登录',exact:true}).click()
    const nav=page.getByRole('navigation',{name:'角色菜单'})
    await expect(nav).toBeVisible()
    for (const label of menus) {
      await nav.getByRole('button',{name:label,exact:true}).click()
      await expect(page.getByRole('heading',{name:label,exact:true,level:1})).toBeVisible()
      await expect(page.locator('main.main')).toHaveAttribute('aria-busy','false')
      if(label==='商品目录') await expect(page.locator('.product-card').first()).toBeVisible()
      if(label==='AI 配置中心') await expect(page.getByRole('heading',{name:'模型连接'})).toBeVisible()
      if(label==='AI 数据助手'||label==='智能客服') await expect(page.getByRole('textbox',{name:'问题'})).toBeVisible()
      if(label==='AI 库存分析') await expect(page.getByRole('button',{name:'生成库存报告'})).toBeVisible()
      if(label==='客服知识库') await expect(page.getByRole('button',{name:'增量构建'})).toBeVisible()
      await page.screenshot({path:join(dir,`${role}-${label}.png`),animations:'disabled'})
    }
    await page.setViewportSize({width:390,height:844})
    await nav.getByRole('button',{name:menus[0],exact:true}).click()
    await expect(page.getByRole('heading',{name:menus[0],exact:true,level:1})).toBeVisible()
    await expect(page.locator('main.main')).toHaveAttribute('aria-busy','false')
    expect(await page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
    await page.screenshot({path:join(dir,`${role}-mobile.png`),animations:'disabled'})
  })
}
