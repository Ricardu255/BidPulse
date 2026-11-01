import type {
  AuditLogView,
  DltMessageView,
  DltReplayStats,
  KafkaGroupView,
  ReconReport,
} from '../types'
import { delay, state } from './shared'

function partitions(totalLag: number) {
  const first = Math.ceil(totalLag / 2)
  return [
    { partition: 0, committed: 5100, end: 5100 + first, lag: first },
    { partition: 1, committed: 3400, end: 3400 + totalLag - first, lag: totalLag - first },
    { partition: 2, committed: 1700, end: 1700, lag: 0 },
  ]
}

export async function mockKafkaGroups(): Promise<KafkaGroupView[]> {
  await delay(120)
  const base = state.paused ? [128, 64, 32] : [0, 0, 0]
  return [
    { group: 'billing-recon', exists: true, partitionCount: 3, totalLag: base[0], partitions: partitions(base[0]) },
    { group: 'hourly-preagg', exists: true, partitionCount: 3, totalLag: base[1], partitions: partitions(base[1]) },
    { group: 'freq-antifraud', exists: true, partitionCount: 3, totalLag: base[2], partitions: partitions(base[2]) },
  ]
}

export async function mockPause(): Promise<{ status: string }> {
  await delay(100)
  state.paused = true
  return { status: 'paused' }
}

export async function mockResume(): Promise<{ status: string }> {
  await delay(100)
  state.paused = false
  return { status: 'resumed' }
}

export async function mockDltMessages(limit = 20): Promise<DltMessageView[]> {
  await delay(160)
  return Array.from({ length: Math.min(limit, 6) }, (_, index) => ({
    topic: 'bid_event.DLT',
    partition: index % 3,
    offset: 2048 + index,
    key: String(110 + index),
    value: JSON.stringify({
      eventId: `mock-dlt-${index}`,
      requestId: `req-${index}`,
      adId: 110 + index,
      payPrice: 1.01,
    }),
    replayCount: index % 3,
    processed: index % 2 === 0,
    timestamp: `2026-10-31 10:2${index}:00`,
    headers: {
      'x-replay-count': String(index % 3),
      'kafka_dlt-original-topic': 'bid_event',
    },
  }))
}

export async function mockReplayDlt(maxRecords: number): Promise<DltReplayStats> {
  await delay(300)
  return { replayed: Math.min(maxRecords, 3), skippedInvalid: 1, skippedExhausted: 1 }
}

export async function mockReplayDltOne(
  partition: number,
  offset: number,
): Promise<{ partition: number; offset: number; outcome: string }> {
  await delay(250)
  return { partition, offset, outcome: 'REPLAYED' }
}

export async function mockRecon(): Promise<ReconReport> {
  await delay(120)
  return { ...state.reconReport }
}

export async function mockAuditLogs(limit = 50): Promise<AuditLogView[]> {
  await delay(140)
  const rows: AuditLogView[] = [
    {
      id: 12,
      actor: 'operator',
      role: 'OPERATOR',
      method: 'PUT',
      path: '/console-api/v1/experiments',
      status: 200,
      ip: '127.0.0.1',
      detail: 'frequencyEnabled=false',
      createdAt: '2026-11-01 17:42:10',
    },
    {
      id: 11,
      actor: 'admin',
      role: 'ADMIN',
      method: 'POST',
      path: '/admin/consumer/pause',
      status: 200,
      ip: '127.0.0.1',
      detail: '',
      createdAt: '2026-11-01 17:40:03',
    },
    {
      id: 10,
      actor: 'anonymous',
      role: 'ANONYMOUS',
      method: 'LOGIN',
      path: '/console-api/v1/auth/login',
      status: 401,
      ip: '127.0.0.1',
      detail: 'username=viewer',
      createdAt: '2026-11-01 17:39:22',
    },
  ]
  return rows.slice(0, Math.max(1, Math.min(limit, rows.length)))
}

export async function mockRunRecon(): Promise<ReconReport> {
  await delay(400)
  state.reconReport = {
    ...state.reconReport,
    lastRunAt: '2026-10-31 10:45:00',
    adCount: 130,
    alerted: 0,
    repaired: 1,
    durationMs: 290,
  }
  return { ...state.reconReport }
}
