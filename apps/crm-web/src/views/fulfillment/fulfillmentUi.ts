import type { AppointmentStatus, EntitlementCorrectionView, RedemptionDetail, RedemptionReversal } from '../../api/fulfillment'

export type FulfillmentTone = 'success' | 'warning' | 'info' | 'dark' | 'danger'

const APPOINTMENT_LABELS: Record<string, string> = {
  PENDING_INSTITUTION: '待机构接诊',
  CONFIRMED: '已接诊 · 待到院',
  CHECKED_IN: '已到院',
  SERVICE_DONE: '服务完成 · 待核销',
  REDEEMED: '已完成核销',
  REJECTED: '机构已拒绝',
  CANCELED: '已取消',
  NO_SHOW: '未到院',
}

export function appointmentStatusLabel(status: string | null | undefined): string {
  return status ? (APPOINTMENT_LABELS[status] ?? '未知状态') : '-'
}

export function appointmentStatusTone(status: AppointmentStatus | string | null | undefined): FulfillmentTone {
  if (status === 'REDEEMED') return 'success'
  if (status === 'PENDING_INSTITUTION' || status === 'CONFIRMED') return 'warning'
  if (status === 'REJECTED' || status === 'NO_SHOW') return 'danger'
  if (status === 'CANCELED') return 'dark'
  return 'info'
}

export function redemptionStatusLabel(status: string): string {
  return status === 'POSTED' ? '机构正式核销' : status === 'PENDING_INSTITUTION' ? '待机构确认' : status === 'SUBMITTED' ? '已提交申请' : status === 'REJECTED' ? '已拒绝' : status === 'REVERSED' ? '已冲正' : '未知状态'
}

export function redemptionStatusTone(status: string): FulfillmentTone {
  if (status === 'POSTED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'SUBMITTED' || status === 'PENDING_INSTITUTION') return 'warning'
  return 'info'
}

export function redemptionSettlementStatusLabel(status: string): string {
  return status === 'PENDING_VERIFICATION' ? '待核实' : status === 'VERIFIED' ? '已核实' : '未知状态'
}

export function redemptionCount(detail: Pick<RedemptionDetail, 'lines'>): number {
  return detail.lines.reduce((total, line) => total + line.count, 0)
}

export function redemptionAmountLabel(): string {
  return '待核实'
}

export function reversalStatusLabel(status: string): string {
  return status === 'REQUESTED' ? '待审核' : status === 'APPROVED' ? '审核通过' : status === 'REJECTED' ? '已驳回' : '未知状态'
}

export function reversalStatusTone(status: string): FulfillmentTone {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'warning'
}

export function isReversalPending(reversal: Pick<RedemptionReversal, 'status'>): boolean {
  return reversal.status === 'REQUESTED'
}

export function correctionTxnTypeLabel(txnType: string): string {
  return txnType === 'CORRECTION_IN' ? '补偿增加' : txnType === 'CORRECTION_OUT' ? '补偿减少' : '其他补偿流水'
}

export function reversalEffectLabel(
  reversal: Pick<RedemptionReversal, 'status'>,
  corrections: readonly EntitlementCorrectionView[],
): string {
  const applied = corrections.filter((entry) => entry.txnType === 'CORRECTION_IN')
  if (applied.length > 0) {
    const count = applied.reduce((total, entry) => total + entry.deltaCount, 0)
    return `补偿已生效 · ${applied.length} 笔 / +${count} 次`
  }
  if (reversal.status === 'APPROVED') return '审核通过，未返回补偿流水，需核对'
  if (reversal.status === 'REJECTED') return '申请已驳回，未改余额'
  return '仅为冲正申请，未改余额'
}

export function createFulfillmentIdempotencyKey(scope: string): string {
  const suffix = typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) => value.toString(16).padStart(2, '0')).join('')
  return `${scope}:${suffix}`
}
