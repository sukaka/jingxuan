import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export type AppointmentStatus = 'PENDING_INSTITUTION' | 'CONFIRMED' | 'CHECKED_IN' | 'SERVICE_DONE' | 'REDEEMED' | 'REJECTED' | 'CANCELED' | 'NO_SHOW'

export interface Appointment {
  id: number
  appointmentNo: string
  customerId: number
  institutionId: number
  ownerStaffId: number
  teamId: number | null
  scheduledAt: string | null
  remark: string | null
  status: AppointmentStatus
  createdBy: number
  createdAt: string
  version: number
}

export interface AppointmentItem {
  id: number
  entitlementId: number
  projectId: number
  plannedCount: number
  postedCount: number
}

export interface AppointmentStatusHistory {
  id: number
  appointmentId: number
  fromStatus: string
  toStatus: string
  actorUserId: number | null
  reason: string | null
  createdAt: string
}

export interface Redemption {
  id: number
  appointmentId: number
  customerId: number
  institutionId: number
  ownerStaffId: number
  teamId: number | null
  serviceAt: string | null
  evidenceRef: string
  voucherFileId: number | null
  status: string
  settlementStatus: string
  idempotencyKey: string
  postedBy: number | null
  postedAt: string | null
  createdAt: string
  version: number
}

export interface RedemptionLine {
  id: number
  redemptionId: number
  appointmentItemId: number
  entitlementId: number
  projectId: number
  count: number
  createdAt: string
}

export interface RedemptionStatusHistory {
  id: number
  redemptionId: number
  fromStatus: string
  toStatus: string
  actorUserId: number | null
  reason: string | null
  createdAt: string
}

export interface RedemptionDetail {
  redemption: Redemption
  lines: RedemptionLine[]
  history: RedemptionStatusHistory[]
}

export interface AppointmentDetail {
  appointment: Appointment
  items: AppointmentItem[]
  history: AppointmentStatusHistory[]
  redemptions: RedemptionDetail[]
}

export interface AppointmentOptionsResponse {
  customerId: number
  institutions: { id: number; fullName: string; shortName: string | null }[]
  entitlements: {
    id: number
    orderId: number
    projectId: number
    projectName: string | null
    availableCount: number
    validFrom: string | null
    validTo: string | null
    institutionIds: number[]
    allocationStatus: string
  }[]
}

export type EntitlementOption = AppointmentOptionsResponse['entitlements'][number]

export interface RedemptionReversal {
  id: number
  redemptionId: number
  customerId: number
  institutionId: number
  ownerStaffId: number
  teamId: number | null
  status: string
  reason: string
  requestedBy: number
  requestedAt: string
  reviewedBy: number | null
  reviewedAt: string | null
  reviewReason: string | null
  requestIdempotencyKey: string
  reviewIdempotencyKey: string | null
  activeRedemptionId: number | null
  createdAt: string
  version: number
}

export interface RedemptionReversalHistory {
  id: number
  reversalId: number
  fromStatus: string
  toStatus: string
  actorUserId: number | null
  reason: string | null
  createdAt: string
}

export interface EntitlementCorrectionView {
  ledgerId: number
  entitlementId: number
  projectId: number
  txnType: string
  deltaCount: number
  sourceRef: string
  idempotencyKey: string
  actorUserId: number | null
  reason: string | null
  createdAt: string
}

export interface ReversalDetail {
  reversal: RedemptionReversal
  history: RedemptionReversalHistory[]
  corrections: EntitlementCorrectionView[]
}

export interface CreateAppointmentInput {
  customerId: number
  institutionId: number
  scheduledAt: string
  remark?: string
  items: { entitlementId: number; count: number }[]
}

export interface AppointmentActionInput {
  version: number
  reason?: string
}

export interface CreateRedemptionInput {
  appointmentVersion: number
  serviceAt: string
  evidenceRef: string
  voucherFileId?: number
  lines: { entitlementId: number; count: number }[]
}

export interface ReversalRequestInput {
  redemptionVersion: number
  reason: string
}

export interface ReversalReviewInput {
  version: number
  reason?: string
}

export async function listAppointments(query: {
  page: number
  size: number
  customerId?: number
  institutionId?: number
  status?: string
}): Promise<PageResult<Appointment>> {
  const res = await http.get<ApiResponse<PageResult<Appointment>>>('/appointments', { params: query })
  return res.data.data
}

