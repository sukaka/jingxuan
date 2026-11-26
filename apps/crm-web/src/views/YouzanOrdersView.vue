<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { routeLocationKey } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { YouzanCustomerLink, YouzanOrderView, YouzanReconcileReport, YouzanShopConfig, YouzanSkuCandidate, YouzanSkuMapping, YouzanSyncIssue } from '../api/youzan'
import {
  batchRetryYouzanSyncIssues,
  getYouzanReconcileReport,
  listYouzanCustomerLinks,
  listYouzanOrders,
  listYouzanShops,
  listYouzanSkuCandidates,
  listYouzanSkuMappings,
  listYouzanSyncIssues,
  reconcileYouzanOrders,
  retryYouzanSyncIssue,
  syncYouzanOrders,
} from '../api/youzan'
import { getOrder } from '../api/trading'
import { getCustomer } from '../api/customer'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import IntegrationRuntimeSettings from '../components/IntegrationRuntimeSettings.vue'
import { useAuthStore } from '../stores/auth'
import { formatDateTime } from './customer/shared'
import StatusPill from './customer/StatusPill.vue'
import YouzanCustomerLinkPanel from './youzan/YouzanCustomerLinkPanel.vue'
import YouzanCustomerPullPanel from './youzan/YouzanCustomerPullPanel.vue'
import YouzanOrderDetailDrawer from './youzan/YouzanOrderDetailDrawer.vue'
import YouzanShopConfigPanel from './youzan/YouzanShopConfigPanel.vue'
import YouzanSkuMappingPanel from './youzan/YouzanSkuMappingPanel.vue'
import YouzanSyncIssuePanel from './youzan/YouzanSyncIssuePanel.vue'
import YouzanSyncJobPanel from './youzan/YouzanSyncJobPanel.vue'
import YouzanSyncPolicyPanel from './youzan/YouzanSyncPolicyPanel.vue'
import {
  createYouzanKey,
  extractUnmappedExternalSkuId,
  isMappingSuccess,
  mappingStatusLabel,
  mappingStatusTone,
  resolveYouzanOrderItems,
  youzanOrderItemQuantityText,
  youzanOrderItemsTotalQuantity,
  youzanOrderItemTitle,
} from './youzan/youzanUi'
import { orderTypeLabel, tradingErrorText } from './trading/tradingUi'
import { orderTypeTone } from '../utils/tagTone'
import { fenToYuan } from '../utils/money'
import './customer/customer-ui.css'
import './trading/trading.css'
import './fulfillment/fulfillment.css'
import './youzan/youzan.css'

const DEFAULT_MOCK_SHOP_ID = 'MOCK-SHOP-001'
const PULL_COUNT_PRESETS = [10, 20, 50, 100, 500] as const

const route = inject(routeLocationKey, null)
const auth = useAuthStore()
const canReadOrders = computed(() => auth.hasPermission('order:read'))
const canRead = computed(() => canReadOrders.value || auth.hasPermission('integration:youzan:manage'))
const canWrite = computed(() => auth.hasPermission('campaign:write') || auth.hasPermission('integration:youzan:manage'))
const canManage = computed(() => auth.hasPermission('integration:youzan:manage'))
const canManageRuntime = computed(() => auth.hasPermission('integration:runtime:manage'))

function resolveTabFromQuery(rawTab: unknown): string | null {
  const value = Array.isArray(rawTab) ? rawTab[0] : rawTab
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  const publicTabs = new Set(['orders', 'mappings', 'customers', 'issues'])
  const manageTabs = new Set(['shops', 'policy', 'jobs', 'customer-pull'])
  if (publicTabs.has(normalized)) return normalized
  if (manageTabs.has(normalized) && canManage.value) return normalized
  return null
}

function readInitialTab(): string {
  const fromRoute = resolveTabFromQuery(route?.query?.tab)
  if (fromRoute) return fromRoute
  try {
    const fromWindow = typeof window !== 'undefined'
      ? resolveTabFromQuery(new URLSearchParams(window.location.search).get('tab'))
      : null
    if (fromWindow) return fromWindow
  } catch {
    // 忽略地址解析异常
  }
  return 'orders'
}

const activeTab = ref(readInitialTab())
const showPrerequisites = ref(false)

if (route) {
  watch(() => route.query.tab, (nextTab) => {
    const resolved = resolveTabFromQuery(nextTab)
    if (resolved) activeTab.value = resolved
  })
}

function handleCustomerPullLinkExternal(externalCustomerId: string) {
  pendingCustomerExternalId.value = externalCustomerId.trim()
  activeTab.value = 'customers'
}

function shopStorageKey(): string {
  return `whale.crm.youzan.shopId:${auth.user?.userId ?? 'default'}`
}

function pullCountStorageKey(): string {
  return `whale.crm.youzan.pullCount:${auth.user?.userId ?? 'default'}`
}

function readPersistedShopId(): string | null {
  try {
    const fromUrl = typeof window !== 'undefined'
      ? new URLSearchParams(window.location.search).get('shopId')?.trim()
      : ''
    if (fromUrl) return fromUrl
    if (typeof window !== 'undefined' && window.localStorage) {
      const saved = window.localStorage.getItem(shopStorageKey())?.trim()
      if (saved) return saved
    }
  } catch {
    // 忽略本地存储异常
  }
  return null
}

