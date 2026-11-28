import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import {
  getMultiStewardSummary,
  listCustomerOwnerOptions,
  listCustomers,
} from '../src/api/customer'
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
  assignCustomerOwner: vi.fn(),
  batchAssignCustomerOwners: vi.fn(),
  listOwnershipHistory: vi.fn(),
  listCustomerOwnerOptions: vi.fn().mockResolvedValue([]),
  getCustomerLegacyProfile: vi.fn().mockResolvedValue([]),
  scanMergeCandidates: vi.fn(),
  listCustomerTags: vi.fn().mockResolvedValue([]),
  createCustomerTag: vi.fn(),
  updateCustomerTag: vi.fn(),
  disableCustomerTag: vi.fn(),
  enableCustomerTag: vi.fn(),
  listCustomerSources: vi.fn().mockResolvedValue([]),
  createCustomerSource: vi.fn(),
  updateCustomerSource: vi.fn(),
  disableCustomerSource: vi.fn(),
  enableCustomerSource: vi.fn(),
  listCustomerChannels: vi.fn().mockResolvedValue([]),
  createCustomerChannel: vi.fn(),
  updateCustomerChannel: vi.fn(),
  disableCustomerChannel: vi.fn(),
  enableCustomerChannel: vi.fn(),
  getMultiStewardSummary: vi.fn(),
  listMultiStewardCustomers: vi.fn(),
  getCustomerStewardRelations: vi.fn(),
}))

