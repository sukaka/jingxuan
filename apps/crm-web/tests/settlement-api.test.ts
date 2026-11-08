import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { http } from '../src/api/http'
import {
  createSettlementBatch,
  getSettlementBatch,
  getSettlementReconciliation,
  listSettlementBatches,
  recordSettlementPayment,
  verifySettlementBatch,
} from '../src/api/settlement'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture() {
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
      data: { code: 'OK', message: 'success', data: {}, requestId: 'request-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P1-4 机构结算 API 契约', () => {
  it('查询批次和只读对账，保留机构筛选与后端分页', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listSettlementBatches({ page: 1, size: 20, institutionId: 91, status: 'DRAFT' })
    await getSettlementBatch(301)
    await getSettlementReconciliation({ institutionId: 91 })

    expect(calls.map((call) => call.url)).toEqual([
      '/settlements/batches',
      '/settlements/batches/301',
      '/settlements/reconciliation',
    ])
    expect(calls[0]?.params).toEqual({ page: 1, size: 20, institutionId: 91, status: 'DRAFT' })
    expect(calls[2]?.params).toEqual({ institutionId: 91 })
  })

  it('创建批次只提交真实正式核销与结算周期', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createSettlementBatch({
      institutionId: 91,
      settlementType: 'MONTHLY',
      periodKey: '2026-10',
      redemptionIds: [1001, 1002],
    }, 'batch-create-key')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/settlements/batches', idempotencyKey: 'batch-create-key' })
    expect(calls[0]?.data).toEqual({ institutionId: 91, settlementType: 'MONTHLY', periodKey: '2026-10', redemptionIds: [1001, 1002] })
  })

  it('核准金额使用整数分，付款携带扫描通过的文件与真实银行流水', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await verifySettlementBatch(301, {
      version: 0,
      ruleVersion: 'OFFLINE-RULE-2026-10',
      grossFen: 50000,
      commissionFen: 5000,
      netPayableFen: 45000,
      verificationEvidence: 'FINANCE-VERIFY-001',
    }, 'verify-key')
    await recordSettlementPayment(301, {
      version: 1,
      amountFen: 20000,
      bankTradeNo: 'BANK-20261026-001',
      paidAt: '2026-10-26T03:00:00.000Z',
      voucherFileId: 601,
      evidenceRef: 'PAYMENT-EVIDENCE-001',
    }, 'payment-key')

    expect(calls.map((call) => call.url)).toEqual([
      '/settlements/batches/301/verification',
      '/settlements/batches/301/payments',
    ])
    expect(calls.map((call) => call.idempotencyKey)).toEqual(['verify-key', 'payment-key'])
    expect(calls[0]?.data).toMatchObject({ grossFen: 50000, commissionFen: 5000, netPayableFen: 45000 })
    expect(calls[1]?.data).toMatchObject({ amountFen: 20000, voucherFileId: 601, bankTradeNo: 'BANK-20261026-001' })
  })
})
