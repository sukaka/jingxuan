<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerListItem } from '../api/customer'
import { listCustomers } from '../api/customer'
import type { Sku } from '../api/catalog'
import { listSkus } from '../api/catalog'
import type { CrmOrder, OrderFulfillmentView } from '../api/trading'
import {
  cancelOrder,
  getOrder,
  listOrders,
  submitOrder,
} from '../api/trading'
import { useAuthStore } from '../stores/auth'
import OrderCreateDrawer from './trading/OrderCreateDrawer.vue'
import OrderDetailDrawer from './trading/OrderDetailDrawer.vue'
import PaymentConfirmDrawer from './trading/PaymentConfirmDrawer.vue'
import OrderPrototypeTable from './trading/OrderPrototypeTable.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { tradingErrorText } from './trading/tradingUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './finance/finance.css'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('order:write'))
const canConfirmPayment = computed(() => auth.hasPermission('order:confirm_payment'))
const canViewRefunds = computed(() => auth.hasPermission('refund:read'))
const canRequestRefund = computed(() => auth.hasPermission('refund:read') && auth.hasPermission('refund:write') && auth.user?.userType !== 'INSTITUTION')

const orders = ref<CrmOrder[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({
  page: 1,
  size: 20,
  customerId: undefined as number | undefined,
  status: '',
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listOrders({
      page: query.page,
      size: query.size,
      customerId: query.customerId,
      status: query.status || undefined,
    })
    orders.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    orders.value = []
    total.value = 0
    errorMessage.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void load()
}

function reset() {
  query.customerId = undefined
  query.status = ''
  query.page = 1
  void load()
}

const createVisible = ref(false)
const customers = ref<CustomerListItem[]>([])
const skus = ref<Sku[]>([])
const customersLoading = ref(false)
const skusLoading = ref(false)
const customerHasMore = ref(false)
const skuHasMore = ref(false)
const customerLoadError = ref('')
const skuLoadError = ref('')
const OPTION_PAGE_SIZE = 20
let customerSearchSequence = 0
let skuSearchSequence = 0

async function searchCustomers(keyword: string) {
  if (!canWrite.value) return
  const sequence = ++customerSearchSequence
  customersLoading.value = true
  customerLoadError.value = ''
  try {
    const result = await listCustomers({ page: 1, size: OPTION_PAGE_SIZE, keyword: keyword.trim() || undefined })
    if (sequence !== customerSearchSequence) return
    customers.value = result.items
    customerHasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === customerSearchSequence) customerLoadError.value = tradingErrorText(error)
  } finally {
    if (sequence === customerSearchSequence) customersLoading.value = false
  }
}

async function searchSkus(keyword: string) {
  if (!canWrite.value) return
  const sequence = ++skuSearchSequence
  skusLoading.value = true
  skuLoadError.value = ''
  try {
    const result = await listSkus({ page: 1, size: OPTION_PAGE_SIZE, keyword: keyword.trim() || undefined, status: 'ACTIVE' })
    if (sequence !== skuSearchSequence) return
    skus.value = result.items
    skuHasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === skuSearchSequence) skuLoadError.value = tradingErrorText(error)
  } finally {
    if (sequence === skuSearchSequence) skusLoading.value = false
  }
}

function openCreate() {
  if (!canWrite.value) return
  customers.value = []
  skus.value = []
  customerHasMore.value = false
  skuHasMore.value = false
  customerLoadError.value = ''
  skuLoadError.value = ''
  createVisible.value = true
  void searchCustomers('')
  void searchSkus('')
}

const detailVisible = ref(false)
const detail = ref<OrderFulfillmentView | null>(null)
const detailLoadingId = ref<number | null>(null)

