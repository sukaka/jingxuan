import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail, CustomerOptionItem } from '../src/api/customer'
import {
  createCustomer,
  createCustomerChannel,
  createCustomerSource,
  disableCustomerChannel,
  disableCustomerSource,
  enableCustomerChannel,
  enableCustomerSource,
  getCustomer,
  listCustomerChannels,
  listCustomerSources,
  updateCustomer,
  updateCustomerChannel,
  updateCustomerSource,
} from '../src/api/customer'
import { listProjects } from '../src/api/catalog'
import { getDictionaryOptions } from '../src/api/dictionary'
import { useAuthStore } from '../src/stores/auth'
import CustomerDetailDrawer from '../src/views/customer/CustomerDetailDrawer.vue'
import CustomerFormDrawer from '../src/views/customer/CustomerFormDrawer.vue'
import CustomerSourceChannelManageDialog from '../src/views/customer/CustomerSourceChannelManageDialog.vue'

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn() },
  }
})

vi.mock('../src/api/customer', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customer')>()),
  createCustomer: vi.fn(),
  getCustomer: vi.fn(),
  updateCustomer: vi.fn(),
  listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  getCustomerLegacyProfile: vi.fn().mockResolvedValue([]),
  listCustomerTags: vi.fn().mockResolvedValue([]),
  listCustomerSources: vi.fn(),
  createCustomerSource: vi.fn(),
  updateCustomerSource: vi.fn(),
  disableCustomerSource: vi.fn(),
  enableCustomerSource: vi.fn(),
  listCustomerChannels: vi.fn(),
  createCustomerChannel: vi.fn(),
  updateCustomerChannel: vi.fn(),
  disableCustomerChannel: vi.fn(),
  enableCustomerChannel: vi.fn(),
}))

vi.mock('../src/api/catalog', () => ({
  listProjects: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn(),
}))

const managerUser: AuthUser = {
  userId: 2,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['customer:read', 'customer:write', 'dictionary:read'],
}

const supervisorUser: AuthUser = {
  userId: 4,
  username: 'lead1',
  displayName: '张敏',
  userType: 'EMPLOYEE',
  roles: ['MANAGER_LEAD'],
  permissions: [
    'customer:read',
    'customer:write',
    'customer:merge',
    'customer:source_channel:manage',
    'dictionary:read',
  ],
}

const sourceItems: CustomerOptionItem[] = [
  { id: 101, optionCode: 'REFERRAL', optionLabel: '老客转介绍', sortNo: 1, status: 'ACTIVE', version: 1 },
  { id: 102, optionCode: 'SRC_OFFLINE', optionLabel: '线下沙龙', sortNo: 2, status: 'INACTIVE', version: 0 },
]

const channelItems: CustomerOptionItem[] = [
  { id: 201, optionCode: 'XHS', optionLabel: '小红书', sortNo: 1, status: 'ACTIVE', version: 2 },
  { id: 202, optionCode: 'CHN_WEIBO', optionLabel: '微博', sortNo: 2, status: 'INACTIVE', version: 1 },
]

const sampleDetail: CustomerDetail = {
  id: 88,
  customerNo: 'C0000000088',
  displayName: '沈女士',
  nickname: '沈沈',
  mobileMasked: '139****8888',
  cityCode: 'SH',
  customerStatus: 'ACTIVE',
  tierCode: 'S',
  intentLevel: 'HIGH',
  ownerStaffId: 2,
  ownerName: '王丽',
  teamId: 1,
  pointsCached: 200,
  lastFollowupAt: null,
  createdAt: '2026-11-26T00:00:00Z',
  version: 2,
  sourceCode: 'SRC_OFFLINE',
  channelCode: 'XHS',
  spendingLevel: 'HIGH',
  birthday: null,
  attentionProjects: null,
  attentionProjectIds: [],
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: [],
  identities: [],
  deleted: false,
  addedStewardCount: 3,
  addedStewards: [
    {
      staffUserId: 2,
      staffName: '王丽',
      teamId: 1,
      teamName: '管家一组',
      inMyScope: true,
      customerId: 88,
      customerNo: 'C0000000088',
      customerName: '沈女士',
      relationTypes: ['CUSTOMER_OWNER', 'WECOM_FOLLOW'],
      addedAt: '2026-11-20T09:00:00Z',
    },
    {
      staffUserId: 12,
      staffName: '周小洁',
      teamId: 1,
      teamName: '管家一组',
      inMyScope: true,
      customerId: 89,
      customerNo: 'C0000000089',
      customerName: '沈女士',
      relationTypes: ['CUSTOMER_OWNER'],
      addedAt: '2026-11-22T10:00:00Z',
    },
    {
      staffUserId: 20,
      staffName: '李青',
      teamId: 2,
      teamName: '管家二组',
      inMyScope: false,
      customerId: null,
      customerNo: null,
      customerName: null,
      relationTypes: ['CUSTOMER_OWNER'],
      addedAt: '2026-11-25T11:30:00Z',
    },
  ],
}

const wrappers: VueWrapper[] = []

