import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  disableComplaintKeyword,
  getComplaintPolicy,
  listComplaintEmpathyScripts,
  listComplaintKeywords,
  updateComplaintPolicy,
} from '../src/api/complaint'
import type { ComplaintKeyword, ComplaintPolicy } from '../src/api/complaint'
import ComplaintAutomationSettingsView from '../src/views/ComplaintAutomationSettingsView.vue'

vi.mock('../src/api/complaint', () => ({
  getComplaintPolicy: vi.fn(),
  updateComplaintPolicy: vi.fn(),
  listComplaintKeywords: vi.fn(),
  createComplaintKeyword: vi.fn(),
  updateComplaintKeyword: vi.fn(),
  disableComplaintKeyword: vi.fn(),
  listComplaintEmpathyScripts: vi.fn(),
  createComplaintEmpathyScript: vi.fn(),
  updateComplaintEmpathyScript: vi.fn(),
  disableComplaintEmpathyScript: vi.fn(),
}))

let allowed = true
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (code: string) => code === 'complaint:config' && allowed }),
}))

const wrappers: VueWrapper[] = []
const policy: ComplaintPolicy = {
  id: 1,
  enabled: true,
  fallbackRecipientUserId: 4,
  empathyScript: '我们理解您的感受，会由专人核实。',
  createdAt: '2026-11-11T01:00:00Z',
  updatedAt: '2026-11-11T01:00:00Z',
  version: 3,
}
const keyword: ComplaintKeyword = {
  id: 7,
  keyword: '投诉',
  status: 'ACTIVE',
  createdBy: 1,
  createdAt: '2026-11-11T01:00:00Z',
  updatedAt: '2026-11-11T01:00:00Z',
  version: 1,
}

async function mountView() {
  const wrapper = mount(ComplaintAutomationSettingsView)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  allowed = true
  vi.clearAllMocks()
  vi.mocked(getComplaintPolicy).mockResolvedValue(policy)
  vi.mocked(listComplaintKeywords).mockResolvedValue([keyword])
  vi.mocked(listComplaintEmpathyScripts).mockResolvedValue([])
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
})

describe('ComplaintAutomationSettingsView 客诉自动化配置', () => {
  it('明确规则检测不依赖 AI，策略保存携带当前 version 与理由', async () => {
    vi.mocked(updateComplaintPolicy).mockResolvedValue({ ...policy, version: 4 })
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('AI 停用不影响关键词检测')
    expect(wrapper.text()).toContain('共情话术仅供人工复制')
    await wrapper.get('[data-testid="policy-reason"]').setValue('调整主管配置')
    await wrapper.get('[data-testid="policy-save"]').trigger('click')
    await flushPromises()

    expect(updateComplaintPolicy).toHaveBeenCalledWith({
      enabled: true,
      fallbackRecipientUserId: 4,
      empathyScript: '我们理解您的感受，会由专人核实。',
      version: 3,
      reason: '调整主管配置',
    }, expect.any(String))
  })

  it('停用关键词必须填写理由并使用列表行版本，不停用历史命中', async () => {
    vi.mocked(disableComplaintKeyword).mockResolvedValue({ ...keyword, status: 'DISABLED', version: 2 })
    const wrapper = await mountView()

    await wrapper.get('[data-testid="disable-keyword-7"]').trigger('click')
    expect(disableComplaintKeyword).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('请填写停用理由')
    await wrapper.get('[data-testid="keyword-reason-7"]').setValue('不再用于新检测')
    await wrapper.get('[data-testid="disable-keyword-7"]').trigger('click')
    await flushPromises()

    expect(disableComplaintKeyword).toHaveBeenCalledWith(7, { version: 1, reason: '不再用于新检测' }, expect.any(String))
    expect(wrapper.text()).toContain('历史命中与正式单不受影响')
  })

  it('无配置权限时不读取策略或关键词', async () => {
    allowed = false
    const wrapper = await mountView()

    expect(wrapper.get('[data-testid="complaint-config-denied"]').text()).toContain('没有客诉配置权限')
    expect(getComplaintPolicy).not.toHaveBeenCalled()
    expect(listComplaintKeywords).not.toHaveBeenCalled()
  })
})
