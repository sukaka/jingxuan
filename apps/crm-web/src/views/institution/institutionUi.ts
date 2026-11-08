import type { InstitutionChangeRequest } from '../../api/institution'
import { decimalRateToPercent } from './number'

export type InstitutionTone = 'success' | 'warning' | 'info' | 'dark' | 'danger'

export interface SensitiveFieldMeta {
  code: string
  label: string
  kind: 'text' | 'textarea' | 'rate' | 'fen' | 'boolean' | 'businessType' | 'ownership' | 'grade' | 'settlementType'
}

const SINGLE_REVIEW_FIELDS = new Set(['bankName', 'bankAccount', 'taxId', 'settlementName', 'fullName', 'businessTypeCode', 'ownershipCode', 'gradeCode'])

export const SENSITIVE_FIELDS: SensitiveFieldMeta[] = [
  { code: 'fullName', label: '机构全称', kind: 'text' },
  { code: 'businessTypeCode', label: '业务类型', kind: 'businessType' },
  { code: 'ownershipCode', label: '机构性质', kind: 'ownership' },
  { code: 'gradeCode', label: '机构等级', kind: 'grade' },
  { code: 'settlementType', label: '结算方式', kind: 'settlementType' },
  { code: 'onlineRate', label: '线上佣金率', kind: 'rate' },
  { code: 'offlineRate', label: '线下佣金率', kind: 'rate' },
  { code: 'platformRate', label: '点评等平台佣金率', kind: 'rate' },
  { code: 'otherRateRule', label: '其他佣金规则', kind: 'textarea' },
  { code: 'chargesFee', label: '是否收费', kind: 'boolean' },
  { code: 'depositFen', label: '保证金（元）', kind: 'fen' },
  { code: 'guaranteeRoi', label: '是否保投产', kind: 'boolean' },
  { code: 'roiTerms', label: '保投产约定', kind: 'textarea' },
  { code: 'settlementName', label: '结款名称', kind: 'text' },
  { code: 'bankName', label: '开户行', kind: 'text' },
  { code: 'bankAccount', label: '银行账号', kind: 'text' },
  { code: 'taxId', label: '纳税人识别号', kind: 'text' },
]

export function requiresBothSteps(request: Pick<InstitutionChangeRequest, 'changeType' | 'fieldCode'>): boolean {
  if (request.changeType === 'ONBOARDING' || request.changeType === 'CONTRACT_ACTIVATION') return true
  if (request.changeType === 'QUALIFICATION_REVIEW') return false
  return !SINGLE_REVIEW_FIELDS.has(request.fieldCode ?? '')
}

function customTextLabel(value: string, unknownLabel: string): string {
  return /[A-Za-z]/.test(value) ? unknownLabel : value
}

export function changeStatusLabel(status: string): string {
  return status === 'PENDING_REVIEW' ? '待审核' : status === 'APPROVED' ? '已生效' : status === 'REJECTED' ? '已驳回' : status === 'CONFLICT_REVALIDATE' ? '版本冲突待复核' : '未知状态'
}

export function changeStatusTone(status: string): InstitutionTone {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'CONFLICT_REVALIDATE') return 'warning'
  return 'info'
}

export function profileStatusLabel(status: string): string {
  return status === 'PENDING' ? '待合作审核' : status === 'ACTIVE' ? '合作中' : status === 'INACTIVE' ? '停止合作' : '未知状态'
}

export function documentStatusLabel(status: string): string {
  return status === 'PENDING_REVIEW' ? '待审核' : status === 'ACTIVE' ? '已生效' : status === 'SUPERSEDED' ? '历史版本' : status === 'APPROVED' ? '审核通过' : status === 'REJECTED' ? '已驳回' : '未知状态'
}

export function contractTypeLabel(value: string): string {
  return { FRAMEWORK: '框架协议', SERVICE: '服务合同', SUPPLEMENTARY: '补充协议' }[value] ?? customTextLabel(value, '其他合同类型')
}

export function qualificationTypeLabel(value: string): string {
  return { MEDICAL_LICENSE: '医疗机构执业许可证', BUSINESS_LICENSE: '营业执照' }[value] ?? customTextLabel(value, '其他资质类型')
}