function persistShopId(id: string) {
  const trimmed = id.trim()
  if (!trimmed) return
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(shopStorageKey(), trimmed)
    }
  } catch {
    // 忽略本地存储异常
  }
}

function readPersistedPullCount(): number {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const rawValue = window.localStorage.getItem(pullCountStorageKey())
      if (rawValue != null && rawValue !== '') {
        const parsed = Math.floor(Number(rawValue))
        if (Number.isFinite(parsed) && parsed >= 1 && parsed <= 500) return parsed
      }
    }
  } catch {
    // 忽略本地存储异常
  }
  return 50
}

function persistPullCount(count: number) {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(pullCountStorageKey(), String(count))
    }
  } catch {
    // 忽略本地存储异常
  }
}

const shopId = ref(DEFAULT_MOCK_SHOP_ID)
const normalizedShopId = computed(() => shopId.value.trim())
const isMockShop = computed(() => normalizedShopId.value.startsWith('MOCK-'))
const currentShop = computed(() => shops.value.find((item) => item.shopId === normalizedShopId.value) ?? null)
const activeRealShops = computed(() => shops.value.filter((item) => item.syncEnabled && item.connectionStatus === 'CONNECTED'))
const hasActiveRealIntegration = computed(() => (!isMockShop.value && Boolean(normalizedShopId.value)) || activeRealShops.value.length > 0)
const pendingCustomerExternalId = ref('')
const pendingSkuExternalId = ref('')
const skuAutoOpenRequest = ref<{ externalSkuId: string; nonce: number } | null>(null)
let skuAutoOpenNonce = 0
const pullCount = ref<number>(readPersistedPullCount())
const effectivePullCount = computed(() => {
  const parsed = Math.floor(Number(pullCount.value))
  return Number.isFinite(parsed) ? Math.min(500, Math.max(1, parsed)) : 50
})

function applyPullPreset(count: number) {
  pullCount.value = count
}
function shopStatusNote(shop: YouzanShopConfig): string {
  if (shop.connectionStatus === 'CONNECTED') return '连接通过，同步未启用'
  if (shop.connectionStatus === 'FAILED') return '连接失败'
  return '连接待验证'
}
const customerNames = ref<Record<number, string>>({})
const pendingMatches = computed(() => orders.value.filter((row) => row.mappingStatus === 'CUSTOMER_UNRESOLVED').length)
const syncErrors = computed(() => orders.value.filter((row) => ['MAPPING_FAILED','PAYMENT_MISMATCH','RETRY','DEAD_LETTER'].includes(row.mappingStatus || '')).length)
function raw(row: YouzanOrderView): Record<string, unknown> {
  try { return row.payloadMaskedJson ? JSON.parse(row.payloadMaskedJson) as Record<string, unknown> : {} } catch { return {} }
}
function text(value: unknown) { return value == null || value === '' ? '—' : String(value) }
function externalCustomerIdOf(row: YouzanOrderView): string | null {
  if (row.externalCustomerId) return row.externalCustomerId
  const value = raw(row).external_customer_id
  return typeof value === 'string' && value.trim() ? value : null
}
function buyerHeadline(row: YouzanOrderView): string {
  const data = raw(row)
  if (data.nickname != null && data.nickname !== '') return String(data.nickname)
  return externalCustomerIdOf(row) || '—'
}
function buyerSubline(row: YouzanOrderView): string {
  const data = raw(row)
  if (data.mobile != null && data.mobile !== '') return String(data.mobile)
  const extId = externalCustomerIdOf(row)
  if (data.nickname != null && data.nickname !== '') return extId || '—'
  return extId ? '有赞外部客户' : '—'
}
function goodsPrimary(row: YouzanOrderView): string {
  const items = resolveYouzanOrderItems(row)
  if (items.length === 0) return '—'
  const first = items[0]
  return `${youzanOrderItemTitle(first, 'table')} × ${youzanOrderItemQuantityText(first.quantity)}`
}
function goodsCountBadge(row: YouzanOrderView): string | null {
  const items = resolveYouzanOrderItems(row)
  return items.length > 1 ? `共 ${items.length} 项` : null
}
function goodsSubline(row: YouzanOrderView): string | null {
  const items = resolveYouzanOrderItems(row)
  if (items.length === 0) return null
  const first = items[0]
  const parts: string[] = []
  if (first.skuSpec) parts.push(first.skuSpec)
  const hasReadableTitle = Boolean(first.itemTitle || first.crmSkuName)
  if (hasReadableTitle && first.externalSkuId) parts.push(`SKU ${first.externalSkuId}`)
  if (items.length > 1) {
    const totalQty = youzanOrderItemsTotalQuantity(items)
    if (totalQty > 0) parts.push(`合计 ${totalQty} 件`)
    const second = items[1]
    const secondTitle = youzanOrderItemTitle(second, 'table')
    const secondQty = youzanOrderItemQuantityText(second.quantity)
    parts.push(items.length === 2 ? `另含 ${secondTitle} × ${secondQty}` : `另含 ${secondTitle} 等 ${items.length - 1} 项`)
  }
  return parts.length > 0 ? parts.join(' · ') : null
}
function orderTypeOf(row: YouzanOrderView) {
  return text(row.orderTypeCode ?? raw(row).order_type_code)
}
function campaignIdOf(row: YouzanOrderView) {
  return text(row.campaignId ?? raw(row).campaign_id)
}
function campaignBatchIdOf(row: YouzanOrderView) {
  return text(row.campaignBatchId ?? raw(row).campaign_batch_id)
}
function paidAtOf(row: YouzanOrderView): string | null {
  if (row.paidAt) return row.paidAt
  const value = raw(row).paid_at
  return value ? String(value) : null
}
function linkedCustomer(row: YouzanOrderView) {
  const extId = externalCustomerIdOf(row)
  const link = extId ? links.value.find((item) => item.externalCustomerId === extId) : undefined
  return link ? customerNames.value[link.customerId] || `客户 #${link.customerId}` : '—'
}
function openSkuMappingWithPrefill(externalSkuId: string) {
  const normalized = externalSkuId.trim()
  pendingSkuExternalId.value = normalized
  if (normalized && canWrite.value) {
    skuAutoOpenNonce += 1
    skuAutoOpenRequest.value = { externalSkuId: normalized, nonce: skuAutoOpenNonce }
  }
  activeTab.value = 'mappings'
}
function handleAutoOpenConsumed(nonce: number) {
  if (skuAutoOpenRequest.value?.nonce === nonce) {
    skuAutoOpenRequest.value = null
  }
}
function matchAndSync(row: YouzanOrderView) {
  if (row.mappingStatus === 'CUSTOMER_UNRESOLVED') {
    pendingCustomerExternalId.value = externalCustomerIdOf(row) ?? ''
    activeTab.value = 'customers'
    return
  }
  const activeMapped = new Set(mappings.value.filter((item) => item.status === 'ACTIVE').map((item) => item.externalSkuId))
  const items = resolveYouzanOrderItems(row)
  const missing = items.find((line) => line.externalSkuId && !activeMapped.has(line.externalSkuId))
  const skuId = missing?.externalSkuId || items[0]?.externalSkuId || extractUnmappedExternalSkuId(row.errorReason)
  if (typeof skuId === 'string' && skuId.trim()) {
    openSkuMappingWithPrefill(skuId)
    return
  }
  pendingSkuExternalId.value = ''
  activeTab.value = 'mappings'
}

