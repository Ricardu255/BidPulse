import type { ApiEnvelope } from './types'

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

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch (e) {
    throw new ApiError(e instanceof Error ? e.message : '网络请求失败', 0)
  }

  let body: ApiEnvelope<T> | null = null
  try {
    body = (await response.json()) as ApiEnvelope<T>
  } catch {
    // 非 JSON 响应（如网关错误页）统一按 HTTP 状态处理
  }

  if (!response.ok || !body || body.code !== 0) {
    throw new ApiError(friendlyMessage(response.status, body?.msg), response.status)
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
      return '网络请求失败，请确认后端已启动'
    case 403:
      return '没有权限：控制台写操作可能被关闭，或前端来源未加入 allowed-origins'
    case 404:
      return '请求的资源不存在'
    case 503:
      return '依赖服务暂不可用，请稍后重试'
    default:
      return `请求失败（HTTP ${status}）`
  }
}
