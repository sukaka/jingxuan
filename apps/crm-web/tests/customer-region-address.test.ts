import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CustomerDetail } from '../src/api/customer'
import { createCustomer, getCustomer, getCustomerLegacyProfile, updateCustomer } from '../src/api/customer'
import { createExportJob, listExportAccessLogs, listExportJobs } from '../src/api/exporting'
import { useAuthStore } from '../src/stores/auth'
import CustomerDetailDrawer from '../src/views/customer/CustomerDetailDrawer.vue'
import CustomerFormDrawer from '../src/views/customer/CustomerFormDrawer.vue'
import ExportsView from '../src/views/ExportsView.vue'

vi.mock('../src/api/customer', () => ({
  createCustomer: vi.fn(),
  getCustomer: vi.fn(),
  updateCustomer: vi.fn(),
  listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  getCustomerLegacyProfile: vi.fn().mockResolvedValue([]),
  listCustomerTags: vi.fn().mockResolvedValue([]),
  listCustomerSources: vi.fn().mockResolvedValue([]),
  listCustomerChannels: vi.fn().mockResolvedValue([]),
}))

vi.mock('../src/api/catalog', () => ({
  listProjects: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([]),
}))

vi.mock('../src/api/exporting', () => ({
  createExportJob: vi.fn(),
  listExportJobs: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 20 }),
  getExportJob: vi.fn(),
  getExportJobDownload: vi.fn(),
  listExportAccessLogs: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 20 }),
}))

vi.mock('../src/api/region', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/region')>()),
  loadRegionTree: vi.fn().mockResolvedValue([
    {
      code: '330000',
      name: '浙江省',
      level: 1,
      children: [
        {
          code: '330100',
          name: '杭州市',
          level: 2,
          children: [{ code: '330106', name: '西湖区', level: 3 }],
        },
      ],
    },
    {
      code: '110000',
      name: '北京市',
      level: 1,
      children: [
        {
          code: '110100',
          name: '北京市',
          level: 2,
          children: [{ code: '110105', name: '朝阳区', level: 3 }],
        },
      ],
    },
  ]),
}))

const managerUser: AuthUser = {
  userId: 2,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['customer:read', 'customer:write', 'dictionary:read', 'export:read', 'export:create'],
}

const existingDetail: CustomerDetail = {
  id: 88,
  customerNo: 'C0000000088',
  displayName: '林女士',
  nickname: '西湖小鹿',
  mobileMasked: null,
  cityCode: 'HZ',
  provinceCode: '330000',
  provinceName: '浙江省',
  regionCityCode: '330100',
  regionCityName: '杭州市',
  districtCode: '330106',
  districtName: '西湖区',
  regionText: '浙江省 / 杭州市 / 西湖区',
  detailAddress: '文三路示例100号',
  customerStatus: 'ACTIVE',
  tierCode: 'A',
  intentLevel: 'HIGH',
  ownerStaffId: 2,
  ownerName: '王丽',
  teamId: 1,
  pointsCached: 0,
  lastFollowupAt: null,
  createdAt: '2026-11-01T00:00:00Z',
  version: 5,
  sourceCode: null,
  channelCode: null,
  spendingLevel: null,
  birthday: null,
  attentionProjects: null,
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: [],
  identities: [],
  deleted: false,
}

const wrappers: VueWrapper[] = []

function makePinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = managerUser
  auth.initialized = true
  return pinia
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(createCustomer).mockResolvedValue(existingDetail)
  vi.mocked(getCustomer).mockResolvedValue(existingDetail)
  vi.mocked(updateCustomer).mockResolvedValue(existingDetail)
  vi.mocked(getCustomerLegacyProfile).mockResolvedValue([
    {
      fieldKey: 'CITY',
      originalName: '所在城市',
      originalTable: '客户信息表',
      targetField: 'crm_customer.city_code',
      sourceFormula: null,
      value: '浙江省 / 杭州市 / 西湖区',
      availability: 'AVAILABLE',
    },
  ])
  vi.mocked(listExportJobs).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  vi.mocked(listExportAccessLogs).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  vi.mocked(createExportJob).mockResolvedValue({
    id: 1,
    jobNo: 'EXP-1',
    exportType: 'CUSTOMER',
    status: 'PENDING',
    filters: {},
    columns: ['customerNo', 'regionText', 'detailAddress'],
    scope: {},
    reason: '按省市区导出核对',
    rowCount: null,
    fileId: null,
    expiresAt: null,
    lastError: null,
    startedAt: null,
    finishedAt: null,
    createdAt: '2026-11-28T00:00:00Z',
    updatedAt: '2026-11-28T00:00:00Z',
    version: 0,
  })
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('客户省市区三级联动与详细地址全链路', () => {
  it('新建客户支持选择省/市/区并填写详细地址，也可全部留空提交', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'create', customerId: null },
      global: {
        plugins: [ElementPlus, makePinia()],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const nameInput = wrapper.get('input[placeholder*="真实姓名"]')
    await nameInput.setValue('林女士')
    const cascader = wrapper.findComponent({ name: 'RegionCascader' })
    await cascader.vm.$emit('update:modelValue', ['330000', '330100', '330106'])
    await cascader.vm.$emit('change', ['330000', '330100', '330106'])
    const addressInput = wrapper.get('[data-testid="customer-detail-address-input"]')
    await addressInput.setValue('  文三路示例100号  ')

    const submitBtn = wrapper.findAll('button').find((b) => b.text() === '建档')
    await submitBtn?.trigger('click')
    await flushPromises()

    expect(createCustomer).toHaveBeenCalledWith(
      expect.objectContaining({
        displayName: '林女士',
        provinceCode: '330000',
        regionCityCode: '330100',
        districtCode: '330106',
        detailAddress: '文三路示例100号',
      }),
      expect.any(String),
    )
  })

  it('编辑客户回显已有省市区与详细地址，支持仅修改到省/市及显式清空地区与详细地址', async () => {
    const wrapper = mount(CustomerFormDrawer, {
      props: { modelValue: false, mode: 'edit', customerId: 88 },
      global: {
        plugins: [ElementPlus, makePinia()],
        stubs: { 'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' } },
      },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const cascader = wrapper.findComponent({ name: 'RegionCascader' })
    expect(cascader.props('modelValue')).toEqual(['330000', '330100', '330106'])
    const addressInput = wrapper.get<HTMLInputElement>('[data-testid="customer-detail-address-input"]')
    expect(addressInput.element.value).toBe('文三路示例100号')

    // 清空地区与详细地址后保存，应显式发送空串通知后端清除
    await cascader.vm.$emit('update:modelValue', [])
    await cascader.vm.$emit('change', [])
    await addressInput.setValue('')
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === '保存')
    await saveBtn?.trigger('click')
    await flushPromises()

    expect(updateCustomer).toHaveBeenCalledWith(
      88,
      expect.objectContaining({
        provinceCode: '',
        regionCityCode: '',
        districtCode: '',
        detailAddress: '',
        version: 5,
      }),
    )
  })

  it('客户详情概览与基础档案展示完整省市区与详细地址，不误报已停用', async () => {
    const wrapper = mount(CustomerDetailDrawer, {
      props: { modelValue: false, customerId: 88, initialTab: 'overview' },
      global: {
        plugins: [ElementPlus, makePinia()],
        stubs: {
          ElDrawer: { template: '<section><slot name="header" /><slot /></section>' },
          CustomerBusinessPanel: true,
          CustomerPrivacyPanel: true,
          CustomerTierPanel: true,
          CustomerTimelinePanel: true,
          FollowupPanel: true,
          OwnershipPanel: true,
          PointsPanel: true,
          TagsIdentityPanel: true,
        },
      },
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.text()).toContain('浙江省 / 杭州市 / 西湖区')
    expect(wrapper.text()).toContain('文三路示例100号')

    const profileWrapper = mount(CustomerDetailDrawer, {
      props: { modelValue: false, customerId: 88, initialTab: 'profile' },
      global: {
        plugins: [ElementPlus, makePinia()],
        stubs: {
          ElDrawer: { template: '<section><slot name="header" /><slot /></section>' },
          CustomerBusinessPanel: true,
          CustomerPrivacyPanel: true,
          CustomerTierPanel: true,
          CustomerTimelinePanel: true,
          FollowupPanel: true,
          OwnershipPanel: true,
          PointsPanel: true,
          TagsIdentityPanel: true,
        },
      },
    })
    wrappers.push(profileWrapper)
    await profileWrapper.setProps({ modelValue: true })
    await flushPromises()

    const profileText = profileWrapper.get('.profile-card').text()
    expect(profileText).toContain('浙江省 / 杭州市 / 西湖区')
    expect(profileText).not.toContain('已停用')
  })

  it('客户导出支持省市区筛选与省/市/区/详细地址列选择', async () => {
    const wrapper = mount(ExportsView, {
      global: { plugins: [ElementPlus, makePinia()] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()

    await wrapper.get('[data-testid="export-dataset-CUSTOMER"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="export-field-provinceName"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="export-field-regionCityName"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="export-field-districtName"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="export-field-regionText"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="export-field-detailAddress"]').exists()).toBe(true)

    const regionFilter = wrapper.findComponent({ name: 'RegionCascader' })
    expect(regionFilter.exists()).toBe(true)
    await regionFilter.vm.$emit('update:modelValue', ['330000', '330100', '330106'])
    await regionFilter.vm.$emit('change', ['330000', '330100', '330106'])

    const reasonField = wrapper.get('[data-testid="export-reason"]')
    await reasonField.setValue('按杭州西湖区导出核对档案')
    await wrapper.get('form.export-request-form').trigger('submit')
    await flushPromises()

    expect(createExportJob).toHaveBeenCalledWith(
      expect.objectContaining({
        exportType: 'CUSTOMER',
        filters: {
          provinceCode: '330000',
          regionCityCode: '330100',
          districtCode: '330106',
        },
        reason: '按杭州西湖区导出核对档案',
      }),
      expect.any(String),
    )
  })
})
