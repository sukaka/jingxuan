import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getAiDelegationPolicy,
  getAiDelegationRuns,
  getAiDelegationStatus,
  setAiDelegationCustomerControl,
  updateAiDelegationPolicy,
} from '../src/api/aiChatDelegation'
import type { AiDelegationPolicy } from '../src/api/aiChatDelegation'
import { useAuthStore } from '../src/stores/auth'
import AiChatDelegationView from '../src/views/AiChatDelegationView.vue'

vi.mock('../src/api/aiChatDelegation', () => ({
  getAiDelegationPolicy: vi.fn(),
  getAiDelegationStatus: vi.fn(),
  getAiDelegationRuns: vi.fn(),
  getAiDelegationCustomerControl: vi.fn(),
  updateAiDelegationPolicy: vi.fn(),
  setAiDelegationEmergencyStop: vi.fn(),
  setAiDelegationCustomerControl: vi.fn(),
}))

const policy: AiDelegationPolicy = {
  enabled: true,
  emergencyStopped: false,
  minIntervalSeconds: 60,
  dailyLimit: 10,
  allowedStart: '09:00',
  allowedEnd: '21:00',
  zoneId: 'Asia/Shanghai',
  blockedTerms: ['验证码'],
  scopes: [
    { scopeType: 'TEAM', scopeId: 3, enabled: true },
    { scopeType: 'STAFF', scopeId: 8, enabled: true },
  ],
  version: 2,
}
const status = {
  enabled: true,
  emergencyStopped: false,
  workerEnabled: true,
  eventConsumerEnabled: true,
  senderMode: 'MOCK',
  realOfficialEnabled: false,
  counts: { MOCKED: 1, UNKNOWN: 1 },
}
const runs = {
  items: [
    {
      id: 11, sourceMessageId: 91, customerId: 101, staffUserId: 8, teamId: 3,
      status: 'MOCKED', reasonMasked: '模拟完成，未发送给客户', intent: 'GENERAL',
      aiProvider: 'mock', sendProvider: 'MOCK', providerMessageId: null,
      generationAttempts: 1, sendAttempts: 1, nextAttemptAt: null,
      createdAt: '2026-01-01T01:00:00Z', updatedAt: '2026-01-01T01:00:01Z', version: 3,
    },
    {
      id: 12, sourceMessageId: 92, customerId: 102, staffUserId: 8, teamId: 3,
      status: 'UNKNOWN', reasonMasked: 'WECOM_RESPONSE_UNKNOWN', intent: 'GENERAL',
      aiProvider: 'mock', sendProvider: 'OFFICIAL', providerMessageId: null,
      generationAttempts: 1, sendAttempts: 1, nextAttemptAt: null,
      createdAt: '2026-01-01T01:00:00Z', updatedAt: '2026-01-01T01:00:01Z', version: 3,
    },
  ], total: 2, page: 1, size: 20,
}

beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().user = {
    userId: 1, username: 'admin', displayName: '管理员', userType: 'EMPLOYEE', roles: ['ADMIN'],
    permissions: ['ai:read', 'ai:write', 'ai:config'],
  }
  vi.mocked(getAiDelegationPolicy).mockReset().mockResolvedValue(policy)
  vi.mocked(getAiDelegationStatus).mockReset().mockResolvedValue(status)
  vi.mocked(getAiDelegationRuns).mockReset().mockResolvedValue(runs)
  vi.mocked(updateAiDelegationPolicy).mockReset()
  vi.mocked(setAiDelegationCustomerControl).mockReset()
})

describe('AI 聊天自动托管页面', () => {
  it('Mock 与 UNKNOWN 均不显示伪成功，并展示自动重发禁令', async () => {
    const wrapper = mount(AiChatDelegationView)
    await flushPromises()

    expect(wrapper.text()).toContain('模拟模式：不会发送给客户')
    expect(wrapper.text()).toContain('模拟完成，未发送给客户')
    expect(wrapper.text()).toContain('结果未知，禁止自动重发')
    expect(wrapper.text()).toContain('普通咨询')
    expect(wrapper.text()).not.toContain('客户已收到')
  })

  it('保存三级开关、频控和时段时携带当前版本与理由', async () => {
    vi.mocked(updateAiDelegationPolicy).mockResolvedValue({ ...policy, version: 3 })
    const wrapper = mount(AiChatDelegationView)
    await flushPromises()

    await wrapper.get('[data-testid="policy-reason"]').setValue('灰度开启')
    await wrapper.get('[data-testid="save-policy"]').trigger('click')
    await flushPromises()

    expect(updateAiDelegationPolicy).toHaveBeenCalledWith(expect.objectContaining({
      enabled: true,
      scopes: policy.scopes,
      version: 2,
      reason: '灰度开启',
    }), expect.any(String))
  })

  it('配置保存失败后沿用同一幂等键重试', async () => {
    vi.mocked(updateAiDelegationPolicy)
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce({ ...policy, version: 3 })
    const wrapper = mount(AiChatDelegationView)
    await flushPromises()
    await wrapper.get('[data-testid="policy-reason"]').setValue('灰度开启')

    await wrapper.get('[data-testid="save-policy"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="save-policy"]').trigger('click')
    await flushPromises()

    expect(updateAiDelegationPolicy).toHaveBeenCalledTimes(2)
    const firstKey = vi.mocked(updateAiDelegationPolicy).mock.calls[0]?.[1]
    const secondKey = vi.mocked(updateAiDelegationPolicy).mock.calls[1]?.[1]
    expect(firstKey).toBe(secondKey)
  })

  it('人工接管需要客户 ID、当前版本和理由', async () => {
    vi.mocked(setAiDelegationCustomerControl).mockResolvedValue({
      customerId: 101, mode: 'HUMAN_TAKEOVER', reason: '客户要求人工服务', version: 3,
    })
    const wrapper = mount(AiChatDelegationView)
    await flushPromises()

    await wrapper.get('[data-testid="control-customer-id"]').setValue('101')
    await wrapper.get('[data-testid="control-version"]').setValue('2')
    await wrapper.get('[data-testid="control-reason"]').setValue('客户要求人工服务')
    await wrapper.get('[data-testid="human-takeover"]').trigger('click')
    await flushPromises()

    expect(setAiDelegationCustomerControl).toHaveBeenCalledWith(101, {
      mode: 'HUMAN_TAKEOVER', version: 2, reason: '客户要求人工服务',
    }, expect.any(String))
    expect(wrapper.text()).toContain('已切换人工接管；后续自动回复已停止')
  })
})
