/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 后端 API 前缀；前后端分开部署时改成完整地址，如 http://localhost:8080/console-api/v1 */
  readonly VITE_API_BASE?: string
  /** 'true' 时使用本地 mock 数据，前端可脱离后端独立展示 */
  readonly VITE_USE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
