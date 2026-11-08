export type YouzanTone = 'success' | 'warning' | 'info' | 'dark' | 'danger'

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

export function mappingStatusLabel(status: string): string {
  return PROCESS_LABELS[status] ?? status
}

export function mappingStatusTone(status: string): YouzanTone {
  if (status === 'PROCESSED') return 'success'
  if (status === 'RECEIVED' || status === 'PENDING_MAPPING' || status === 'RETRY') return 'warning'
  if (status === 'ERROR' || status === 'DEAD_LETTER' || status === 'MAPPING_FAILED' || status === 'CUSTOMER_UNRESOLVED' || status === 'PAYMENT_MISMATCH') return 'danger'
  return 'info'
}

export function verificationMethodLabel(method: string | null | undefined): string {
  if (!method) return '尚未确认'
  return method === 'PROVIDER_CALLBACK' ? '供应商支付回调' : method === 'MANUAL_REVIEW' ? '人工凭证复核' : method
}

export function issueTypeLabel(issueType: string): string {
  return PROCESS_LABELS[issueType] ?? issueType
}

export function issueStatusLabel(status: string): string {
  return ISSUE_LABELS[status] ?? status
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

export function createYouzanKey(scope: string): string {
  const suffix = typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) => value.toString(16).padStart(2, '0')).join('')
  return `${scope}:${suffix}`
}
