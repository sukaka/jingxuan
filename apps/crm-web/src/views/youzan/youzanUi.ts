import type { YouzanOrderView } from '../../api/youzan'

export type YouzanTone = 'success' | 'warning' | 'info' | 'dark' | 'danger'

export interface NormalizedYouzanOrderItem {
  externalSkuId: string
  quantity: number | null
  itemTitle: string | null
  skuSpec: string | null
  priceFen: number | null
  paymentFen: number | null
  outerSkuCode: string | null
  crmSkuId: number | null
  crmSkuName: string | null
}

const PROCESS_LABELS: Record<string, string> = {
  RECEIVED: '已接收待处理',
  PROCESSED: '处理完成',
  MAPPING_FAILED: 'SKU 映射失败',
  CUSTOMER_UNRESOLVED: '客户待人工匹配',
  PAYMENT_MISMATCH: '支付核验异常',
  RETRY: '等待补偿重试',
  DEAD_LETTER: '补偿失败已停止',
  PENDING_MAPPING: '待映射处理',
  ERROR: '同步异常',
}

const ISSUE_LABELS: Record<string, string> = {
  OPEN: '待处理',
  RETRYING: '补偿重试中',
  RESOLVED: '已解决',
  DEAD: '已终止',
}

const ASSIGNMENT_STATUS_LABELS: Record<string, string> = {
  ASSIGNED: '已分配',
  UNMATCHED: '账号未匹配',
  AMBIGUOUS: '重名冲突',
  NO_ACCOUNT: '备注无账号',
}

const ASSIGNMENT_SOURCE_LABELS: Record<string, string> = {
  AUTO_ALIAS: '别名自动匹配',
  AUTO_DISPLAY_NAME: '姓名自动匹配',
  MANUAL: '人工分配',
  NONE: '尚未分配',
}

const PROVIDER_ORDER_STATUS_LABELS: Record<string, string> = {
  WAIT_BUYER_PAY: '待付款',
  TRADE_PAID: '已支付',
  WAIT_CONFIRM: '待确认',
  WAIT_SELLER_SEND_GOODS: '待发货',
  WAIT_BUYER_CONFIRM_GOODS: '已发货',
  TRADE_SUCCESS: '交易完成',
  TRADE_CLOSED: '交易关闭',
  TRADE_CLOSED_BY_USER: '买家关闭',
}

export function assignmentStatusLabel(status: string | null | undefined): string {
  if (!status) return '待分配'
  return ASSIGNMENT_STATUS_LABELS[status] ?? status
}

export function assignmentStatusTone(status: string | null | undefined): YouzanTone {
  if (status === 'ASSIGNED') return 'success'
  if (status === 'UNMATCHED' || status === 'AMBIGUOUS') return 'warning'
  return 'info'
}

export function assignmentSourceLabel(source: string | null | undefined): string {
  if (!source) return '尚未分配'
  return ASSIGNMENT_SOURCE_LABELS[source] ?? source
}

export function providerOrderStatusLabel(
  status: string | null | undefined,
  statusText?: string | null,
): string | null {
  const cleanText = statusText?.trim()
  if (cleanText) return cleanText
  const cleanStatus = status?.trim()
  if (!cleanStatus) return null
  return PROVIDER_ORDER_STATUS_LABELS[cleanStatus] ?? cleanStatus
}

export function mappingStatusLabel(status: string): string {
  return PROCESS_LABELS[status] ?? '未知状态'
}

export function mappingStatusTone(status: string): YouzanTone {
  if (status === 'PROCESSED') return 'success'
  if (status === 'RECEIVED' || status === 'PENDING_MAPPING' || status === 'RETRY') return 'warning'
  if (status === 'ERROR' || status === 'DEAD_LETTER' || status === 'MAPPING_FAILED' || status === 'CUSTOMER_UNRESOLVED' || status === 'PAYMENT_MISMATCH') return 'danger'
  return 'info'
}

export function verificationMethodLabel(method: string | null | undefined): string {
  if (!method) return '尚未确认'
  return method === 'PROVIDER_CALLBACK' ? '供应商支付回调' : method === 'MANUAL_REVIEW' ? '人工凭证复核' : '其他核验方式'
}

export function issueTypeLabel(issueType: string): string {
  return PROCESS_LABELS[issueType] ?? '其他同步异常'
}

export function issueStatusLabel(status: string): string {
  return ISSUE_LABELS[status] ?? '未知状态'
}

export function issueStatusTone(status: string): YouzanTone {
  if (status === 'RESOLVED') return 'success'
  if (status === 'DEAD') return 'danger'
  if (status === 'OPEN' || status === 'RETRYING') return 'warning'
  return 'info'
}

