import { describe, expect, it } from 'vitest'
import { currentVersion, errorMessage, key } from '../../src/api'

describe('API helpers', () => {
  it('formats timeouts and server messages for the user', () => {
    expect(errorMessage({ isAxiosError: true, code: 'ECONNABORTED' })).toContain('超时')
    expect(errorMessage({ isAxiosError: true, response: { status: 429, data: { message: '请稍后重试' } } }))
      .toBe('请稍后重试')
  })

  it('returns the current revision only for conflicts with a body', () => {
    const conflict = { isAxiosError: true, response: { status: 409, data: { current: { revision: 2 } } } }
    expect(currentVersion(conflict)).toEqual({ revision: 2 })
    expect(currentVersion({ ...conflict, response: { status: 409, data: null } })).toBeUndefined()
    expect(currentVersion({ ...conflict, response: { ...conflict.response, status: 400 } })).toBeUndefined()
  })

  it('creates random idempotency keys in the server format', () => {
    const first = key()
    expect(first).toMatch(/^[a-f0-9]{32}$/)
    expect(key()).not.toBe(first)
  })
})
