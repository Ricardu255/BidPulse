import { reactive } from 'vue'

export type Role = 'VIEWER' | 'OPERATOR' | 'ADMIN'

export interface AuthUser {
  username: string
  role: Role
}

interface StoredAuth {
  token: string
  user: AuthUser
}

const AUTH_KEY = 'bidpulse.console.auth'
export const IS_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

function storage(): Storage | null {
  // token 只放 sessionStorage：关闭标签页即失效，减少 XSS/共享设备上的暴露面
  return typeof sessionStorage === 'undefined' ? null : sessionStorage
}

function load(): StoredAuth | null {
  try {
    const raw = storage()?.getItem(AUTH_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StoredAuth
    if (!parsed?.token || !parsed.user?.username || !isRole(parsed.user.role)) return null
    return parsed
  } catch {
    return null
  }
}

function isRole(value: unknown): value is Role {
  return value === 'VIEWER' || value === 'OPERATOR' || value === 'ADMIN'
}

const stored = load()

export const auth = reactive<{ token: string | null; user: AuthUser | null }>({
  token: stored?.token ?? null,
  user: stored?.user ?? (IS_MOCK ? { username: 'mock-admin', role: 'ADMIN' } : null),
})

export function getToken(): string | null {
  return auth.token
}

export function setAuth(token: string, user: AuthUser): void {
  auth.token = token
  auth.user = user
  if (!IS_MOCK) {
    storage()?.setItem(AUTH_KEY, JSON.stringify({ token, user }))
  }
}

export function clearAuth(): void {
  auth.token = null
  auth.user = IS_MOCK ? { username: 'mock-admin', role: 'ADMIN' } : null
  storage()?.removeItem(AUTH_KEY)
}

/** mock 模式用于纯前端预览，不依赖后端登录。 */
export function isLoggedIn(): boolean {
  return IS_MOCK || (!!auth.token && !!auth.user)
}

export function hasRole(...roles: Role[]): boolean {
  return !!auth.user && roles.includes(auth.user.role)
}

export function roleLabel(role: Role | undefined): string {
  if (role === 'ADMIN') return '管理员'
  if (role === 'OPERATOR') return '操作员'
  if (role === 'VIEWER') return '只读'
  return '未登录'
}
