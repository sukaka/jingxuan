import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createCustomer,
  listCustomers,
  updateCustomer,
  updateCustomerTags,
} from '../src/api/customer'
import type { CustomerDetail } from '../src/api/customer'
import { getDictionaryOptions } from '../src/api/dictionary'
import { createFollowup } from '../src/api/followup'
import { createAppointment, getAppointmentOptions } from '../src/api/fulfillment'
import { createTask } from '../src/api/task'
import { linkWecomSidebarContact } from '../src/api/wecomSidebar'
import WecomQuickActionsPanel from '../src/components/WecomQuickActionsPanel.vue'

vi.mock('../src/api/customer', () => ({
  listCustomers: vi.fn(),
  createCustomer: vi.fn(),
  updateCustomer: vi.fn(),
  updateCustomerTags: vi.fn(),
}))
vi.mock('../src/api/dictionary', () => ({ getDictionaryOptions: vi.fn() }))
vi.mock('../src/api/followup', () => ({ createFollowup: vi.fn() }))
vi.mock('../src/api/fulfillment', () => ({
  getAppointmentOptions: vi.fn(),
  createAppointment: vi.fn(),
}))
vi.mock('../src/api/task', () => ({ createTask: vi.fn() }))
vi.mock('../src/api/wecomSidebar', () => ({ linkWecomSidebarContact: vi.fn() }))

let allowedPermissions = new Set<string>()
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (code: string) => allowedPermissions.has(code),
  }),
}))

const sampleCustomer: CustomerDetail = {
  id: 1010,
  customerNo: 'CU1001',
  displayName: '王女士',
  nickname: null,
  mobileMasked: '138****1234',
  cityCode: '310000',
  customerStatus: 'ACTIVE',
  tierCode: 'S',
  intentLevel: 'MEDIUM',
  ownerStaffId: 2,
  ownerName: '张管家',
  teamId: 1,
  pointsCached: 0,
  lastFollowupAt: '2026-11-12T08:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
  version: 3,
  sourceCode: 'WECOM',
  channelCode: 'PRIVATE',
  spendingLevel: null,
  birthday: null,
  attentionProjects: null,
  attentionProjectIds: [],
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: '2026-01-01T00:00:00Z',
  firstOrderAt: '2026-02-01T00:00:00Z',
  tagCodes: ['VIP'],
  identities: [],
  deleted: false,
}

const wrappers: VueWrapper[] = []

function mountPanel(props: { customer: CustomerDetail | null; externalUserid: string }) {
  const wrapper = mount(WecomQuickActionsPanel, { props })
  wrappers.push(wrapper)
  return wrapper
}

