import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getYouzanSyncPolicy, saveYouzanSyncPolicy } from '../src/api/youzan'
import { getWecomSyncPolicy, saveWecomSyncPolicy } from '../src/api/wecom'
import YouzanSyncPolicyPanel from '../src/views/youzan/YouzanSyncPolicyPanel.vue'
import WecomSyncPolicyPanel from '../src/views/wecom/WecomSyncPolicyPanel.vue'

vi.mock('../src/api/youzan', () => ({ getYouzanSyncPolicy: vi.fn(), saveYouzanSyncPolicy: vi.fn() }))
vi.mock('../src/api/wecom', () => ({ getWecomSyncPolicy: vi.fn(), saveWecomSyncPolicy: vi.fn() }))

const youzanPolicy = {
  id: 1, shopId: '123', initialStartTime: '2026-01-01T00:00:00Z', intervalSeconds: 300,
  overlapSeconds: 300, pageSize: 50, enabled: true, nextRunAt: '2026-11-01T07:00:00Z', lastScheduledAt: null, version: 0,
}
const wecomPolicy = {
  id: 1, tenantId: 1, corpId: 'corp-1', enabled: true, contactSyncEnabled: true, archiveSyncEnabled: true,
  intervalSeconds: 300, nextRunAt: '2026-11-01T07:00:00Z', lastScheduledAt: null, lastStatus: 'NEVER' as const, lastError: null, version: 0,
}

async function setNextRun(wrapper: ReturnType<typeof mount>, value: string) {
  const picker = wrapper.findAllComponents({ name: 'ElDatePicker' }).at(-1)
  if (!picker) throw new Error('日期选择器不存在')
  picker.vm.$emit('update:modelValue', new Date(`${value}:00Z`))
  await flushPromises()
}

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('可编辑定时同步策略', () => {
  it('有赞页面提交修改后的下次执行时间', async () => {
    vi.mocked(getYouzanSyncPolicy).mockResolvedValue(youzanPolicy)
    vi.mocked(saveYouzanSyncPolicy).mockResolvedValue({ ...youzanPolicy, nextRunAt: '2026-11-01T08:00:00.000Z' })
    const wrapper = mount(YouzanSyncPolicyPanel, { props: { shopId: '123', canWrite: true }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    await setNextRun(wrapper, '2026-11-01T08:00')
    await wrapper.get('[data-testid="youzan-policy-save"]').trigger('click')
    await flushPromises()

    expect(saveYouzanSyncPolicy).toHaveBeenCalledWith('123', expect.objectContaining({
      enabled: true, nextRunAt: '2026-11-01T08:00:00.000Z',
    }), expect.any(String))
    wrapper.unmount()
  })

  it('企微页面分别保存两类开关和修改后的时间', async () => {
    vi.mocked(getWecomSyncPolicy).mockResolvedValue(wecomPolicy)
    vi.mocked(saveWecomSyncPolicy).mockResolvedValue({ ...wecomPolicy, nextRunAt: '2026-11-01T09:30:00.000Z' })
    const wrapper = mount(WecomSyncPolicyPanel, { props: { corpId: 'corp-1', canWrite: true }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    await setNextRun(wrapper, '2026-11-01T09:30')
    await wrapper.get('[data-testid="wecom-policy-save"]').trigger('click')
    await flushPromises()

    expect(saveWecomSyncPolicy).toHaveBeenCalledWith('corp-1', expect.objectContaining({
      enabled: true, contactSyncEnabled: true, archiveSyncEnabled: true, nextRunAt: '2026-11-01T09:30:00.000Z',
    }), expect.any(String))
    wrapper.unmount()
  })
})