vi.mock('../src/api/followup', () => ({
  listFollowups: vi.fn(),
  createFollowup: vi.fn(),
  updateFollowup: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockImplementation(async (dictCode: string) => (
    dictCode === 'customerStatuses'
      ? [{ code: 'ACTIVE', label: '服务中', sortNo: 1 }]
      : dictCode === 'cities'
        ? [{ code: 'HZ', label: '杭州', sortNo: 1 }]
        : []
  )),
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
  ]),
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
    vi.mocked(getMultiStewardSummary).mockReset().mockResolvedValue({
      multiStewardCustomerCount: 0,
      crossTeamCustomerCount: 0,
      maxStewardCount: 0,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('列表渲染 12 列客户资料（含地区），且不出现明文手机号', async () => {
    vi.mocked(listCustomers).mockResolvedValue({
      ...samplePage,
      items: [{
        ...samplePage.items[0]!,
        provinceCode: '330000',
        provinceName: '浙江省',
        regionCityCode: '330100',
        regionCityName: '杭州市',
        districtCode: '330106',
        districtName: '西湖区',
        regionText: '浙江省 / 杭州市 / 西湖区',
        detailAddress: '文三路示例100号',
      }],
    })
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
      '地区',
      '所属管家',
      '最近跟进',
      '下一次跟进',
      '状态',
      '操作',
    ])
    expect(wrapper.text()).toContain('C0000000001')
    expect(wrapper.text()).toContain('陈女士')
    expect(wrapper.text()).toContain('浙江省 / 杭州市 / 西湖区')
    expect(wrapper.text()).toContain('文三路示例100号')
    expect(wrapper.text()).toContain('意向 高')
    expect(wrapper.text()).not.toContain('HIGH')
    expect(wrapper.text()).not.toContain('ACTIVE')
    expect(wrapper.text()).not.toContain('13812345678')
    expect(wrapper.find('.crm-avatar').exists()).toBe(true)
  })

  it('按省/市/区级联筛选时将 provinceCode/regionCityCode/districtCode 传给后端', async () => {
    vi.mocked(listCustomers).mockResolvedValue(samplePage)
    const wrapper = mountView(managerUser)
    await flushPromises()
    vi.mocked(listCustomers).mockClear()

    const regionFilter = wrapper.findComponent({ name: 'RegionCascader' })
    expect(regionFilter.exists()).toBe(true)
    await regionFilter.vm.$emit('update:modelValue', ['330000', '330100', '330106'])
    await regionFilter.vm.$emit('change', ['330000', '330100', '330106'])
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledWith(expect.objectContaining({
      page: 1,
      provinceCode: '330000',
      regionCityCode: '330100',
      districtCode: '330106',
    }))
  })

  it('具备 customer:merge 权限时展示全部四项统计与多管家徽章；无 customer:merge 时不请求多管家汇总且隐藏入口', async () => {
    vi.mocked(listCustomers).mockImplementation(async (query) => {
      if (query.tierCode === 'S') return { ...samplePage, total: 12, size: 1 }
      if (query.tierCode === 'A') return { ...samplePage, total: 8, size: 1 }
      if (query.followupDueBefore) return { ...samplePage, total: 3, size: 1 }
      return {
        ...samplePage,
        items: [{
          ...samplePage.items[0]!,
          addedStewardCount: 2,
          addedStewardNames: ['王丽', '周小洁'],
        }],
      }
    })
    vi.mocked(getMultiStewardSummary).mockResolvedValue({
      multiStewardCustomerCount: 2,
      crossTeamCustomerCount: 1,
      maxStewardCount: 3,
    })

    const withMerge = mountView({
      ...managerUser,
      permissions: [...managerUser.permissions, 'customer:merge', 'customer:source_channel:manage'],
    })
    await flushPromises()
    expect(withMerge.findAll('.proto-stat-card .number').map((value) => value.text())).toEqual([
      '12 位',
      '8 位',
      '3 条',
      '2 位',
    ])
    expect(getMultiStewardSummary).toHaveBeenCalledTimes(1)
    expect(withMerge.get('[data-testid="customer-merge-link"]').text()).toBe('多管家排查')
    expect(withMerge.find('[data-testid="customer-source-channel-manage-btn"]').exists()).toBe(true)
    expect(withMerge.get('[data-testid="customer-multi-steward-1"]').text()).toBe('已加 2 位管家')
    withMerge.unmount()

    vi.mocked(getMultiStewardSummary).mockClear()
    const withoutMerge = mountView(managerUser)
    await flushPromises()
    expect(getMultiStewardSummary).not.toHaveBeenCalled()
    expect(withoutMerge.findAll('.proto-stat-card .number').map((value) => value.text())).toEqual([
      '12 位',
      '8 位',
      '3 条',
    ])
    expect(withoutMerge.find('[data-testid="customer-merge-link"]').exists()).toBe(false)
    expect(withoutMerge.find('[data-testid="customer-source-channel-manage-btn"]').exists()).toBe(false)
    expect(withoutMerge.find('[data-testid="customer-multi-steward-1"]').exists()).toBe(false)
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

  it('新建客户保存后清空筛选并回到第 1 页；编辑客户保存保留当前筛选与页码', async () => {
    vi.mocked(listCustomers).mockImplementation(async (query) => ({
      items: samplePage.items,
      total: 40,
      page: query.page,
      size: query.size,
    }))
    const wrapper = mountView(managerUser)
    await flushPromises()

    // 设置关键词、客户等级并翻到第 2 页
    await wrapper.find('input').setValue('旧关键词')
    const tierSelect = wrapper.findComponent({ name: 'ElSelect' })
    await tierSelect.vm.$emit('change', 'S')
    await flushPromises()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })
    await pagination.vm.$emit('current-change', 2)
    await flushPromises()

    // 点击“＋ 新建客户”并触发保存
    vi.mocked(listCustomers).mockClear()
    const createBtn = wrapper.findAll('button').find((b) => b.text().includes('新建客户'))
    expect(createBtn).toBeDefined()
    await createBtn?.trigger('click')
    const formDrawer = wrapper.findComponent({ name: 'CustomerFormDrawer' })
    await formDrawer.vm.$emit('saved')
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: '',
      tierCode: undefined,
      followupDueBefore: undefined,
    })

    // 再次设置关键词与第 2 页后，点击“运营信息”编辑保存应保留原筛选和页码
    await wrapper.find('input').setValue('陈女士')
    await pagination.vm.$emit('current-change', 2)
    await flushPromises()
    vi.mocked(listCustomers).mockClear()

    const editBtn = wrapper.findAll('button').find((b) => b.text() === '运营信息')
    expect(editBtn).toBeDefined()
    await editBtn?.trigger('click')
    await formDrawer.vm.$emit('saved')
    await flushPromises()

    expect(listCustomers).toHaveBeenCalledWith({
      page: 2,
      size: 20,
      keyword: '陈女士',
      tierCode: undefined,
      followupDueBefore: undefined,
    })
  })

  it('主管具备 customer:transfer 时展示所属管家筛选、单客分配与批量勾选分配入口', async () => {
    const leadUser: AuthUser = {
      userId: 4,
      username: 'lead1',
      displayName: '张敏',
      userType: 'EMPLOYEE',
      roles: ['MANAGER_LEAD'],
      permissions: ['customer:read', 'customer:write', 'customer:transfer', 'dictionary:read'],
    }
    vi.mocked(listCustomerOwnerOptions).mockResolvedValue([
      { id: 2, displayName: '王丽', teamId: 1, teamName: '管家一组' },
      { id: 12, displayName: '周小洁', teamId: 1, teamName: '管家一组' },
      { id: 4, displayName: '张敏', teamId: 1, teamName: '管家一组' },
    ])
    vi.mocked(listCustomers).mockResolvedValue(samplePage)
    const wrapper = mountView(leadUser)
    await flushPromises()

    expect(wrapper.find('[data-testid="customer-owner-filter"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="row-assign-btn"]').exists()).toBe(true)

    vi.mocked(listCustomers).mockClear()
    const ownerSelect = wrapper.findAllComponents({ name: 'ElSelect' })
      .find((select) => select.attributes('data-testid') === 'customer-owner-filter')
    expect(ownerSelect).toBeDefined()
    await ownerSelect?.vm.$emit('update:modelValue', 2)
    await ownerSelect?.vm.$emit('change', 2)
    await flushPromises()
    expect(listCustomers).toHaveBeenCalledWith(expect.objectContaining({ page: 1, ownerId: 2 }))

    // 勾选列表行后出现批量分配栏
    const appTable = wrapper.findComponent({ name: 'AppTable' })
    await appTable.vm.$emit('selection-change', samplePage.items)
    await flushPromises()
    expect(wrapper.find('[data-testid="batch-assign-bar"]').text()).toContain('已选择 1 位客户')

    await wrapper.find('[data-testid="batch-assign-btn"]').trigger('click')
    await flushPromises()
    const assignDialog = wrapper.findComponent({ name: 'CustomerAssignDialog' })
    expect(assignDialog.props('modelValue')).toBe(true)
    expect(assignDialog.props('customers')).toHaveLength(1)
  })

  it('同时具备 integration:youzan:manage 与 customer:write 时显示从有赞拉取客户入口，缺其一时隐藏', async () => {
    vi.mocked(listCustomers).mockResolvedValue(samplePage)

    const withBoth = mountView({
      ...managerUser,
      permissions: [...managerUser.permissions, 'integration:youzan:manage'],
    })
    await flushPromises()
    const link = withBoth.find('[data-testid="customer-youzan-pull-link"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('从有赞拉取客户')
    expect(link.attributes('to')).toBe('/integrations/youzan?tab=customer-pull')
    withBoth.unmount()

    const onlyManage = mountView({
      ...readonlyUser,
      permissions: ['customer:read', 'integration:youzan:manage'],
    })
    await flushPromises()
    expect(onlyManage.find('[data-testid="customer-youzan-pull-link"]').exists()).toBe(false)
    onlyManage.unmount()

    const onlyWrite = mountView(managerUser)
    await flushPromises()
    expect(onlyWrite.find('[data-testid="customer-youzan-pull-link"]').exists()).toBe(false)
    onlyWrite.unmount()
  })
})
