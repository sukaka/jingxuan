import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Entitlement, EntitlementDetail } from '../src/api/trading'
import { getEntitlement, listEntitlements } from '../src/api/trading'
import EntitlementsView from '../src/views/EntitlementsView.vue'

vi.mock('../src/api/trading', () => ({
  listEntitlements: vi.fn(),
  getEntitlement: vi.fn(),
}))

const entitlement: Entitlement = {
  id: 81,
  customerId: 42,
  orderItemId: 711,
  projectId: 101,
  projectName: '测试项目',
  grantedCount: 10,
  availableCount: 8,
  versionNo: 1,
  validFrom: '2026-10-24',
  validTo: '2027-10-24',
  status: 'AVAILABLE',
  createdAt: '2026-10-24T01:03:00Z',
  version: 0,
}

const detail: EntitlementDetail = {
  entitlement,
  availableBalance: 8,
  ledger: [
    {
      id: 9001,
      entitlementId: 81,
      txnType: 'GRANT',
      deltaCount: 10,
      sourceType: 'PAYMENT',
      sourceRef: '7111',
      idempotencyKey: 'entitlement.grant:71:711:101',
      actorUserId: 9,
      reason: '支付确认发放',
      createdAt: '2026-10-24T01:03:00Z',
      version: 0,
    },
    {
      id: 9002,
      entitlementId: 81,
      txnType: 'REDEEM',
      deltaCount: -2,
      sourceType: 'REDEMPTION',
      sourceRef: '701',
      idempotencyKey: 'redemption.post:701:81',
      actorUserId: 88,
      reason: '机构确认正式核销',
      createdAt: '2026-10-25T03:00:00Z',
      version: 0,
    },
  ],
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

function mountView() {
  const wrapper = mount(EntitlementsView, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('EntitlementsView 权益列表、搜索与流水', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listEntitlements).mockResolvedValue({ items: [entitlement], total: 1, page: 1, size: 100 })
    vi.mocked(getEntitlement).mockResolvedValue(detail)
  })

  it('列表通过详情补充累计核销，关键词只筛选已加载真实数据', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(listEntitlements).toHaveBeenCalledWith({ page: 1, size: 100 })
    expect(getEntitlement).toHaveBeenCalledWith(81)
    expect(wrapper.text()).toContain('测试项目')
    expect(wrapper.text()).toContain('客户 #42')
    const redeemedCard = wrapper.findAll('.proto-stat-card').find((card) => card.text().includes('已正式核销'))
    expect(redeemedCard?.text()).toContain('2 次')
    expect(redeemedCard?.text()).toContain('仅统计正式核销流水')
    expect(redeemedCard?.text()).not.toContain('REDEEM')

    await wrapper.get('.proto-toolbar input').setValue('不存在的客户')
    await flushPromises()
    expect(wrapper.text()).not.toContain('测试项目')
    expect(wrapper.text()).toContain('暂无权益；可信支付确认后才会发放')
    expect(listEntitlements).toHaveBeenCalledTimes(1)

    await wrapper.get('.proto-toolbar input').setValue('42')
    await flushPromises()
    expect(wrapper.text()).toContain('测试项目')
  })

  it('流水详情使用权益 ID 对应的权威余额、GRANT 与 REDEEM 流水', async () => {
    const wrapper = mountView()
    await flushPromises()

    const ledgerButton = wrapper.findAll('button').find((button) => button.text() === '流水详情')
    expect(ledgerButton).toBeDefined()
    await ledgerButton!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))

    expect(getEntitlement).toHaveBeenCalledWith(81)
    expect(document.body.textContent).toContain('权威流水汇总可用次数')
    expect(document.body.textContent).toContain('权益发放')
    expect(document.body.textContent).toContain('正式核销')
    expect(document.body.textContent).toContain('支付确认')
    expect(document.body.textContent).toContain('机构核销')
    expect(document.body.textContent).not.toContain('PAYMENT')
    expect(document.body.textContent).not.toContain('REDEMPTION')
    expect(document.body.textContent).toContain('+10')
    expect(document.body.textContent).toContain('-2')
    expect(document.body.textContent).toContain('entitlement.grant:71:711:101')
    expect(document.body.textContent).toContain('当前不开放管家正式核销')
  })
})
