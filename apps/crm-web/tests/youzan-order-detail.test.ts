import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { YouzanOrderView } from '../src/api/youzan'
import { useAuthStore } from '../src/stores/auth'
import YouzanOrderDetailDrawer from '../src/views/youzan/YouzanOrderDetailDrawer.vue'
import drawerSource from '../src/views/youzan/YouzanOrderDetailDrawer.vue?raw'

const order: YouzanOrderView = {
  mapId: 1,
  shopId: 'MOCK-SHOP-001',
  externalOrderNo: 'YZ-MOCK-PAID-001-WITH-A-VERY-LONG-IDENTIFIER',
  orderId: 71,
  status: 'PROCESSED',
  paidFen: 362200,
  sourceSystem: 'YOUZAN',
  externalShopId: 'MOCK-SHOP-001-WITH-A-VERY-LONG-IDENTIFIER',
  eventId: 'YZ-EVENT-PAID-001',
  mappingStatus: 'PROCESSED',
  orderStatus: 'PAID',
  sourceUpdatedAt: '2026-10-24T03:00:00Z',
  externalAmountFen: 362200,
  verificationMethod: 'PROVIDER_CALLBACK',
  payloadMaskedJson: '{"event_id":"YZ-MOCK-PAID-001","mobile":"***","receiver":{"address":"***"}}',
  errorReason: null,
}

const formattedPayload = JSON.stringify(JSON.parse(order.payloadMaskedJson!), null, 2)
const wrappers: VueWrapper[] = []

function mountDrawer(permissions: string[], value: YouzanOrderView = order) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1,
    username: 'youzan-detail-test',
    displayName: '有赞详情测试',
    userType: 'EMPLOYEE',
    roles: [],
    permissions,
  }
  auth.initialized = true
  const wrapper = mount(YouzanOrderDetailDrawer, {
    props: { modelValue: true, order: value },
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

async function settle() {
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
}

async function click(testId: string) {
  const element = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  expect(element, `缺少 ${testId}`).not.toBeNull()
  element!.click()
  await flushPromises()
}

function rawElement() {
  return document.body.querySelector('[data-testid="youzan-raw-json"]')
}

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('YouzanOrderDetailDrawer 业务信息与脱敏原始详情', () => {
  it('管理员看到四个清晰分区，Raw 默认折叠并在展开后格式化', async () => {
    mountDrawer(['order:read', 'integration:youzan:manage'])
    await settle()

    expect(document.body.textContent).toContain('订单与同步状态')
    expect(document.body.textContent).toContain('金额与支付核验')
    expect(document.body.textContent).toContain('来源与时间')
    expect(document.body.textContent).toContain('异常信息')
    expect(document.body.textContent).toContain('3622.00')
    expect(document.body.textContent).toContain('供应商支付回调')
    expect(document.body.textContent).toContain('处理完成')
    expect(document.body.textContent).toContain('管理员排障')
    expect(rawElement()).toBeNull()
    expect(document.body.textContent).not.toContain('"mobile"')

    await click('youzan-raw-toggle')
    expect(rawElement()?.textContent).toBe(formattedPayload)
    expect(document.body.textContent).toContain('"mobile": "***"')

    await click('youzan-raw-toggle')
    expect(rawElement()).toBeNull()
  })

  it('管理员复制格式化后的 Raw 并收到成功反馈', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
    mountDrawer(['integration:youzan:manage'])
    await settle()
    await click('youzan-raw-toggle')
    await click('youzan-raw-copy')

    expect(writeText).toHaveBeenCalledWith(formattedPayload)
    expect(success).toHaveBeenCalledWith('脱敏原始数据已复制')
  })

  it('复制被拒绝时保留 Raw 并提示失败', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
    mountDrawer(['integration:youzan:manage'])
    await settle()
    await click('youzan-raw-toggle')
    await click('youzan-raw-copy')

    expect(writeText).toHaveBeenCalledWith(formattedPayload)
    expect(error).toHaveBeenCalledWith('复制失败，请手动选择文本')
    expect(rawElement()?.textContent).toBe(formattedPayload)
  })

  it('普通管家不渲染 Raw、展开入口或复制按钮', async () => {
    mountDrawer(['order:read'])
    await settle()

    expect(document.body.querySelector('[data-testid="youzan-raw-section"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="youzan-raw-toggle"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="youzan-raw-copy"]')).toBeNull()
    expect(document.body.textContent).not.toContain('原始脱敏详情')
    expect(document.body.textContent).not.toContain('"mobile"')
  })

  it('非法 JSON 不抛错，展开后保留原文', async () => {
    const invalid = '{"mobile":"***",broken'
    mountDrawer(['integration:youzan:manage'], { ...order, payloadMaskedJson: invalid })
    await settle()
    await click('youzan-raw-toggle')

    expect(rawElement()?.textContent).toBe(invalid)
  })

  it('空 Raw 说明记录缺口且不提供展开或复制', async () => {
    mountDrawer(['integration:youzan:manage'], { ...order, payloadMaskedJson: null })
    await settle()

    expect(document.body.textContent).toContain('当前记录无脱敏原始数据')
    expect(document.body.textContent).toContain('不代表真实有赞连接或真实资金到账')
    expect(document.body.querySelector('[data-testid="youzan-raw-toggle"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="youzan-raw-copy"]')).toBeNull()
  })

  it('移动端切为单列，长键值和 pre 强制换行且抽屉不横向溢出', () => {
    expect(drawerSource).toContain('size="min(820px, 96vw)"')
    expect(drawerSource).toContain('@media (max-width: 640px)')
    expect(drawerSource).toContain('grid-template-columns: minmax(0, 1fr)')
    expect(drawerSource).toContain('overflow-x: hidden')
    expect(drawerSource).toContain('white-space: pre-wrap')
    expect(drawerSource).toContain('overflow-wrap: anywhere')
    expect(drawerSource).toContain('word-break: break-word')
  })
})