export async function createAppointment(body: CreateAppointmentInput, idempotencyKey: string): Promise<AppointmentDetail> {
  const res = await http.post<ApiResponse<AppointmentDetail>>('/appointments', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function getAppointment(id: number): Promise<AppointmentDetail> {
  const res = await http.get<ApiResponse<AppointmentDetail>>(`/appointments/${id}`)
  return res.data.data
}

async function transitionAppointment(id: number, action: string, body: AppointmentActionInput, idempotencyKey: string): Promise<AppointmentDetail> {
  const res = await http.post<ApiResponse<AppointmentDetail>>(`/appointments/${id}/${action}`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export function confirmAppointment(id: number, body: AppointmentActionInput, idempotencyKey: string): Promise<AppointmentDetail> {
  return transitionAppointment(id, 'confirm', body, idempotencyKey)
}

export function rejectAppointment(id: number, body: AppointmentActionInput, idempotencyKey: string): Promise<AppointmentDetail> {
  return transitionAppointment(id, 'reject', body, idempotencyKey)
}

export function cancelAppointment(id: number, body: AppointmentActionInput, idempotencyKey: string): Promise<AppointmentDetail> {
  return transitionAppointment(id, 'cancel', body, idempotencyKey)
}

export function checkInAppointment(id: number, body: AppointmentActionInput, idempotencyKey: string): Promise<AppointmentDetail> {
  return transitionAppointment(id, 'check-in', body, idempotencyKey)
}

export function completeAppointmentService(id: number, body: AppointmentActionInput, idempotencyKey: string): Promise<AppointmentDetail> {
  return transitionAppointment(id, 'service-done', body, idempotencyKey)
}

export function getAppointmentOptions(customerId: number): Promise<AppointmentOptionsResponse>
export function getAppointmentOptions(query: { appointmentId: number }): Promise<AppointmentOptionsResponse>
export async function getAppointmentOptions(query: number | { appointmentId: number }): Promise<AppointmentOptionsResponse> {
  const params = typeof query === 'number' ? { customerId: query } : { appointmentId: query.appointmentId }
  const res = await http.get<ApiResponse<AppointmentOptionsResponse>>('/appointment-options', { params })
  return res.data.data
}

export async function createRedemption(appointmentId: number, body: CreateRedemptionInput, idempotencyKey: string): Promise<RedemptionDetail> {
  const res = await http.post<ApiResponse<RedemptionDetail>>(`/appointments/${appointmentId}/redemptions`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function listRedemptions(query: {
  page: number
  size: number
  institutionId?: number
  customerId?: number
  projectId?: number
  status?: string
}): Promise<PageResult<Redemption>> {
  const res = await http.get<ApiResponse<PageResult<Redemption>>>('/redemptions', { params: query })
  return res.data.data
}

export async function getRedemption(id: number): Promise<RedemptionDetail> {
  const res = await http.get<ApiResponse<RedemptionDetail>>(`/redemptions/${id}`)
  return res.data.data
}

export async function requestRedemptionReversal(id: number, body: ReversalRequestInput, idempotencyKey: string): Promise<ReversalDetail> {
  const res = await http.post<ApiResponse<ReversalDetail>>(`/redemptions/${id}/reversal-requests`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function listRedemptionReversals(query: {
  page: number
  size: number
  status?: string
}): Promise<PageResult<RedemptionReversal>> {
  const res = await http.get<ApiResponse<PageResult<RedemptionReversal>>>('/redemption-reversals', { params: query })
  return res.data.data
}

export async function getRedemptionReversal(id: number): Promise<ReversalDetail> {
  const res = await http.get<ApiResponse<ReversalDetail>>(`/redemption-reversals/${id}`)
  return res.data.data
}

export async function approveRedemptionReversal(id: number, body: ReversalReviewInput, idempotencyKey: string): Promise<ReversalDetail> {
  const res = await http.post<ApiResponse<ReversalDetail>>(`/redemption-reversals/${id}/approve`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function rejectRedemptionReversal(id: number, body: ReversalReviewInput, idempotencyKey: string): Promise<ReversalDetail> {
  const res = await http.post<ApiResponse<ReversalDetail>>(`/redemption-reversals/${id}/reject`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}
