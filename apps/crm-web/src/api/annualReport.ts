import type { ApiResponse, PageResult } from '../types/api'
import { ApiError } from '../types/api'
import { http } from './http'

export type AnnualToneCode = 'AUTO' | 'POETIC' | 'CLINICAL' | 'MINIMAL'
export type AnnualGenderHint = 'FEMALE' | 'MALE' | 'UNKNOWN'
export type AnnualTelemetryEventType =
  | 'SESSION_OPEN'
  | 'SCENE_CHANGE'
  | 'HEARTBEAT'
  | 'VISIBILITY_HIDE'
  | 'PAGE_HIDE'
  | 'IGNITE_LAMP'
  | 'VIEW_ORDER'
  | 'TOGGLE_PRIVACY'
  | 'GENERATE_POSTER'
  | 'BOOKING_INTENT'

export interface AnnualReportItem {
  id: number
  customerId: number
  ownerStaffId: number
  reportYear: number
  shareKey: string
  link: string
  toneCode: 'POETIC' | 'CLINICAL' | 'MINIMAL'
  toneReason: string
  displayName: string
  tierLabel: string
  butlerName: string
  orderCount: number
  netSpendFen: number
  redeemCount: number
  remainingEntitlementCount: number
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string
  version: number
  visitCount: number
  totalVisibleDurationMs: number
  maxSceneReached: number
  maxViewedSceneCount: number
  generatedPoster: boolean
  clickedBookButler: boolean
  lastActiveAt: string | null
}

export interface AnnualReportSession {
  id: number
  reportId: number
  sessionKey: string
  firstOpenAt: string
  lastActiveAt: string
  visibleDurationMs: number
  maxSceneReached: number
  viewedSceneCount: number
  viewedScenes: number[]
  sceneDurationsMs: Record<string, number>
  ignitedLamp: boolean
  openedOrderCount: number
  toggledPrivacy: boolean
  generatedPoster: boolean
  clickedBookButler: boolean
  bookingTaskId: number | null
  clientDevice: string
}

export interface AnnualReportSummary {
  reportYear: number
  totalReports: number
  activeReports: number
  openedReports: number
  completedReports: number
  totalSessions: number
  avgVisibleDurationMs: number
  posterReports: number
  bookingIntentReports: number
  sceneFunnel: Array<{
    sceneIndex: number
    label: string
    reachedReports: number
  }>
}

export interface CreateAnnualReportInput {
  customerId: number
  reportYear: number
  toneCode?: AnnualToneCode
  genderHint?: AnnualGenderHint
  customButlerNote?: string
  disablePreviousActive?: boolean
}

export interface BatchGenerateAnnualReportInput {
  customerIds: number[]
  reportYear: number
  toneCode?: AnnualToneCode
  disablePreviousActive?: boolean
}

export interface BatchGenerateAnnualReportResult {
  reportYear: number
  requestedCount: number
  createdCount: number
  skippedCount: number
  items: AnnualReportItem[]
  skipped: Array<{ customerId: number; reason: string }>
}

export interface PublicAnnualReport {
  reportYear: number
  memberNo: string
  displayName: string
  tierLabel: string
  sealText: string
  butlerName: string
  firstMeetDate: string
  daysTogether: number
  orderCount: number
  netSpendFen: number
  categoryCount: number
  redeemCount: number
  institutionCount: number
  butlerResponseCount: number
  toneCode: 'POETIC' | 'CLINICAL' | 'MINIMAL'
  toneLabel: string
  toneReason: string
  copy: {
    salutation: string
    coverSubTitle: string
    igniteBtnText: string
    igniteHint: string
    firstMeetNote: string
    coverNextBtn: string
    scene1Title: string
    scene2Title: string
    midnightStory: string
    scene3Title: string
    favDesc: string
    scene4Title: string
    vaultBadge: string
    vaultCta: string
    personaTitle: string
    personaQuote: string
    butlerSign: string
    posterHeading: string
  }
  midnight: {
    time: string
    date: string
    orderTitle: string
    eventType: string
    peakLabel: string
    hourDistribution: Array<{
      hour: number
      weight: number
      latest: boolean
      label: string
    }>
  }
  favorite: {
    projectName: string
    beatPercent: string
    tags: string[]
    dimensions: Array<{ name: string; percent: number }>
    top3: Array<{ rank: string; name: string; countText: string }>
  }
  remainingEntitlements: {
    totalCount: number
    expireDate: string
    items: Array<{ name: string; subtitle: string; countText: string }>
  }
  orders: Array<{
    maskedOrderNo: string
    date: string
    md: string
    time: string
    season: string
    title: string
    category: string
    amountFen: number
    statusText: string
    open: boolean
    channel: string
    skus: Array<{ name: string; qtyText: string }>
  }>
}