async function openDetail(id: number) {
  detailLoadingId.value = id
  try {
    detail.value = await getOrder(id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

function applyView(view: OrderFulfillmentView) {
  const savedOrder = view.order.order
  const index = orders.value.findIndex((item) => item.id === savedOrder.id)
  if (index >= 0) orders.value[index] = savedOrder
  if (detail.value?.order.order.id === savedOrder.id) detail.value = view
}

function handleCreated(view: OrderFulfillmentView) {
  detail.value = view
  detailVisible.value = true
  void load()
}

const actingOrderId = ref<number | null>(null)

async function submit(order: CrmOrder) {
  try {
    await ElMessageBox.confirm(
      `订单 ${order.orderNo} 将进入待支付状态。提交后仍需授权人员核验真实支付，当前不会发放权益。`,
      '提交待支付',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '返回' },
    )
  } catch {
    return
  }

  actingOrderId.value = order.id
  try {
    const saved = await submitOrder(order.id, order.version, '人工提交待支付')
    applyView(saved)
    await load()
    ElMessage.success('订单已提交待支付，尚未发放权益')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    actingOrderId.value = null
  }
}

async function cancel(order: CrmOrder) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `取消未支付订单 ${order.orderNo} 不会删除历史，也不会产生权益。请填写取消原因。`,
      '取消订单',
      {
        type: 'warning',
        confirmButtonText: '确认取消订单',
        cancelButtonText: '返回',
        inputType: 'textarea',
        inputPlaceholder: '取消原因将写入订单状态历史与审计',
        inputValidator: (value) => {
          const trimmed = value.trim()
          if (!trimmed) return '请填写取消原因'
          return trimmed.length <= 500 || '取消原因不能超过 500 字'
        },
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }

  actingOrderId.value = order.id
  try {
    const saved = await cancelOrder(order.id, order.version, reason)
    applyView(saved)
    await load()
    ElMessage.success('订单已取消，历史记录仍保留')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    actingOrderId.value = null
  }
}

const paymentVisible = ref(false)
const paymentOrder = ref<CrmOrder | null>(null)

function openPayment(order: CrmOrder) {
  paymentOrder.value = order
  paymentVisible.value = true
}

function handlePaymentSaved(view: OrderFulfillmentView) {
  applyView(view)
  detail.value = view
  detailVisible.value = true
  void load()
}

function submitFromDetail() {
  const order = detail.value?.order.order
  if (order) void submit(order)
}

function cancelFromDetail() {
  const order = detail.value?.order.order
  if (order) void cancel(order)
}

function confirmFromDetail() {
  const order = detail.value?.order.order
  if (order) openPayment(order)
}

function requestRefundFromDetail() {
  const order = detail.value?.order.order
  if (!order || !canRequestRefund.value) return
  detailVisible.value = false
  void router.push({ path: '/refunds', query: { create: '1', orderId: String(order.id) } })
}

onMounted(() => {
  void load()
  if (route?.query?.create === '1') openCreate()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="订单售卖" description="已支付订单禁止覆盖交易事实；草稿、支付与活动归属按真实业务状态处理">
      <template #actions><RouterLink to="/integrations/youzan" class="el-button">有赞同步</RouterLink><el-button v-if="canWrite" type="primary" data-testid="order-create" @click="openCreate">＋ 新建订单</el-button></template>
    </PageHeader>
    <PageCard>
      <div class="proto-toolbar"><div class="proto-toolbar-left"><el-input v-model="query.customerId" placeholder="客户 ID" clearable style="width:180px" @keyup.enter="search" /><el-select v-model="query.status" placeholder="全部订单状态" clearable style="width:160px"><el-option label="草稿" value="DRAFT" /><el-option label="待支付" value="PENDING_PAYMENT" /><el-option label="已支付" value="PAID" /><el-option label="部分履约" value="PARTIALLY_REDEEMED" /><el-option label="已完成" value="FULFILLED" /><el-option label="已取消" value="CANCELED" /></el-select><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div><span class="proto-note">已支付订单金额、项目和次数不可覆盖</span></div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
      <div class="proto-table-wrap"><OrderPrototypeTable :orders="orders" :can-write="canWrite" :can-confirm-payment="canConfirmPayment" @detail="openDetail" @submit="submit" @cancel="cancel" @payment="openPayment" /></div>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()" />
    </PageCard>
    <OrderCreateDrawer v-if="canWrite" v-model="createVisible" :customers="customers" :skus="skus" :customers-loading="customersLoading" :skus-loading="skusLoading" :customer-has-more="customerHasMore" :sku-has-more="skuHasMore" :customer-load-error="customerLoadError" :sku-load-error="skuLoadError" @saved="handleCreated" @search-customers="searchCustomers" @search-skus="searchSkus" />
    <OrderDetailDrawer v-model="detailVisible" :view="detail" :can-write="canWrite" :can-confirm-payment="canConfirmPayment" :can-request-refund="canRequestRefund" :can-view-refunds="canViewRefunds" @submit="submitFromDetail" @cancel="cancelFromDetail" @confirm="confirmFromDetail" @request-refund="requestRefundFromDetail" />
    <PaymentConfirmDrawer v-if="canConfirmPayment" v-model="paymentVisible" :order="paymentOrder" @saved="handlePaymentSaved" />
  </div>
</template>

<style scoped>
.customer-filter { width: 170px; }
</style>
