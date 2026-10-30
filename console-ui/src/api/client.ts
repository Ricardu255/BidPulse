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
    throw new ApiError(body?.msg || `HTTP ${response.status}`, response.status)
  }
  return body
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const body = await requestEnvelope<T>(path, init)
  return body.data
}
