<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { YouzanCustomerLink, YouzanOrderView, YouzanReconcileReport, YouzanShopConfig, YouzanSkuMapping, YouzanSyncIssue } from '../api/youzan'
import {
  getYouzanReconcileReport,
  listYouzanCustomerLinks,
  listYouzanOrders,
  listYouzanShops,
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
import YouzanOrderDetailDrawer from './youzan/YouzanOrderDetailDrawer.vue'
import YouzanShopConfigPanel from './youzan/YouzanShopConfigPanel.vue'
import YouzanSkuMappingPanel from './youzan/YouzanSkuMappingPanel.vue'
import YouzanSyncIssuePanel from './youzan/YouzanSyncIssuePanel.vue'
import YouzanSyncJobPanel from './youzan/YouzanSyncJobPanel.vue'
import YouzanSyncPolicyPanel from './youzan/YouzanSyncPolicyPanel.vue'
import { createYouzanKey, isMappingSuccess, mappingStatusLabel, mappingStatusTone } from './youzan/youzanUi'
import { orderTypeLabel, tradingErrorText } from './trading/tradingUi'
import { orderTypeTone } from '../utils/tagTone'
import { fenToYuan } from '../utils/money'
import './customer/customer-ui.css'
import './trading/trading.css'
import './fulfillment/fulfillment.css'
import './youzan/youzan.css'

const auth = useAuthStore()
const canRead = computed(() => auth.hasPermission('order:read'))
const canWrite = computed(() => auth.hasPermission('campaign:write'))
const canManage = computed(() => auth.hasPermission('integration:youzan:manage'))
const canManageRuntime = computed(() => auth.hasPermission('integration:runtime:manage'))
const activeTab = ref('orders')
const showPrerequisites = ref(false)
const shopId = ref('MOCK-SHOP-001')
const customerNames = ref<Record<number, string>>({})
const pendingMatches = computed(() => orders.value.filter((row) => row.mappingStatus === 'CUSTOMER_UNRESOLVED').length)
const syncErrors = computed(() => orders.value.filter((row) => ['MAPPING_FAILED','PAYMENT_MISMATCH','RETRY','DEAD_LETTER'].includes(row.mappingStatus || '')).length)
function raw(row: YouzanOrderView): Record<string, unknown> {
  try { return row.payloadMaskedJson ? JSON.parse(row.payloadMaskedJson) as Record<string, unknown> : {} } catch { return {} }
}
function text(value: unknown) { return value == null || value === '' ? '—' : String(value) }
function goods(row: YouzanOrderView) {
  const items = raw(row).items
  return Array.isArray(items) ? items.map((item) => {
    const line = item as Record<string, unknown>
    return `${text(line.external_sku_id)} × ${text(line.quantity)}`
  }).join('、') : '—'
}
function linkedCustomer(row: YouzanOrderView) {
  const link = links.value.find((item) => item.externalCustomerId === raw(row).external_customer_id)
  return link ? customerNames.value[link.customerId] || `客户 #${link.customerId}` : '—'
}
function matchAndSync(row: YouzanOrderView) {
  activeTab.value = row.mappingStatus === 'CUSTOMER_UNRESOLVED' ? 'customers' : 'mappings'
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
    const result = await listYouzanOrders({ shopId: shopId.value, page: orderQuery.page, size: orderQuery.size })
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
    report.value = await getYouzanReconcileReport(shopId.value)
  } catch (error) {
    report.value = null
    reportError.value = tradingErrorText(error)
  }
}

async function loadMappings() {
  mappingsLoading.value = true
  try {
    mappings.value = await listYouzanSkuMappings(shopId.value)
  } catch (error) {
    mappings.value = []
    ElMessage.error(tradingErrorText(error))
  } finally {
    mappingsLoading.value = false
  }
}

