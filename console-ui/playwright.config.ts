import { defineConfig } from '@playwright/test'

/**
 * 前端联调 E2E。
 *
 * 默认启动 mock 模式（不依赖后端）：
 *   npm run test:e2e
 *
 * 对真实后端联调时先启动后端 + Vite，再指定地址：
 *   E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173',
    headless: true,
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev:mock -- --host 127.0.0.1 --port 4173',
        url: 'http://127.0.0.1:4173/',
        reuseExistingServer: true,
        timeout: 120_000,
      },
})
