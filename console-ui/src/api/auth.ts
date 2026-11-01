import { request } from './client'
import { IS_MOCK, setAuth } from '@/auth'
import type { AuthUser, Role } from '@/auth'

interface LoginResponse {
  token: string
  username: string
  role: Role
  expiresAt: string
}

/** 登录：真实后端返回签名 token；mock 模式直接进入管理员身份。 */
export async function login(username: string, password: string): Promise<AuthUser> {
  if (IS_MOCK) {
    const user: AuthUser = { username: username || 'mock-admin', role: 'ADMIN' }
    setAuth('mock-token', user)
    return user
  }
  const result = await request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  if (result.role !== 'VIEWER' && result.role !== 'OPERATOR' && result.role !== 'ADMIN') {
    throw new Error('服务端返回了未知角色')
  }
  setAuth(result.token, { username: result.username, role: result.role })
  return { username: result.username, role: result.role }
}
