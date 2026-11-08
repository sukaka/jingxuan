import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface Project {
  id: number
  projectCode: string
  projectName: string
  categoryCode: string | null
  listPriceFen: number
  status: string
  createdAt: string
  version: number
}

export interface ProductPackage {
  id: number
  packageCode: string
  packageName: string
  status: string
  createdAt: string
  version: number
}

export interface PackageVersion {
  id: number
  packageId: number
  versionNo: number
  selectionMode: 'FIXED' | 'CHOOSE_N' | 'USE_N'
  choiceCount: number
  selectionRules: string | null
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  createdAt: string
  version: number
}

export interface PackageProject {
  id: number | null
  packageVersionId: number | null
  projectId: number
  projectCode: string | null
  projectName: string | null
  categoryCode: string | null
  listPriceFen: number | null
  projectStatus: string | null
  grantCount: number
  selectionWeight: number
  allocationPriceFen: number
  optionGroup: string
  version: number | null
}

export interface Sku {
  id: number
  skuCode: string
  packageVersionId: number
  packageId: number | null
  packageName: string | null
  versionNo: number | null
  selectionMode: string | null
  specName: string
  salePriceFen: number
  status: 'ACTIVE' | 'INACTIVE'
  onSaleAt: string | null
  offSaleAt: string | null
  createdAt: string
  version: number
}

export interface Institution {
  id: number
  institutionNo: string
  fullName: string
  shortName: string | null
  cityCode: string | null
  status: string
  version: number
}

export interface PackageVersionSnapshot {
  id: number
  packageId: number
  packageCode: string
  packageName: string
  versionNo: number
  selectionMode: 'FIXED' | 'CHOOSE_N' | 'USE_N'
  choiceCount: number
  selectionRules: string | null
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  version: number
  rulesStatus: 'READY' | 'PENDING_Q01'
  allocationStatus: 'CONFIGURED' | 'PENDING_Q02'
  entitlementIssuable: boolean
  projects: PackageProject[]
  skus: Sku[]
  institutions: Institution[]
}

export interface PackageProjectInput {
  projectId: number
  grantCount: number
  selectionWeight?: number
  allocationPriceFen?: number
  optionGroup?: string
}

export interface SkuQualityQuery {
  from: string
  to: string
  timeZone: string
  sourceSystem?: string
  page?: number
  size?: number
  lowFrequencyThreshold?: number
}

export interface SkuQualityReportResponse {
  from: string
  to: string
  fromUtc: string
  toUtcExclusive: string
  timeZone: string
  sourceSystem: string
  receiverType: string
  metricVersion: string
  sourceWatermark: string | null
  lowFrequencyThreshold: number
  periodDays: number
  lowFrequencyAction: string
  qualityRules: { code: string; description: string }[]
  items: {
    skuId: number
    skuCode: string
    packageVersionId: number
    packageName: string | null
    specName: string | null
    standardName: string | null
    salePriceFen: number
    skuStatus: string
    activeInstitutionCount: number
    salesQuantity: number
    paidOrderCount: number
    paidLineCount: number
    lastSoldAt: string | null
    lowFrequency: boolean
    qualityStatus: string
    issueCodes: string[]
    automaticAction: string
  }[]
  total: number
  page: number
  size: number
  drillDown: unknown[]
  drillDownType: string | null
  drillDownLimit: number
}

export interface SkuQualitySummary {
  totalSkuCount: number
  namingIssueCount: number
  lowFrequencySkuCount: number
  lowFrequencyRatio: number | null
}

export interface SkuQualityItem {
  skuId: number
  skuCode: string
  sales90Days: number
  lowFrequency: boolean
  qualityStatus: string
  issues: string[]
  automaticAction: string
}

export interface SkuQualityCheck {
  checkCode: string
  checkName: string
  issueCount: number
  handlingRule: string
}

export interface SkuQualityReport {
  from: string
  to: string
  timeZone: string
  sourceSystem: string
  metricVersion: string
  sourceWatermark: string | null
  periodDays: number
  lowFrequencyThreshold: number
  lowFrequencyAction: string
  total: number
  page: number
  size: number
  summary: SkuQualitySummary
  items: SkuQualityItem[]
  checks: SkuQualityCheck[]
}

