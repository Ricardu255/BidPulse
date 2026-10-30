import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 前后端分离：
 * - 开发态 `npm run dev` 独立启动在 5173，`/console-api` 代理到后端 8080；
 * - 生产态 `npm run build` 产出独立 dist，可交给 Nginx/任意静态服务器托管；
 * - `npm run build:embedded` 仅用于单 jar 演示，把产物放进后端 static 目录。
 *
 * base 用相对路径，dist 放在任意子路径下都能加载资源。
 */
export default defineConfig({
  base: './',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/console-api': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