async function loadLinks() {
  linksLoading.value = true
  try {
    links.value = await listYouzanCustomerLinks(shopId.value)
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
  shopId.value = id
  search()
}

function loadAll() {
  if (!canRead.value) return
  void Promise.allSettled([loadOrders(), loadIssues(), loadMappings(), loadLinks(), loadReport(), loadShops()])
}

function search() {
  orderQuery.page = 1
  issueQuery.page = 1
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
  if (order.orderId == null) {
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
let syncAttempt: { signature: string; key: string } | null = null

async function runSync(mode: 'sync' | 'reconcile') {
  if (syncing.value) return
  const cursor = mode === 'sync' ? syncCursor.value : ''
  const signature = `${shopId.value}:${mode}:${cursor}`
  if (syncAttempt?.signature !== signature) syncAttempt = { signature, key: createYouzanKey(`youzan.${mode}`) }
  const key = syncAttempt.key
  syncing.value = true
  try {
    const result = mode === 'sync'
      ? await syncYouzanOrders({ shopId: shopId.value, cursor: cursor || undefined, limit: 50 }, key)
      : await reconcileYouzanOrders({ shopId: shopId.value, cursor: cursor || undefined, limit: 50 }, key)
    syncAttempt = null
    syncCursor.value = result.nextCursor ?? ''
    await Promise.allSettled([loadOrders(), loadIssues(), loadReport()])
    if (result.issues > 0) {
      ElMessage.warning(`模拟同步返回 ${result.issues} 条异常，不能视为全部成功；已处理 ${result.processed} 条，重复 ${result.duplicates} 条`)
    } else {
      ElMessage.success(`模拟同步完成：接收 ${result.received} 条，处理 ${result.processed} 条，重复 ${result.duplicates} 条`)
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    syncing.value = false
  }
}

const retryingId = ref<number | null>(null)
let retryAttempt: { signature: string; key: string } | null = null

async function retryIssue(issue: YouzanSyncIssue) {
  const signature = `${issue.id}:${issue.version}`
  if (retryAttempt?.signature !== signature) retryAttempt = { signature, key: createYouzanKey('youzan.issue.retry') }
  const key = retryAttempt.key
  retryingId.value = issue.id
  try {
    const result = await retryYouzanSyncIssue(issue.id, key)
    retryAttempt = null
    await Promise.allSettled([loadOrders(), loadIssues(), loadReport()])
    if (isMappingSuccess(result.processStatus)) {
      ElMessage.success('补偿处理已完成，未重复创建内部订单')
    } else {
      ElMessage.warning(result.lastError || '补偿重试已返回，但仍需处理映射或支付异常')
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    retryingId.value = null
  }
}

onMounted(() => {
  loadAll()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="有赞订单同步中心" description="线上订单自动进入 CRM：去重 → 匹配客户 → 识别套餐 → 保留活动归属 → 生成订单/权益">
      <template #actions><el-button type="info" plain @click="showPrerequisites = true">接口前置条件</el-button><el-button v-if="canManage" text data-testid="youzan-sync" @click="runSync('sync')">手动单页同步</el-button><el-button v-if="canManage" type="primary" :loading="syncing" data-testid="youzan-reconcile" @click="runSync('reconcile')">立即拉取订单</el-button></template>
    </PageHeader>
    <div class="youzan-mock-banner" data-testid="youzan-mock-banner"><span class="youzan-mock-badge">模拟</span><strong>真实接入功能已预建 · 默认禁用 · Q11 待授权联调</strong><p>模拟回调不代表真实资金到账；真实店铺须完成授权、沙箱验收后另行启用。</p></div>
    <el-alert v-if="!canRead" type="error" title="缺少 order:read 权限，无法查看有赞同步数据" :closable="false" />
    <template v-else>
      <IntegrationRuntimeSettings v-if="canManageRuntime" provider="youzan" />
      <div class="proto-stat-grid"><StatCard label="今日拉取" :value="`${report?.todayEvents ?? 0} 单`" sub="按实际接收时间" icon="bag" /><StatCard label="待匹配" :value="`${pendingMatches} 单`" sub="需要确认 CRM 客户" icon="users" /><StatCard label="同步异常" :value="`${syncErrors} 单`" sub="商品映射或支付核验异常" icon="shield" /><StatCard label="活动订单" :value="`${report?.campaignOrders ?? 0} 单`" sub="保留活动与批次归属" icon="activity" /></div>
      <div class="proto-toolbar shop-card"><div class="proto-toolbar-left"><el-input v-model="shopId" placeholder="模拟店铺 ID" data-testid="youzan-shop-id" @keyup.enter="search" /><el-button @click="search">查询</el-button></div><span class="proto-note">默认店铺来自后端模拟适配器；其他店铺不会伪造同步结果</span></div>
      <el-alert v-if="reportError" type="error" :title="reportError" :closable="false" />

      <el-card shadow="never">
        <el-tabs v-model="activeTab" class="fulfillment-status-tabs">
          <el-tab-pane label="订单与原始状态" name="orders" />
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
              <el-table-column label="客户昵称 / 手机" min-width="135"><template #default="{row}">{{ text(raw(row).nickname) }}<div class="proto-subcell">{{ text(raw(row).mobile) }}</div></template></el-table-column>
              <el-table-column label="商品" min-width="155"><template #default="{row}">{{ goods(row) }}</template></el-table-column>
              <el-table-column label="实付" width="95" align="right"><template #default="{row}">{{ fenToYuan(row.externalAmountFen) }}</template></el-table-column>
              <el-table-column label="订单类型" width="110"><template #default="{row}"><span class="proto-pill" :class="orderTypeTone(text(raw(row).order_type_code))">{{ orderTypeLabel(text(raw(row).order_type_code)) }}</span></template></el-table-column>
              <el-table-column label="活动 / 批次" min-width="110"><template #default="{row}">活动 #{{ text(raw(row).campaign_id) }}<div class="proto-subcell">批次 #{{ text(raw(row).campaign_batch_id) }}</div></template></el-table-column>
              <el-table-column label="支付时间" min-width="150"><template #default="{row}">{{ raw(row).paid_at ? formatDateTime(String(raw(row).paid_at)) : '未支付' }}</template></el-table-column>
              <el-table-column label="CRM匹配" min-width="105"><template #default="{row}">{{ linkedCustomer(row) }}</template></el-table-column>
              <el-table-column label="状态" width="120"><template #default="{row}"><StatusPill :text="mappingStatusLabel(row.mappingStatus)" :tone="mappingStatusTone(row.mappingStatus)" /></template></el-table-column>
              <el-table-column label="操作" width="190" fixed="right"><template #default="{row}"><el-button v-if="row.mappingStatus !== 'PROCESSED'" size="small" type="primary" @click="matchAndSync(row)">匹配并同步</el-button><span v-else class="proto-muted">已入队</span><el-button text data-testid="youzan-order-detail" @click="openOrder(row)">脱敏详情</el-button></template></el-table-column>
              <template #empty>暂无原始订单事件</template>
            </app-table>
          </div>
          <el-pagination v-model:current-page="orderQuery.page" v-model:page-size="orderQuery.size" :total="orderTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="loadOrders" @size-change="orderQuery.page = 1; loadOrders()" />
        </template>

        <template v-else-if="activeTab === 'shops'">
          <YouzanShopConfigPanel :shops="shops" :loading="shopsLoading" :can-write="canManage" @saved="loadShops" @select="selectShop" />
        </template>

        <template v-else-if="activeTab === 'policy'">
          <YouzanSyncPolicyPanel :shop-id="shopId" :can-write="canManage" />
        </template>

        <template v-else-if="activeTab === 'jobs'">
          <YouzanSyncJobPanel :shop-id="shopId" />
        </template>

        <template v-else-if="activeTab === 'mappings'">
          <YouzanSkuMappingPanel :mappings="mappings" :shop-id="shopId" :can-write="canWrite" :loading="mappingsLoading" @saved="loadMappings" />
        </template>

        <template v-else-if="activeTab === 'customers'">
          <YouzanCustomerLinkPanel :links="links" :shop-id="shopId" :can-write="canWrite" :loading="linksLoading" @saved="loadLinks" />
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
          </div>
          <el-alert v-if="issueError" type="error" :title="`异常队列加载失败：${issueError}`" :closable="false" class="crm-table-alert" />
          <YouzanSyncIssuePanel :issues="issues" :loading="issueLoading" :can-write="canWrite" :processing-id="retryingId" @retry="retryIssue" />
          <el-pagination v-model:current-page="issueQuery.page" v-model:page-size="issueQuery.size" :total="issueTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="loadIssues" @size-change="issueQuery.page = 1; loadIssues()" />
        </template>
      </el-card>
    </template>

    <YouzanOrderDetailDrawer v-model="detailVisible" :order="selectedOrder" />
    <el-dialog v-model="showPrerequisites" title="有赞接口前置条件" width="520px"><p>正式接入需确认店铺类型、开放平台应用与授权、订单/退款接口、回调安全规则及分页限速。当前 Q11 未确认，真实 API 保持禁用。</p><p class="proto-note">有赞订单号必须唯一；重复推送不能重复发权益，退款回调也必须幂等。</p></el-dialog>
  </div>
</template>

<style scoped>
.shop-card { margin-bottom: 16px; }
.shop-input { width: min(300px, 100%); }
.summary-time { margin-left: 6px; color: #8298a4; font-size: 11px; font-weight: 400; }
</style>
