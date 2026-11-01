import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'

function stubFetch(body: unknown, status = 200): void {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
    typeof body === 'string' ? body : JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } },
  )))
}

describe('api client', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('unwraps a successful envelope', async () => {
    stubFetch({ code: 0, msg: 'ok', data: { value: 1 } })
    await expect(request<{ value: number }>('/x')).resolves.toEqual({ value: 1 })
  })

  it('throws server business message when code is non-zero', async () => {
    stubFetch({ code: 400, msg: '参数不合法', data: null })
    await expect(request('/x')).rejects.toMatchObject({ message: '参数不合法', status: 400 })
  })

  it('maps 403 without body to an actionable message', async () => {
    stubFetch('', 403)
    await expect(request('/x')).rejects.toSatisfy((e: unknown) =>
      e instanceof ApiError && e.status === 403 && e.message.includes('没有权限'))
  })

  it('maps 503 JSON body to the server message', async () => {
    stubFetch({ code: 503, msg: 'Kafka 暂不可用' }, 503)
    await expect(request('/x')).rejects.toMatchObject({ message: 'Kafka 暂不可用', status: 503 })
  })

  it('maps network failure to status 0 with a hint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(request('/x')).rejects.toSatisfy((e: unknown) =>
      e instanceof ApiError && e.status === 0 && e.message.includes('后端已启动'))
  })

  it('maps non-JSON error body by HTTP status', async () => {
    stubFetch('<html>bad gateway</html>', 500)
    await expect(request('/x')).rejects.toMatchObject({ message: '请求失败（HTTP 500）', status: 500 })
  })
})