export function isMappingSuccess(status: string): boolean {
  return status === 'PROCESSED'
}

export function extractUnmappedExternalSkuId(text: string | null | undefined): string | null {
  if (!text) return null
  const match = text.match(/外部\s*SKU\s*未映射(?:或数量无效)?\s*[:：]\s*([^\s,，;；。]+)/)
  const value = match?.[1]?.trim() ?? ''
  if (!value || value.toLowerCase() === 'null') return null
  return value
}

export function createYouzanKey(scope: string): string {
  const suffix = typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) => value.toString(16).padStart(2, '0')).join('')
  return `${scope}:${suffix}`
}

function nonEmptyString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function nonNegativeInt(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isInteger(value) && value >= 0 ? value : null
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!/^\d+$/.test(trimmed)) return null
    const parsed = Number(trimmed)
    return Number.isSafeInteger(parsed) ? parsed : null
  }
  return null
}

function normalizeOrderItem(entry: unknown): NormalizedYouzanOrderItem | null {
  if (!entry || typeof entry !== 'object') return null
  const record = entry as Record<string, unknown>
  const externalSkuId = nonEmptyString(record.external_sku_id) ?? nonEmptyString(record.externalSkuId) ?? ''
  const quantity = nonNegativeInt(record.quantity)
  const itemTitle = nonEmptyString(record.itemTitle)
    ?? nonEmptyString(record.item_title)
    ?? nonEmptyString(record.title)
    ?? nonEmptyString(record.goods_title)
  const skuSpec = nonEmptyString(record.skuSpec) ?? nonEmptyString(record.sku_spec)
  const priceFen = nonNegativeInt(record.priceFen) ?? nonNegativeInt(record.price_fen)
  const paymentFen = nonNegativeInt(record.paymentFen) ?? nonNegativeInt(record.payment_fen)
  const outerSkuCode = nonEmptyString(record.outerSkuCode)
    ?? nonEmptyString(record.outer_sku_code)
    ?? nonEmptyString(record.outer_sku_id)
    ?? nonEmptyString(record.outer_item_id)
  const crmSkuId = nonNegativeInt(record.crmSkuId) ?? nonNegativeInt(record.crm_sku_id)
  const crmSkuName = nonEmptyString(record.crmSkuName) ?? nonEmptyString(record.crm_sku_name)

  if (!externalSkuId && !itemTitle && !crmSkuName && quantity == null) return null

  return {
    externalSkuId,
    quantity,
    itemTitle,
    skuSpec,
    priceFen,
    paymentFen,
    outerSkuCode,
    crmSkuId,
    crmSkuName,
  }
}

export function resolveYouzanOrderItems(order: Pick<YouzanOrderView, 'items' | 'payloadMaskedJson'> | null | undefined): NormalizedYouzanOrderItem[] {
  if (!order) return []
  let source: unknown[] = []
  if (Array.isArray(order.items) && order.items.length > 0) {
    source = order.items
  } else if (order.payloadMaskedJson) {
    try {
      const parsed = JSON.parse(order.payloadMaskedJson) as Record<string, unknown>
      if (parsed && typeof parsed === 'object' && Array.isArray(parsed.items)) {
        source = parsed.items
      }
    } catch {
      source = []
    }
  }
  return source
    .map((entry) => normalizeOrderItem(entry))
    .filter((item): item is NormalizedYouzanOrderItem => item !== null)
}

export function youzanOrderItemTitle(item: NormalizedYouzanOrderItem, mode: 'drawer' | 'table' = 'drawer'): string {
  if (item.itemTitle) return item.itemTitle
  if (item.crmSkuName) return item.crmSkuName
  if (mode === 'drawer') {
    return item.externalSkuId ? `外部 SKU ${item.externalSkuId}` : '未命名商品'
  }
  return item.externalSkuId || '—'
}

export function youzanOrderItemQuantityText(quantity: number | null): string {
  return quantity == null ? '—' : String(quantity)
}

export function youzanOrderItemsTotalQuantity(items: NormalizedYouzanOrderItem[]): number {
  return items.reduce((sum, item) => sum + (item.quantity != null && item.quantity > 0 ? item.quantity : 0), 0)
}

export function youzanOrderItemMappingLabel(item: NormalizedYouzanOrderItem): string {
  if (item.crmSkuId == null) return '未映射 CRM SKU'
  if (item.crmSkuName) return `已映射 CRM：${item.crmSkuName} #${item.crmSkuId}`
  return `已映射 CRM SKU #${item.crmSkuId}`
}
