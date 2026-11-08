import { fenToYuan } from '../../utils/money'

export type FinanceTone = 'success' | 'warning' | 'info' | 'dark' | 'danger'

const REFUND_STATUS_LABELS: Record<string, string> = {
  REQUESTED: '待审批',
  APPROVED: '已批准 · 待执行',
  REJECTED: '已驳回',
  PROCESSING: '退款执行中',
  SUCCEEDED: '退款成功',
  FAILED: '退款失败',
}

const REFUND_PAYMENT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待渠道结果',
  SUCCEEDED: '已确认成功',
  FAILED: '执行失败',
}

const SETTLEMENT_STATUS_LABELS: Record<string, string> = {
  DRAFT: '待核准',
  CONFIRMED: '已核准 · 待付款',
  PARTIALLY_PAID: '部分付款',
  PAID: '已完成付款',
}

export function refundStatusLabel(status: string | null | undefined): string {
  return status ? (REFUND_STATUS_LABELS[status] ?? '未知退款状态') : '-'
}

export function refundStatusTone(status: string | null | undefined): FinanceTone {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'REJECTED' || status === 'FAILED') return 'danger'
  if (status === 'REQUESTED' || status === 'APPROVED' || status === 'PROCESSING') return 'warning'
  return 'info'
}

export function refundPaymentStatusLabel(status: string): string {
  return REFUND_PAYMENT_STATUS_LABELS[status] ?? '未知退款渠道状态'
}

export function settlementStatusLabel(status: string): string {
  return SETTLEMENT_STATUS_LABELS[status] ?? '未知批次状态'
}

export function settlementStatusTone(status: string): FinanceTone {
  if (status === 'PAID') return 'success'
  if (status === 'CONFIRMED' || status === 'PARTIALLY_PAID') return 'info'
  return 'warning'
}

export function amountStatusLabel(status: string): string {
  return status === 'VERIFIED' ? '金额已核准' : status === 'PENDING_VERIFICATION' ? '金额待核实' : '未知金额状态'
}

export function settlementAmount(amountStatus: string, fen: number | null | undefined): string {
  return amountStatus === 'PENDING_VERIFICATION' ? '待核实' : fenToYuan(fen)
}

export function settlementTypeLabel(type: string): string {
  return type === 'MONTHLY' ? '机构月结' : type === 'PER_REDEMPTION' ? '次卡次结' : '未知结算方式'
}

export function settlementPeriodLabel(type: string, periodKey: string): string {
  if (type !== 'PER_REDEMPTION') return periodKey || '未设置结算周期'
  const match = /^REDEMPTION:(\d+)$/.exec(periodKey)
  return match ? `核销 #${match[1]}` : '单笔正式核销'
}

export function providerLabel(provider: string): string {
  if (provider === 'BANK_TRANSFER') return '银行转账'
  if (provider === 'OFFLINE') return '线下退款'
  if (provider === 'MOCK') return '模拟验签通道'
  return '未知支付渠道'
}

export function createFinanceIdempotencyKey(scope: string): string {
  const suffix = typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) => value.toString(16).padStart(2, '0')).join('')
  return `${scope}:${suffix}`
}
