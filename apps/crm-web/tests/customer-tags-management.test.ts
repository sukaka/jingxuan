import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail, CustomerTagItem } from '../src/api/customer'
import {
  createCustomer,
  createCustomerTag,
  disableCustomerTag,
  enableCustomerTag,
  getCustomer,
  listCustomers,
  listCustomerTags,
  listMergeCandidates,
  updateCustomer,
  updateCustomerTag,
  updateCustomerTags,
} from '../src/api/customer'
import { listProjects } from '../src/api/catalog'
import { getDictionaryOptions } from '../src/api/dictionary'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import CustomersView from '../src/views/CustomersView.vue'
import CustomerDetailDrawer from '../src/views/customer/CustomerDetailDrawer.vue'
import CustomerFormDrawer from '../src/views/customer/CustomerFormDrawer.vue'
import CustomerTagManageDialog from '../src/views/customer/CustomerTagManageDialog.vue'

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
  listCustomers: vi.fn(),
  createCustomer: vi.fn(),
  getCustomer: vi.fn(),
  updateCustomer: vi.fn(),
  updateCustomerTags: vi.fn(),
  listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  getCustomerLegacyProfile: vi.fn().mockResolvedValue([]),
  listMergeCandidates: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 1 }),
  getMultiStewardSummary: vi.fn().mockResolvedValue({
    multiStewardCustomerCount: 0,
    crossTeamCustomerCount: 0,
    maxStewardCount: 0,
  }),
  listCustomerTags: vi.fn(),
  createCustomerTag: vi.fn(),
  updateCustomerTag: vi.fn(),
  disableCustomerTag: vi.fn(),
  enableCustomerTag: vi.fn(),
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
  permissions: ['customer:read', 'customer:write', 'customer:tag:manage', 'dictionary:read'],
}

const tagItems: CustomerTagItem[] = [
  { id: 1, optionCode: 'VIP', optionLabel: '核心贵宾', sortNo: 1, status: 'ACTIVE', version: 2 },
  { id: 2, optionCode: 'TAG_SENSITIVE01', optionLabel: '敏感肌', sortNo: 2, status: 'ACTIVE', version: 0 },
  { id: 3, optionCode: 'TAG_OLD_01', optionLabel: '旧活动客', sortNo: 3, status: 'INACTIVE', version: 1 },
]

