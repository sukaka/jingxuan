import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Campaign, CampaignBatch, CampaignDetail } from '../src/api/campaign'
import {
  createCampaign,
  createCampaignBatch,
  getCampaign,
  listCampaigns,
  updateCampaign,
  updateCampaignBatch,
} from '../src/api/campaign'
import type { CampaignAnalyticsReport, PendingRatio, ReportContext } from '../src/api/report'
import { getCampaignAnalytics } from '../src/api/report'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import CampaignsView from '../src/views/CampaignsView.vue'
import { instantToShanghaiInput, shanghaiInputToInstant } from '../src/views/campaign/campaignTime'

vi.mock('../src/api/report', () => ({
  getOperationsReport: vi.fn(),
  getPerformanceReport: vi.fn(),
  getCampaignAnalytics: vi.fn(),
}))
vi.mock('../src/api/campaign', () => ({
  listCampaigns: vi.fn(),
  getCampaign: vi.fn(),
  createCampaign: vi.fn(),
  updateCampaign: vi.fn(),
  createCampaignBatch: vi.fn(),
  updateCampaignBatch: vi.fn(),
}))

const pendingRatio: PendingRatio = { value: null, status: 'PENDING_DEFINITION', questionCode: 'Q13', description: '统计分母待确认' }
const campaign: Campaign = {
  id: 81,
  campaignCode: 'CMP-TEST',
  campaignName: '契约测试活动',
  campaignType: 'PROMOTION',
  startsAt: '2026-10-01T01:00:00Z',
  endsAt: '2026-10-31T10:00:00Z',
  status: 'ACTIVE',
  createdAt: '2026-09-01T01:00:00Z',
  version: 3,
}
const batch: CampaignBatch = {
  id: 901,
  campaignId: 81,
  batchCode: 'WAVE-TEST',
  batchName: '测试批次',
  startsAt: '2026-10-01T01:00:00Z',
  endsAt: '2026-10-31T10:00:00Z',
  status: 'ACTIVE',
  createdAt: '2026-09-01T01:00:00Z',
  version: 2,
}
const detail: CampaignDetail = { ...campaign, batches: [batch] }
const context: ReportContext = {
  from: '2026-10-01', to: '2026-10-31', fromUtc: '2026-09-30T16:00:00Z', toUtcExclusive: '2026-10-31T16:00:00Z',
  timeZone: 'Asia/Shanghai', sourceSystem: 'ALL', receiverType: 'ALL', metricVersion: 'P2_FACT_V1', sourceWatermark: '2026-10-26T08:00:00Z',
}
const money = {
  paidFen: 300000, platformPaidFen: 300000, institutionPaidFen: 0,
  refundFen: 10000, platformRefundFen: 10000, institutionRefundFen: 0,
  netPaidFen: 290000, platformNetPaidFen: 290000, institutionNetPaidFen: 0,
}

function analytics(): CampaignAnalyticsReport {
  return {
    ...context,
    totals: {
      ...money,
      paidOrderCount: 4,
      refundCount: 1,
      confirmedAppointmentCount: 6,
      checkedInCustomerCount: 3,
      checkedInAppointmentCount: 3,
      redemptionBatchCount: 1,
      redeemedCount: 2,
      touchCustomerCount: null,
      touchStatus: 'SOURCE_NOT_AVAILABLE',
    },
    items: [{
      ...money,
      campaignId: 81,
      campaignBatchId: 901,
      campaignCode: 'CMP-TEST',
      campaignBatchCode: 'WAVE-TEST',
      campaignName: '契约测试活动',
      campaignBatchName: '测试批次',
      paidOrderCount: 4,
      refundCount: 1,
      confirmedAppointmentCount: 6,
      checkedInCustomerCount: 3,
      checkedInAppointmentCount: 3,
      redemptionBatchCount: 1,
      redeemedCount: 2,
      touchCustomerCount: null,
      touchStatus: 'SOURCE_NOT_AVAILABLE',
      conversionRates: { contactToPaid: pendingRatio, appointmentToCheckedIn: pendingRatio },
      sourceWatermark: '2026-10-26T08:00:00Z',
    }],
    total: 1,
    page: 1,
    size: 20,
    drillDown: [{
      factType: 'PAYMENT', sourceId: 601, orderId: 9901, customerId: 79, campaignId: 81, campaignBatchId: 901, skuId: null,
      eventAt: '2026-10-11T02:00:00Z', amountFen: 300000, quantity: null, sourceSystem: 'MANUAL', receiverType: 'PLATFORM',
    }],
    drillDownType: 'PAYMENT',
    drillDownLimit: 50,
  }
}

