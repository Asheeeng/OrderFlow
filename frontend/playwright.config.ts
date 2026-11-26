import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir:'./tests',
  outputDir:process.env.OF_BROWSER_OUTPUT_DIR || '../.runtime/playwright',
  workers:1,
  timeout:90000,
  expect:{timeout:22000},
  use:{
    baseURL:`http://127.0.0.1:${process.env.WEB_PORT || '5173'}`,
    viewport:{width:1440,height:1050},
    channel:process.env.OF_BROWSER_CHANNEL || 'chrome',
    trace:'off',
  },
})