export function changeTypeLabel(changeType: string): string {
  return changeType === 'ONBOARDING' ? '机构合作激活' : changeType === 'CONTRACT_ACTIVATION' ? '合同版本激活' : changeType === 'QUALIFICATION_REVIEW' ? '资质审核' : changeType === 'PROFILE_FIELD' ? '敏感字段变更' : '其他申请类型'
}

export function fieldLabel(fieldCode: string | null): string {
  const label = SENSITIVE_FIELDS.find((item) => item.code === fieldCode)?.label
  if (label) return label
  return fieldCode ? customTextLabel(fieldCode, '未知字段') : '工作流申请'
}

export function institutionBusinessTypeLabel(value: string | null | undefined): string {
  if (!value) return '待维护'
  return { MEDICAL_BEAUTY: '医美', MED_AESTHETIC: '医美', DENTAL: '口腔', INTIMATE: '私密', COMPREHENSIVE: '综合' }[value] ?? customTextLabel(value, '其他业务类型')
}

export function institutionOwnershipLabel(value: string | null | undefined): string {
  if (!value) return '待维护'
  return { PUBLIC: '公立机构', PRIVATE: '民营机构' }[value] ?? customTextLabel(value, '其他机构性质')
}

export function institutionGradeLabel(value: string | null | undefined): string {
  if (!value) return '待维护'
  return ['S', 'A', 'B', 'C'].includes(value) ? `${value}类` : customTextLabel(value, '其他等级')
}

export function settlementTypeLabel(value: string | null | undefined): string {
  if (!value) return '待维护'
  return { MONTHLY: '月结', PER_REDEMPTION: '次卡次结' }[value] ?? customTextLabel(value, '其他结算方式')
}

export function cityLabel(value: string | null | undefined): string {
  if (!value) return '未设城市'
  return { BJ: '北京', SH: '上海', GZ: '广州', SZ: '深圳', HZ: '杭州', CD: '成都', CQ: '重庆', NJ: '南京', WH: '武汉', SUZ: '苏州', TJ: '天津', XA: '西安', QD: '青岛' }[value] ?? customTextLabel(value, '其他城市')
}

export function maskedDisplay(value: unknown): string {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>
    if ('value' in record) return maskedDisplay(record.value)
    if ('status' in record) return maskedDisplay(record.status)
    return JSON.stringify(value)
  }
  return String(value)
}

export function workflowStepLabel(value: string | null | undefined): string {
  if (!value) return '未开始'
  return { MARKET: '市场', FINANCE: '财务', COMPLETED: '已完成', NONE: '无需审核', REQUESTED: '申请', CONFLICT: '版本冲突待复核' }[value] ?? '未知审核步骤'
}

export function reviewStatusLabel(value: string | null | undefined): string {
  if (!value) return '待审核'
  return { PENDING: '待审核', PENDING_REVIEW: '待审核', APPROVED: '已通过', REJECTED: '已驳回', SKIPPED: '无需审核', REQUESTED: '已提交', CONFLICT_REVALIDATE: '待重新复核', REVALIDATE: '重新复核', ACTIVE: '已生效' }[value] ?? '未知审核状态'
}

export function changeValueLabel(fieldCode: string | null, value: unknown): string {
  const display = maskedDisplay(value)
  if (display === '-') return display
  if (!fieldCode) return reviewStatusLabel(display)
  if (fieldCode === 'businessTypeCode') return institutionBusinessTypeLabel(display)
  if (fieldCode === 'ownershipCode') return institutionOwnershipLabel(display)
  if (fieldCode === 'gradeCode') return institutionGradeLabel(display)
  if (fieldCode === 'settlementType') return settlementTypeLabel(display)
  if (fieldCode === 'chargesFee' || fieldCode === 'guaranteeRoi') return display === 'true' ? '是' : display === 'false' ? '否' : display
  return display
}

export function rateDisplay(value: number | string | null): string {
  if (value === null) return '-'
  const percent = decimalRateToPercent(value)
  return percent === '-' ? percent : `${percent}%`
}

export function createInstitutionKey(scope: string): string {
  const suffix = typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) => value.toString(16).padStart(2, '0')).join('')
  return `${scope}:${suffix}`
}