const orders = ref<YouzanOrderView[]>([])
const orderTotal = ref(0)
const orderLoading = ref(false)
const orderError = ref('')
const orderQuery = reactive({ page: 1, size: 20 })

const report = ref<YouzanReconcileReport | null>(null)
const reportError = ref('')
const mappings = ref<YouzanSkuMapping[]>([])
const mappingsLoading = ref(false)
const skuCandidates = ref<YouzanSkuCandidate[]>([])
const skuCandidatesLoading = ref(false)
const skuCandidatesError = ref('')
let skuCandidatesSeq = 0
const activeMappedSkuIds = computed(() => new Set(mappings.value.filter((item) => item.status === 'ACTIVE').map((item) => item.externalSkuId)))
const readyRetryCandidatesCount = computed(() => skuCandidates.value.filter((item) => item.mapped && item.pendingIssueCount > 0).length)
const links = ref<YouzanCustomerLink[]>([])
const linksLoading = ref(false)
const shops = ref<YouzanShopConfig[]>([])
const shopsLoading = ref(false)

const issues = ref<YouzanSyncIssue[]>([])
const issueTotal = ref(0)
const issueLoading = ref(false)
const issueError = ref('')
const issueQuery = reactive({ page: 1, size: 20, status: '' })

async function loadOrders() {
  orderLoading.value = true
  orderError.value = ''
  try {
    const result = await listYouzanOrders({ shopId: normalizedShopId.value, page: orderQuery.page, size: orderQuery.size })
    orders.value = result.items
    orderTotal.value = result.total
    orderQuery.page = result.page
    orderQuery.size = result.size
  } catch (error) {
    orders.value = []
    orderTotal.value = 0
    orderError.value = tradingErrorText(error)
  } finally {
    orderLoading.value = false
  }
}

async function loadReport() {
  reportError.value = ''
  try {
    report.value = await getYouzanReconcileReport(normalizedShopId.value)
  } catch (error) {
    report.value = null
    reportError.value = tradingErrorText(error)
  }
}

async function loadMappings() {
  mappingsLoading.value = true
  try {
    mappings.value = await listYouzanSkuMappings(normalizedShopId.value)
  } catch (error) {
    mappings.value = []
    ElMessage.error(tradingErrorText(error))
  } finally {
    mappingsLoading.value = false
  }
}

async function loadSkuCandidates() {
  const sequence = ++skuCandidatesSeq
  const targetShopId = normalizedShopId.value
  skuCandidatesError.value = ''
  if (!targetShopId) {
    skuCandidates.value = []
    skuCandidatesLoading.value = false
    return
  }
  skuCandidatesLoading.value = true
  try {
    const result = await listYouzanSkuCandidates(targetShopId)
    if (sequence !== skuCandidatesSeq || normalizedShopId.value !== targetShopId) return
    skuCandidates.value = Array.isArray(result) ? result : []
  } catch (error) {
    if (sequence !== skuCandidatesSeq || normalizedShopId.value !== targetShopId) return
    skuCandidates.value = []
    skuCandidatesError.value = tradingErrorText(error)
  } finally {
    if (sequence === skuCandidatesSeq) skuCandidatesLoading.value = false
  }
}

