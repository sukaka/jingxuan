import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { listCustomers, listMergeCandidates } from '../src/api/customer'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import CustomersView from '../src/views/CustomersView.vue'

vi.mock('../src/api/customer', () => ({
  listCustomers: vi.fn(),
  createCustomer: vi.fn(),
  getCustomer: vi.fn(),
  updateCustomer: vi.fn(),
  updateCustomerTags: vi.fn(),
  getCustomerPoints: vi.fn(),
  adjustCustomerPoints: vi.fn(),
  listMergeCandidates: vi.fn(),
  createMergeCase: vi.fn(),
  reviewMergeCase: vi.fn(),
  confirmMergeCase: vi.fn(),
  rejectMergeCase: vi.fn(),
  createOwnershipTransfer: vi.fn(),
  listOwnershipTransfers: vi.fn(),
  approveOwnershipTransfer: vi.fn(),
  rejectOwnershipTransfer: vi.fn(),
  listOwnershipHistory: vi.fn(),
  listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  getCustomerLegacyProfile: vi.fn().mockResolvedValue([]),
  scanMergeCandidates: vi.fn(),
}))

vi.mock('../src/api/followup', () => ({
  listFollowups: vi.fn(),
  createFollowup: vi.fn(),
  updateFollowup: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockImplementation(async (dictCode: string) => (
    dictCode === 'customerStatuses' ? [{ code: 'ACTIVE', label: '服务中', sortNo: 1 }] : []
  )),
}))

const managerUser: AuthUser = {
  userId: 2,
  username: 'manager1',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['MANAGER'],
  permissions: ['customer:read', 'customer:write', 'followup:read', 'followup:write', 'dictionary:read'],
}

const readonlyUser: AuthUser = {
  userId: 7,
  username: 'owner1',
  displayName: '经营负责人',
  userType: 'EMPLOYEE',
  roles: ['OWNER'],
  permissions: ['customer:read', 'dictionary:read'],
}

const samplePage = {
  items: [
    {
      id: 1,
      customerNo: 'C0000000001',
      displayName: '陈女士',
      nickname: '小鱼',
      mobileMasked: '138****5678',
      cityCode: 'HZ',
      customerStatus: 'ACTIVE',
      tierCode: 'S',
      intentLevel: 'HIGH',
      ownerStaffId: 2,
      ownerName: '王丽',
      teamId: 1,
      pointsCached: 320,
      lastFollowupAt: '2026-10-23T10:00:00',
      createdAt: '2026-10-01T08:00:00',
      version: 0,
    },
  ],
  total: 1,
  page: 1,
  size: 20,
}

