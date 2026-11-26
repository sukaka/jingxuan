import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  batchGenerateAnnualReports,
  createAnnualReport,
  disableAnnualReport,
  getAnnualReportSummary,
  getPublicAnnualReport,
  isValidAnnualShareKey,
  listAnnualReportSessions,
  listAnnualReports,
  sendPublicAnnualTelemetry,
  submitPublicAnnualBookingIntent,
} from '../src/api/annualReport'

const { getMock, postMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
}))

vi.mock('../src/api/http', () => ({
  http: {
    get: getMock,
    post: postMock,
  },
}))

const VALID_KEY = 'A'.repeat(43)

describe('annualReport API client', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
  })

  it('validates 43-char Base64URL shareKey format', () => {
    expect(isValidAnnualShareKey(VALID_KEY)).toBe(true)
    expect(isValidAnnualShareKey('short-key')).toBe(false)
    expect(isValidAnnualShareKey(`${'A'.repeat(42)}!`)).toBe(false)
  })

  it('calls management, public, telemetry, and booking endpoints with expected paths and headers', async () => {
    postMock.mockResolvedValue({ data: { data: { id: 1 } } })
    getMock.mockResolvedValue({ data: { data: { items: [], total: 0, page: 1, size: 20 } } })

    await createAnnualReport({ customerId: 10, reportYear: 2026, toneCode: 'AUTO' }, 'idem-1')
    expect(postMock).toHaveBeenCalledWith(
      '/annual-reports',
      { customerId: 10, reportYear: 2026, toneCode: 'AUTO' },
      { headers: { 'Idempotency-Key': 'idem-1' } },
    )

    await batchGenerateAnnualReports({ customerIds: [10, 11], reportYear: 2026 }, 'idem-2')
    expect(postMock).toHaveBeenCalledWith(
      '/annual-reports/batch-generate',
      { customerIds: [10, 11], reportYear: 2026 },
      { headers: { 'Idempotency-Key': 'idem-2' } },
    )

    await listAnnualReports({ reportYear: 2026, page: 1, size: 20 })
    expect(getMock).toHaveBeenCalledWith('/annual-reports', { params: { reportYear: 2026, page: 1, size: 20 } })

    await getAnnualReportSummary(2026)
    expect(getMock).toHaveBeenCalledWith('/annual-reports/summary', { params: { reportYear: 2026 } })

    await listAnnualReportSessions(7, 50)
    expect(getMock).toHaveBeenCalledWith('/annual-reports/7/sessions', { params: { limit: 50 } })

    await disableAnnualReport(7, { version: 0, reason: '停用' }, 'idem-3')
    expect(postMock).toHaveBeenCalledWith(
      '/annual-reports/7/disable',
      { version: 0, reason: '停用' },
      { headers: { 'Idempotency-Key': 'idem-3' } },
    )

    await getPublicAnnualReport(VALID_KEY)
    expect(getMock).toHaveBeenCalledWith(`/public/annual-reports/${VALID_KEY}`)

    await sendPublicAnnualTelemetry(VALID_KEY, {
      sessionKey: 'ses_12345678',
      eventType: 'HEARTBEAT',
      sceneIndex: 2,
      deltaDurationMs: 5000,
    })
    expect(postMock).toHaveBeenCalledWith(`/public/annual-reports/${VALID_KEY}/telemetry`, {
      sessionKey: 'ses_12345678',
      eventType: 'HEARTBEAT',
      sceneIndex: 2,
      deltaDurationMs: 5000,
    })

    await submitPublicAnnualBookingIntent(VALID_KEY, { sessionKey: 'ses_12345678' })
    expect(postMock).toHaveBeenCalledWith(`/public/annual-reports/${VALID_KEY}/booking-intent`, {
      sessionKey: 'ses_12345678',
    })
  })

  it('rejects malformed shareKey before hitting backend', async () => {
    await expect(getPublicAnnualReport('bad-key')).rejects.toThrow('无效')
    expect(getMock).not.toHaveBeenCalled()
  })
})