function handleSkuMappingSaved() {
  pendingSkuExternalId.value = ''
  skuAutoOpenRequest.value = null
  batchRetryAttempt = null
  void Promise.allSettled([loadMappings(), loadSkuCandidates()])
}

async function loadLinks() {
  linksLoading.value = true
  try {
    links.value = await listYouzanCustomerLinks(normalizedShopId.value)
    const ids = [...new Set(links.value.map((item) => item.customerId))]
    const results = await Promise.allSettled(ids.map((id) => getCustomer(id)))
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') customerNames.value[ids[index]] = result.value.displayName || result.value.nickname || `客户 #${ids[index]}`
    })
  } catch (error) {
    links.value = []
    ElMessage.error(tradingErrorText(error))
  } finally {
    linksLoading.value = false
  }
}

async function loadIssues() {
  issueLoading.value = true
  issueError.value = ''
  try {
    const result = await listYouzanSyncIssues({ status: issueQuery.status || undefined, page: issueQuery.page, size: issueQuery.size })
    issues.value = result.items
    issueTotal.value = result.total
    issueQuery.page = result.page
    issueQuery.size = result.size
  } catch (error) {
    issues.value = []
    issueTotal.value = 0
    issueError.value = tradingErrorText(error)
  } finally {
    issueLoading.value = false
  }
}

async function loadShops() {
  if (!canManage.value) return
  shopsLoading.value = true
  try {
    shops.value = await listYouzanShops()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    shopsLoading.value = false
  }
}

function selectShop(id: string) {
  syncCursor.value = ''
  reconcileCursor.value = ''
  reconcileHasMore.value = false
  pendingCustomerExternalId.value = ''
  pendingSkuExternalId.value = ''
  skuAutoOpenRequest.value = null
  syncAttempt = null
  batchRetryAttempt = null
  shopId.value = id.trim()
  persistShopId(shopId.value)
  search()
}

function loadAll(includeShops = true) {
  if (!canRead.value) return
  const tasks: Array<Promise<unknown>> = [loadOrders(), loadIssues(), loadMappings(), loadSkuCandidates(), loadLinks(), loadReport()]
  if (includeShops) tasks.push(loadShops())
  void Promise.allSettled(tasks)
}

function search() {
  if (normalizedShopId.value) persistShopId(normalizedShopId.value)
  orderQuery.page = 1
  issueQuery.page = 1
  loadAll()
}

async function initializePage() {
  if (!canRead.value) return
  const persisted = readPersistedShopId()
  if (persisted) {
    shopId.value = persisted
    loadAll()
    return
  }
  if (canManage.value) {
    await loadShops()
    const preferred = activeRealShops.value[0] ?? shops.value[0]
    if (preferred?.shopId) {
      shopId.value = preferred.shopId
      persistShopId(preferred.shopId)
    }
    loadAll(false)
    return
  }
  loadAll()
}

const detailVisible = ref(false)
const selectedOrder = ref<YouzanOrderView | null>(null)
const detailLoadingId = ref<string | null>(null)

function orderRowKey(order: YouzanOrderView): string {
  return order.eventId || order.externalOrderNo
}

async function openOrder(order: YouzanOrderView) {
  detailLoadingId.value = orderRowKey(order)
  selectedOrder.value = order
  if (order.orderId == null || !canReadOrders.value) {
    detailVisible.value = true
    detailLoadingId.value = null
    return
  }
  try {
    const fulfillment = await getOrder(order.orderId)
    const payment = fulfillment.order.payments.at(-1)
    selectedOrder.value = { ...order, verificationMethod: payment?.verificationMethod ?? order.verificationMethod }
  } catch (error) {
    ElMessage.error(`内部支付流水加载失败：${tradingErrorText(error)}`)
  } finally {
    detailVisible.value = true
    detailLoadingId.value = null
  }
}

const syncing = ref(false)
const syncCursor = ref('')
const reconcileCursor = ref('')
const reconcileHasMore = ref(false)
let syncAttempt: { signature: string; key: string } | null = null

watch(shopId, () => {
  syncCursor.value = ''
  reconcileCursor.value = ''
  reconcileHasMore.value = false
  pendingCustomerExternalId.value = ''
  pendingSkuExternalId.value = ''
  skuAutoOpenRequest.value = null
  syncAttempt = null
  batchRetryAttempt = null
}, { flush: 'sync' })

watch(effectivePullCount, (next) => {
  syncCursor.value = ''
  reconcileCursor.value = ''
  reconcileHasMore.value = false
  syncAttempt = null
  persistPullCount(next)
}, { flush: 'sync' })