describe('WecomQuickActionsPanel 快捷业务操作', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    allowedPermissions = new Set([
      'wecom:sidebar:view',
      'followup:write',
      'task:write',
      'customer:read',
      'customer:write',
      'appointment:read',
      'appointment:write',
    ])
    vi.mocked(getDictionaryOptions).mockImplementation(async (dictCode: string) => {
      if (dictCode === 'customerTags') {
        return [
          { code: 'VIP', label: '贵宾客户', sortNo: 1 },
          { code: 'HIGH_INTENT', label: '高意向', sortNo: 2 },
          { code: 'RISK', label: '风险预警', sortNo: 3 },
        ]
      }
      return [
        { code: 'WECOM', label: '企业微信', sortNo: 1 },
        { code: 'PHONE', label: '电话', sortNo: 2 },
      ]
    })
    vi.mocked(createFollowup).mockResolvedValue({} as never)
    vi.mocked(createTask).mockResolvedValue({} as never)
    vi.mocked(updateCustomer).mockResolvedValue({ ...sampleCustomer, intentLevel: 'HIGH', version: 4 })
    vi.mocked(updateCustomerTags).mockResolvedValue({ ...sampleCustomer, tagCodes: ['VIP', 'HIGH_INTENT'] })
    vi.mocked(getAppointmentOptions).mockResolvedValue({
      customerId: 1010,
      institutions: [{ id: 50, fullName: '上海静安美学机构', shortName: '静安机构' }],
      entitlements: [
        {
          id: 901,
          orderId: 88,
          projectId: 7,
          projectName: '光子嫩肤护理包',
          availableCount: 4,
          validFrom: '2026-01-01',
          validTo: '2027-01-01',
          institutionIds: [50],
          allocationStatus: 'ALLOCATED',
        },
      ],
    })
    vi.mocked(createAppointment).mockResolvedValue({} as never)
    vi.mocked(listCustomers).mockResolvedValue({
      items: [sampleCustomer],
      total: 1,
      page: 1,
      size: 10,
    })
    vi.mocked(createCustomer).mockResolvedValue({ ...sampleCustomer, id: 2020, customerNo: 'CU2020' })
    vi.mocked(linkWecomSidebarContact).mockResolvedValue({
      contactId: 12,
      corpId: 'ww-test',
      externalUserid: 'ext-unlinked-1',
      staffUserId: 2,
      customerId: 1010,
      linkType: 'MANUAL',
      linkReason: '企微侧边栏人工核对关联',
      alreadyLinked: false,
    })
  })

  afterEach(() => {
    while (wrappers.length) wrappers.pop()?.unmount()
  })

  it('可快速登记跟进并同步创建回访提醒任务', async () => {
    const wrapper = mountPanel({ customer: sampleCustomer, externalUserid: 'ext-1001' })

    await wrapper.get('[data-testid="quick-action-followup"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="quick-followup-summary"]').setValue('客户咨询下周复诊时间')
    await wrapper.get('[data-testid="quick-followup-next-at"]').setValue('2026-11-25T10:30')
    await wrapper.get('[data-testid="quick-followup-create-task"]').setValue(true)
    await wrapper.get('[data-testid="quick-task-title"]').setValue('确认复诊安排')
    await wrapper.get('[data-testid="quick-followup-form"]').trigger('submit')
    await flushPromises()

    expect(createFollowup).toHaveBeenCalledWith(
      expect.objectContaining({
        customerId: 1010,
        channelCode: 'WECOM',
        summary: '客户咨询下周复诊时间',
      }),
      expect.stringContaining('sidebar-followup-'),
    )
    expect(createTask).toHaveBeenCalledWith(
      expect.objectContaining({
        customerId: 1010,
        taskType: 'FOLLOWUP',
        priority: 'MEDIUM',
        title: '确认复诊安排',
      }),
    )
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('可快捷更新意向等级与标签（动态加载 customerTags 字典并携带乐观锁 version）', async () => {
    const wrapper = mountPanel({ customer: sampleCustomer, externalUserid: 'ext-1001' })

    await wrapper.get('[data-testid="quick-action-tags"]').trigger('click')
    await flushPromises()

    expect(getDictionaryOptions).toHaveBeenCalledWith('customerTags')
    expect(wrapper.get('[data-testid="quick-preset-tag-RISK"]').text()).toBe('风险预警')

    await wrapper.get('[data-testid="quick-intent-HIGH"]').trigger('click')
    await wrapper.get('[data-testid="quick-preset-tag-HIGH_INTENT"]').trigger('click')
    await wrapper.get('[data-testid="quick-preset-tag-RISK"]').trigger('click')
    await wrapper.get('[data-testid="quick-tags-form"]').trigger('submit')
    await flushPromises()

    expect(updateCustomer).toHaveBeenCalledWith(1010, { intentLevel: 'HIGH', version: 3 })
    expect(updateCustomerTags).toHaveBeenCalledWith(1010, ['VIP', 'HIGH_INTENT', 'RISK'])
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('customerTags 字典加载失败时优雅降级到默认标签选项', async () => {
    vi.mocked(getDictionaryOptions).mockRejectedValueOnce(new Error('network error'))
    const wrapper = mountPanel({ customer: sampleCustomer, externalUserid: 'ext-1001' })

    await wrapper.get('[data-testid="quick-action-tags"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="quick-preset-tag-VIP"]').text()).toBe('贵宾客户')
    expect(wrapper.get('[data-testid="quick-preset-tag-REFERRAL_GOOD"]').text()).toBe('优质转介绍')
  })

  it('可基于生效权益发起预约申请，绝不直接核销扣减次数', async () => {
    const wrapper = mountPanel({ customer: sampleCustomer, externalUserid: 'ext-1001' })

    await wrapper.get('[data-testid="quick-action-appointment"]').trigger('click')
    await flushPromises()

    expect(getAppointmentOptions).toHaveBeenCalledWith(1010)
    expect(wrapper.text()).toContain('不扣减权益次数')
    await wrapper.get('[data-testid="quick-appt-count"]').setValue(2)
    await wrapper.get('[data-testid="quick-appt-time"]').setValue('2026-11-28T14:00')
    await wrapper.get('[data-testid="quick-appt-remark"]').setValue('客户偏好下午场')
    await wrapper.get('[data-testid="quick-appointment-form"]').trigger('submit')
    await flushPromises()

    expect(createAppointment).toHaveBeenCalledWith(
      expect.objectContaining({
        customerId: 1010,
        institutionId: 50,
        remark: '客户偏好下午场',
        items: [{ entitlementId: 901, count: 2 }],
      }),
      expect.stringContaining('sidebar-appt-'),
    )
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('未关联客户支持搜索已有 CRM 客户并绑定', async () => {
    const wrapper = mountPanel({ customer: null, externalUserid: 'ext-unlinked-1' })

    await wrapper.get('[data-testid="unlinked-search-input"]').setValue('王女士')
    await wrapper.get('[data-testid="unlinked-search-btn"]').trigger('click')
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledWith({ page: 1, size: 10, keyword: '王女士' })
    await wrapper.get('[data-testid="link-customer-1010"]').trigger('click')
    await flushPromises()

    expect(linkWecomSidebarContact).toHaveBeenCalledWith({
      externalUserid: 'ext-unlinked-1',
      customerId: 1010,
      reason: '企微侧边栏人工核对关联',
    })
    expect(wrapper.emitted('linked')).toEqual([[1010]])
  })

  it('未关联客户支持新建 CRM 客户并绑定，且缺少 customer:write 时拒绝显示操作表单', async () => {
    const wrapper = mountPanel({ customer: null, externalUserid: 'ext-unlinked-2' })

    await wrapper.get('[data-testid="unlinked-mode-create"]').trigger('click')
    await wrapper.get('[data-testid="unlinked-new-display-name"]').setValue('林女士')
    await wrapper.get('[data-testid="unlinked-new-intent"]').setValue('HIGH')
    await wrapper.get('[data-testid="unlinked-create-form"]').trigger('submit')
    await flushPromises()

    expect(createCustomer).toHaveBeenCalledWith(
      expect.objectContaining({ displayName: '林女士', intentLevel: 'HIGH' }),
      expect.stringContaining('sidebar-customer-create-'),
    )
    expect(linkWecomSidebarContact).toHaveBeenCalledWith({
      externalUserid: 'ext-unlinked-2',
      customerId: 2020,
      reason: '企微侧边栏新建客户并关联',
    })
    expect(wrapper.emitted('linked')).toEqual([[2020]])

    allowedPermissions = new Set(['wecom:sidebar:view'])
    const readonlyWrapper = mountPanel({ customer: null, externalUserid: 'ext-unlinked-3' })
    expect(readonlyWrapper.text()).toContain('无客户写入权限')
    expect(readonlyWrapper.find('[data-testid="unlinked-mode-search"]').exists()).toBe(false)
  })
})
