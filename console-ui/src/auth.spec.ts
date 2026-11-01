import { beforeEach, describe, expect, it } from 'vitest'
import { auth, clearAuth, hasRole, isLoggedIn, roleLabel, setAuth } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    clearAuth()
  })

  it('stores token and role in memory', () => {
    setAuth('token-1', { username: 'operator', role: 'OPERATOR' })

    expect(isLoggedIn()).toBe(true)
    expect(auth.token).toBe('token-1')
    expect(auth.user?.username).toBe('operator')
    expect(hasRole('OPERATOR', 'ADMIN')).toBe(true)
    expect(hasRole('ADMIN')).toBe(false)
  })

  it('clears auth state', () => {
    setAuth('token-1', { username: 'viewer', role: 'VIEWER' })
    clearAuth()

    expect(isLoggedIn()).toBe(false)
    expect(auth.user).toBeNull()
  })

  it('renders role labels', () => {
    expect(roleLabel('ADMIN')).toBe('管理员')
    expect(roleLabel('OPERATOR')).toBe('操作员')
    expect(roleLabel('VIEWER')).toBe('只读')
  })
})
