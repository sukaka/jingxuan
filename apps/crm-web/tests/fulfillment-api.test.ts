import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  approveRedemptionReversal,
  cancelAppointment,
  checkInAppointment,
  completeAppointmentService,
  confirmAppointment,
  createAppointment,
  createRedemption,
  getAppointment,
  getAppointmentOptions,
  getRedemption,
  getRedemptionReversal,
  listAppointments,
  listRedemptionReversals,
  listRedemptions,
  rejectAppointment,
  rejectRedemptionReversal,
  requestRedemptionReversal,
} from '../src/api/fulfillment'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture(response: unknown = {}) {
  const calls: { method: string; url: string; data: unknown; params: unknown; idempotencyKey: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params,
      idempotencyKey: config.headers?.get('Idempotency-Key'),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: response, requestId: 'fulfillment-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P0-4 履约 API 契约', () => {
  it('预约分页、详情与权益选项使用约定查询参数', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listAppointments({ page: 2, size: 20, customerId: 42, institutionId: 91, status: 'CONFIRMED' })
    await getAppointment(501)
    await getAppointmentOptions(42)
    await getAppointmentOptions({ appointmentId: 501 })

    expect(calls.map((call) => call.url)).toEqual(['/appointments', '/appointments/501', '/appointment-options', '/appointment-options'])
    expect(calls[0]?.params).toEqual({ page: 2, size: 20, customerId: 42, institutionId: 91, status: 'CONFIRMED' })
    expect(calls[2]?.params).toEqual({ customerId: 42 })
    expect(calls[3]?.params).toEqual({ appointmentId: 501 })
  })

  it('创建预约逐项目传权益批次、计划次数与幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createAppointment({
      customerId: 42,
      institutionId: 91,
      scheduledAt: '2026-10-25T02:00:00.000Z',
      remark: '脱敏测试备注',
      items: [{ entitlementId: 81, count: 2 }],
    }, 'appointment-create-fixed')

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/appointments')
    expect(calls[0]?.data).toMatchObject({ customerId: 42, institutionId: 91, remark: '脱敏测试备注', items: [{ entitlementId: 81, count: 2 }] })
    expect(calls[0]?.idempotencyKey).toBe('appointment-create-fixed')
  })

  it('状态端点同时携带版本、原因和真实幂等头', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await confirmAppointment(501, { version: 1, reason: '确认接诊' }, 'confirm-fixed')
    await rejectAppointment(501, { version: 1, reason: '无法接诊' }, 'reject-fixed')
    await cancelAppointment(501, { version: 1, reason: '客户取消' }, 'cancel-fixed')
    await checkInAppointment(501, { version: 2, reason: '实际到院' }, 'check-in-fixed')
    await completeAppointmentService(501, { version: 3, reason: '服务完成' }, 'service-done-fixed')

    expect(calls.map((call) => call.url)).toEqual([
      '/appointments/501/confirm',
      '/appointments/501/reject',
      '/appointments/501/cancel',
      '/appointments/501/check-in',
      '/appointments/501/service-done',
    ])
    expect(calls[4]?.data).toEqual({ version: 3, reason: '服务完成' })
    expect(calls.map((call) => call.idempotencyKey)).toEqual(['confirm-fixed', 'reject-fixed', 'cancel-fixed', 'check-in-fixed', 'service-done-fixed'])
  })

  it('正式核销使用 appointmentVersion 和 lines，历史保留机构客户项目筛选', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createRedemption(501, {
      appointmentVersion: 3,
      serviceAt: '2026-10-25T03:00:00.000Z',
      evidenceRef: 'SERVICE-EVIDENCE-TEST',
      lines: [{ entitlementId: 81, count: 2 }],
    }, 'redemption-post-fixed')
    await listRedemptions({ page: 1, size: 20, institutionId: 91, customerId: 42, projectId: 101, status: 'POSTED' })
    await getRedemption(701)

    expect(calls[0]?.url).toBe('/appointments/501/redemptions')
    expect(calls[0]?.data).toMatchObject({ appointmentVersion: 3, evidenceRef: 'SERVICE-EVIDENCE-TEST', lines: [{ entitlementId: 81, count: 2 }] })
    expect(calls[0]?.idempotencyKey).toBe('redemption-post-fixed')
    expect(calls[1]?.url).toBe('/redemptions')
    expect(calls[1]?.params).toMatchObject({ institutionId: 91, customerId: 42, projectId: 101, status: 'POSTED' })
    expect(calls[2]?.url).toBe('/redemptions/701')
  })

  it('冲正申请、详情与审核使用独立端点和固定幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await requestRedemptionReversal(701, { redemptionVersion: 2, reason: '履约异常测试' }, 'reversal-request-fixed')
    await listRedemptionReversals({ page: 1, size: 20, status: 'REQUESTED' })
    await getRedemptionReversal(801)
    await approveRedemptionReversal(801, { version: 2, reason: '审核通过' }, 'reversal-approve-fixed')
    await rejectRedemptionReversal(802, { version: 1, reason: '证据不足' }, 'reversal-reject-fixed')

    expect(calls.map((call) => call.url)).toEqual([
      '/redemptions/701/reversal-requests',
      '/redemption-reversals',
      '/redemption-reversals/801',
      '/redemption-reversals/801/approve',
      '/redemption-reversals/802/reject',
    ])
    expect(calls[0]?.data).toEqual({ redemptionVersion: 2, reason: '履约异常测试' })
    expect(calls[0]?.idempotencyKey).toBe('reversal-request-fixed')
    expect(calls[3]?.idempotencyKey).toBe('reversal-approve-fixed')
    expect(calls[4]?.idempotencyKey).toBe('reversal-reject-fixed')
  })
})
