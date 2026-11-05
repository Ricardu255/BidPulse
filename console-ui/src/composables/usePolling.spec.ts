import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPollingController } from './usePolling'

describe('createPollingController', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not overlap scheduled refreshes while one is still in flight', async () => {
    let release: (() => void) | undefined
    const task = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve
        }),
    )
    const poll = createPollingController(task, 1000)

    poll.start()
    await vi.advanceTimersByTimeAsync(1000)
    expect(task).toHaveBeenCalledTimes(1)

    // 上一轮未返回：后续定时触发全部跳过
    await vi.advanceTimersByTimeAsync(3000)
    expect(task).toHaveBeenCalledTimes(1)

    release?.()
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1000)
    expect(task).toHaveBeenCalledTimes(2)

    poll.stop()
  })

  it('marks data stale on failure and clears it on the next success', async () => {
    let fail = true
    const poll = createPollingController(async () => {
      if (fail) throw new Error('boom')
    }, 1000)

    await poll.refresh()
    expect(poll.stale.value).toBe(true)
    expect(poll.lastError.value).toBeInstanceOf(Error)
    expect(poll.lastUpdatedAt.value).toBeNull()

    fail = false
    vi.setSystemTime(new Date('2026-11-05T10:00:00Z'))
    await poll.refresh()
    expect(poll.stale.value).toBe(false)
    expect(poll.lastError.value).toBeNull()
    expect(poll.lastUpdatedAt.value).toBe(Date.now())
  })

  it('treats a false return as a failed refresh', async () => {
    const poll = createPollingController(() => false, 1000)
    await poll.refresh()
    expect(poll.stale.value).toBe(true)
  })

  it('only lets the latest manual refresh write state (latest-wins)', async () => {
    const writes: number[] = []
    let releaseFirst: (() => void) | undefined
    let releaseSecond: (() => void) | undefined
    let call = 0

    const poll = createPollingController(async ({ isCurrent }) => {
      const id = ++call
      await new Promise<void>((resolve) => {
        if (id === 1) releaseFirst = resolve
        else releaseSecond = resolve
      })
      if (isCurrent()) writes.push(id)
    }, 1000)

    const first = poll.refresh()
    const second = poll.refresh()

    // 后发先至：第二次请求先返回，第一次的响应随后到达也必须被丢弃
    releaseSecond?.()
    await second
    releaseFirst?.()
    await first

    expect(writes).toEqual([2])
  })

  it('starts and stops the timer', async () => {
    const task = vi.fn()
    const poll = createPollingController(task, 500)

    poll.start()
    expect(poll.running.value).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    expect(task).toHaveBeenCalledTimes(1)

    poll.stop()
    expect(poll.running.value).toBe(false)
    await vi.advanceTimersByTimeAsync(2000)
    expect(task).toHaveBeenCalledTimes(1)
  })
})