const NAMING_ISSUE_CODES = new Set([
  'MISSING_SKU_CODE',
  'MISSING_STANDARD_NAME',
  'DUPLICATE_SPEC',
  'CAMPAIGN_AS_PRODUCT',
  'NAMING_ISSUE',
])
const QUALITY_CHECK_NAMES: Record<string, string> = {
  MISSING_SKU_CODE: 'SKU 编码缺失',
  MISSING_STANDARD_NAME: '标准名称或规格缺失',
  MISSING_ACTIVE_INSTITUTION: 'SKU 未配置启用机构',
  DUPLICATE_SPEC: '同名不同规格未拆 SKU',
  CAMPAIGN_AS_PRODUCT: '活动名替代产品标准名',
}

function qualityHandlingRule(code: string, description: string, automaticAction: string): string {
  if (code === 'LOW_FREQUENCY') return `进入长尾复核，不自动下架（自动动作：${automaticAction}）`
  if (code === 'MISSING_ACTIVE_INSTITUTION') return '未配置启用机构禁止上架'
  if (code === 'MISSING_SKU_CODE') return '补齐 SKU 编码后再上架'
  if (code === 'MISSING_STANDARD_NAME') return '人工补齐标准名称与规格，不改写历史快照'
  return description
}

export async function getSkuQualityReport(query: SkuQualityQuery): Promise<SkuQualityReport> {
  const res = await http.get<ApiResponse<SkuQualityReportResponse>>('/reports/sku-quality', {
    params: {
      from: query.from,
      to: query.to,
      timeZone: query.timeZone,
      sourceSystem: query.sourceSystem ?? 'ALL',
      page: query.page ?? 1,
      size: query.size ?? 100,
      lowFrequencyThreshold: query.lowFrequencyThreshold ?? 3,
    },
  })
  const report = res.data.data
  const items = report.items.map((item) => ({
    skuId: item.skuId,
    skuCode: item.skuCode,
    sales90Days: item.salesQuantity,
    lowFrequency: item.lowFrequency,
    qualityStatus: item.qualityStatus,
    issues: item.issueCodes,
    automaticAction: item.automaticAction,
  }))
  const namingIssueCount = items.filter((item) => item.issues.some((code) => NAMING_ISSUE_CODES.has(code))).length
  const lowFrequencySkuCount = items.filter((item) => item.lowFrequency).length
  return {
    from: report.from,
    to: report.to,
    timeZone: report.timeZone,
    sourceSystem: report.sourceSystem,
    metricVersion: report.metricVersion,
    sourceWatermark: report.sourceWatermark,
    periodDays: report.periodDays,
    lowFrequencyThreshold: report.lowFrequencyThreshold,
    lowFrequencyAction: report.lowFrequencyAction,
    total: report.total,
    page: report.page,
    size: report.size,
    summary: {
      totalSkuCount: report.total,
      namingIssueCount,
      lowFrequencySkuCount,
      lowFrequencyRatio: items.length ? lowFrequencySkuCount / items.length : null,
    },
    items,
    checks: report.qualityRules.map((rule) => ({
      checkCode: rule.code,
      checkName: rule.code === 'LOW_FREQUENCY'
        ? `${report.periodDays} 天低频 SKU`
        : (QUALITY_CHECK_NAMES[rule.code] ?? rule.description),
      issueCount: items.filter((item) => item.issues.includes(rule.code)).length,
      handlingRule: qualityHandlingRule(rule.code, rule.description, report.lowFrequencyAction),
    })),
  }
}

interface PageQuery {
  page: number
  size: number
  keyword?: string
  status?: string
}

function params(query: PageQuery) {
  return { page: query.page, size: query.size, keyword: query.keyword || undefined, status: query.status || undefined }
}

export async function listProjects(query: PageQuery): Promise<PageResult<Project>> {
  const res = await http.get<ApiResponse<PageResult<Project>>>('/projects', { params: params(query) })
  return res.data.data
}

