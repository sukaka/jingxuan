import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  confirmComplaintHit,
  getComplaint,
  listComplaintHits,
  listComplaints,
} from '../src/api/complaint'
import type { ComplaintCase, ComplaintCaseDetail, ComplaintHit } from '../src/api/complaint'
import ComplaintsView from '../src/views/ComplaintsView.vue'

vi.mock('../src/api/complaint', () => ({
  listComplaintHits: vi.fn(),
  getComplaintHit: vi.fn(),
  confirmComplaintHit: vi.fn(),
  dismissComplaintHit: vi.fn(),
  listComplaints: vi.fn(),
  getComplaint: vi.fn(),
  assignComplaint: vi.fn(),
  resolveComplaint: vi.fn(),
  closeComplaint: vi.fn(),
  dismissComplaint: vi.fn(),
}))

let permissions = ['complaint:read', 'complaint:write']
vi.mock('../src/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: (code: string) => permissions.includes(code) }),
}))

const wrappers: VueWrapper[] = []

function hit(overrides: Partial<ComplaintHit> = {}): ComplaintHit {
  return {
    id: 11,
    customerId: 42,
    ownerStaffIdSnapshot: 2,
    teamIdSnapshot: 1,
    sourceType: 'FOLLOWUP',
    sourceId: '81',
    keywordId: 7,
    keywordSnapshot: '投诉',
    evidenceMasked: '我要投诉，电话138****8000',
    status: 'OPEN',
    routingStatus: 'ROUTED',
    routingError: null,
    recipientUserIdSnapshot: 4,
    recipientSourceSnapshot: 'TEAM_LEADER',
    policyVersionSnapshot: 0,
    complaintCaseId: null,
    reviewReasonMasked: null,
    reviewedBy: null,
    reviewedAt: null,
    createdAt: '2026-11-11T01:00:00Z',
    updatedAt: '2026-11-11T01:00:00Z',
    version: 2,
    ...overrides,
  }
}

function complaintCase(overrides: Partial<ComplaintCase> = {}): ComplaintCase {
  return {
    id: 19,
    complaintCaseNo: 'CP000000000011',
    complaintHitId: 11,
    customerId: 42,
    ownerStaffIdSnapshot: 2,
    teamIdSnapshot: 1,
    recipientUserIdSnapshot: 4,
    recipientSourceSnapshot: 'TEAM_LEADER',
    status: 'ASSIGNED',
    assigneeUserId: 4,
    resolutionMasked: null,
    resolvedAt: null,
    closedAt: null,
    dismissReasonMasked: null,
    createdBy: 4,
    createdAt: '2026-11-11T01:00:00Z',
    updatedAt: '2026-11-11T01:00:00Z',
    version: 5,
    ...overrides,
  }
}

function page<T>(items: T[]) {
  return { items, total: items.length, page: 1, size: 20 }
}

async function mountView() {
  const wrapper = mount(ComplaintsView)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  permissions = ['complaint:read', 'complaint:write']
  vi.clearAllMocks()
  vi.mocked(listComplaintHits).mockResolvedValue(page([hit()]))
  vi.mocked(listComplaints).mockResolvedValue(page([complaintCase()]))
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  vi.restoreAllMocks()
})

describe('ComplaintsView 客诉核查与闭环', () => {
  it('命中明确展示为待核查而非正式客诉，确认时防止双击并使用当前版本', async () => {
    let resolveConfirm!: (value: ComplaintCaseDetail) => void
    vi.mocked(confirmComplaintHit).mockReturnValue(new Promise((resolve) => { resolveConfirm = resolve }))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('待核查')
    expect(wrapper.text()).toContain('不是正式客诉')
    expect(wrapper.text()).toContain('138****8000')
    await wrapper.get('[data-testid="confirm-hit-11"]').trigger('click')
    await wrapper.get('[data-testid="action-reason"]').setValue('人工电话核实')
    await wrapper.get('[data-testid="action-submit"]').trigger('submit')
    await wrapper.get('[data-testid="action-submit"]').trigger('submit')

    expect(confirmComplaintHit).toHaveBeenCalledTimes(1)
    expect(confirmComplaintHit).toHaveBeenCalledWith(11, { version: 2, reason: '人工电话核实' }, expect.any(String))
    resolveConfirm({ complaintCase: complaintCase(), history: [], empathyScript: '仅供复制' })
    await flushPromises()
  })

  it('无接收人的命中展示异常，且不能由前端伪造接收人', async () => {
    vi.mocked(listComplaintHits).mockResolvedValue(page([hit({
      routingStatus: 'EXCEPTION', routingError: 'NO_RECIPIENT', recipientUserIdSnapshot: null,
      recipientSourceSnapshot: 'NONE',
    })]))
    const wrapper = await mountView()

    expect(wrapper.get('[data-testid="routing-exception-11"]').text()).toContain('无接收人')
    expect(wrapper.find('input[name="recipientUserId"]').exists()).toBe(false)
  })

  it('多条共情话术可选择复制，不调用任何发送或账务接口', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    vi.mocked(getComplaint).mockResolvedValue({
      complaintCase: complaintCase(),
      history: [],
      empathyScript: '我们理解您的感受，会由专人核实。',
      empathyScripts: [
        { id: 1, title: '首次响应', script: '我们理解您的感受，会由专人核实。', status: 'ACTIVE', createdBy: 1, createdAt: '', updatedAt: '', version: 0 },
        { id: 2, title: '进度跟进', script: '感谢您的耐心等待，我们会持续同步处理进度。', status: 'ACTIVE', createdBy: 1, createdAt: '', updatedAt: '', version: 0 },
      ],
    })
    const wrapper = await mountView()
    await wrapper.get('[data-testid="cases-tab"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-detail-19"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="empathy-script-select"]').setValue('2')
    await wrapper.get('[data-testid="copy-empathy"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('感谢您的耐心等待，我们会持续同步处理进度。')
    expect(wrapper.text()).toContain('共 2 条')
    expect(wrapper.text()).toContain('话术已复制，需人工确认后自行发送')
    expect(confirmComplaintHit).not.toHaveBeenCalled()
  })

  it('无读取权限时不发起请求', async () => {
    permissions = []
    const wrapper = await mountView()

    expect(wrapper.get('[data-testid="complaint-permission-empty"]').text()).toContain('没有客诉查看权限')
    expect(listComplaintHits).not.toHaveBeenCalled()
  })
})
