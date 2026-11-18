import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listInstitutionProfiles } from '../src/api/institution'
import {
  createQuickReply,
  createQuickReplyCategory,
  disableQuickReply,
  disableQuickReplyCategory,
  getQuickReplyStatistics,
  listQuickReplyCategories,
  listQuickReplies,
  listQuickReplyVariables,
  previewQuickReply,
  updateQuickReply,
  updateQuickReplyCategory,
} from '../src/api/quickReply'
import type { QuickReplyCategory, QuickReplyTemplate } from '../src/api/quickReply'
import { routes } from '../src/router'
import QuickReplyAdminView from '../src/views/QuickReplyAdminView.vue'

vi.mock('../src/api/institution', () => ({ listInstitutionProfiles: vi.fn() }))
vi.mock('../src/api/quickReply', () => ({
  createQuickReply: vi.fn(), createQuickReplyCategory: vi.fn(), disableQuickReply: vi.fn(),
  disableQuickReplyCategory: vi.fn(), getQuickReplyStatistics: vi.fn(), listQuickReplyCategories: vi.fn(),
  listQuickReplies: vi.fn(), listQuickReplyVariables: vi.fn(), previewQuickReply: vi.fn(),
  updateQuickReply: vi.fn(), updateQuickReplyCategory: vi.fn(),
}))

let allowed = true
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: () => allowed }),
}))

const category: QuickReplyCategory = {
  id: 5, parentId: null, name: '项目咨询', code: 'PROJECT', scopeType: 'GLOBAL', scopeId: null,
  sortNo: 1, status: 'ACTIVE', createdBy: 1, createdAt: '', updatedAt: '', version: 0,
}
const template: QuickReplyTemplate = {
  id: 1, categoryId: 5, name: '剩余次数说明', code: 'PROJECT_REMAINING', messageType: 'TEXT',
  content: '还剩{remaining_count}次', scene: 'PACKAGE_REMAINING', scopeType: 'GLOBAL', scopeId: null,
  allowEdit: true, requirePreview: true, aiEnabled: true, featured: false, favorite: false, lastUsedAt: null,
  usageCount: 4, status: 'ACTIVE', sortNo: 1, createdBy: 1, createdAt: '', updatedAt: '', version: 0,
}
const wrappers: VueWrapper[] = []

