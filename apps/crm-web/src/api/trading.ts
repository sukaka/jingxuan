import type { ApiResponse, PageResult } from '../types/api'
import { http } from './http'

export interface CrmOrder {
  id: number
  orderNo: string
  customerId: number
  customerName: string | null
  soldByUserId: number
  ownerAtSaleId: number
  externalOrderNo: string | null
  sourceSystem: string
  orderTypeCode: string
  campaignId: number | null
  campaignBatchId: number | null
  status: 'DRAFT' | 'PENDING_PAYMENT' | 'PAID' | 'PARTIALLY_REDEEMED' | 'FULFILLED' | 'CANCELED'
  receiverType: 'PLATFORM' | 'INSTITUTION'
  listAmountFen: number
  discountAmountFen: number
  payableFen: number
  paidFen: number
  refundedFen: number
  orderedAt: string | null
  paidAt: string | null
  createdAt: string
  version: number
}

export interface OrderItem {
  id: number
  orderId: number
  skuId: number
  packageVersionId: number
  skuCodeSnapshot: string | null
  skuNameSnapshot: string
  purchaseQuantity: number
  listAmountFen: number
  paidAllocatedFen: number
  entitlementSnapshot: string
  version: number
}

export interface PaymentTxn {
  id: number
  orderId: number
  paymentChannel: string
  receiverType: string
  externalTradeNo: string
  amountFen: number
  status: string
  confirmedAt: string | null
  confirmedBy: number | null
  voucherFileId: number | null
  evidenceRef: string | null
  verificationMethod: string
  createdAt: string
  version: number
}

export interface OrderStatusHistory {
  id: number
  orderId: number
  fromStatus: string
  toStatus: string
  actorUserId: number | null
  triggerType: string
  triggerRef: string | null
  reason: string | null
  createdAt: string
}

export interface Entitlement {
  id: number
  customerId: number
  orderItemId: number
  projectId: number
  projectName: string | null
  grantedCount: number
  availableCount: number
  versionNo: number
  validFrom: string | null
  validTo: string | null
  status: string
  createdAt: string
  version: number
}

export interface EntitlementLedgerEntry {
  id: number
  entitlementId: number
  txnType: string
  deltaCount: number
  sourceType: string
  sourceRef: string
  idempotencyKey: string
  actorUserId: number | null
  reason: string | null
  createdAt: string
  version: number
}

export interface OrderFulfillmentView {
  order: {
    order: CrmOrder
    items: OrderItem[]
    payments: PaymentTxn[]
    history: OrderStatusHistory[]
  }
  entitlements: Entitlement[]
}

export interface EntitlementDetail {
  entitlement: Entitlement
  availableBalance: number
  ledger: EntitlementLedgerEntry[]
}

export interface EntitlementSummary {
  availableCount: number
  customerCount: number
}

export interface CreateOrderInput {
  customerId: number
  receiverType: string
  orderTypeCode: string
  campaignId?: number
  campaignBatchId?: number
  payableFen: number
  validTo?: string
  items: { skuId: number; quantity: number }[]
}

export interface PaymentConfirmInput {
  externalTradeNo: string
  paymentChannel: string
  amountFen: number
  paidAt: string
  evidenceRef: string
  voucherFileId?: number
}

export async function listOrders(query: { page: number; size: number; customerId?: number; status?: string }): Promise<PageResult<CrmOrder>> {
  const res = await http.get<ApiResponse<PageResult<CrmOrder>>>('/orders', { params: query })
  return res.data.data
}

export async function createOrder(body: CreateOrderInput): Promise<OrderFulfillmentView> {
  const res = await http.post<ApiResponse<OrderFulfillmentView>>('/orders', body)
  return res.data.data
}

export async function getOrder(id: number): Promise<OrderFulfillmentView> {
  const res = await http.get<ApiResponse<OrderFulfillmentView>>(`/orders/${id}`)
  return res.data.data
}

export async function submitOrder(id: number, version: number, reason?: string): Promise<OrderFulfillmentView> {
  const res = await http.post<ApiResponse<OrderFulfillmentView>>(`/orders/${id}/submit`, { version, reason })
  return res.data.data
}

export async function cancelOrder(id: number, version: number, reason?: string): Promise<OrderFulfillmentView> {
  const res = await http.post<ApiResponse<OrderFulfillmentView>>(`/orders/${id}/cancel`, { version, reason })
  return res.data.data
}

export async function confirmPayment(id: number, body: PaymentConfirmInput, idempotencyKey: string): Promise<OrderFulfillmentView> {
  const res = await http.post<ApiResponse<OrderFulfillmentView>>(`/orders/${id}/payment-confirmations`, body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return res.data.data
}

export async function listEntitlements(query: { page: number; size: number; customerId?: number; status?: string }): Promise<PageResult<Entitlement>> {
  const res = await http.get<ApiResponse<PageResult<Entitlement>>>('/entitlements', { params: query })
  return res.data.data
}

export async function getEntitlementSummary(customerId?: number): Promise<EntitlementSummary> {
  const res = await http.get<ApiResponse<EntitlementSummary>>('/entitlements/summary', {
    params: customerId === undefined ? {} : { customerId },
  })
  return res.data.data
}

export async function getEntitlement(id: number): Promise<EntitlementDetail> {
  const res = await http.get<ApiResponse<EntitlementDetail>>(`/entitlements/${id}`)
  return res.data.data
}
