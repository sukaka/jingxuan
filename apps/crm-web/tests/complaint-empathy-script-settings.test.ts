import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createComplaintEmpathyScript,
  listComplaintEmpathyScripts,
  updateComplaintEmpathyScript,
} from '../src/api/complaint'
import type { ComplaintEmpathyScript } from '../src/api/complaint'
import ComplaintEmpathyScriptSettings from '../src/components/ComplaintEmpathyScriptSettings.vue'

vi.mock('../src/api/complaint', () => ({
  listComplaintEmpathyScripts: vi.fn(),
  createComplaintEmpathyScript: vi.fn(),
  updateComplaintEmpathyScript: vi.fn(),
  disableComplaintEmpathyScript: vi.fn(),
}))

vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: () => true }),
}))

const scripts: ComplaintEmpathyScript[] = [
  {
    id: 1,
    title: '首次响应',
    script: '我们理解您的感受，会由专人核实。',
    status: 'ACTIVE',
    createdBy: 1,
    createdAt: '2026-11-13T01:00:00Z',
    updatedAt: '2026-11-13T01:00:00Z',
    version: 0,
  },
  {
    id: 2,
    title: '进度跟进',
    script: '感谢您的耐心等待，我们会持续同步处理进度。',
    status: 'ACTIVE',
    createdBy: 1,
    createdAt: '2026-11-13T01:01:00Z',
    updatedAt: '2026-11-13T01:01:00Z',
    version: 1,
  },
]

describe('人工共情话术库', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listComplaintEmpathyScripts).mockResolvedValue(scripts)
    vi.mocked(createComplaintEmpathyScript).mockResolvedValue({ ...scripts[0]!, id: 3, title: '处理完成' })
    vi.mocked(updateComplaintEmpathyScript).mockResolvedValue({ ...scripts[1]!, version: 2 })
  })

  it('同时展示多条话术并新增独立场景模板', async () => {
    const wrapper = mount(ComplaintEmpathyScriptSettings)
    await flushPromises()

    expect((wrapper.get('[data-testid="script-title-1"]').element as HTMLInputElement).value).toBe('首次响应')
    expect((wrapper.get('[data-testid="script-title-2"]').element as HTMLInputElement).value).toBe('进度跟进')
    await wrapper.get('[data-testid="script-create-open"]').trigger('click')
    await wrapper.get('[data-testid="script-create-title"]').setValue('处理完成')
    await wrapper.get('[data-testid="script-create-content"]').setValue('问题已核实并处理完成，感谢您的反馈。')
    await wrapper.get('[data-testid="script-create-reason"]').setValue('补充处理完成场景')
    await wrapper.get('[data-testid="script-create-form"]').trigger('submit')

    expect(createComplaintEmpathyScript).toHaveBeenCalledWith({
      title: '处理完成',
      script: '问题已核实并处理完成，感谢您的反馈。',
      reason: '补充处理完成场景',
    }, expect.any(String))
  })

  it('修改话术使用该行版本和理由，不改写其他话术', async () => {
    const wrapper = mount(ComplaintEmpathyScriptSettings)
    await flushPromises()

    await wrapper.get('[data-testid="script-reason-2"]').setValue('优化进度说明')
    await wrapper.get('[data-testid="script-save-2"]').trigger('click')

    expect(updateComplaintEmpathyScript).toHaveBeenCalledWith(2, {
      title: '进度跟进',
      script: '感谢您的耐心等待，我们会持续同步处理进度。',
      version: 1,
      reason: '优化进度说明',
    }, expect.any(String))
  })
})
