import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerDetail, LegacyProfileField } from '../src/api/customer'
import { getCustomer, getCustomerLegacyProfile } from '../src/api/customer'
import { getDictionaryOptions } from '../src/api/dictionary'
import { useAuthStore } from '../src/stores/auth'
import CustomerDetailDrawer from '../src/views/customer/CustomerDetailDrawer.vue'
import { formatDateTime } from '../src/views/customer/shared'

vi.mock('../src/api/customer', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/customer')>()),
  getCustomer: vi.fn(),
  getCustomerLegacyProfile: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([]),
}))

const detail: CustomerDetail = {
  id: 1,
  customerNo: 'C0000001001',
  displayName: '沈梦琪',
  nickname: 'Momo酱',
  mobileMasked: '138****0011',
  cityCode: 'HZ',
  customerStatus: 'ACTIVE',
  tierCode: 'A',
  intentLevel: 'HIGH',
  ownerStaffId: 2,
  ownerName: '王丽',
  teamId: 1,
  pointsCached: 320,
  lastFollowupAt: '2026-10-24T08:20:00Z',
  createdAt: '2026-09-18T02:30:00Z',
  version: 0,
  sourceCode: 'EXTERNAL_CONTENT',
  channelCode: 'XIAOHONGSHU',
  spendingLevel: 'HIGH',
  birthday: null,
  attentionProjects: '皮肤管理',
  blockerNote: null,
  remark: null,
  remarkName: null,
  firstAddedAt: null,
  firstOrderAt: null,
  tagCodes: [],
  identities: [],
  deleted: false,
}

const profileFields: LegacyProfileField[] = [
  {
    fieldKey: 'USER_NAME',
    originalName: '用户姓名',
    originalTable: '客户信息表',
    targetField: 'crm_customer.display_name',
    sourceFormula: null,
    value: '沈梦琪',
    availability: 'AVAILABLE',
  },
  {
    fieldKey: 'MOBILE',
    originalName: '客户电话',
    originalTable: '客户信息表',
    targetField: 'crm_customer.mobile_masked',
    sourceFormula: null,
    value: '138****0011',
    availability: 'AVAILABLE',
  },
  {
    fieldKey: 'NICKNAME',
    originalName: '客户昵称',
    originalTable: '客户信息表',
    targetField: 'crm_customer.nickname',
    sourceFormula: null,
    value: null,
    availability: 'PENDING_IMPORT',
  },
]

async function mountProfile() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1,
    username: 'admin',
    displayName: '运营管理员',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions: ['customer:read', 'customer:write', 'dictionary:read'],
  }
  auth.initialized = true
  const wrapper = mount(CustomerDetailDrawer, {
    props: { modelValue: false, customerId: 1, initialTab: 'profile' },
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        ElDrawer: { template: '<section><slot name="header" /><slot /></section>' },
        CustomerBusinessPanel: true,
        FollowupPanel: true,
        OwnershipPanel: { template: '<div data-test="ownership-panel" />' },
        PointsPanel: true,
        TagsIdentityPanel: true,
      },
    },
  })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}

describe('CustomerDetailDrawer 基础档案', () => {
  beforeEach(() => {
    vi.mocked(getCustomer).mockReset().mockResolvedValue(detail)
    vi.mocked(getCustomerLegacyProfile).mockReset().mockResolvedValue(profileFields)
    vi.mocked(getDictionaryOptions).mockReset().mockResolvedValue([])
  })

  it('只展示业务字段名和内容，不向运营暴露研发映射信息', async () => {
    const wrapper = await mountProfile()

    expect(getCustomer).toHaveBeenCalledWith(1)
    expect(getCustomerLegacyProfile).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.profile-item').map((item) => ({
      label: item.find('.profile-label').text(),
      value: item.find('.profile-value').text(),
    }))).toEqual([
      { label: '用户姓名', value: '沈梦琪' },
      { label: '客户电话', value: '138****0011' },
      { label: '客户昵称', value: '暂未维护' },
    ])
    expect(wrapper.find('[data-test="ownership-panel"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('USER_NAME')
    expect(wrapper.text()).not.toContain('crm_customer')
    expect(wrapper.text()).not.toContain('字段标识')
    expect(wrapper.text()).not.toContain('目标字段')
    expect(wrapper.text()).not.toContain('来源公式')
    expect(wrapper.text()).not.toContain('已入库')
    expect(wrapper.text()).not.toContain('待历史导入')
    expect(wrapper.find('.profile-card .el-table').exists()).toBe(false)
  })

  it('来源、渠道、城市和状态显示中文，时间使用页面统一格式', async () => {
    const dictionaryOptions: Record<string, { code: string; label: string; sortNo: number }[]> = {
      customerSources: [{ code: 'EXTERNAL_CONTENT', label: '外部内容', sortNo: 1 }],
      customerChannels: [{ code: 'XHS', label: '小红书', sortNo: 1 }],
      cities: [{ code: 'BJ', label: '北京', sortNo: 1 }],
      customerStatuses: [{ code: 'ACTIVE', label: '服务中', sortNo: 1 }],
    }
    vi.mocked(getDictionaryOptions).mockImplementation(async (dictCode) => dictionaryOptions[dictCode] ?? [])
    const baseField = profileFields[0]!
    vi.mocked(getCustomerLegacyProfile).mockResolvedValue([
      { ...baseField, fieldKey: 'SOURCE', originalName: '客户来源', value: 'EXTERNAL_CONTENT' },
      { ...baseField, fieldKey: 'CHANNEL', originalName: '客户渠道', value: 'XHS' },
      { ...baseField, fieldKey: 'CITY', originalName: '所在城市', value: 'BJ' },
      { ...baseField, fieldKey: 'CUSTOMER_STATUS', originalName: '客户状态', value: 'ACTIVE' },
      { ...baseField, fieldKey: 'FIRST_ADDED_AT', originalName: '客户添加时间', value: '2026-09-01T10:00:00Z' },
    ])
    const wrapper = await mountProfile()

    expect(wrapper.findAll('.profile-item').map((item) => ({
      label: item.find('.profile-label').text(),
      value: item.find('.profile-value').text(),
    }))).toEqual([
      { label: '客户来源', value: '外部内容' },
      { label: '客户渠道', value: '小红书' },
      { label: '所在城市', value: '北京' },
      { label: '客户状态', value: '服务中' },
      { label: '客户添加时间', value: formatDateTime('2026-09-01T10:00:00Z') },
    ])
    expect(wrapper.text()).not.toContain('EXTERNAL_CONTENT')
    expect(wrapper.text()).not.toContain('2026-09-01T10:00:00Z')
  })

  it('档案加载失败时保留明确错误提示', async () => {
    vi.mocked(getCustomerLegacyProfile).mockRejectedValue(new Error('网络不可用'))
    const wrapper = await mountProfile()

    expect(wrapper.text()).toContain('档案加载失败:网络不可用')
    expect(wrapper.find('.profile-card').exists()).toBe(false)
  })
})
