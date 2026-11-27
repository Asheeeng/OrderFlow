import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

test.skip(process.env.OF_AI_CONFIG_BROWSER !== '1', 'Only for the isolated configuration-center Mock deployment.')
test('管理员查看脱敏配置、通过工具测试并查看请求日志', async ({page})=>{
  await page.goto('/')
  await page.getByRole('textbox',{name:'用户名'}).fill('demo_admin')
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('button',{name:'AI 配置中心',exact:true}).click()
  await expect(page.getByRole('heading',{name:'模型连接'})).toBeVisible()
  await expect(page.getByLabel('API Key',{exact:true})).toHaveValue('')
  await page.getByRole('button',{name:'一键测试',exact:true}).click()
  await expect(page.getByText(/Function Calling · 通过/)).toBeVisible()
  await expect(page.getByText(/知识库可用性 · 通过/)).toBeVisible()
  await expect(page.getByRole('heading',{name:'最近 30 条请求'})).toBeVisible()
  await page.getByRole('button',{name:'保存并立即应用',exact:true}).click()
  await expect(page.getByText('已保存，后续请求使用新配置。')).toBeVisible()
  const directory=process.env.OF_BROWSER_EVIDENCE_DIR || '../.runtime/config-screenshots'
  await mkdir(directory,{recursive:true})
  await page.screenshot({path:join(directory,'mock-config.png')})
})
