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
  it('管理员看到完整业务分区（含购买商品明细），Raw 默认折叠并在展开后格式化', async () => {
    mountDrawer(['order:read', 'integration:youzan:manage'])
    await settle()

    expect(document.body.textContent).toContain('订单与同步状态')
    expect(document.body.textContent).toContain('购买商品明细')
    expect(document.body.textContent).toContain('金额与支付核验')
    expect(document.body.textContent).toContain('来源与时间')
    expect(document.body.textContent).toContain('异常信息')
    const headings = Array.from(document.body.querySelectorAll('.detail-section h3')).map((el) => el.textContent?.trim())
    expect(headings.slice(0, 5)).toEqual(['订单与同步状态', '购买商品明细', '金额与支付核验', '来源与时间', '异常信息'])
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

  it('购买商品明细展示标题、规格、数量、单价、小计、外部 SKU、商家编码与 CRM 映射状态', async () => {
    mountDrawer(['order:read', 'integration:youzan:manage'], {
      ...order,
      items: [
        {
          external_sku_id: '5505085839:26076889518',
          quantity: 2,
          itemTitle: '鲸选光子嫩肤尊享卡',
          skuSpec: '3次卡 / 全国通用',
          priceFen: 150000,
          paymentFen: 298000,
          outerSkuCode: 'OUTER-GZ-3',
          crmSkuId: 301,
          crmSkuName: '光子嫩肤尊享套卡',
        },
        {
          external_sku_id: '5505085839:990001',
          quantity: 1,
          itemTitle: '水光焕肤体验装',
          skuSpec: null,
          priceFen: 64200,
          paymentFen: null,
          outerSkuCode: null,
          crmSkuId: null,
          crmSkuName: null,
        },
        {
          external_sku_id: '5505085839:990002',
          quantity: 1,
          itemTitle: null,
          skuSpec: null,
          priceFen: null,
          paymentFen: null,
          outerSkuCode: null,
          crmSkuId: 302,
          crmSkuName: null,
        },
      ],
    })
    await settle()

    const section = document.body.querySelector('[data-testid="youzan-order-items"]')
    expect(section).not.toBeNull()
    expect(document.body.querySelector('[data-testid="youzan-order-items-summary"]')?.textContent).toBe('共 3 项 · 合计 4 件')

    const cards = Array.from(document.body.querySelectorAll('[data-testid="youzan-order-item"]'))
    expect(cards).toHaveLength(3)

    expect(cards[0]?.textContent).toContain('鲸选光子嫩肤尊享卡')
    expect(cards[0]?.textContent).toContain('3次卡 / 全国通用')
    expect(cards[0]?.textContent).toContain('× 2')
    expect(cards[0]?.textContent).toContain('单价 ¥1500.00')
    expect(cards[0]?.textContent).toContain('小计 ¥2980.00')
    expect(cards[0]?.textContent).toContain('5505085839:26076889518')
    expect(cards[0]?.textContent).toContain('OUTER-GZ-3')
    expect(cards[0]?.textContent).toContain('已映射 CRM：光子嫩肤尊享套卡 #301')

    expect(cards[1]?.textContent).toContain('水光焕肤体验装')
    expect(cards[1]?.textContent).toContain('× 1')
    expect(cards[1]?.textContent).toContain('单价 ¥642.00')
    expect(cards[1]?.textContent).not.toContain('小计 ¥')
    expect(cards[1]?.textContent).toContain('5505085839:990001')
    expect(cards[1]?.textContent).toContain('未映射 CRM SKU')

    expect(cards[2]?.textContent).toContain('外部 SKU 5505085839:990002')
    expect(cards[2]?.textContent).toContain('已映射 CRM SKU #302')
  })

  it('itemTitle 缺失时依次回退 crmSkuName 与外部 SKU 标题，且仅在 order.items 缺失时回退 raw.items', async () => {
    mountDrawer(['integration:youzan:manage'], {
      ...order,
      items: [
        {
          external_sku_id: 'YZ-SKU-FALLBACK-1',
          quantity: 1,
          itemTitle: null,
          crmSkuId: 401,
          crmSkuName: '热玛吉面部标准版',
        },
        {
          external_sku_id: 'YZ-SKU-FALLBACK-2',
          quantity: 3,
          itemTitle: null,
          crmSkuId: null,
          crmSkuName: null,
        },
      ],
      payloadMaskedJson: '{"items":[{"external_sku_id":"RAW-IGNORED","quantity":9}]}',
    })
    await settle()

    const cards = Array.from(document.body.querySelectorAll('[data-testid="youzan-order-item"]'))
    expect(cards).toHaveLength(2)
    expect(cards[0]?.textContent).toContain('热玛吉面部标准版')
    expect(cards[1]?.textContent).toContain('外部 SKU YZ-SKU-FALLBACK-2')
    expect(document.body.textContent).not.toContain('RAW-IGNORED')

    while (wrappers.length) wrappers.pop()?.unmount()
    document.body.innerHTML = ''

    mountDrawer(['integration:youzan:manage'], {
      ...order,
      items: [],
      payloadMaskedJson: '{"items":[{"external_sku_id":"RAW-SKU-001","quantity":2,"item_title":"历史回退商品","sku_spec":"双次卡","price_fen":88000}]}',
    })
    await settle()

    const fallbackCards = Array.from(document.body.querySelectorAll('[data-testid="youzan-order-item"]'))
    expect(fallbackCards).toHaveLength(1)
    expect(fallbackCards[0]?.textContent).toContain('历史回退商品')
    expect(fallbackCards[0]?.textContent).toContain('双次卡')
    expect(fallbackCards[0]?.textContent).toContain('× 2')
    expect(fallbackCards[0]?.textContent).toContain('单价 ¥880.00')
    expect(fallbackCards[0]?.textContent).toContain('RAW-SKU-001')
  })

  it('无商品明细时展示空状态与 0 项汇总', async () => {
    mountDrawer(['order:read'], {
      ...order,
      items: [],
      payloadMaskedJson: null,
    })
    await settle()

    expect(document.body.querySelector('[data-testid="youzan-order-items-summary"]')?.textContent).toBe('共 0 项')
    expect(document.body.querySelector('[data-testid="youzan-order-items-empty"]')?.textContent).toContain('当前订单暂无商品明细')
    expect(document.body.querySelectorAll('[data-testid="youzan-order-item"]')).toHaveLength(0)
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

  it('普通管家不渲染 Raw、展开入口或复制按钮，但可看到结构化商品明细与非敏感外部客户标识', async () => {
    mountDrawer(['order:read'], {
      ...order,
      payloadMaskedJson: null,
      externalCustomerId: 'yz-open-detail-1',
      items: [
        {
          external_sku_id: '5505085839:112233',
          quantity: 1,
          itemTitle: '舒敏修复护理',
          skuSpec: '单次体验',
          priceFen: 59900,
          paymentFen: 59900,
          crmSkuId: 301,
          crmSkuName: '舒敏修复单次卡',
        },
      ],
    })
    await settle()

    const itemsSection = document.body.querySelector('[data-testid="youzan-order-items"]')
    expect(itemsSection?.textContent).toContain('舒敏修复护理')
    expect(itemsSection?.textContent).toContain('单次体验')
    expect(itemsSection?.textContent).toContain('单价 ¥599.00')
    expect(itemsSection?.textContent).toContain('小计 ¥599.00')
    expect(itemsSection?.textContent).toContain('已映射 CRM：舒敏修复单次卡 #301')
    expect(document.body.querySelector('[data-testid="youzan-detail-external-customer"]')?.textContent).toBe('yz-open-detail-1')
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
    expect(drawerSource).not.toContain('v-html')
  })

  it('展开原始数据按钮在浅色卡片上使用深青高对比文字而非白色文字', () => {
    const rawToggleBlocks = drawerSource.match(/[^}]*\.raw-toggle[^}]*\{[^}]*\}/g) ?? []
    expect(rawToggleBlocks.length).toBeGreaterThanOrEqual(3)
    for (const block of rawToggleBlocks) {
      expect(block).not.toMatch(/color\s*:\s*(?:#fff(?:fff)?|white)\b/i)
    }
    expect(drawerSource).not.toContain('.proto-page .raw-header :deep(.raw-toggle) { color: #fff; }')
    expect(drawerSource).toContain('color: #175c55;')
    expect(drawerSource).toContain('color: #0f443f;')
    expect(drawerSource).toContain('background-color: #eaf4f2;')
  })
})
