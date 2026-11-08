import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { CrmOrder, OrderFulfillmentView } from '../src/api/trading'
import { getOrder, listOrders } from '../src/api/trading'
import { useAuthStore } from '../src/stores/auth'
import OrdersView from '../src/views/OrdersView.vue'

vi.mock('../src/api/trading', () => ({
  listOrders: vi.fn(),
  createOrder: vi.fn(),
  getOrder: vi.fn(),
  submitOrder: vi.fn(),
  cancelOrder: vi.fn(),
  confirmPayment: vi.fn(),
  listEntitlements: vi.fn(),
  getEntitlement: vi.fn(),
}))

vi.mock('../src/api/customer', () => ({
  listCustomers: vi.fn(),
}))

vi.mock('../src/api/catalog', () => ({
  listSkus: vi.fn(),
}))

function makeUser(permissions: string[]): AuthUser {
  return {
    userId: 9,
    username: 'trading-test',
    displayName: '交易测试用户',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  }
}

function makeOrder(status: CrmOrder['status']): CrmOrder {
  return {
    id: 71,
    orderNo: 'J000000000071',
    customerId: 42,
    customerName: '测试客户',
    soldByUserId: 9,
    ownerAtSaleId: 9,
    externalOrderNo: null,
    sourceSystem: 'CRM',
    orderTypeCode: 'REGULAR',
    campaignId: null,
    campaignBatchId: null,
    status,
    receiverType: 'PLATFORM',
    listAmountFen: 22000,
    discountAmountFen: 2100,
    payableFen: 19900,
    paidFen: status === 'PAID' ? 19900 : 0,
    refundedFen: 0,
    orderedAt: '2026-10-24T01:00:00Z',
    paidAt: status === 'PAID' ? '2026-10-24T01:02:03Z' : null,
    createdAt: '2026-10-24T01:00:00Z',
    version: 2,
  }
}

function makeView(order: CrmOrder): OrderFulfillmentView {
  return {
    order: {
      order,
      items: [{
        id: 711,
        orderId: order.id,
        skuId: 301,
        packageVersionId: 201,
        skuCodeSnapshot: 'SKU-TEST-301',
        skuNameSnapshot: '测试套餐｜3次',
        purchaseQuantity: 1,
        listAmountFen: 22000,
        paidAllocatedFen: order.status === 'PAID' ? 19900 : 0,
        entitlementSnapshot: JSON.stringify({ allocationStatus: 'CONFIGURED', institutionIds: [91, 92] }),
        version: 0,
      }],
      payments: [],
      history: [],
    },
    entitlements: [],
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

function mountView(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(OrdersView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('OrdersView 订单列表与操作权限', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listOrders).mockResolvedValue({ items: [makeOrder('DRAFT')], total: 1, page: 1, size: 20 })
    vi.mocked(getOrder).mockImplementation(async () => makeView(makeOrder('DRAFT')))
  })

  it('渲染真实订单字段，并通过详情补充卡项和机构', async () => {
    const wrapper = mountView(makeUser(['order:read', 'order:write']))
    await flushPromises()

    expect(listOrders).toHaveBeenCalledWith({ page: 1, size: 20, customerId: undefined, status: undefined })
    expect(getOrder).toHaveBeenCalledWith(71)
    expect(wrapper.text()).toContain('J000000000071')
    expect(wrapper.text()).toContain('测试客户')
    expect(wrapper.text()).toContain('测试套餐｜3次')
    expect(wrapper.text()).toContain('机构 #91、机构 #92')
    expect(wrapper.text()).toContain('0.00')
    expect(wrapper.text()).toContain('已支付订单金额、项目和次数不可覆盖')
    expect(wrapper.find('.proto-pill.teal').exists()).toBe(true)
    expect(wrapper.find('.proto-pill.warning').exists()).toBe(true)
    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons).toContain('＋ 新建订单')
    expect(buttons).toContain('提交')
    expect(buttons).toContain('取消')
  })

  it('已支付订单列表把实付整数分转为元', async () => {
    const paid = makeOrder('PAID')
    vi.mocked(listOrders).mockResolvedValue({ items: [paid], total: 1, page: 1, size: 20 })
    vi.mocked(getOrder).mockResolvedValue(makeView(paid))
    const wrapper = mountView(makeUser(['order:read']))
    await flushPromises()

    expect(wrapper.text()).toContain('199.00')
    expect(wrapper.text()).toContain('已支付')
  })

  it('状态筛选由后端查询，不在前端伪造分页过滤', async () => {
    const wrapper = mountView(makeUser(['order:read', 'order:write']))
    await flushPromises()
    vi.mocked(listOrders).mockClear()

    const statusFilter = wrapper.getComponent('.proto-toolbar .el-select') as VueWrapper
    statusFilter.vm.$emit('update:modelValue', 'DRAFT')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '查询')?.trigger('click')
    await flushPromises()

    expect(listOrders).toHaveBeenCalledWith({ page: 1, size: 20, customerId: undefined, status: 'DRAFT' })
  })

  it('财务只有支付确认权限时可确认，但看不到新建、提交或取消', async () => {
    const pending = makeOrder('PENDING_PAYMENT')
    vi.mocked(listOrders).mockResolvedValue({ items: [pending], total: 1, page: 1, size: 20 })
    const wrapper = mountView(makeUser(['order:read', 'order:confirm_payment']))
    await flushPromises()

    const buttons = wrapper.get('.proto-table-wrap').findAll('button').map((button) => button.text())
    expect(buttons).toContain('确认支付')
    expect(buttons).toContain('修改')
    expect(buttons).not.toContain('提交')
    expect(buttons).not.toContain('取消')
    expect(wrapper.find('[data-testid="order-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="order-save-draft"]').exists()).toBe(false)
    const { listCustomers } = await import('../src/api/customer')
    const { listSkus } = await import('../src/api/catalog')
    expect(listCustomers).not.toHaveBeenCalled()
    expect(listSkus).not.toHaveBeenCalled()
  })

  it('打开详情重新读取真实订单聚合，草稿不伪装支付或权益', async () => {
    const order = makeOrder('DRAFT')
    vi.mocked(getOrder).mockResolvedValue(makeView(order))
    const wrapper = mountView(makeUser(['order:read']))
    await flushPromises()
    vi.mocked(getOrder).mockClear()

    const detailButton = wrapper.findAll('button').find((button) => button.text() === '修改')
    expect(detailButton).toBeDefined()
    await detailButton!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 40))

    expect(getOrder).toHaveBeenCalledWith(71)
    expect(document.body.textContent).toContain('订单行与权益快照')
    expect(document.body.textContent).toContain('测试套餐｜3次')
    expect(document.body.textContent).toContain('当前为草稿：未确认支付，不发放任何可用权益')
    expect(document.body.textContent).toContain('草稿不发放权益')
  })
})
