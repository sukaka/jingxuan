import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  confirmComplaintHit,
  createComplaintEmpathyScript,
  createComplaintKeyword,
  dismissComplaint,
  updateComplaintPolicy,
} from '../src/api/complaint'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

function capture(responseData: unknown) {
  const calls: Array<Record<string, unknown>> = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method,
      url: config.url,
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      headers: config.headers.toJSON(),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'req-complaint', timestamp: '' },
    }
  }
  return { adapter, calls }
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
})

describe('客诉 API 写契约', () => {
  it('关键词创建携带 CSRF 与幂等键，不接受租户参数', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=complaint-csrf`
    const { adapter, calls } = capture({ id: 7 })
    http.defaults.adapter = adapter

    await createComplaintKeyword({ keyword: '投诉' }, 'keyword-create-7')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/complaint-automation/keywords', data: { keyword: '投诉' } })
    expect(calls[0]?.headers).toMatchObject({ 'Idempotency-Key': 'keyword-create-7', [CSRF_HEADER_NAME]: 'complaint-csrf' })
    expect(calls[0]?.data).not.toHaveProperty('tenantId')
  })

  it('策略修改携带 version、理由与幂等键', async () => {
    const { adapter, calls } = capture({ id: 1, version: 4 })
    http.defaults.adapter = adapter

    await updateComplaintPolicy({
      enabled: true,
      fallbackRecipientUserId: 4,
      empathyScript: '我们理解您的感受，会由专人核实。',
      version: 3,
      reason: '调整兜底主管',
    }, 'policy-key')

    expect(calls[0]).toMatchObject({
      method: 'patch', url: '/complaint-automation/settings',
      data: { enabled: true, fallbackRecipientUserId: 4, version: 3, reason: '调整兜底主管' },
      headers: { 'Idempotency-Key': 'policy-key' },
    })
  })

  it('新增场景话术使用独立资源并携带理由、CSRF 与幂等键', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=complaint-csrf`
    const { adapter, calls } = capture({ id: 2, title: '进度跟进', version: 0 })
    http.defaults.adapter = adapter

    await createComplaintEmpathyScript({
      title: '进度跟进', script: '我们会持续同步处理进度。', reason: '补充跟进场景',
    }, 'empathy-create-2')

    expect(calls[0]).toMatchObject({
      method: 'post',
      url: '/complaint-automation/empathy-scripts',
      data: { title: '进度跟进', script: '我们会持续同步处理进度。', reason: '补充跟进场景' },
      headers: { 'Idempotency-Key': 'empathy-create-2', [CSRF_HEADER_NAME]: 'complaint-csrf' },
    })
    expect(calls[0]?.data).not.toHaveProperty('tenantId')
  })

  it('命中确认与正式单驳回分别使用独立资源、版本和理由', async () => {
    const { adapter, calls } = capture({})
    http.defaults.adapter = adapter

    await confirmComplaintHit(11, { version: 2, reason: '人工核实为正式客诉' }, 'confirm-key')
    await dismissComplaint(19, { version: 5, reason: '复核后驳回' }, 'dismiss-key')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/complaint-hits/11/confirm', data: { version: 2 } })
    expect(calls[1]).toMatchObject({ method: 'post', url: '/complaints/19/dismiss', data: { version: 5 } })
    expect(calls[0]?.headers).toMatchObject({ 'Idempotency-Key': 'confirm-key' })
    expect(calls[1]?.headers).toMatchObject({ 'Idempotency-Key': 'dismiss-key' })
  })
})