const sampleDetail: CustomerDetail = {
  id: 11,
  customerNo: 'C0000000011',
  displayName: '林女士',
  nickname: '林林',
  mobileMasked: '138****9999',
  cityCode: 'SH',
  customerStatus: 'ACTIVE',
  tierCode: 'A',
  intentLevel: 'HIGH',
  ownerStaffId: 2,
  ownerName: '王丽',
  teamId: 1,
  pointsCached: 100,
  lastFollowupAt: null,
  createdAt: '2026-11-26T00:00:00Z',
  version: 2,
  sourceCode: null,
  channelCode: null,
  spendingLevel: 'HIGH',
  birthday: null,
  attentionProjects: null,
  attentionProjectIds: [],
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: ['VIP', 'TAG_OLD_01'],
  identities: [],
  deleted: false,
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
    if (dictCode === 'customerTags') {
      return [
        { code: 'VIP', label: '核心贵宾', sortNo: 1 },
        { code: 'TAG_SENSITIVE01', label: '敏感肌', sortNo: 2 },
      ]
    }
    return []
  })
  vi.mocked(listProjects).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  vi.mocked(listCustomerTags).mockResolvedValue(tagItems)
  vi.mocked(createCustomerTag).mockResolvedValue({
    id: 4, optionCode: 'TAG_NEW12345678', optionLabel: '抗衰重点', sortNo: 4, status: 'ACTIVE', version: 0,
  })
  vi.mocked(updateCustomerTag).mockResolvedValue({
    ...tagItems[0]!, optionLabel: '至尊贵宾', sortNo: 1, version: 3,
  })
  vi.mocked(disableCustomerTag).mockResolvedValue({ ...tagItems[1]!, status: 'INACTIVE', version: 1 })
  vi.mocked(enableCustomerTag).mockResolvedValue({ ...tagItems[2]!, status: 'ACTIVE', version: 2 })
  vi.mocked(createCustomer).mockResolvedValue(sampleDetail)
  vi.mocked(getCustomer).mockResolvedValue(sampleDetail)
  vi.mocked(updateCustomer).mockResolvedValue(sampleDetail)
  vi.mocked(updateCustomerTags).mockResolvedValue(sampleDetail)
  vi.mocked(listMergeCandidates).mockResolvedValue({ items: [], total: 0, page: 1, size: 1 })
  vi.mocked(ElMessageBox.confirm).mockResolvedValue({ value: 'confirm', action: 'confirm' } as never)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('新建/编辑客户直接打标与管家只选约束', () => {
  it('普通管家建档可直接选择已有标签提交，不允许自由创建标签，且无标签管理入口', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'create', customerId: null },
      global: {
        plugins: [ElementPlus, createAuthPinia(managerUser)],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const tagSelect = wrapper.findAllComponents({ name: 'ElSelect' })
      .find((select) => select.attributes('data-testid') === 'customer-tag-select')
    expect(tagSelect).toBeDefined()
    expect(tagSelect!.props('allowCreate')).toBeFalsy()
    expect(wrapper.find('[data-testid="form-manage-tags-btn"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('请联系管家主管维护')

    await wrapper.get('input[placeholder*="真实姓名或称呼"]').setValue('林女士')
    await tagSelect!.vm.$emit('update:modelValue', ['VIP', 'TAG_SENSITIVE01'])
    await flushPromises()

    const submitBtn = wrapper.findAll('button').find((b) => b.text() === '建档')
    await submitBtn?.trigger('click')
    await flushPromises()

    expect(createCustomer).toHaveBeenCalledWith(
      expect.objectContaining({
        displayName: '林女士',
        tagCodes: ['VIP', 'TAG_SENSITIVE01'],
      }),
      expect.any(String),
    )
  })

  it('编辑客户回显已有标签（停用历史标签只读保留），管家主管可见管理标签内容入口', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'edit', customerId: 11 },
      global: {
        plugins: [ElementPlus, createAuthPinia(supervisorUser)],
        stubs: {
          'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' },
          CustomerTagManageDialog: { template: '<div data-testid="tag-manage-dialog-stub" />' },
        },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="form-manage-tags-btn"]').exists()).toBe(true)
    const options = wrapper.findAllComponents({ name: 'ElOption' })
    const activeOpt = options.find((opt) => opt.props('value') === 'TAG_SENSITIVE01')
    const disabledHistorical = options.find((opt) => opt.props('value') === 'TAG_OLD_01')
    expect(activeOpt?.props('label')).toBe('敏感肌')
    expect(activeOpt?.props('disabled')).toBe(false)
    expect(disabledHistorical?.props('disabled')).toBe(true)
    expect(disabledHistorical?.props('label')).toBe('TAG_OLD_01（已停用）')

    const saveBtn = wrapper.findAll('button').find((b) => b.text() === '保存')
    await saveBtn?.trigger('click')
    await flushPromises()

    expect(updateCustomer).toHaveBeenCalledWith(
      11,
      expect.objectContaining({ tagCodes: ['VIP', 'TAG_OLD_01'], version: 2 }),
    )
  })
})

