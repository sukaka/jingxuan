import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  activateCustomerTierRule,
  adjustCustomerTier,
  createCustomerTierRule,
  reviewCustomerTierDowngrade,
} from '../src/api/customerTier'
import type { CustomerTierRuleRequest } from '../src/api/customerTier'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

function capture(responseData: unknown, status = 200) {
  const calls: { url: string; method: string; data: unknown; headers: Record<string, unknown> }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      url: config.url ?? '',
      method: config.method ?? 'get',
      data: config.data ? JSON.parse(config.data as string) : undefined,
      headers: config.headers as unknown as Record<string, unknown>,
    })
    return {
      status,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'tier-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

const rule: CustomerTierRuleRequest = {
  ruleCode: 'TIER_TEST',
  ruleName: '测试规则',
  timezone: 'Asia/Shanghai',
  levels: [
    { tierCode: 'S', tierName: '核心', sortNo: 1, minScore: '0', minConsumptionFen: 1000000 },
    { tierCode: 'A', tierName: '重要', sortNo: 2, minScore: '0', minConsumptionFen: 800001 },
    { tierCode: 'B', tierName: '成长', sortNo: 3, minScore: '0', minConsumptionFen: 1000 },
    { tierCode: 'C', tierName: '普通', sortNo: 4, minScore: '0', minConsumptionFen: 1 },
    { tierCode: 'D', tierName: '培育', sortNo: 5, minScore: '0', minConsumptionFen: 0 },
  ],
  metrics: [],
}

describe('客户分级 API', () => {
  it('规则创建与激活携带结构化配置、version 和幂等键', async () => {
    const { adapter, calls } = capture({ id: 9, status: 'DRAFT', version: 0 }, 201)
    http.defaults.adapter = adapter

    await createCustomerTierRule(rule, 'tier-create-key')
    expect(calls[0]).toMatchObject({
      url: '/customer-tier-rules', method: 'post', data: rule,
    })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('tier-create-key')

    await activateCustomerTierRule(9, 0, 'tier-active-key')
    expect(calls[1]).toMatchObject({
      url: '/customer-tier-rules/9/activation', method: 'post', data: { version: 0 },
    })
    expect(calls[1]?.headers['Idempotency-Key']).toBe('tier-active-key')
  })

  it('人工等级调整与降级审批只走专用接口', async () => {
    const { adapter, calls } = capture({ id: 3, status: 'APPROVED' })
    http.defaults.adapter = adapter

    await adjustCustomerTier(3, {
      tierCode: 'A', reason: '人工复核高意向客户', lock: true, version: 4,
    }, 'tier-adjust-key')
    await reviewCustomerTierDowngrade(8, 'approve', {
      reason: '确认指标变化', version: 0,
    }, 'tier-review-key')

    expect(calls[0]).toMatchObject({
      url: '/customers/3/tier-adjustments', method: 'post',
      data: { tierCode: 'A', reason: '人工复核高意向客户', lock: true, version: 4 },
    })
    expect(calls[1]).toMatchObject({
      url: '/customer-tier-reviews/8/approve', method: 'post',
      data: { reason: '确认指标变化', version: 0 },
    })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('tier-adjust-key')
    expect(calls[1]?.headers['Idempotency-Key']).toBe('tier-review-key')
  })
})
