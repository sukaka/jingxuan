import type { OrderItem } from '../../api/trading'
import { ApiError } from '../../types/api'
import { orderStatusTone as sharedOrderStatusTone } from '../../utils/tagTone'
import type { TagTone } from '../../utils/tagTone'

export type TradingTone = TagTone

const ORDER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  PARTIALLY_REDEEMED: '部分履约',
  FULFILLED: '已完成',
  CANCELED: '已取消',
}

const ENTITLEMENT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待生效',
  AVAILABLE: '可使用',
  EXHAUSTED: '已用完',
  EXPIRED: '已过期',
  REVOKED: '已回收',
}

const LEDGER_TXN_LABELS: Record<string, string> = {
  GRANT: '权益发放',
  REDEEM: '正式核销',
  REFUND_REVOKE: '退款回收',
  CORRECTION_IN: '更正增加',
  CORRECTION_OUT: '更正减少',
  EXTEND: '有效期顺延',
}

export function orderStatusLabel(status: string | null | undefined): string {
  return status ? (ORDER_STATUS_LABELS[status] ?? '未知状态') : '-'
}

export function orderStatusTone(status: string | null | undefined): TradingTone {
  return sharedOrderStatusTone(status)
}

export function entitlementStatusLabel(status: string | null | undefined): string {
  return status ? (ENTITLEMENT_STATUS_LABELS[status] ?? '未知状态') : '-'
}

export function entitlementStatusTone(status: string | null | undefined): TradingTone {
  if (status === 'AVAILABLE') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'EXPIRED' || status === 'REVOKED') return 'danger'
  if (status === 'EXHAUSTED') return 'dark'
  return 'info'
}

export function ledgerTxnTypeLabel(txnType: string): string {
  return LEDGER_TXN_LABELS[txnType] ?? '其他变动'
}

export function ledgerSourceTypeLabel(sourceType: string): string {
  const labels: Record<string, string> = {
    PAYMENT: '支付确认',
    REDEMPTION: '机构核销',
    REDEMPTION_REVERSAL: '核销冲正',
    REFUND: '退款',
    CORRECTION: '人工更正',
    MANUAL: '人工调整',
  }
  return labels[sourceType] ?? '其他来源'
}

export function paymentChannelLabel(channel: string): string {
  return channel === 'BANK_TRANSFER' ? '银行转账' : channel === 'OFFLINE' ? '线下收款' : '未知支付渠道'
}

export function paymentStatusLabel(status: string): string {
  return status === 'CONFIRMED' ? '已确认' : status === 'PENDING' ? '待核验' : status === 'FAILED' ? '失败' : status === 'REVERSED' ? '已冲正' : '未知状态'
}

export function paymentVerificationMethodLabel(method: string): string {
  return method === 'MANUAL_REVIEW' ? '人工凭证复核' : method === 'PROVIDER_CALLBACK' ? '支付渠道回调' : '其他确认方式'
}

export function orderTypeLabel(orderType: string): string {
  return orderType === 'REGULAR' ? '普通订单' : orderType === 'FLASH' ? '快闪订单' : orderType === 'PROMOTION' ? '大促活动' : '未知订单类型'
}

export function orderSourceLabel(sourceSystem: string): string {
  return sourceSystem === 'CRM' ? 'CRM' : sourceSystem === 'YOUZAN' ? '有赞' : sourceSystem === 'WEBHOOK' ? '平台通知' : sourceSystem === 'MANUAL' ? '人工录入' : '其他来源'
}

export function historyTriggerLabel(triggerType: string): string {
  return triggerType === 'CREATE' ? '创建草稿' : triggerType === 'SUBMIT' ? '提交待支付' : triggerType === 'CANCEL' ? '取消订单' : triggerType === 'PAYMENT_CONFIRMED' ? '人工支付确认' : '其他操作'
}

export function orderItemAllocationStatus(item: Pick<OrderItem, 'entitlementSnapshot'>): string {
  try {
    const snapshot = JSON.parse(item.entitlementSnapshot) as unknown
    if (snapshot && typeof snapshot === 'object' && 'allocationStatus' in snapshot) {
      const value = (snapshot as { allocationStatus?: unknown }).allocationStatus
      return typeof value === 'string' ? value : 'UNKNOWN'
    }
  } catch {
    return 'UNKNOWN'
  }
  return 'UNKNOWN'
}

export function formatDeltaCount(value: number): string {
  return value > 0 ? `+${value}` : String(value)
}

export function createPaymentIdempotencyKey(): string {
  if (typeof globalThis.crypto.randomUUID === 'function') return globalThis.crypto.randomUUID()
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}

export function tradingErrorText(error: unknown): string {
  const message = error instanceof Error ? error.message : '操作失败，请稍后重试'
  return error instanceof ApiError && error.requestId ? `${message}（请求 ID：${error.requestId}）` : message
}
