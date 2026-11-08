import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface InstitutionProfile {
  id: number
  institutionNo: string
  fullName: string
  shortName: string | null
  cityCode: string | null
  businessTypeCode: string | null
  ownershipCode: string | null
  gradeCode: string | null
  address: string | null
  contactName: string | null
  contactPhoneMasked: string | null
  status: string
  settlementType: string | null
  onlineRate: number | string | null
  offlineRate: number | string | null
  platformRate: number | string | null
  otherRateRule: string | null
  chargesFee: boolean | null
  depositFen: number | null
  guaranteeRoi: boolean | null
  roiTerms: string | null
  settlementName: string | null
  bankNameMasked: string | null
  bankAccountMasked: string | null
  taxIdMasked: string | null
  introduction: string | null
  createdAt: string
  version: number
}

export interface CreateInstitutionProfileInput {
  institutionNo: string
  fullName: string
  shortName?: string
  cityCode?: string
  businessTypeCode?: string
  ownershipCode?: string
  gradeCode?: string
  address?: string
  contactName?: string
  contactPhone?: string
  settlementType: string
  onlineRate?: string
  offlineRate?: string
  platformRate?: string
  otherRateRule?: string
  chargesFee?: boolean
  depositFen?: number
  guaranteeRoi?: boolean
  roiTerms?: string
  settlementName?: string
  bankName?: string
  bankAccount?: string
  taxId?: string
  introduction?: string
}

export interface UpdateInstitutionProfileInput {
  version: number
  shortName?: string
  cityCode?: string
  address?: string
  contactName?: string
  contactPhone?: string
  introduction?: string
}

export interface ProfileChangeStep {
  id: number
  changeRequestId: number
  sequenceNo: number
  stepCode: string
  status: string
  reviewerUserId: number | null
  reviewedAt: string | null
  comment: string | null
  createdAt: string
}

export interface InstitutionChangeRequest {
  id: number
  institutionId: number
  changeType: string
  fieldCode: string | null
  targetId: number | null
  baseVersion: number
  targetVersion: number | null
  beforeMasked: unknown
  afterMasked: unknown
  reason: string
  evidenceFileId: number | null
  status: string
  currentStep: string
  requestedBy: number
  requestedAt: string
  completedAt: string | null
  version: number
  steps: ProfileChangeStep[]
}

export interface CreateProfileChangeInput {
  institutionId: number
  fieldCode: string
  newValue: unknown
  baseVersion: number
  reason: string
  evidenceFileId?: number
}

export interface CreateWorkflowInput {
  baseVersion: number
  reason: string
  evidenceFileId?: number
}

export interface ReviewChangeInput {
  version: number
  decision: 'APPROVE' | 'REJECT'
  comment?: string
}

export interface RevalidateChangeInput {
  version: number
  baseVersion: number
  reason: string
}

export interface InstitutionContract {
  id: number
  institutionId: number
  contractNo: string
  versionNo: number
  title: string
  contractType: string
  startDate: string
  endDate: string | null
  fileId: number
  amountFen: number | null
  notes: string | null
  status: string
  previousContractId: number | null
  createdBy: number
  createdAt: string
  version: number
}

export interface CreateContractInput {
  institutionId: number
  previousContractId?: number
  contractNo: string
  title: string
  contractType: string
  startDate: string
  endDate?: string
  fileId: number
  amountFen?: number
  notes?: string
}

export interface InstitutionQualification {
  id: number
  institutionId: number
  qualificationType: string
  title: string
  licenseNoMasked: string | null
  fileId: number
  validFrom: string | null
  validTo: string | null
  status: string
  createdBy: number
  reviewedBy: number | null
  reviewedAt: string | null
  createdAt: string
  version: number
}

export interface CreateQualificationInput {
  institutionId: number
  qualificationType: string
  title: string
  licenseNo?: string
  fileId: number
  validFrom?: string
  validTo?: string
}

export async function listInstitutionProfiles(query: {
  page: number
  size: number
  keyword?: string
  merchantKeyword?: string
  status?: string
  cityCode?: string
}): Promise<PageResult<InstitutionProfile>> {
  const res = await http.get<ApiResponse<PageResult<InstitutionProfile>>>('/institution-profiles', { params: query })
  return res.data.data
}

export async function getInstitutionProfile(id: number): Promise<InstitutionProfile> {
  const res = await http.get<ApiResponse<InstitutionProfile>>(`/institution-profiles/${id}`)
  return res.data.data
}

async function postWithKey<T>(url: string, body: unknown, idempotencyKey: string): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body, { headers: { 'Idempotency-Key': idempotencyKey } })
  return res.data.data
}

export function createInstitutionProfile(body: CreateInstitutionProfileInput, key: string): Promise<InstitutionProfile> {
  return postWithKey('/institution-profiles', body, key)
}

