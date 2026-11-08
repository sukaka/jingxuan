import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { RiskCase, RiskCaseDetail } from '../src/api/risk'
import { claimRiskCase, closeRiskCase, getRiskCase, listRiskCases, resolveRiskCase } from '../src/api/risk'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import RiskCasesView from '../src/views/RiskCasesView.vue'

vi.mock('../src/api/risk', () => ({
  listRiskCases: vi.fn(),
  getRiskCase: vi.fn(),
  claimRiskCase: vi.fn(),
  resolveRiskCase: vi.fn(),
  closeRiskCase: vi.fn(),
}))

function risk(status: string, version = 0): RiskCase {
  return {
    id: 31,
    riskCaseNo: 'RISK-TEST',
    riskType: 'MAPPING',
    severity: 'HIGH',
    sourceType: 'YOUZAN_ORDER',
    sourceRef: 'ORDER-TEST',
    status,
    ownerUserId: status === 'OPEN' ? null : 1,
    evidence: '<img src=x onerror=alert(1)>测试风险证据',
    resolution: status === 'RESOLVED' || status === 'CLOSED' ? '测试处理结论' : null,
    resolvedAt: null,
    createdAt: '2026-10-25T01:00:00Z',
    updatedAt: '2026-10-25T01:00:00Z',
    version,
    orderId: null,
    orderNo: null,
    customerId: null,
    customerNo: null,
    customerName: null,
  }
}