function createAuthPinia(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  return pinia
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getDictionaryOptions).mockImplementation(async (dictCode) => {
    if (dictCode === 'customerSources') {
      return [{ code: 'REFERRAL', label: '老客转介绍', sortNo: 1 }]
    }
    if (dictCode === 'customerChannels') {
      return [{ code: 'XHS', label: '小红书', sortNo: 1 }]
    }
    return []
  })
  vi.mocked(listProjects).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  vi.mocked(listCustomerSources).mockResolvedValue(sourceItems)
  vi.mocked(createCustomerSource).mockResolvedValue({
    id: 103, optionCode: 'SRC_NEW', optionLabel: '私域社群', sortNo: 3, status: 'ACTIVE', version: 0,
  })
  vi.mocked(updateCustomerSource).mockResolvedValue({
    ...sourceItems[0]!, optionLabel: '老客口碑', sortNo: 1, version: 2,
  })
  vi.mocked(disableCustomerSource).mockResolvedValue({ ...sourceItems[0]!, status: 'INACTIVE', version: 2 })
  vi.mocked(enableCustomerSource).mockResolvedValue({ ...sourceItems[1]!, status: 'ACTIVE', version: 1 })

  vi.mocked(listCustomerChannels).mockResolvedValue(channelItems)
  vi.mocked(createCustomerChannel).mockResolvedValue({
    id: 203, optionCode: 'CHN_NEW', optionLabel: '视频号', sortNo: 3, status: 'ACTIVE', version: 0,
  })
  vi.mocked(updateCustomerChannel).mockResolvedValue({
    ...channelItems[0]!, optionLabel: '小红书官方', sortNo: 1, version: 3,
  })
  vi.mocked(disableCustomerChannel).mockResolvedValue({ ...channelItems[0]!, status: 'INACTIVE', version: 3 })
  vi.mocked(enableCustomerChannel).mockResolvedValue({ ...channelItems[1]!, status: 'ACTIVE', version: 2 })

  vi.mocked(createCustomer).mockResolvedValue(sampleDetail)
  vi.mocked(getCustomer).mockResolvedValue(sampleDetail)
  vi.mocked(updateCustomer).mockResolvedValue(sampleDetail)
  vi.mocked(ElMessageBox.confirm).mockResolvedValue({ value: 'confirm', action: 'confirm' } as never)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('客户来源与渠道统一管理（管家主管维护，普通管家仅下拉选择）', () => {
  it('普通管家在建档与编辑时来源和渠道仅支持下拉选择，无管理入口且回显停用历史项只读', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'edit', customerId: 88 },
      global: {
        plugins: [ElementPlus, createAuthPinia(managerUser)],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    const sourceSelect = selects.find((s) => s.attributes('data-testid') === 'customer-source-select')
    const channelSelect = selects.find((s) => s.attributes('data-testid') === 'customer-channel-select')
    expect(sourceSelect).toBeDefined()
    expect(channelSelect).toBeDefined()
    expect(sourceSelect!.props('allowCreate')).toBeFalsy()
    expect(channelSelect!.props('allowCreate')).toBeFalsy()

    expect(wrapper.find('[data-testid="form-manage-sources-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="form-manage-channels-btn"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="form-source-readonly-hint"]').text()).toContain('仅可下拉选择')
    expect(wrapper.get('[data-testid="form-channel-readonly-hint"]').text()).toContain('仅可下拉选择')

    const options = wrapper.findAllComponents({ name: 'ElOption' })
    const historicalSource = options.find((opt) => opt.props('value') === 'SRC_OFFLINE')
    expect(historicalSource?.props('disabled')).toBe(true)
    expect(historicalSource?.props('label')).toBe('SRC_OFFLINE（已停用）')

    const saveBtn = wrapper.findAll('button').find((b) => b.text() === '保存')
    await saveBtn?.trigger('click')
    await flushPromises()
    expect(updateCustomer).toHaveBeenCalledWith(
      88,
      expect.objectContaining({ sourceCode: 'SRC_OFFLINE', channelCode: 'XHS', version: 2 }),
    )
  })

  it('管家主管在建档表单可见「管理来源」「管理渠道」入口并能切换弹窗标签', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'create', customerId: null },
      global: {
        plugins: [ElementPlus, createAuthPinia(supervisorUser)],
        stubs: {
          'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' },
          CustomerSourceChannelManageDialog: {
            props: ['modelValue', 'initialTab'],
            template: '<div data-testid="sc-dialog-stub" :data-visible="modelValue" :data-tab="initialTab" />',
          },
        },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="form-manage-sources-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-manage-channels-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-source-readonly-hint"]').exists()).toBe(false)

    await wrapper.get('[data-testid="form-manage-channels-btn"]').trigger('click')
    await flushPromises()
    const stub = wrapper.get('[data-testid="sc-dialog-stub"]')
    expect(stub.attributes('data-visible')).toBe('true')
    expect(stub.attributes('data-tab')).toBe('channels')
  })

  it('CustomerSourceChannelManageDialog 支持来源与渠道双页签的新增、修改、停用与启用并通知刷新', async () => {
    const wrapper = mount(CustomerSourceChannelManageDialog, {
      props: { modelValue: true, initialTab: 'sources' },
      global: { plugins: [ElementPlus, createAuthPinia(supervisorUser)] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()

    expect(listCustomerSources).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('老客转介绍')

    // 新增来源
    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-create-btn"]')?.click()
    await flushPromises()
    const input = document.body.querySelector<HTMLInputElement>('[data-testid="source-channel-label-input"]')
    expect(input).not.toBeNull()
    input!.value = '私域社群'
    input!.dispatchEvent(new Event('input', { bubbles: true }))
    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-save-btn"]')?.click()
    await flushPromises()
    expect(createCustomerSource).toHaveBeenCalledWith({ optionLabel: '私域社群', sortNo: 3 })
    expect(ElMessage.success).toHaveBeenCalledWith('客户来源已新增')

    // 修改来源
    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-edit-101"]')?.click()
    await flushPromises()
    const editInput = document.body.querySelector<HTMLInputElement>('[data-testid="source-channel-label-input"]')
    editInput!.value = '老客口碑'
    editInput!.dispatchEvent(new Event('input', { bubbles: true }))
    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-save-btn"]')?.click()
    await flushPromises()
    expect(updateCustomerSource).toHaveBeenCalledWith(101, { optionLabel: '老客口碑', sortNo: 1, version: 1 })

    // 停用与启用来源
    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-disable-101"]')?.click()
    await flushPromises()
    expect(disableCustomerSource).toHaveBeenCalledWith(101)

    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-enable-102"]')?.click()
    await flushPromises()
    expect(enableCustomerSource).toHaveBeenCalledWith(102)

    // 切到客户渠道页签并执行新增/停用
    const tabs = wrapper.findComponent({ name: 'ElTabs' })
    await tabs.vm.$emit('update:modelValue', 'channels')
    await flushPromises()
    expect(listCustomerChannels).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('小红书')

    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-create-btn"]')?.click()
    await flushPromises()
    const chInput = document.body.querySelector<HTMLInputElement>('[data-testid="source-channel-label-input"]')
    chInput!.value = '视频号'
    chInput!.dispatchEvent(new Event('input', { bubbles: true }))
    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-save-btn"]')?.click()
    await flushPromises()
    expect(createCustomerChannel).toHaveBeenCalledWith({ optionLabel: '视频号', sortNo: 3 })

    document.body.querySelector<HTMLButtonElement>('[data-testid="source-channel-disable-201"]')?.click()
    await flushPromises()
    expect(disableCustomerChannel).toHaveBeenCalledWith(201)

    expect(wrapper.emitted('changed')).toHaveLength(6)
  })
})

describe('客户详情多管家添加情况（无需合并，跨组仅汇总）', () => {
  it('管家主管（含 customer:merge）在详情顶部与概览区可见多管家数与跨组仅汇总标记；普通管家不可见', async () => {
    const leadWrapper = mount(CustomerDetailDrawer, {
      props: { modelValue: false, customerId: 88 },
      global: {
        plugins: [ElementPlus, createAuthPinia(supervisorUser)],
        stubs: {
          ElDrawer: { template: '<section><slot name="header" /><slot /></section>' },
          CustomerBusinessPanel: true,
          FollowupPanel: true,
          OwnershipPanel: true,
          PointsPanel: true,
        },
      },
      attachTo: document.body,
    })
    wrappers.push(leadWrapper)
    await leadWrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(leadWrapper.get('[data-testid="detail-multi-steward-pill"]').text()).toBe('已加 3 位管家')
    const card = leadWrapper.get('[data-testid="detail-steward-relations-card"]')
    expect(card.text()).toContain('多管家添加情况（无需合并）')
    expect(card.text()).toContain('王丽（管家一组）')
    expect(card.text()).toContain('当前档案')
    expect(card.text()).toContain('周小洁（管家一组）')
    expect(card.text()).toContain('本组档案 C0000000089')
    expect(card.text()).toContain('李青（管家二组）')
    expect(card.get('[data-testid="detail-cross-team-tag"]').text()).toBe('跨组仅汇总')

    // 普通管家无 customer:merge 权限，详情不展示多管家区块
    vi.mocked(getCustomer).mockResolvedValueOnce({
      ...sampleDetail,
      addedStewardCount: null,
      addedStewards: null,
    })
    const mgrWrapper = mount(CustomerDetailDrawer, {
      props: { modelValue: false, customerId: 88 },
      global: {
        plugins: [ElementPlus, createAuthPinia(managerUser)],
        stubs: {
          ElDrawer: { template: '<section><slot name="header" /><slot /></section>' },
          CustomerBusinessPanel: true,
          FollowupPanel: true,
          OwnershipPanel: true,
          PointsPanel: true,
        },
      },
      attachTo: document.body,
    })
    wrappers.push(mgrWrapper)
    await mgrWrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(mgrWrapper.find('[data-testid="detail-multi-steward-pill"]').exists()).toBe(false)
    expect(mgrWrapper.find('[data-testid="detail-steward-relations-card"]').exists()).toBe(false)
  })
})