async function runSync(mode: 'sync' | 'reconcile') {
  if (syncing.value) return
  const targetShopId = normalizedShopId.value
  if (!targetShopId) {
    ElMessage.warning('请先输入或选择有赞店铺编号（kdt_id）')
    return
  }
  persistShopId(targetShopId)
  const count = effectivePullCount.value
  const syncLimit = Math.min(count, 100)
  const reconcileLimit = count <= 100 ? count : undefined
  const cursor = mode === 'sync' ? syncCursor.value : (reconcileHasMore.value ? reconcileCursor.value : '')
  const signature = `${targetShopId}:${mode}:${cursor}:${count}`
  if (syncAttempt?.signature !== signature) syncAttempt = { signature, key: createYouzanKey(`youzan.${mode}`) }
  const key = syncAttempt.key
  syncing.value = true
  try {
    const result = mode === 'sync'
      ? await syncYouzanOrders({ shopId: targetShopId, cursor: cursor || undefined, limit: syncLimit }, key)
      : await reconcileYouzanOrders({
        shopId: targetShopId,
        cursor: cursor || undefined,
        ...(reconcileLimit != null ? { limit: reconcileLimit } : {}),
        maxOrders: count,
      }, key)
    syncAttempt = null
    if (normalizedShopId.value === targetShopId) {
      if (mode === 'sync') {
        syncCursor.value = result.hasMore && result.nextCursor ? result.nextCursor : ''
      } else if (result.hasMore && result.nextCursor) {
        reconcileCursor.value = result.nextCursor
        reconcileHasMore.value = true
      } else {
        reconcileCursor.value = ''
        reconcileHasMore.value = false
      }
    }
    orderQuery.page = 1
    await Promise.allSettled([loadOrders(), loadIssues(), loadSkuCandidates(), loadReport()])
    const syncLabel = targetShopId.startsWith('MOCK-') ? '模拟演示同步' : '同步'
    const moreHint = result.hasMore ? '；仍有后续页，请继续拉取' : ''
    if (result.issues > 0) {
      ElMessage.warning(`${syncLabel}返回 ${result.issues} 条异常，不能视为全部成功；已处理 ${result.processed} 条，重复 ${result.duplicates} 条${moreHint}`)
    } else {
      ElMessage.success(`${syncLabel}完成：接收 ${result.received} 条，处理 ${result.processed} 条，重复 ${result.duplicates} 条${moreHint}`)
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重新提交不会重复执行`)
  } finally {
    syncing.value = false
  }
}

const retryingId = ref<number | null>(null)
let retryAttempt: { signature: string; key: string } | null = null
const batchRetryingSku = ref<string | null>(null)
let batchRetryAttempt: { signature: string; key: string } | null = null

async function retryIssue(issue: YouzanSyncIssue) {
  const signature = `${issue.id}:${issue.version}`
  if (retryAttempt?.signature !== signature) retryAttempt = { signature, key: createYouzanKey('youzan.issue.retry') }
  const key = retryAttempt.key
  retryingId.value = issue.id
  try {
    const result = await retryYouzanSyncIssue(issue.id, key)
    retryAttempt = null
    await Promise.allSettled([loadOrders(), loadIssues(), loadSkuCandidates(), loadReport()])
    if (isMappingSuccess(result.processStatus)) {
      ElMessage.success('补偿处理已完成，未重复创建内部订单')
    } else {
      ElMessage.warning(result.lastError || '补偿重试已返回，但仍需处理映射或支付异常')
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重新提交不会重复执行`)
  } finally {
    retryingId.value = null
  }
}