function detail(item: RiskCase): RiskCaseDetail {
  return {
    riskCase: item,
    history: [{
      id: 501,
      riskCaseId: item.id,
      action: 'OPEN',
      fromStatus: null,
      toStatus: 'OPEN',
      actorUserId: null,
      note: '服务端原始风险记录',
      createdAt: '2026-10-25T01:00:00Z',
    }],
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function mountView(permissions = ['risk:read', 'risk:write']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1,
    username: 'risk-test',
    displayName: '风控测试账号',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  } satisfies AuthUser
  auth.initialized = true
  const wrapper = mount(RiskCasesView, {
    global: { plugins: [pinia, ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function element(testId: string): HTMLElement {
  const hit = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  expect(hit, `缺少 ${testId}`).not.toBeNull()
  return hit!
}

async function click(testId: string) {
  element(testId).dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

async function input(testId: string, value: string) {
  const root = element(testId)
  const field = root.matches('input,textarea')
    ? root as HTMLInputElement | HTMLTextAreaElement
    : root.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')
  expect(field, `缺少输入框 ${testId}`).not.toBeNull()
  field!.value = value
  field!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

describe('RiskCasesView 服务端状态机与追加历史', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listRiskCases).mockResolvedValue({ items: [risk('OPEN')], total: 1, page: 1, size: 20 })
    vi.mocked(getRiskCase).mockResolvedValue(detail(risk('OPEN')))
  })

  it('认领必须填写理由；失败不改本地状态，重试沿用幂等键并由列表刷新确认', async () => {
    const assigned = risk('ASSIGNED', 1)
    vi.mocked(listRiskCases)
      .mockResolvedValueOnce({ items: [risk('OPEN')], total: 1, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [assigned], total: 1, page: 1, size: 20 })
    vi.mocked(claimRiskCase)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '认领请求超时测试' }))
      .mockResolvedValueOnce(detail(assigned))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('待处理')
    expect(wrapper.find('img[src="x"]').exists()).toBe(false)
    await click('risk-claim-31')
    expect(document.body.textContent).toContain('服务端原始风险记录')
    await click('risk-action-submit')
    expect(claimRiskCase).not.toHaveBeenCalled()

    await input('risk-action-reason', '认领核对原始订单映射')
    await click('risk-action-submit')
    expect(wrapper.text()).toContain('待处理')
    expect(document.body.textContent).toContain('认领请求超时测试')
    expect(document.body.querySelectorAll('.risk-history-entry')).toHaveLength(1)

    await click('risk-action-submit')
    expect(claimRiskCase).toHaveBeenCalledTimes(2)
    const calls = vi.mocked(claimRiskCase).mock.calls
    expect(calls[0]?.[1]).toEqual({ version: 0, reason: '认领核对原始订单映射' })
    expect(calls[1]?.[2]).toBe(calls[0]?.[2])
    expect(listRiskCases).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('处理中')
  })

  it('处理完成提交 resolution 和最新 version，历史仍只来自详情接口', async () => {
    const assigned = risk('ASSIGNED', 4)
    vi.mocked(listRiskCases).mockResolvedValue({ items: [assigned], total: 1, page: 1, size: 20 })
    vi.mocked(getRiskCase).mockResolvedValue(detail(assigned))
    vi.mocked(resolveRiskCase).mockResolvedValue(detail(risk('RESOLVED', 5)))
    mountView()
    await flushPromises()

    await click('risk-resolve-31')
    await input('risk-action-reason', '已根据原始订单与映射日志完成核对')
    await click('risk-action-submit')

    expect(resolveRiskCase).toHaveBeenCalledWith(31, {
      version: 4,
      resolution: '已根据原始订单与映射日志完成核对',
    }, expect.any(String))
    expect(closeRiskCase).not.toHaveBeenCalled()
  })

  it('关闭必须携带理由和已处理版本，历史仍由服务端刷新', async () => {
    const resolved = risk('RESOLVED', 6)
    vi.mocked(listRiskCases).mockResolvedValue({ items: [resolved], total: 1, page: 1, size: 20 })
    vi.mocked(getRiskCase).mockResolvedValue(detail(resolved))
    vi.mocked(closeRiskCase).mockResolvedValue(detail(risk('CLOSED', 7)))
    mountView()
    await flushPromises()

    await click('risk-close-31')
    await input('risk-action-reason', '复核处理结论与原始凭证一致，同意关闭')
    await click('risk-action-submit')

    expect(closeRiskCase).toHaveBeenCalledWith(31, {
      version: 6,
      reason: '复核处理结论与原始凭证一致，同意关闭',
    }, expect.any(String))
    expect(resolveRiskCase).not.toHaveBeenCalled()
  })

  it('客户列只展示服务端真实关联，未关联明确待核实且不伪造客户', async () => {
    const linked = {
      ...risk('OPEN'),
      orderId: 81,
      orderNo: 'J000000000081',
      customerId: 91,
      customerNo: 'C0000000091',
      customerName: '合成真实客户',
    }
    const unlinked = { ...risk('OPEN'), id: 32, riskCaseNo: 'RISK-UNLINKED' }
    vi.mocked(listRiskCases).mockResolvedValue({ items: [linked, unlinked], total: 2, page: 1, size: 20 })
    mountView()
    await flushPromises()

    expect(element('risk-customer-31').textContent).toContain('合成真实客户')
    expect(element('risk-customer-31').textContent).toContain('C0000000091')
    expect(element('risk-customer-31').textContent).toContain('J000000000081')
    expect(element('risk-customer-32').textContent).toContain('未关联 / 待核实')
    expect(element('risk-customer-32').textContent).toContain('来源记录尚未形成可靠客户关联')
    expect(element('risk-customer-32').textContent).not.toContain('客户 #0')
  })

  it('有赞同步入口要求风控查看和 order:read 权限', async () => {
    const withoutOrderRead = mountView(['risk:read'])
    await flushPromises()
    expect(withoutOrderRead.find('[data-testid="risk-youzan-sync"]').exists()).toBe(false)
    withoutOrderRead.unmount()

    const withEntry = mountView(['risk:read', 'order:read'])
    await flushPromises()
    expect(element('risk-youzan-sync').textContent).toContain('有赞同步')
    expect(element('risk-youzan-sync').getAttribute('to')).toBe('/integrations/youzan')
    withEntry.unmount()

    const withoutRiskRead = mountView(['order:read'])
    await flushPromises()
    expect(withoutRiskRead.find('[data-testid="risk-youzan-sync"]').exists()).toBe(false)
    expect(listRiskCases).toHaveBeenCalledTimes(2)
  })

  it('只读账号没有动作入口；无查看权限时不请求台账', async () => {
    const readonly = mountView(['risk:read'])
    await flushPromises()
    expect(readonly.find('[data-testid="risk-claim-31"]').exists()).toBe(false)
    readonly.unmount()

    mountView([])
    await flushPromises()
    expect(element('risk-permission-empty').textContent).toContain('没有风控台账查看权限')
    expect(listRiskCases).toHaveBeenCalledTimes(1)
  })
})
