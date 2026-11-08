import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { confirmAiJob, createAiJob, getAiJob, getAiQualitySummary, listAiJobs } from '../src/api/ai'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture() {
  const calls: { method: string; url: string; data: unknown; params: unknown; key: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({ method: config.method ?? 'get', url: config.url ?? '', data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data, params: config.params, key: config.headers?.get('Idempotency-Key') })
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: {}, requestId: 'ai-test', timestamp: '' } }
  }
  return { adapter, calls }
}

describe('P1-3 AI API 契约', () => {
  it('任务创建、查询和人工确认使用幂等键与版本', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter
    await createAiJob([1, 2], 'create-key')
    await listAiJobs({ conversationId: 'CONV', status: 'PROPOSED', page: 1, size: 20 })
    await getAiJob(9)
    await confirmAiJob(9, { version: 3, decision: 'CONFIRM', title: '人工回访', description: '核实后处理' }, 'confirm-key')
    await getAiQualitySummary()
    expect(calls[0]).toMatchObject({ method: 'post', url: '/ai/jobs', data: { messageIds: [1, 2] }, key: 'create-key' })
    expect(calls[1]).toMatchObject({ method: 'get', url: '/ai/jobs', params: { conversationId: 'CONV', status: 'PROPOSED', page: 1, size: 20 } })
    expect(calls[2]).toMatchObject({ method: 'get', url: '/ai/jobs/9' })
    expect(calls[3]).toMatchObject({ method: 'post', url: '/ai/jobs/9/confirmation', key: 'confirm-key' })
    expect(calls[3].data).toMatchObject({ version: 3, decision: 'CONFIRM', title: '人工回访' })
    expect(calls[4]).toMatchObject({ method: 'get', url: '/ai/quality/summary' })
  })
})
