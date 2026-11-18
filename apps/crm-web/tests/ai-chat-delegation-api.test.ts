import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '../src/api/http'
import {
  getAiDelegationRuns,
  setAiDelegationCustomerControl,
  setAiDelegationEmergencyStop,
  updateAiDelegationPolicy,
} from '../src/api/aiChatDelegation'

vi.mock('../src/api/http', () => ({
  http: { get: vi.fn(), put: vi.fn(), post: vi.fn() },
}))

beforeEach(() => {
  vi.mocked(http.get).mockReset()
  vi.mocked(http.put).mockReset()
  vi.mocked(http.post).mockReset()
})

describe('AI 聊天托管 API', () => {
  it('所有写操作带幂等键，配置更新携带乐观锁版本与审计理由', async () => {
    vi.mocked(http.put).mockResolvedValue({ data: { data: { version: 4 } } })
    vi.mocked(http.post).mockResolvedValue({ data: { data: { version: 5 } } })
    const body = {
      enabled: true,
      minIntervalSeconds: 60,
      dailyLimit: 10,
      allowedStart: '09:00',
      allowedEnd: '21:00',
      zoneId: 'Asia/Shanghai',
      blockedTerms: ['验证码'],
      scopes: [{ scopeType: 'STAFF' as const, scopeId: 8, enabled: true }],
      version: 3,
      reason: '灰度开启',
    }

    await updateAiDelegationPolicy(body, 'policy-key')
    await setAiDelegationEmergencyStop({ stopped: true, version: 4, reason: '紧急停用' }, 'stop-key')

    expect(http.put).toHaveBeenCalledWith('/ai/chat-delegation/policy', body, {
      headers: { 'Idempotency-Key': 'policy-key' },
    })
    expect(http.post).toHaveBeenCalledWith('/ai/chat-delegation/emergency-stop',
      { stopped: true, version: 4, reason: '紧急停用' }, { headers: { 'Idempotency-Key': 'stop-key' } })
  })

  it('人工接管按客户和数据范围调用，不发送聊天正文', async () => {
    vi.mocked(http.put).mockResolvedValue({ data: { data: { mode: 'HUMAN_TAKEOVER' } } })
    vi.mocked(http.get).mockResolvedValue({ data: { data: { items: [], total: 0, page: 1, size: 20 } } })

    await setAiDelegationCustomerControl(91, {
      mode: 'HUMAN_TAKEOVER', version: 2, reason: '客户要求人工服务',
    }, 'takeover-key')
    await getAiDelegationRuns({ customerId: 91, status: 'UNKNOWN', page: 1, size: 20 })

    expect(http.put).toHaveBeenCalledWith('/ai/chat-delegation/customers/91/control', {
      mode: 'HUMAN_TAKEOVER', version: 2, reason: '客户要求人工服务',
    }, { headers: { 'Idempotency-Key': 'takeover-key' } })
    expect(http.get).toHaveBeenCalledWith('/ai/chat-delegation/runs', {
      params: { customerId: 91, status: 'UNKNOWN', page: 1, size: 20 },
    })
  })
})
