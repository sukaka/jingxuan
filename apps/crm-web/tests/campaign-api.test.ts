import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createCampaign,
  createCampaignBatch,
  getCampaign,
  listCampaigns,
  updateCampaign,
  updateCampaignBatch,
} from '../src/api/campaign'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture() {
  const calls: { method: string; url: string; data: unknown; params: unknown; key: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params,
      key: config.headers?.get('Idempotency-Key'),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: {}, requestId: 'campaign-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P2 活动与批次 API 契约', () => {
  it('活动列表和详情使用约定端点并保留后端分页筛选', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listCampaigns({ page: 2, size: 20, keyword: '周年', status: 'ACTIVE', campaignType: 'PROMOTION' })
    await getCampaign(81)

    expect(calls[0]).toMatchObject({ method: 'get', url: '/campaigns' })
    expect(calls[0]?.params).toEqual({ page: 2, size: 20, keyword: '周年', status: 'ACTIVE', campaignType: 'PROMOTION' })
    expect(calls[1]).toMatchObject({ method: 'get', url: '/campaigns/81' })
  })

  it('创建活动不携带虚构渠道归因，批次独立追加且写请求带幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createCampaign({
      campaignCode: 'CMP-TEST',
      campaignName: '接口契约测试活动',
      campaignType: 'FLASH',
      startsAt: '2026-11-01T01:00:00Z',
      endsAt: '2026-11-01T10:00:00Z',
      status: 'DRAFT',
    }, 'campaign-create-test')
    await createCampaignBatch(81, {
      batchCode: 'WAVE-01',
      batchName: '第一批',
      startsAt: '2026-11-01T01:00:00Z',
      endsAt: '2026-11-01T04:00:00Z',
      status: 'DRAFT',
    }, 'batch-create-test')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/campaigns', key: 'campaign-create-test' })
    expect(calls[0]?.data).not.toHaveProperty('channelId')
    expect(calls[0]?.data).not.toHaveProperty('businessOwnerUserId')
    expect(calls[1]).toMatchObject({ method: 'post', url: '/campaigns/81/batches', key: 'batch-create-test' })
    expect(calls[1]?.data).toMatchObject({ batchCode: 'WAVE-01', batchName: '第一批', status: 'DRAFT' })
  })

  it('编辑活动与批次携带乐观锁和合法状态，编码不作为历史覆盖字段提交', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await updateCampaign(81, {
      campaignName: '更名后的活动',
      campaignType: 'FLASH',
      startsAt: null,
      endsAt: null,
      status: 'INACTIVE',
      version: 3,
    }, 'campaign-update-test')
    await updateCampaignBatch(81, 901, {
      batchName: '更名后的批次',
      startsAt: null,
      endsAt: null,
      status: 'ARCHIVED',
      version: 2,
    }, 'batch-update-test')

    expect(calls[0]).toMatchObject({ method: 'patch', url: '/campaigns/81', key: 'campaign-update-test' })
    expect(calls[0]?.data).toMatchObject({ version: 3, status: 'INACTIVE' })
    expect(calls[0]?.data).not.toHaveProperty('campaignCode')
    expect(calls[1]).toMatchObject({ method: 'patch', url: '/campaigns/81/batches/901', key: 'batch-update-test' })
    expect(calls[1]?.data).toMatchObject({ version: 2, batchName: '更名后的批次', status: 'ARCHIVED' })
    expect(calls[1]?.data).not.toHaveProperty('batchCode')
  })
})