async function batchRetryIssues(externalSkuId?: string) {
  if (batchRetryingSku.value) return
  const targetShopId = normalizedShopId.value
  if (!targetShopId) {
    ElMessage.warning('请先输入或选择有赞店铺编号（kdt_id）')
    return
  }
  const normalizedSku = externalSkuId?.trim() || ''
  const token = normalizedSku || '__ALL__'
  const signature = `${targetShopId}:${token}`
  if (batchRetryAttempt?.signature !== signature) {
    batchRetryAttempt = { signature, key: createYouzanKey('youzan.issue.batch-retry') }
  }
  const key = batchRetryAttempt.key
  batchRetryingSku.value = token
  try {
    const result = await batchRetryYouzanSyncIssues({
      shopId: targetShopId,
      ...(normalizedSku ? { externalSkuId: normalizedSku } : {}),
      limit: 50,
    }, key)
    batchRetryAttempt = null
    await Promise.allSettled([loadOrders(), loadIssues(), loadSkuCandidates(), loadReport()])
    if (result.matched === 0) {
      ElMessage.info('当前没有可批量重试的已映射 SKU 异常')
      return
    }
    const parts = [`已尝试 ${result.attempted} 条：处理完成 ${result.processed} 条`]
    if (result.advanced > 0) parts.push(`SKU 已通过待继续处理（客户匹配/支付核验）${result.advanced} 条`)
    if (result.stillFailed > 0) parts.push(`仍失败 ${result.stillFailed} 条`)
    if (result.skipped > 0) parts.push(`跳过 ${result.skipped} 条`)
    const suffix = result.remaining > 0 ? `；剩余 ${result.remaining} 条可再次点击继续重试` : ''
    const message = `${parts.join('，')}${suffix}`
    if (result.stillFailed === 0 && result.skipped === 0 && result.remaining === 0 && result.advanced === 0 && result.attempted > 0) {
      ElMessage.success(message)
    } else {
      ElMessage.warning(message)
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重新提交不会重复建单`)
  } finally {
    batchRetryingSku.value = null
  }
}

onMounted(() => {
  void initializePage()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="有赞订单同步中心" description="线上订单自动进入 CRM：去重 → 匹配客户 → 识别套餐 → 保留活动归属 → 生成订单/权益">
      <template #actions>
        <el-button type="info" plain @click="showPrerequisites = true">接口前置条件</el-button>
        <div v-if="canManage" class="youzan-pull-control" data-testid="youzan-pull-control">
          <span class="youzan-pull-label">拉取条数</span>
          <div class="youzan-pull-presets" role="group" aria-label="快捷拉取条数">
            <button
              v-for="preset in PULL_COUNT_PRESETS"
              :key="preset"
              type="button"
              class="youzan-pull-preset"
              :class="{ 'is-active': effectivePullCount === preset }"
              :data-testid="`youzan-pull-preset-${preset}`"
              @click="applyPullPreset(preset)"
            >
              {{ preset }}
            </button>
          </div>
          <el-input-number
            v-model="pullCount"
            :min="1"
            :max="500"
            :step="10"
            :precision="0"
            size="small"
            controls-position="right"
            class="youzan-pull-input"
            data-testid="youzan-pull-count"
            aria-label="自定义拉取条数"
          />
        </div>
        <el-button v-if="canManage" text :disabled="syncing" data-testid="youzan-sync" @click="runSync('sync')">手动单页同步</el-button>
        <el-button v-if="canManage" type="primary" :loading="syncing" data-testid="youzan-reconcile" @click="runSync('reconcile')">{{ reconcileHasMore ? '继续拉取订单' : '立即拉取订单' }}</el-button>
      </template>
    </PageHeader>
    <div v-if="isMockShop && activeRealShops.length === 0" class="youzan-mock-banner" data-testid="youzan-mock-banner"><span class="youzan-mock-badge">模拟演示</span><strong>真实接入功能已预建 · 默认禁用 · 待授权联调</strong><p>模拟演示数据不代表真实资金到账；真实店铺须完成授权与验收后另行启用。</p></div>
    <div v-else-if="isMockShop" class="youzan-mock-banner" data-testid="youzan-mock-banner"><span class="youzan-mock-badge">模拟演示</span><strong>当前查看模拟演示店铺（{{ normalizedShopId }}）；已存在 {{ activeRealShops.length }} 个已连接并启用的真实店铺</strong><p>模拟演示数据不代表真实资金到账；如需操作真实店铺，请切换为对应有赞店铺编号（kdt_id）或在「店铺接入」中选择。</p></div>
    <div v-else class="youzan-real-banner" data-testid="youzan-real-banner">
      <span class="youzan-real-badge">真实店铺</span>
      <strong v-if="currentShop?.syncEnabled && currentShop?.connectionStatus === 'CONNECTED'">当前店铺「{{ currentShop.shopName }}」（{{ currentShop.shopId }}）已连接并启用真实同步</strong>
      <strong v-else>当前查看有赞店铺 {{ normalizedShopId }}{{ currentShop ? `（${currentShop.shopName} · ${shopStatusNote(currentShop)}）` : '' }}</strong>
      <p>订单与支付状态以有赞官方接口及 CRM 支付核验为准；消息推送与重叠窗口自动去重，异常请前往异常队列处理。</p>
    </div>
    <el-alert v-if="!canRead" type="error" title="当前账号暂无订单查看或有赞接入管理权限，无法查看有赞同步数据" :closable="false" />
    <template v-else>
      <IntegrationRuntimeSettings v-if="canManageRuntime" provider="youzan" />
      <div class="proto-stat-grid"><StatCard label="今日拉取" :value="`${report?.todayEvents ?? 0} 单`" sub="按实际接收时间" icon="bag" /><StatCard label="待匹配" :value="`${pendingMatches} 单`" sub="需要确认 CRM 客户" icon="users" /><StatCard label="同步异常" :value="`${syncErrors} 单`" sub="商品映射或支付核验异常" icon="shield" /><StatCard label="活动订单" :value="`${report?.campaignOrders ?? 0} 单`" sub="保留活动与批次归属" icon="activity" /></div>
      <div class="proto-toolbar shop-card">
        <div class="proto-toolbar-left">
          <el-input v-model="shopId" placeholder="请输入有赞店铺编号（kdt_id）" class="shop-input" data-testid="youzan-shop-id" @keyup.enter="search" />
          <el-button @click="search">查询</el-button>
          <div v-if="shops.length > 0" class="youzan-quick-shops" data-testid="youzan-quick-shops">
            <span class="youzan-quick-shops__label">已配置店铺：</span>
            <button
              v-for="item in shops"
              :key="item.shopId"
              type="button"
              class="youzan-quick-shop-chip"
              :class="{ 'is-active': normalizedShopId === item.shopId }"
              :data-testid="`youzan-quick-shop-${item.shopId}`"
              @click="selectShop(item.shopId)"
            >
              {{ item.shopName }}（{{ item.shopId }}）
            </button>
          </div>
        </div>
        <span class="proto-note">{{ isMockShop ? '当前为模拟演示店铺；未授权真实店铺不会伪造同步结果' : '当前按真实有赞店铺编号（kdt_id）查询与同步；未授权店铺不会伪造同步结果' }}</span>
      </div>
      <el-alert v-if="reportError" type="error" :title="reportError" :closable="false" />

      <el-card shadow="never">
        <el-tabs v-model="activeTab" class="fulfillment-status-tabs">
          <el-tab-pane label="订单与原始状态" name="orders" />
          <el-tab-pane v-if="canManage" label="客户拉取与建档" name="customer-pull" />
          <el-tab-pane v-if="canManage" label="店铺接入" name="shops" />
          <el-tab-pane v-if="canManage" label="同步策略" name="policy" />
          <el-tab-pane v-if="canManage" label="同步任务" name="jobs" />
          <el-tab-pane label="SKU 映射" name="mappings" />
          <el-tab-pane label="人工客户链接" name="customers" />
          <el-tab-pane label="异常队列" name="issues" />
        </el-tabs>

        <template v-if="activeTab === 'orders'">
          <el-alert v-if="orderError" type="error" :title="`订单加载失败：${orderError}`" :closable="false" class="crm-table-alert" />
          <div class="proto-table-wrap">
            <app-table v-loading="orderLoading" :data="orders" :row-key="orderRowKey">
              <el-table-column prop="externalOrderNo" label="有赞订单号" min-width="160" />
              <el-table-column label="客户昵称 / 手机" min-width="135"><template #default="{row}">{{ buyerHeadline(row) }}<div class="proto-subcell">{{ buyerSubline(row) }}</div></template></el-table-column>
              <el-table-column label="商品" min-width="210">
                <template #default="{row}">
                  <div class="youzan-goods-cell" data-testid="youzan-order-goods">
                    <div class="youzan-goods-head">
                      <span class="youzan-goods-title">{{ goodsPrimary(row) }}</span>
                      <span v-if="goodsCountBadge(row)" class="youzan-goods-count">{{ goodsCountBadge(row) }}</span>
                    </div>
                    <div v-if="goodsSubline(row)" class="proto-subcell youzan-goods-sub">{{ goodsSubline(row) }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="实付" width="95" align="right"><template #default="{row}">{{ fenToYuan(row.externalAmountFen) }}</template></el-table-column>
              <el-table-column label="订单类型" width="110"><template #default="{row}"><span class="proto-pill" :class="orderTypeTone(orderTypeOf(row))">{{ orderTypeLabel(orderTypeOf(row)) }}</span></template></el-table-column>
              <el-table-column label="活动 / 批次" min-width="110"><template #default="{row}">活动 #{{ campaignIdOf(row) }}<div class="proto-subcell">批次 #{{ campaignBatchIdOf(row) }}</div></template></el-table-column>
              <el-table-column label="支付时间" min-width="150"><template #default="{row}">{{ paidAtOf(row) ? formatDateTime(paidAtOf(row)) : '未支付' }}</template></el-table-column>
              <el-table-column label="CRM匹配" min-width="105"><template #default="{row}">{{ linkedCustomer(row) }}</template></el-table-column>
              <el-table-column label="状态" width="120"><template #default="{row}"><StatusPill :text="mappingStatusLabel(row.mappingStatus)" :tone="mappingStatusTone(row.mappingStatus)" /></template></el-table-column>
              <el-table-column label="操作" width="190" fixed="right"><template #default="{row}"><el-button v-if="row.mappingStatus !== 'PROCESSED'" size="small" type="primary" @click="matchAndSync(row)">匹配并同步</el-button><span v-else class="proto-muted">已入队</span><el-button text data-testid="youzan-order-detail" @click="openOrder(row)">脱敏详情</el-button></template></el-table-column>
              <template #empty>暂无原始订单事件</template>
            </app-table>
          </div>
          <el-pagination v-model:current-page="orderQuery.page" v-model:page-size="orderQuery.size" :total="orderTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="loadOrders" @size-change="orderQuery.page = 1; loadOrders()" />
        </template>

        <template v-else-if="activeTab === 'customer-pull' && canManage">
          <YouzanCustomerPullPanel
            :shop-id="normalizedShopId"
            @link-external="handleCustomerPullLinkExternal"
            @imported="loadLinks"
          />
        </template>

        <template v-else-if="activeTab === 'shops'">
          <YouzanShopConfigPanel :shops="shops" :loading="shopsLoading" :can-write="canManage" @saved="loadShops" @select="selectShop" />
        </template>

        <template v-else-if="activeTab === 'policy'">
          <YouzanSyncPolicyPanel :shop-id="normalizedShopId" :can-write="canManage" />
        </template>

        <template v-else-if="activeTab === 'jobs'">
          <YouzanSyncJobPanel :shop-id="normalizedShopId" />
        </template>

        <template v-else-if="activeTab === 'mappings'">
          <YouzanSkuMappingPanel
            :mappings="mappings"
            :candidates="skuCandidates"
            :candidates-loading="skuCandidatesLoading"
            :candidates-error="skuCandidatesError"
            :shop-id="normalizedShopId"
            :can-write="canWrite"
            :loading="mappingsLoading"
            :suggested-external-id="pendingSkuExternalId"
            :auto-open-request="skuAutoOpenRequest"
            :batch-retrying-sku="batchRetryingSku"
            @saved="handleSkuMappingSaved"
            @batch-retry="batchRetryIssues"
            @auto-open-consumed="handleAutoOpenConsumed"
            @reload-candidates="loadSkuCandidates"
          />
        </template>

        <template v-else-if="activeTab === 'customers'">
          <YouzanCustomerLinkPanel :links="links" :shop-id="normalizedShopId" :can-write="canWrite" :loading="linksLoading" :suggested-external-id="pendingCustomerExternalId" @saved="pendingCustomerExternalId = ''; loadLinks()" />
        </template>

        <template v-else>
          <div class="crm-toolbar">
            <el-select v-model="issueQuery.status" placeholder="全部异常状态" clearable class="youzan-filter-select" data-testid="issue-status-filter">
              <el-option label="待处理" value="OPEN" />
              <el-option label="补偿重试中" value="RETRYING" />
              <el-option label="已解决" value="RESOLVED" />
              <el-option label="已终止" value="DEAD" />
            </el-select>
            <el-button type="primary" :loading="issueLoading" @click="issueQuery.page = 1; loadIssues()">查询</el-button>
            <el-button @click="issueQuery.status = ''; issueQuery.page = 1; loadIssues()">重置</el-button>
            <el-button
              v-if="canWrite && readyRetryCandidatesCount > 0"
              type="success"
              plain
              :loading="batchRetryingSku === '__ALL__'"
              :disabled="batchRetryingSku !== null && batchRetryingSku !== '__ALL__'"
              :title="`仅批量重试当前所选店铺（${normalizedShopId}）下已完成 SKU 映射的异常`"
              data-testid="issues-batch-retry"
              @click="batchRetryIssues()"
            >
              批量重试已映射异常（{{ readyRetryCandidatesCount }} 个 SKU）
            </el-button>
            <span v-if="canWrite && readyRetryCandidatesCount > 0" class="proto-note">批量重试仅作用于当前店铺（{{ normalizedShopId }}）</span>
          </div>
          <el-alert v-if="issueError" type="error" :title="`异常队列加载失败：${issueError}`" :closable="false" class="crm-table-alert" />
          <YouzanSyncIssuePanel
            :issues="issues"
            :loading="issueLoading"
            :can-write="canWrite"
            :processing-id="retryingId"
            :mapped-sku-ids="activeMappedSkuIds"
            :batch-retrying="batchRetryingSku !== null"
            @retry="retryIssue"
            @map-sku="openSkuMappingWithPrefill"
          />
          <el-pagination v-model:current-page="issueQuery.page" v-model:page-size="issueQuery.size" :total="issueTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="loadIssues" @size-change="issueQuery.page = 1; loadIssues()" />
        </template>
      </el-card>
    </template>

    <YouzanOrderDetailDrawer v-model="detailVisible" :order="selectedOrder" />
    <el-dialog v-model="showPrerequisites" title="有赞接口前置条件" width="520px">
      <p v-if="hasActiveRealIntegration">当前已进入真实店铺接入：请确认使用有赞云无容器自用型应用凭证、系统出口 IP 已加入有赞 IP 白名单、公网 HTTPS 消息地址可正常返回 HTTP 200 {"code":0,"msg":"success"}，并按策略执行分页与增量同步。</p>
      <p v-else>正式接入需确认店铺类型、开放平台应用与授权、订单/退款接口、回调安全规则及分页限速。当前尚未启用已验证的真实店铺，真实接口默认保持禁用，完成授权与连接测试后可在全局开关及店铺配置中启用。</p>
      <p class="proto-note">有赞订单号必须唯一；重复推送不会重复发放权益，退款回调也会自动防重。</p>
    </el-dialog>
  </div>
</template>

<style scoped>
.shop-card { margin-bottom: 16px; }
.shop-input { width: min(300px, 100%); }
.summary-time { margin-left: 6px; color: #8298a4; font-size: 11px; font-weight: 400; }
.youzan-real-banner { margin-bottom: 18px; padding: 16px 20px; border: 1px solid #cce0db; border-radius: 14px; background: linear-gradient(105deg, #f1f9f7, #f8fcfb); }
.youzan-real-banner strong { display: block; margin-bottom: 6px; color: #173f3a; font-size: 14px; }
.youzan-real-banner p { margin: 0; color: #4c6d67; font-size: 12px; line-height: 1.75; }
.youzan-real-badge { display: inline-flex; margin-bottom: 8px; padding: 3px 8px; border-radius: 6px; background: #1e6b63; color: #fff; font-size: 11px; font-weight: 800; letter-spacing: .5px; }
.youzan-goods-cell { min-width: 0; }
.youzan-goods-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; }
.youzan-goods-title { color: #1f3c47; font-weight: 600; line-height: 1.5; overflow-wrap: anywhere; }
.youzan-goods-count { display: inline-flex; align-items: center; padding: 1px 6px; border: 1px solid #cce0db; border-radius: 999px; background: #eef7f5; color: #175c55; font-size: 10px; font-weight: 700; white-space: nowrap; }
.youzan-goods-sub { line-height: 1.5; overflow-wrap: anywhere; }
</style>
