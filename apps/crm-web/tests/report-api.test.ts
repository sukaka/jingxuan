import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { http } from '../src/api/http'
import { getCampaignAnalytics, getOperationsReport, getPerformanceReport } from '../src/api/report'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture() {
  const calls: { method: string; url: string; params: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({ method: config.method ?? 'get', url: config.url ?? '', params: config.params })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: {}, requestId: 'report-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P2 经营报表 API 契约', () => {
  it('运营报表使用后端日期、时区、来源、收款方和下钻参数，不提交租户或数据范围', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await getOperationsReport({
      from: '2026-10-01', to: '2026-10-31', timeZone: 'Asia/Shanghai', sourceSystem: 'YOUZAN', receiverType: 'PLATFORM',
      staffUserId: 7, drillDownType: 'PAYMENT', drillDownLimit: 50,
    })

    expect(calls[0]).toEqual({
      method: 'get',
      url: '/reports/operations',
      params: {
        from: '2026-10-01', to: '2026-10-31', timeZone: 'Asia/Shanghai', sourceSystem: 'YOUZAN', receiverType: 'PLATFORM',
        staffUserId: 7, drillDownType: 'PAYMENT', drillDownLimit: 50,
      },
    })
    expect(calls[0]?.params).not.toHaveProperty('tenantId')
    expect(calls[0]?.params).not.toHaveProperty('scope')
  })

  it('管家与商务业绩使用 performanceRole，活动批次事实下钻保留批次筛选', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter
    const range = { from: '2026-10-01', to: '2026-10-31', timeZone: 'Asia/Shanghai' }

    await getPerformanceReport({ ...range, performanceRole: 'MANAGER', staffUserId: 7, drillDownType: 'PAYMENT', page: 1, size: 20 })
    await getPerformanceReport({ ...range, performanceRole: 'BUSINESS', sourceSystem: 'MANUAL' })
    await getCampaignAnalytics({ ...range, campaignId: 91, campaignBatchId: 901, drillDownType: 'REDEMPTION', drillDownLimit: 20 })

    expect(calls.map((call) => call.url)).toEqual(['/reports/performance', '/reports/performance', '/reports/campaigns'])
    expect(calls[0]?.params).toMatchObject({ performanceRole: 'MANAGER', staffUserId: 7, drillDownType: 'PAYMENT' })
    expect(calls[1]?.params).toMatchObject({ performanceRole: 'BUSINESS', sourceSystem: 'MANUAL' })
    expect(calls[2]?.params).toMatchObject({ campaignId: 91, campaignBatchId: 901, drillDownType: 'REDEMPTION' })
  })
})