export async function createProject(body: { projectCode: string; projectName: string; categoryCode?: string; listPriceFen: number }) {
  const res = await http.post<ApiResponse<Project>>('/projects', body)
  return res.data.data
}

export async function updateProject(id: number, body: { projectName: string; categoryCode?: string; listPriceFen: number; status: string; version: number }) {
  const res = await http.patch<ApiResponse<Project>>(`/projects/${id}`, body)
  return res.data.data
}

export async function listPackages(query: PageQuery): Promise<PageResult<ProductPackage>> {
  const res = await http.get<ApiResponse<PageResult<ProductPackage>>>('/packages', { params: params(query) })
  return res.data.data
}

export async function createPackage(body: { packageCode: string; packageName: string }) {
  const res = await http.post<ApiResponse<ProductPackage>>('/packages', body)
  return res.data.data
}

export async function updatePackage(id: number, body: { packageName: string; status: string; version: number }) {
  const res = await http.patch<ApiResponse<ProductPackage>>(`/packages/${id}`, body)
  return res.data.data
}

export async function listPackageVersions(packageId: number): Promise<PackageVersion[]> {
  const res = await http.get<ApiResponse<PackageVersion[]>>(`/packages/${packageId}/versions`)
  return res.data.data
}

export async function createPackageVersion(packageId: number, body: { selectionMode: string; choiceCount: number; selectionRules?: string; projects: PackageProjectInput[] }) {
  const res = await http.post<ApiResponse<PackageVersionSnapshot>>(`/packages/${packageId}/versions`, body)
  return res.data.data
}

export async function getPackageVersionSnapshot(id: number): Promise<PackageVersionSnapshot> {
  const res = await http.get<ApiResponse<PackageVersionSnapshot>>(`/package-versions/${id}/snapshot`)
  return res.data.data
}

export async function updatePackageVersion(id: number, body: { selectionMode: string; choiceCount: number; selectionRules?: string; version: number }) {
  const res = await http.patch<ApiResponse<PackageVersionSnapshot>>(`/package-versions/${id}`, body)
  return res.data.data
}

export async function replaceVersionProjects(id: number, body: { version: number; projects: PackageProjectInput[] }) {
  const res = await http.put<ApiResponse<PackageVersionSnapshot>>(`/package-versions/${id}/projects`, body)
  return res.data.data
}

export async function activatePackageVersion(id: number, version: number) {
  const res = await http.post<ApiResponse<PackageVersionSnapshot>>(`/package-versions/${id}/activate`, { version })
  return res.data.data
}

export async function archivePackageVersion(id: number, version: number) {
  const res = await http.post<ApiResponse<PackageVersionSnapshot>>(`/package-versions/${id}/archive`, { version })
  return res.data.data
}

export async function listSkus(query: PageQuery & { packageId?: number }): Promise<PageResult<Sku>> {
  const res = await http.get<ApiResponse<PageResult<Sku>>>('/skus', { params: { ...params(query), packageId: query.packageId } })
  return res.data.data
}

export async function createSku(body: { skuCode: string; packageVersionId: number; specName: string; salePriceFen: number; institutionIds: number[] }) {
  const res = await http.post<ApiResponse<Sku>>('/skus', body)
  return res.data.data
}

export async function updateSku(id: number, body: { specName: string; salePriceFen: number; institutionIds: number[]; version: number }) {
  const res = await http.patch<ApiResponse<Sku>>(`/skus/${id}`, body)
  return res.data.data
}

export async function activateSku(id: number, version: number) {
  const res = await http.post<ApiResponse<Sku>>(`/skus/${id}/activate`, { version })
  return res.data.data
}

export async function disableSku(id: number, version: number) {
  const res = await http.post<ApiResponse<Sku>>(`/skus/${id}/disable`, { version })
  return res.data.data
}

export async function listSkuInstitutions(id: number): Promise<Institution[]> {
  const res = await http.get<ApiResponse<Institution[]>>(`/skus/${id}/institutions`)
  return res.data.data
}

export async function listInstitutions(query: PageQuery & { status?: string }): Promise<PageResult<Institution>> {
  const res = await http.get<ApiResponse<PageResult<Institution>>>('/institutions', { params: params(query) })
  return res.data.data
}