function mountView(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  return mount(CustomersView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

describe('CustomersView 客户中心', () => {
  beforeEach(() => {
    vi.mocked(listCustomers).mockReset()
    vi.mocked(listMergeCandidates).mockReset().mockResolvedValue({ items: [], total: 0, page: 1, size: 1 })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('列表渲染 11 列客户资料，且不出现明文手机号', async () => {
    vi.mocked(listCustomers).mockResolvedValue(samplePage)
    const wrapper = mountView(managerUser)
    await flushPromises()

    const headers = wrapper.findAll('.el-table__header-wrapper th').map((header) => header.text())
    expect(headers).toEqual([
      '客户',
      '客户等级',
      '意向 / 消费能力',
      '标签',
      '生日 / 积分',
      '来源渠道',
      '所属管家',
      '最近跟进',
      '下一次跟进',
      '状态',
      '操作',
    ])
    expect(wrapper.text()).toContain('C0000000001')
    expect(wrapper.text()).toContain('陈女士')
    expect(wrapper.text()).toContain('意向 高')
    expect(wrapper.text()).not.toContain('HIGH')
    expect(wrapper.text()).not.toContain('ACTIVE')
    expect(wrapper.text()).not.toContain('13812345678')
    expect(wrapper.find('.crm-avatar').exists()).toBe(true)
  })

  it('具备 customer:merge 权限时展示全部四项统计；无 customer:merge 时不请求查重接口且不把 S/A/跟进 KPI 归零', async () => {
    vi.mocked(listCustomers).mockImplementation(async (query) => {
      if (query.tierCode === 'S') return { ...samplePage, total: 12, size: 1 }
      if (query.tierCode === 'A') return { ...samplePage, total: 8, size: 1 }
      if (query.followupDueBefore) return { ...samplePage, total: 3, size: 1 }
      return samplePage
    })
    vi.mocked(listMergeCandidates).mockResolvedValue({ items: [], total: 2, page: 1, size: 1 })

    const withMerge = mountView({ ...managerUser, permissions: [...managerUser.permissions, 'customer:merge'] })
    await flushPromises()
    expect(withMerge.findAll('.proto-stat-card .number').map((value) => value.text())).toEqual([
      '12 位',
      '8 位',
      '3 条',
      '2 组',
    ])
    expect(listMergeCandidates).toHaveBeenCalledWith({ status: 'SUGGESTED', page: 1, size: 1 })
    withMerge.unmount()

    vi.mocked(listMergeCandidates).mockClear()
    const withoutMerge = mountView(managerUser)
    await flushPromises()
    expect(listMergeCandidates).not.toHaveBeenCalled()
    expect(withoutMerge.findAll('.proto-stat-card .number').map((value) => value.text())).toEqual([
      '12 位',
      '8 位',
      '3 条',
    ])
    expect(withoutMerge.find('[data-testid="customer-merge-link"]').exists()).toBe(false)
    expect(listCustomers).toHaveBeenCalledWith({ tierCode: 'S', page: 1, size: 1 })
    expect(listCustomers).toHaveBeenCalledWith({ tierCode: 'A', page: 1, size: 1 })
    expect(listCustomers).toHaveBeenCalledWith({
      followupDueBefore: expect.any(String),
      page: 1,
      size: 1,
    })
    withoutMerge.unmount()
  })

  it('加载失败展示错误态而非崩溃', async () => {
    vi.mocked(listCustomers).mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接后端服务' }),
    )
    const wrapper = mountView(managerUser)
    await flushPromises()

    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).toContain('无法连接后端服务')
  })

  it('空列表给出可操作引导', async () => {
    vi.mocked(listCustomers).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    const wrapper = mountView(managerUser)
    await flushPromises()

    expect(wrapper.text()).toContain('暂无客户')
    expect(wrapper.text()).toContain('新建客户')
  })

  it('无 customer:write 权限时隐藏新建和运营信息按钮，保留客户详情', async () => {
    vi.mocked(listCustomers).mockResolvedValue(samplePage)
    const wrapper = mountView(readonlyUser)
    await flushPromises()

    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons.some((text) => text.includes('新建客户'))).toBe(false)
    expect(buttons).not.toContain('运营信息')
    expect(buttons).toContain('客户详情')
  })

  it('查询把关键字筛选条件传给后端', async () => {
    vi.mocked(listCustomers).mockResolvedValue(samplePage)
    const wrapper = mountView(managerUser)
    await flushPromises()
    vi.mocked(listCustomers).mockClear()

    const keyword = wrapper.find('input')
    await keyword.setValue('小鱼')
    const queryButton = wrapper.findAll('button').find((b) => b.text() === '查询')
    expect(queryButton).toBeDefined()
    await queryButton?.trigger('click')
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, keyword: '小鱼' }),
    )
  })

  it('客户等级提供 S/A/B/C/D 并支持 D 级筛选', async () => {
    vi.mocked(listCustomers).mockResolvedValue(samplePage)
    const wrapper = mountView(managerUser)
    await flushPromises()
    vi.mocked(listCustomers).mockClear()

    const headers = wrapper.findAll('.el-table__header-wrapper th').map((header) => header.text())
    expect(headers[1]).toBe('客户等级')
    const options = wrapper.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value'))
    expect(options).toEqual(expect.arrayContaining(['S', 'A', 'B', 'C', 'D']))
    const tierSelect = wrapper.findComponent({ name: 'ElSelect' })
    await tierSelect.vm.$emit('change', 'D')
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledWith(expect.objectContaining({ page: 1, tierCode: 'D' }))
  })

  it('北京时间凌晨（UTC 前一日 16:30Z）仍按 Asia/Shanghai 日期判定逾期跟进', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-25T16:30:00Z'))
    vi.mocked(listCustomers).mockResolvedValue({
      items: [{
        ...samplePage.items[0],
        lastFollowupAt: '2026-10-25T16:30:00Z',
        nextFollowupAt: '2026-10-25T16:30:00Z',
      }],
      total: 1,
      page: 1,
      size: 20,
    })
    const wrapper = mountView(managerUser)
    await flushPromises()

    expect(wrapper.text()).toContain('2026-10-26')
    expect(wrapper.text()).toContain('已到期')
  })
})
