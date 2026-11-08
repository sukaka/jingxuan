const REFUND_STATUS_LABELS: Record<string, string> = {
  REQUESTED: '待审批',
  APPROVED: '已批准 · 待执行',
  REJECTED: '已驳回',
  PROCESSING: '退款执行中',
  SUCCEEDED: '退款成功',
  FAILED: '退款失败',
}

const REFUND_PAYMENT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待通道结果',
  SUCCEEDED: '已确认成功',
  FAILED: '执行失败',
}

const REFUND_REASON_LABELS: Record<string, string> = {
  CUSTOMER_REQUEST: '客户主动申请',
  SERVICE_ISSUE: '服务问题',
  OTHER: '其他人工核实原因',
}

const PROVIDER_LABELS: Record<string, string> = {
  BANK_TRANSFER: '银行转账',
  OFFLINE: '线下退款',
  MOCK: '模拟验签通道',
}

const VERIFICATION_METHOD_LABELS: Record<string, string> = {
  MANUAL_REVIEW: '人工凭证复核',
  PROVIDER_CALLBACK: '供应商验签回调',
}

function label(value: string | null | undefined, labels: Record<string, string>, fallback: string): string {
  if (!value) return '未记录'
  return labels[value] ?? fallback
}

export function refundStatusLabel(status: string | null | undefined): string {
  return label(status, REFUND_STATUS_LABELS, '未知退款状态')
}

export function refundPaymentStatusLabel(status: string | null | undefined): string {
  return label(status, REFUND_PAYMENT_STATUS_LABELS, '未知执行状态')
}

export function refundReasonLabel(reason: string | null | undefined): string {
  return label(reason, REFUND_REASON_LABELS, '未知退款原因')
}

export function providerLabel(provider: string | null | undefined): string {
  return label(provider, PROVIDER_LABELS, '未知退款通道')
}

export function verificationMethodLabel(method: string | null | undefined): string {
  return label(method, VERIFICATION_METHOD_LABELS, '未知确认方式')
}
