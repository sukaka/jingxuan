export type WecomTone = 'success' | 'warning' | 'info' | 'danger'

const CONSENT_LABELS: Record<string, string> = {
  PENDING: '模拟待确认',
  GRANTED: '模拟同意',
  REVOKED: '模拟撤回',
}

const MESSAGE_STATUS_LABELS: Record<string, string> = {
  STORED: '模拟已归档',
  UNMATCHED: '待人工匹配',
}

const MONITOR_STATUS_LABELS: Record<string, string> = {
  NEVER_SYNCED: '尚未同步',
  SUCCESS: '最近同步成功（模拟）',
  ERROR: '采集失败',
}

export function consentStatusLabel(status: string): string {
  return CONSENT_LABELS[status] ?? '未知同意状态'
}

export function consentStatusTone(status: string): WecomTone {
  if (status === 'GRANTED') return 'success'
  if (status === 'REVOKED') return 'danger'
  return 'warning'
}

export function messageStatusLabel(status: string): string {
  return MESSAGE_STATUS_LABELS[status] ?? '未知归档状态'
}

export function messageStatusTone(status: string): WecomTone {
  if (status === 'STORED') return 'success'
  if (status === 'UNMATCHED') return 'warning'
  return 'info'
}

export function monitorStatusLabel(status: string): string {
  return MONITOR_STATUS_LABELS[status] ?? '未知同步状态'
}

export function monitorStatusTone(status: string): WecomTone {
  if (status === 'SUCCESS') return 'success'
  if (status === 'ERROR') return 'danger'
  return 'warning'
}

export function senderTypeLabel(senderType: string): string {
  return senderType === 'EMPLOYEE' ? '企业员工' : senderType === 'CUSTOMER' ? '外部联系人' : '未知发送方'
}

export function createWecomKey(scope: string): string {
  const suffix = typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) => value.toString(16).padStart(2, '0')).join('')
  return `${scope}:${suffix}`
}