async function mountView() {
  const wrapper = mount(QuickReplyAdminView)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('快捷话术后台管理', () => {
  beforeEach(() => {
    allowed = true
    vi.clearAllMocks()
    vi.mocked(listQuickReplyCategories).mockResolvedValue([category])
    vi.mocked(listQuickReplies).mockResolvedValue([template])
    vi.mocked(listQuickReplyVariables).mockResolvedValue([
      { variableCode: 'customer_name', variableName: '客户称呼', dataSource: 'CRM_CUSTOMER', dataType: 'STRING', required: true, description: '客户展示称呼' },
      { variableCode: 'remaining_count', variableName: '剩余次数', dataSource: 'CRM_ENTITLEMENT', dataType: 'NUMBER', required: true, description: '剩余服务次数' },
    ])
    vi.mocked(listInstitutionProfiles).mockResolvedValue({ items: [{ id: 7, fullName: '测试机构', shortName: '测试机构' }], total: 1, page: 1, size: 100 } as never)
    vi.mocked(createQuickReply).mockResolvedValue(template)
    vi.mocked(updateQuickReply).mockResolvedValue({ ...template, version: 1 })
    vi.mocked(disableQuickReply).mockResolvedValue({ ...template, status: 'DISABLED', version: 1 })
    vi.mocked(createQuickReplyCategory).mockResolvedValue(category)
    vi.mocked(updateQuickReplyCategory).mockResolvedValue({ ...category, version: 1 })
    vi.mocked(disableQuickReplyCategory).mockResolvedValue({ ...category, status: 'DISABLED', version: 1 })
    vi.mocked(previewQuickReply).mockResolvedValue({ templateId: 1, content: '测试客户您好，还剩6次', variables: { remaining_count: 6 }, missingVariables: [], canSend: true, allowEdit: true })
    vi.mocked(getQuickReplyStatistics).mockResolvedValue([{
      templateId: 1, templateName: template.name, templateCode: template.code, totalCount: 5, copiedCount: 2,
      successCount: 2, failedCount: 1, unknownCount: 0, editedCount: 1, employeeCount: 1, successRate: 66.67,
      lastUsedAt: '2026-11-13T01:00:00Z',
    }])
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('无管理权限时不读取配置，路由与菜单使用 manage 权限', async () => {
    allowed = false
    const wrapper = await mountView()
    expect(wrapper.get('[data-testid="quick-reply-admin-denied"]').text()).toContain('没有话术管理权限')
    expect(listQuickReplies).not.toHaveBeenCalled()
    const route = routes.find((item) => item.name === 'quick-reply-settings')
    expect(route?.path).toBe('/settings/quick-replies')
    expect(route?.meta?.permission).toBe('quickreply:manage')
  })

  it('新增话术继承分类作用域，不传版本并携带使用策略和审计理由', async () => {
    const wrapper = await mountView()
    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await wrapper.get('[data-testid="template-name"]').setValue('术后关怀')
    await wrapper.get('[data-testid="template-code"]').setValue('POSTOPERATIVE_CARE')
    await wrapper.get('[data-testid="template-scene"]').setValue('POSTOPERATIVE')
    await wrapper.get('[data-testid="template-content"]').setValue('{customer_name}您好，想了解您的恢复感受。')
    await wrapper.get('[data-testid="template-ai"]').setValue(true)
    await wrapper.get('[data-testid="template-featured"]').setValue(true)
    await wrapper.get('[data-testid="template-reason"]').setValue('新增术后标准表达')
    await wrapper.get('[data-testid="template-form"]').trigger('submit')
    await flushPromises()

    expect(createQuickReply).toHaveBeenCalledWith(expect.objectContaining({
      categoryId: 5, name: '术后关怀', code: 'POSTOPERATIVE_CARE', scene: 'POSTOPERATIVE',
      content: '{customer_name}您好，想了解您的恢复感受。', scopeType: 'GLOBAL', scopeId: null,
      messageType: 'TEXT', aiEnabled: true, featured: true, reason: '新增术后标准表达',
    }), expect.any(String))
    expect(vi.mocked(createQuickReply).mock.calls[0]?.[0]).not.toHaveProperty('version')
    expect(wrapper.text()).toContain('话术已保存')
  })

  it('编辑编码只读，停用携带当前版本且不做物理删除', async () => {
    const wrapper = await mountView()
    await wrapper.get('[data-testid="edit-template-1"]').trigger('click')
    expect(wrapper.get('[data-testid="template-code"]').attributes('readonly')).toBeDefined()
    await wrapper.get('[data-testid="template-reason"]').setValue('下线过时表达')
    await wrapper.get('[data-testid="disable-template"]').trigger('click')
    await flushPromises()

    expect(disableQuickReply).toHaveBeenCalledWith(1, { version: 0, reason: '下线过时表达' }, expect.any(String))
    expect(updateQuickReply).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('历史使用日志与 AI 候选保留')
  })

  it('分类支持指定真实机构并携带幂等键', async () => {
    const wrapper = await mountView()
    await wrapper.get('[data-testid="categories-tab"]').trigger('click')
    await wrapper.get('[data-testid="new-category"]').trigger('click')
    await wrapper.get('[data-testid="category-name"]').setValue('机构回访')
    await wrapper.get('[data-testid="category-code"]').setValue('INSTITUTION_FOLLOWUP')
    await wrapper.get('[data-testid="category-scope"]').setValue('INSTITUTION')
    await wrapper.get('[data-testid="category-reason"]').setValue('按机构配置回访话术')
    await wrapper.get('[data-testid="category-form"]').trigger('submit')
    await flushPromises()

    expect(createQuickReplyCategory).toHaveBeenCalledWith(expect.objectContaining({
      name: '机构回访', code: 'INSTITUTION_FOLLOWUP', scopeType: 'INSTITUTION', scopeId: 7,
      parentId: null, reason: '按机构配置回访话术',
    }), expect.any(String))
    expect(vi.mocked(createQuickReplyCategory).mock.calls[0]?.[0]).not.toHaveProperty('version')
  })

  it('真实客户预览展示服务端事实和缺失阻断', async () => {
    vi.mocked(previewQuickReply).mockResolvedValue({ templateId: 1, content: '还剩次', variables: {}, missingVariables: ['remaining_count'], canSend: false, allowEdit: true })
    const wrapper = await mountView()
    await wrapper.get('[data-testid="edit-template-1"]').trigger('click')
    await wrapper.get('[data-testid="preview-customer"]').setValue(1010)
    await wrapper.get('[data-testid="preview-template"]').trigger('click')
    await flushPromises()

    expect(previewQuickReply).toHaveBeenCalledWith(1, 1010, 'PACKAGE_REMAINING')
    expect(wrapper.get('[data-testid="template-preview"]').text()).toContain('缺少必填变量')
    expect(wrapper.get('[data-testid="template-preview"]').text()).toContain('剩余次数')
  })

  it('使用统计按日期查询并区分复制与 SDK 成功率', async () => {
    const wrapper = await mountView()
    await wrapper.get('[data-testid="statistics-tab"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('66.67%')
    expect(wrapper.text()).toContain('失败 / 未知')
    await wrapper.get('[data-testid="statistics-from"]').setValue('2026-11-01')
    await wrapper.get('[data-testid="statistics-to"]').setValue('2026-11-13')
    await wrapper.get('.statistics-filter').trigger('submit')
    await flushPromises()

    expect(getQuickReplyStatistics).toHaveBeenLastCalledWith({ from: '2026-11-01', to: '2026-11-13' })
    expect(getQuickReplyStatistics).toHaveBeenCalledTimes(2)
  })
})