export interface PublicAnnualTelemetryPayload {
  sessionKey: string
  eventType: AnnualTelemetryEventType
  sceneIndex: number
  deltaDurationMs: number
  clientDevice?: 'MOBILE' | 'DESKTOP' | 'WECHAT' | 'UNKNOWN'
  detail?: string
}

export interface PublicAnnualBookingResult {
  submitted: boolean
  alreadyRequested: boolean
  butlerName: string
  message: string
}

export const ANNUAL_SHARE_KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/

export function isValidAnnualShareKey(shareKey: string): boolean {
  return ANNUAL_SHARE_KEY_PATTERN.test(shareKey)
}

function idempotency(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}

export async function createAnnualReport(
  body: CreateAnnualReportInput,
  key: string,
): Promise<AnnualReportItem> {
  const res = await http.post<ApiResponse<AnnualReportItem>>('/annual-reports', body, idempotency(key))
  return res.data.data
}

export async function batchGenerateAnnualReports(
  body: BatchGenerateAnnualReportInput,
  key: string,
): Promise<BatchGenerateAnnualReportResult> {
  const res = await http.post<ApiResponse<BatchGenerateAnnualReportResult>>(
    '/annual-reports/batch-generate',
    body,
    idempotency(key),
  )
  return res.data.data
}

export async function listAnnualReports(params: {
  reportYear?: number
  customerId?: number
  status?: 'ACTIVE' | 'DISABLED'
  page?: number
  size?: number
}): Promise<PageResult<AnnualReportItem>> {
  const res = await http.get<ApiResponse<PageResult<AnnualReportItem>>>('/annual-reports', { params })
  return res.data.data
}

export async function getAnnualReportSummary(reportYear: number): Promise<AnnualReportSummary> {
  const res = await http.get<ApiResponse<AnnualReportSummary>>('/annual-reports/summary', {
    params: { reportYear },
  })
  return res.data.data
}

export async function listAnnualReportSessions(id: number, limit = 50): Promise<AnnualReportSession[]> {
  const res = await http.get<ApiResponse<AnnualReportSession[]>>(`/annual-reports/${id}/sessions`, {
    params: { limit },
  })
  return res.data.data
}

export async function disableAnnualReport(
  id: number,
  body: { version: number; reason: string },
  key: string,
): Promise<AnnualReportItem> {
  const res = await http.post<ApiResponse<AnnualReportItem>>(
    `/annual-reports/${id}/disable`,
    body,
    idempotency(key),
  )
  return res.data.data
}

export async function getPublicAnnualReport(shareKey: string): Promise<PublicAnnualReport> {
  if (!isValidAnnualShareKey(shareKey)) {
    throw new ApiError({
      status: 400,
      code: 'INVALID_SHARE_KEY',
      message: '年度报告分享链接无效或格式不正确',
    })
  }
  const res = await http.get<ApiResponse<PublicAnnualReport>>(
    `/public/annual-reports/${encodeURIComponent(shareKey)}`,
  )
  return res.data.data
}

export async function sendPublicAnnualTelemetry(
  shareKey: string,
  payload: PublicAnnualTelemetryPayload,
  useBeacon = false,
): Promise<void> {
  if (!isValidAnnualShareKey(shareKey)) {
    return
  }
  const url = `/api/v1/public/annual-reports/${encodeURIComponent(shareKey)}/telemetry`
  if (useBeacon && typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
    const blob = new Blob([JSON.stringify(payload)], { type: 'application/json' })
    navigator.sendBeacon(url, blob)
    return
  }
  await http.post(`/public/annual-reports/${encodeURIComponent(shareKey)}/telemetry`, payload)
}

export async function submitPublicAnnualBookingIntent(
  shareKey: string,
  body: { sessionKey: string; note?: string },
): Promise<PublicAnnualBookingResult> {
  const res = await http.post<ApiResponse<PublicAnnualBookingResult>>(
    `/public/annual-reports/${encodeURIComponent(shareKey)}/booking-intent`,
    body,
  )
  return res.data.data
}
