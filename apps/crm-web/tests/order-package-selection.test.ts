import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PackageVersionSnapshot, Sku } from '../src/api/catalog'
import { getPackageVersionSnapshot } from '../src/api/catalog'
import type { CustomerListItem } from '../src/api/customer'
import type { OrderFulfillmentView } from '../src/api/trading'
import { createOrder } from '../src/api/trading'
import OrderCreateDrawer from '../src/views/trading/OrderCreateDrawer.vue'

vi.mock('../src/api/catalog', () => ({ getPackageVersionSnapshot: vi.fn() }))
vi.mock('../src/api/trading', () => ({ createOrder: vi.fn() }))

const sku: Sku = {
  id: 301, skuCode: 'SKU-GROUP-A', packageVersionId: 401, packageId: 201, packageName: '独立价自选套餐',
  versionNo: 1, selectionMode: 'CHOOSE_N', specName: 'A组4选2', salePriceFen: 100000,
  status: 'ACTIVE', onSaleAt: '2026-11-01T00:00:00Z', offSaleAt: null,
  createdAt: '2026-11-01T00:00:00Z', version: 0,
}
const snapshot = {
  id: 401, packageId: 201, packageCode: 'PKG-GROUP', packageName: '独立价自选套餐', versionNo: 1,
  selectionMode: 'CHOOSE_N', choiceCount: 2, selectionRules: null, status: 'ACTIVE', version: 0,
  rulesStatus: 'READY', allocationStatus: 'PENDING_Q02', entitlementIssuable: true,
  groups: [{ id: 1, packageVersionId: 401, groupCode: 'A', groupName: '焕肤组', minSelections: 2, maxSelections: 2, sortOrder: 1, version: 0 }],
  projects: [
    { projectId: 101, projectName: '项目一', optionGroup: 'A', grantCount: 1, referencePriceFen: 80000 },
    { projectId: 102, projectName: '项目二', optionGroup: 'A', grantCount: 1, referencePriceFen: 40000 },
    { projectId: 103, projectName: '项目三', optionGroup: 'A', grantCount: 1, referencePriceFen: 30000 },
    { projectId: 104, projectName: '项目四', optionGroup: 'A', grantCount: 1, referencePriceFen: 20000 },
  ],
  skus: [sku], institutions: [],
} as unknown as PackageVersionSnapshot
const customer = { id: 42, displayName: '测试客户', nickname: '测试客户', customerNo: 'C42' } as CustomerListItem

function component(wrapper: VueWrapper, selector: string): VueWrapper {
  return wrapper.getComponent(selector) as VueWrapper
}

beforeEach(() => {
  vi.mocked(getPackageVersionSnapshot).mockResolvedValue(snapshot)
  vi.mocked(createOrder).mockResolvedValue({ order: { order: { orderNo: 'J42' } } } as unknown as OrderFulfillmentView)
})
afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('OrderCreateDrawer 分组自选', () => {
  it('展示已选/需选与三项金额，未满足组规则不能提交', async () => {
    const wrapper = mount(OrderCreateDrawer, {
      props: { modelValue: true, customers: [customer], skus: [sku] },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()
    const customerSelect = component(wrapper, '[data-testid="order-customer-select"]')
    customerSelect.vm.$emit('update:modelValue', 42)
    customerSelect.vm.$emit('change', 42)
    const skuSelect = component(wrapper, '[data-testid="order-sku-select"]')
    skuSelect.vm.$emit('update:modelValue', 301)
    skuSelect.vm.$emit('change', 301)
    await flushPromises()

    expect(getPackageVersionSnapshot).toHaveBeenCalledWith(401)
    expect(document.body.textContent).toContain('焕肤组')
    expect(document.body.textContent).toContain('已选 0 / 需选 2')
    expect(wrapper.get('[data-testid="order-save-draft"]').attributes('disabled')).toBeDefined()

    const group = component(wrapper, '[data-testid="package-group-A"]')
    group.vm.$emit('update:modelValue', [101])
    await flushPromises()
    expect(document.body.textContent).toContain('已选 1 / 需选 2')
    await wrapper.get('[data-testid="order-save-draft"]').trigger('click')
    expect(createOrder).not.toHaveBeenCalled()

    group.vm.$emit('update:modelValue', [101, 102])
    await flushPromises()
    expect(document.body.textContent).toContain('已选 2 / 需选 2')
    expect(document.body.textContent).toContain('单品参考总价')
    expect(document.body.textContent).toContain('1200.00')
    expect(document.body.textContent).toContain('套餐价')
    expect(document.body.textContent).toContain('1000.00')
    expect(document.body.textContent).toContain('优惠额')
    expect(document.body.textContent).toContain('200.00')
    expect(wrapper.get('[data-testid="order-save-draft"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-testid="order-save-draft"]').trigger('click')
    await flushPromises()

    expect(createOrder).toHaveBeenCalledWith(expect.objectContaining({
      customerId: 42,
      payableFen: 100000,
      items: [{ skuId: 301, quantity: 1, selections: [
        { groupCode: 'A', projectId: 101 },
        { groupCode: 'A', projectId: 102 },
      ] }],
    }))
    wrapper.unmount()
  })
})
