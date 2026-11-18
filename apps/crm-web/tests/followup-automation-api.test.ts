import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createMonthlyDispatch,
  executeMonthlyDispatch,
  getFollowupAutomationPolicy,
  previewMonthlyDispatch,
  retryMonthlyDispatch,
  updateFollowupAutomationPolicy,
} from '../src/api/followup-automation'
import { createFollowup } from '../src/api/followup'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

interface CapturedCall {
  method: string
  url: string
  data: unknown
  params: Record<string, unknown>
  headers: Record<string, unknown>
}

function captureAdapter(responseData: unknown) {
  const calls: CapturedCall[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: (config.method ?? 'get').toLowerCase(),
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: (config.params ?? {}) as Record<string, unknown>,
      headers: config.headers.toJSON() as Record<string, unknown>,
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'req-followup-auto', timestamp: '' },
    }
  }
  return { adapter, calls }
}

afterEach(() => { http.defaults.adapter = originalAdapter })

describe('跟进自动化 API 契约', () => {
  it('策略 GET/PUT 携带 version 与幂等键，不接受 tenantId', async () => {
    const policy = { defaultIntervalDays: 14, intentOverrides: [], version: 2 }
    const { adapter, calls } = captureAdapter(policy)
    http.defaults.adapter = adapter

    await expect(getFollowupAutomationPolicy()).resolves.toEqual(policy)
    await updateFollowupAutomationPolicy({ defaultIntervalDays: 14, intentOverrides: [{ intentLevel: 'HIGH', intervalDays: 3 }], version: 2 }, 'policy-key')

    expect(calls[0]).toMatchObject({ method: 'get', url: '/followup-automation/policy' })
    expect(calls[1]).toMatchObject({ method: 'put', url: '/followup-automation/policy' })
    expect(calls[1]?.headers['Idempotency-Key']).toBe('policy-key')
    expect(calls[1]?.data).not.toHaveProperty('tenantId')
  })

  it('创建跟进使用调用方稳定的幂等键', async () => {
    const { adapter, calls } = captureAdapter({ id: 8 })
    http.defaults.adapter = adapter

    await createFollowup({ customerId: 3, channelCode: 'WECOM' }, 'followup-create-key')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/followups' })
    expect(calls[0]?.headers['Idempotency-Key']).toBe('followup-create-key')
  })

  it('月度批次预览、创建、执行和重试使用独立资源与写版本', async () => {
    const { adapter, calls } = captureAdapter({ id: 9, version: 1, items: [] })
    http.defaults.adapter = adapter

    await previewMonthlyDispatch({ orderMonth: '2026-08', page: 2, size: 20 })
    await createMonthlyDispatch({ orderMonth: '2026-08' }, 'batch-create-key')
    await executeMonthlyDispatch(9, 0, 'batch-execute-key')
    await retryMonthlyDispatch(9, 1, 'batch-retry-key')

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['get', '/followup-automation/batches/preview'],
      ['post', '/followup-automation/batches'],
      ['post', '/followup-automation/batches/9/execute'],
      ['post', '/followup-automation/batches/9/retry'],
    ])
    expect(calls[0]?.params).toEqual({ orderMonth: '2026-08', page: 2, size: 20 })
    expect(calls[1]?.headers['Idempotency-Key']).toBe('batch-create-key')
    expect(calls[2]?.data).toEqual({ version: 0 })
    expect(calls[3]?.data).toEqual({ version: 1 })
  })
})