describe('客户详情标签上移与列表动态标签展示', () => {
  it('客户详情顶部与概览区直接展示并可编辑标签，不折叠在折叠面板中，且禁止管家新建标签', async () => {
    const wrapper = mount(CustomerDetailDrawer, {
      props: { modelValue: false, customerId: 11 },
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
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.get('[data-testid="detail-head-tags"]').text()).toContain('核心贵宾')
    const tagsCard = wrapper.get('[data-testid="detail-tags-card"]')
    expect(tagsCard.element.closest('.el-collapse')).toBeNull()

    const select = wrapper.findAllComponents({ name: 'ElSelect' })
      .find((item) => item.attributes('data-testid') === 'detail-customer-tag-select')
    expect(select).toBeDefined()
    expect(tagsCard.element.contains(select!.element)).toBe(true)
    expect(select!.props('allowCreate')).toBeFalsy()
    expect(tagsCard.find('[data-testid="panel-manage-tags-btn"]').exists()).toBe(false)
    expect(tagsCard.text()).toContain('标签内容由管家主管统一维护')

    await select!.vm.$emit('update:modelValue', ['VIP', 'TAG_SENSITIVE01'])
    await tagsCard.get('[data-testid="detail-save-tags-btn"]').trigger('click')
    await flushPromises()
    expect(updateCustomerTags).toHaveBeenCalledWith(11, ['VIP', 'TAG_SENSITIVE01'])
  })

  it('客户中心列表展示字典中文标签与超量计数，并按 customer:tag:manage 控制标签管理入口', async () => {
    vi.mocked(listCustomers).mockResolvedValue({
      items: [{
        ...sampleDetail,
        tagCodes: ['VIP', 'TAG_SENSITIVE01', 'TAG_OLD_01'],
      }],
      total: 1,
      page: 1,
      size: 20,
    })

    const managerWrapper = mount(CustomersView, {
      global: {
        plugins: [ElementPlus, createAuthPinia(managerUser)],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    })
    wrappers.push(managerWrapper)
    await flushPromises()
    expect(managerWrapper.find('[data-testid="customer-tag-manage-btn"]').exists()).toBe(false)
    expect(managerWrapper.text()).toContain('核心贵宾')
    expect(managerWrapper.text()).toContain('敏感肌')
    expect(managerWrapper.text()).toContain('+1')

    const supervisorWrapper = mount(CustomersView, {
      global: {
        plugins: [ElementPlus, createAuthPinia(supervisorUser)],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    })
    wrappers.push(supervisorWrapper)
    await flushPromises()
    expect(supervisorWrapper.find('[data-testid="customer-tag-manage-btn"]').exists()).toBe(true)
  })
})

describe('CustomerTagManageDialog 管家主管标签内容维护', () => {
  it('支持新增、修改、停用与重新启用客户标签内容，并在每次变更后通知父组件刷新', async () => {
    const wrapper = mount(CustomerTagManageDialog, {
      props: { modelValue: true },
      global: {
        plugins: [ElementPlus, createAuthPinia(supervisorUser)],
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()

    expect(listCustomerTags).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('核心贵宾')
    expect(document.body.textContent).toContain('敏感肌')

    // 新增标签（仅填中文名称，无需填英文 code）
    const createBtn = document.body.querySelector<HTMLButtonElement>('[data-testid="customer-tag-create-btn"]')
    createBtn?.click()
    await flushPromises()
    const labelInput = document.body.querySelector<HTMLInputElement>('[data-testid="customer-tag-label-input"]')
    expect(labelInput).not.toBeNull()
    labelInput!.value = '抗衰重点'
    labelInput!.dispatchEvent(new Event('input', { bubbles: true }))
    document.body.querySelector<HTMLButtonElement>('[data-testid="customer-tag-save-btn"]')?.click()
    await flushPromises()
    expect(createCustomerTag).toHaveBeenCalledWith({ optionLabel: '抗衰重点', sortNo: 4 })
    expect(ElMessage.success).toHaveBeenCalledWith('标签内容已新增')

    // 修改标签（携带乐观锁 version）
    document.body.querySelector<HTMLButtonElement>('[data-testid="customer-tag-edit-1"]')?.click()
    await flushPromises()
    const editInput = document.body.querySelector<HTMLInputElement>('[data-testid="customer-tag-label-input"]')
    editInput!.value = '至尊贵宾'
    editInput!.dispatchEvent(new Event('input', { bubbles: true }))
    document.body.querySelector<HTMLButtonElement>('[data-testid="customer-tag-save-btn"]')?.click()
    await flushPromises()
    expect(updateCustomerTag).toHaveBeenCalledWith(1, { optionLabel: '至尊贵宾', sortNo: 1, version: 2 })

    // 停用与重新启用
    document.body.querySelector<HTMLButtonElement>('[data-testid="customer-tag-disable-2"]')?.click()
    await flushPromises()
    expect(disableCustomerTag).toHaveBeenCalledWith(2)

    document.body.querySelector<HTMLButtonElement>('[data-testid="customer-tag-enable-3"]')?.click()
    await flushPromises()
    expect(enableCustomerTag).toHaveBeenCalledWith(3)

    expect(wrapper.emitted('changed')).toHaveLength(4)
  })

  it('已归档标签仅展示已归档状态且隐藏修改与启停按钮；建档 409 冲突透传后端原因', async () => {
    vi.mocked(listCustomerTags).mockResolvedValueOnce([
      { id: 9, optionCode: 'TAG_ARCHIVED', optionLabel: '已归档旧标签', sortNo: 9, status: 'ARCHIVED', version: 4 },
    ])
    const dialog = mount(CustomerTagManageDialog, {
      props: { modelValue: true },
      global: { plugins: [ElementPlus, createAuthPinia(supervisorUser)] },
      attachTo: document.body,
    })
    wrappers.push(dialog)
    await flushPromises()

    expect(document.body.textContent).toContain('已归档')
    expect(document.body.querySelector('[data-testid="customer-tag-edit-9"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="customer-tag-disable-9"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="customer-tag-enable-9"]')).toBeNull()

    vi.mocked(createCustomer).mockRejectedValueOnce(
      new ApiError({ status: 409, code: 'CONFLICT', message: '客户标签不存在或已停用：TAG_OLD_01' }),
    )
    const drawer = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'create', customerId: null },
      global: {
        plugins: [ElementPlus, createAuthPinia(managerUser)],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    wrappers.push(drawer)
    await drawer.setProps({ modelValue: true })
    await flushPromises()
    await drawer.get('input[placeholder*="真实姓名或称呼"]').setValue('林女士')
    await drawer.findAll('button').find((b) => b.text() === '建档')?.trigger('click')
    await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('客户标签不存在或已停用：TAG_OLD_01')
  })
})