export async function updateInstitutionProfile(id: number, body: UpdateInstitutionProfileInput, key: string): Promise<InstitutionProfile> {
  const res = await http.patch<ApiResponse<InstitutionProfile>>(`/institution-profiles/${id}`, body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}

export function requestInstitutionActivation(id: number, body: CreateWorkflowInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey(`/institution-profiles/${id}/activation-requests`, body, key)
}

export async function listInstitutionChanges(query: {
  page: number
  size: number
  institutionId?: number
  status?: string
}): Promise<PageResult<InstitutionChangeRequest>> {
  const res = await http.get<ApiResponse<PageResult<InstitutionChangeRequest>>>('/institution-change-requests', { params: query })
  return res.data.data
}

export async function getInstitutionChange(id: number): Promise<InstitutionChangeRequest> {
  const res = await http.get<ApiResponse<InstitutionChangeRequest>>(`/institution-change-requests/${id}`)
  return res.data.data
}

export function createInstitutionChange(body: CreateProfileChangeInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey('/institution-change-requests', body, key)
}

export function reviewInstitutionChangeMarket(id: number, body: ReviewChangeInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey(`/institution-change-requests/${id}/market-review`, body, key)
}

export function reviewInstitutionChangeFinance(id: number, body: ReviewChangeInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey(`/institution-change-requests/${id}/finance-review`, body, key)
}

export function revalidateInstitutionChange(id: number, body: RevalidateChangeInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey(`/institution-change-requests/${id}/revalidate`, body, key)
}

export async function listInstitutionContracts(query: {
  institutionId: number
  page: number
  size: number
  status?: string
}): Promise<PageResult<InstitutionContract>> {
  const res = await http.get<ApiResponse<PageResult<InstitutionContract>>>('/institution-contracts', { params: query })
  return res.data.data
}

export async function getInstitutionContract(id: number): Promise<InstitutionContract> {
  const res = await http.get<ApiResponse<InstitutionContract>>(`/institution-contracts/${id}`)
  return res.data.data
}

export function createInstitutionContract(body: CreateContractInput, key: string): Promise<InstitutionContract> {
  return postWithKey('/institution-contracts', body, key)
}

export function requestContractActivation(id: number, body: CreateWorkflowInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey(`/institution-contracts/${id}/activation-requests`, body, key)
}

export async function listInstitutionQualifications(query: {
  institutionId: number
  page: number
  size: number
  status?: string
}): Promise<PageResult<InstitutionQualification>> {
  const res = await http.get<ApiResponse<PageResult<InstitutionQualification>>>('/institution-qualifications', { params: query })
  return res.data.data
}

export async function getInstitutionQualification(id: number): Promise<InstitutionQualification> {
  const res = await http.get<ApiResponse<InstitutionQualification>>(`/institution-qualifications/${id}`)
  return res.data.data
}

export function createInstitutionQualification(body: CreateQualificationInput, key: string): Promise<InstitutionQualification> {
  return postWithKey('/institution-qualifications', body, key)
}

export function requestQualificationReview(id: number, body: CreateWorkflowInput, key: string): Promise<InstitutionChangeRequest> {
  return postWithKey(`/institution-qualifications/${id}/review-requests`, body, key)
}

export type MerchantResourceType = 'DOCTOR' | 'EQUIPMENT' | 'CASE'
export type MerchantResourceStatus = 'PUBLISHED' | 'OFFLINE'

export interface InstitutionMerchantResource {
  id: number
  resourceNo: string
  institutionId: number
  resourceType: MerchantResourceType
  title: string
  subtitle: string | null
  description: string | null
  fileId: number | null
  status: MerchantResourceStatus
  createdBy: number
  updatedBy: number | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface InstitutionMerchantResourceSummary {
  institutionId: number
  doctorCount: number
  equipmentCount: number
  caseCount: number
}

export interface CreateMerchantResourceInput {
  resourceType: MerchantResourceType
  title: string
  subtitle?: string
  description?: string
  fileId?: number
}

export interface UpdateMerchantResourceInput {
  version: number
  title: string
  subtitle?: string
  description?: string
  fileId?: number | null
  status: MerchantResourceStatus
}

export async function listInstitutionMerchantResources(query: {
  institutionId?: number
  resourceType?: MerchantResourceType
  status?: MerchantResourceStatus | 'ALL'
  keyword?: string
  page: number
  size: number
}): Promise<PageResult<InstitutionMerchantResource>> {
  const res = await http.get<ApiResponse<PageResult<InstitutionMerchantResource>>>('/institution-merchant-resources', { params: query })
  return res.data.data
}

export async function listInstitutionMerchantResourceSummary(): Promise<InstitutionMerchantResourceSummary[]> {
  const res = await http.get<ApiResponse<InstitutionMerchantResourceSummary[]>>('/institution-merchant-resources/summary')
  return res.data.data
}

export function createInstitutionMerchantResource(
  institutionId: number,
  body: CreateMerchantResourceInput,
  key: string,
): Promise<InstitutionMerchantResource> {
  return postWithKey(`/institution-profiles/${institutionId}/merchant-resources`, body, key)
}

export async function updateInstitutionMerchantResource(
  id: number,
  body: UpdateMerchantResourceInput,
  key: string,
): Promise<InstitutionMerchantResource> {
  const res = await http.patch<ApiResponse<InstitutionMerchantResource>>(`/institution-merchant-resources/${id}`, body, {
    headers: { 'Idempotency-Key': key },
  })
  return res.data.data
}
