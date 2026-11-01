import type { ApiEnvelope } from './types'
import { clearAuth, getToken } from '@/auth'

const API_BASE = (import.meta.env.VITE_API_BASE || '/console-api/v1').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function requestEnvelope<T>(path: string, init?: RequestInit): Promise<ApiEnvelope<T>> {
  const headers = new Headers(init?.headers)
  if (!headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch {
    throw new ApiError(friendlyMessage(0), 0)
  }

  let body: ApiEnvelope<T> | null = null
  try {
    body = (await response.json()) as ApiEnvelope<T>
  } catch {
    // 非 JSON 响应（如网关错误页）统一按 HTTP 状态处理
  }

  if (!response.ok || !body || body.code !== 0) {
    // HTTP 200 但业务 code 非 0 时，使用业务 code 作为错误状态，避免出现“HTTP 200 却报错”。
    const status = response.ok ? (body?.code ?? response.status) : response.status
    if (status === 401) {
      clearAuth()
      if (typeof window !== 'undefined' && !window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
    }
    throw new ApiError(friendlyMessage(status, body?.msg), status)
  }
  return body
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const body = await requestEnvelope<T>(path, init)
  return body.data
}


/** 把裸 HTTP 状态码翻译成用户能理解的原因；后端已有业务 msg 时优先用后端文案。 */
function friendlyMessage(status: number, serverMsg?: string): string {
  if (serverMsg) return serverMsg
  switch (status) {
    case 0:
      return '网络请求失败：请确认后端已启动；若为跨域，请检查 allowed-origins'
    case 401:
      return '登录已过期或凭据无效，请重新登录'
    case 403:
      return '没有权限：当前角色无权执行该操作，或控制台写操作已关闭'
    case 404:
      return '请求的资源不存在'
    case 503:
      return '依赖服务暂不可用，请稍后重试'
    default:
      return `请求失败（HTTP ${status}）`
  }
}