const wrappers: VueWrapper[] = []
function mountView(permissions = ['report:read', 'campaign:read', 'campaign:write']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  const user: AuthUser = { userId: 1, username: 'campaign-test', displayName: '活动测试', userType: 'EMPLOYEE', roles: ['ADMIN'], permissions }
  auth.user = user
  auth.initialized = true
  const wrapper = mount(CampaignsView, { global: { plugins: [ElementPlus, pinia] }, attachTo: document.body })
  wrappers.push(wrapper)
  return wrapper
}

function nativeInput(testId: string): HTMLInputElement | null {
  return document.body.querySelector<HTMLInputElement>(`input[data-testid="${testId}"], [data-testid="${testId}"] input`)
}

async function setInput(testId: string, value: string) {
  const input = nativeInput(testId)
  expect(input, testId).not.toBeNull()
  input!.value = value
  input!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function clickDrawerButton(testId: string) {
  const button = document.body.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)
  expect(button, testId).not.toBeNull()
  button!.click()
  await flushPromises()
}

async function fillCreateForm() {
  await setInput('campaign-code', 'CMP-NEW-TEST')
  await setInput('campaign-name', '新建契约活动')
  await setInput('batch-code', 'WAVE-NEW')
  await setInput('batch-name', '新建批次')
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getCampaignAnalytics).mockResolvedValue(analytics())
  vi.mocked(listCampaigns).mockResolvedValue({ items: [campaign], total: 1, page: 1, size: 20 })
  vi.mocked(getCampaign).mockResolvedValue(detail)
  vi.mocked(createCampaign).mockResolvedValue(campaign)
  vi.mocked(updateCampaign).mockResolvedValue({ ...campaign, version: 4 })
  vi.mocked(createCampaignBatch).mockResolvedValue(batch)
  vi.mocked(updateCampaignBatch).mockResolvedValue({ ...batch, version: 3 })
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('CampaignsView 活动分析与维护', () => {
  it('展示真实批次指标、触达空值和 Q13 状态，下钻携带活动批次并展示真实事实', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.proto-stat-card .number').map((node) => node.text())).toEqual(['待确认', '4 单', '¥3000.00', '待确认'])
    expect(wrapper.text()).toContain('¥100.00')
    expect(wrapper.text()).toMatch(/2026-10-26 \d{2}:\d{2}:\d{2}/)
    expect(wrapper.text()).toContain('渠道归因以成交时快照为准')
    expect(wrapper.text()).not.toContain('0.00%')
    expect(wrapper.find('[data-testid="campaign-channel"]').exists()).toBe(false)

    await wrapper.get('[data-testid="campaign-report-drilldown"]').trigger('click')
    await flushPromises()
    expect(getCampaignAnalytics).toHaveBeenLastCalledWith(expect.objectContaining({ campaignId: 81, campaignBatchId: 901, drillDownType: 'PAYMENT' }))
    expect(document.body.textContent).toContain('#9901')
    expect(document.body.textContent).toContain('支付流水')
  })

  it('创建表单包含批次状态，分别调用活动与追加批次接口并携带幂等键', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="campaign-create"]').trigger('click')
    await flushPromises()
    await fillCreateForm()
    await clickDrawerButton('campaign-save')

    expect(createCampaign).toHaveBeenCalledWith({
      campaignCode: 'CMP-NEW-TEST', campaignName: '新建契约活动', campaignType: 'REGULAR', startsAt: null, endsAt: null, status: 'DRAFT',
    }, expect.any(String))
    expect(createCampaignBatch).toHaveBeenCalledWith(81, {
      batchCode: 'WAVE-NEW', batchName: '新建批次', startsAt: null, endsAt: null, status: 'DRAFT',
    }, expect.any(String))
    const createBody = vi.mocked(createCampaign).mock.calls[0]?.[0]
    expect(createBody).not.toHaveProperty('channelId')
    expect(createBody).not.toHaveProperty('businessOwnerUserId')
  })

  it('编辑活动与已有批次使用详情版本和 PATCH，不允许覆盖固定编码', async () => {
    const wrapper = mountView()
    await flushPromises()
    const managementTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '活动管理')
    await managementTab?.trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="campaign-edit"]').trigger('click')
    await flushPromises()

    expect(getCampaign).toHaveBeenCalledWith(81)
    expect(nativeInput('campaign-code')!.disabled).toBe(true)
    expect(nativeInput('batch-code')!.disabled).toBe(true)
    await setInput('campaign-name', '活动新名称')
    await setInput('batch-name', '批次新名称')
    await clickDrawerButton('campaign-save')

    expect(updateCampaign).toHaveBeenCalledWith(81, expect.objectContaining({ campaignName: '活动新名称', version: 3 }), expect.any(String))
    expect(updateCampaignBatch).toHaveBeenCalledWith(81, 901, expect.objectContaining({ batchName: '批次新名称', status: 'ACTIVE', version: 2 }), expect.any(String))
  })

  it('仅修改批次时不覆盖未变更的活动主表', async () => {
    const wrapper = mountView()
    await flushPromises()
    const managementTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '活动管理')
    await managementTab?.trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="campaign-edit"]').trigger('click')
    await flushPromises()
    await setInput('batch-name', '仅批次变更')
    await clickDrawerButton('campaign-save')

    expect(updateCampaign).not.toHaveBeenCalled()
    expect(updateCampaignBatch).toHaveBeenCalledWith(81, 901, expect.objectContaining({ batchName: '仅批次变更', version: 2 }), expect.any(String))
  })

  it('批次失败后保留错误，重试不重复创建活动且沿用批次幂等键', async () => {
    vi.mocked(createCampaignBatch)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '批次保存连接失败' }))
      .mockResolvedValueOnce(batch)
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="campaign-create"]').trigger('click')
    await flushPromises()
    await fillCreateForm()
    await clickDrawerButton('campaign-save')

    expect(document.body.textContent).toContain('活动已创建，但批次未全部保存')
    expect(document.body.textContent).toContain('批次保存连接失败')
    expect(createCampaign).toHaveBeenCalledTimes(1)
    const firstKey = vi.mocked(createCampaignBatch).mock.calls[0]?.[2]

    await clickDrawerButton('campaign-save')
    expect(createCampaign).toHaveBeenCalledTimes(1)
    expect(createCampaignBatch).toHaveBeenCalledTimes(2)
    expect(vi.mocked(createCampaignBatch).mock.calls[1]?.[2]).toBe(firstKey)
  })

  it('只读账号不展示创建或编辑，分析错误与管理空态分别呈现', async () => {
    vi.mocked(getCampaignAnalytics).mockRejectedValueOnce(new ApiError({ status: 500, code: 'REPORT_FAILED', message: '活动分析暂不可用' }))
    vi.mocked(listCampaigns).mockResolvedValueOnce({ items: [], total: 0, page: 1, size: 20 })
    const wrapper = mountView(['report:read', 'campaign:read'])
    await flushPromises()

    expect(wrapper.text()).toContain('活动分析暂不可用')
    expect(wrapper.find('[data-testid="campaign-create"]').exists()).toBe(false)
    const managementTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '活动管理')
    await managementTab?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无活动，具备维护权限时可创建活动与批次')
    expect(wrapper.find('[data-testid="campaign-edit"]').exists()).toBe(false)
  })

  it('活动时间按 Asia/Shanghai 与 ISO Instant 双向转换', () => {
    expect(shanghaiInputToInstant('2026-11-01T09:00:00')).toBe('2026-11-01T01:00:00.000Z')
    expect(instantToShanghaiInput('2026-11-01T01:00:00Z')).toBe('2026-11-01T09:00:00')
    expect(shanghaiInputToInstant(null)).toBeNull()
  })
})
