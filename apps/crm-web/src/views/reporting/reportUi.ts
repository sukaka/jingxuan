import type { PendingRatio, ReportQuery } from '../../api/report'
import { ApiError } from '../../types/api'
import { fenToYuan } from '../../utils/money'

export function countText(value: number | null | undefined, unit = ''): string {
  if (value === null || value === undefined) return '待确认'
  return unit ? `${value} ${unit}` : String(value)
}

export function moneyText(value: number | null | undefined, pending = '待确认'): string {
  return value === null || value === undefined ? pending : `¥${fenToYuan(value)}`
}

const PENDING_STATUSES = new Set(['PENDING_DEFINITION', 'UNCONFIGURED', 'SOURCE_NOT_AVAILABLE', 'PENDING_APPROVAL', 'NOT_PAYROLL'])

export function rateText(value: number | null | undefined, status: string | null | undefined): string {
  if (status === 'UNCONFIGURED') return '待配置'
  if (value === null || value === undefined || !Number.isFinite(value) || !status || PENDING_STATUSES.has(status)) return '待确认'
  return `${(value * 100).toFixed(2)}%`
}

export function percentageText(value: number | null | undefined, status: string | null | undefined): string {
  if (status === 'UNCONFIGURED') return '待配置'
  if (value === null || value === undefined || !Number.isFinite(value) || !status || PENDING_STATUSES.has(status)) return '待确认'
  return `${value.toFixed(2)}%`
}

export function ratioText(metric: PendingRatio | null | undefined): string {
  return rateText(metric?.value, metric?.status)
}

export function orderSourceText(source: string | null | undefined): string {
  if (!source || source === 'ALL') return '全部来源'
  return ({ MANUAL: '手工订单', YOUZAN: '有赞订单', WEBHOOK: '有赞 webhook' } as Record<string, string>)[source] ?? source
}

export function receiverTypeText(receiver: string | null | undefined): string {
  if (!receiver || receiver === 'ALL') return '全部收款方'
  return ({ PLATFORM: '平台收款', INSTITUTION: '机构收款' } as Record<string, string>)[receiver] ?? receiver
}

export function factTypeText(type: string | null | undefined): string {
  return ({
    PAYMENT: '支付流水',
    REFUND: '成功退款',
    REDEMPTION: '正式核销',
    FOLLOWUP: '客户跟进',
    APPOINTMENT_CONFIRMED: '预约确认',
    CHECKED_IN: '到院事实',
    SERVICE_DONE: '服务完成',
    PAID_ORDER_LINE: '已支付订单行',
  } as Record<string, string>)[type ?? ''] ?? '业务事实'
}

export function attributionStatusText(status: string): string {
  return ({ AT_SALE_SNAPSHOT: '成交时快照', USER_NOT_FOUND: '归属用户缺失', UNCONFIGURED: '未配置归因' } as Record<string, string>)[status] ?? '归因状态待确认'
}

export function reportErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.requestId ? `（requestId：${error.requestId}）` : ''}`
  }
  return error instanceof Error ? error.message : '报表加载失败，请稍后重试'
}

export function campaignErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return `${error.message}。数据可能已被修改，请重新打开后再核对`
    return `${error.message}${error.requestId ? `（requestId：${error.requestId}）` : ''}`
  }
  return error instanceof Error ? error.message : '活动保存失败，请稍后重试'
}

export function todayInTimeZone(timeZone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

export function todayInShanghai(): string {
  return todayInTimeZone('Asia/Shanghai')
}

function shiftCalendarDate(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10)
}

export type ReportPeriod = 'day' | 'week' | 'month'

export function reportRangeForPeriod(period: ReportPeriod, timeZone: string, now = new Date()): [string, string] {
  const today = todayInTimeZone(timeZone, now)
  if (period === 'day') return [today, today]
  if (period === 'week') {
    const weekday = new Date(`${today}T00:00:00Z`).getUTCDay()
    return [shiftCalendarDate(today, -((weekday + 6) % 7)), today]
  }
  return [`${today.slice(0, 8)}01`, today]
}

export function initialMonthRange(): [string, string] {
  const today = todayInShanghai()
  return [`${today.slice(0, 8)}01`, today]
}

export function hasCompleteRange(range: [string, string] | string[]): boolean {
  return Boolean(range[0] && range[1] && range[0] <= range[1])
}

export function toReportQuery(
  range: [string, string] | string[],
  timeZone: string,
  sourceSystem?: string,
  receiverType?: string,
): ReportQuery {
  return {
    from: range[0] ?? '',
    to: range[1] ?? '',
    timeZone,
    sourceSystem: sourceSystem || 'ALL',
    receiverType: receiverType || 'ALL',
  }
}

export function displayDateTime(value: string | null | undefined): string {
  if (!value) return '待确认'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const parts = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`
}

export function metricVersionText(value: string | null | undefined): string {
  if (!value) return '待确认'
  const match = /^P\d+_FACT_V(\d+)$/i.exec(value)
  return match ? `统计口径 V${match[1]}` : '自定义统计口径'
}
