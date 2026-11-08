<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { Refund, RefundDetail } from '../api/refund'
import { getRefund, listRefunds } from '../api/refund'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatusPill from './customer/StatusPill.vue'
import { formatDateTime } from './customer/shared'
import RefundCreateDrawer from './refund/RefundCreateDrawer.vue'
import RefundDetailDrawer from './refund/RefundDetailDrawer.vue'
import RefundReviewDrawer from './refund/RefundReviewDrawer.vue'
import RefundExecutionDrawer from './refund/RefundExecutionDrawer.vue'
import { fenToYuan } from '../utils/money'
import { refundStatusTone } from './finance/financeUi'
import { refundStatusLabel } from './refund/refundUi'
import { tradingErrorText } from './trading/tradingUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './institution/institution.css'
import './finance/finance.css'

const route = useRoute()
const auth = useAuthStore()
const isInstitution = computed(() => auth.user?.userType === 'INSTITUTION')
const canCreate = computed(() => auth.hasPermission('refund:write') && !isInstitution.value)
const canApprove = computed(() => auth.hasPermission('refund:approve') && !isInstitution.value)
const canExecutePermission = computed(() => auth.hasPermission('refund:execute') && !isInstitution.value)

const refunds = ref<Refund[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({ page: 1, size: 20, orderId: undefined as number | undefined, customerId: undefined as number | undefined, status: '' })

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listRefunds({ page: query.page, size: query.size, orderId: query.orderId, customerId: query.customerId, status: query.status || undefined })
    refunds.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    refunds.value = []
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
  query.orderId = undefined
  query.customerId = undefined
  query.status = ''
  query.page = 1
  void load()
}

function canReview(row: Refund): boolean {
  return canApprove.value && row.status === 'REQUESTED' && row.requestedBy !== auth.user?.userId
}

function canExecute(row: Refund): boolean {
  return canExecutePermission.value && ['APPROVED', 'FAILED'].includes(row.status)
}

function canConfirmSuccess(row: Refund): boolean {
  return canExecutePermission.value && row.status === 'PROCESSING'
}

const createVisible = ref(false)
const createOrderId = ref<number | undefined>(undefined)
function openCreate(orderId?: number) {
  if (!canCreate.value) return
  createOrderId.value = orderId
  createVisible.value = true
}

const detailVisible = ref(false)
const detail = ref<RefundDetail | null>(null)
const detailLoadingId = ref<number | null>(null)
async function openDetail(id: number) {
  detailLoadingId.value = id
  try {
    detail.value = await getRefund(id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

const reviewVisible = ref(false)
const reviewRefundRow = ref<Refund | null>(null)
function openReview(row: Refund) {
  if (!canReview(row)) return
  reviewRefundRow.value = row
  reviewVisible.value = true
}

const executionVisible = ref(false)
const executionMode = ref<'execute' | 'success'>('execute')
const executionDetail = ref<RefundDetail | null>(null)
async function openExecution(row: Refund, mode: 'execute' | 'success') {
  try {
    executionDetail.value = await getRefund(row.id)
    executionMode.value = mode
    executionVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  }
}

function handleSaved(saved: RefundDetail) {
  detail.value = saved
  executionDetail.value = saved
  const index = refunds.value.findIndex((item) => item.id === saved.refund.id)
  if (index >= 0) refunds.value[index] = saved.refund
  void load()
}

function reviewFromDetail() {
  if (detail.value) openReview(detail.value.refund)
}

function executeFromDetail() {
  if (detail.value) {
    executionDetail.value = detail.value
    executionMode.value = 'execute'
    executionVisible.value = true
  }
}

function successFromDetail() {
  if (detail.value) {
    executionDetail.value = detail.value
    executionMode.value = 'success'
    executionVisible.value = true
  }
}

onMounted(() => {
  const rawOrderId = Array.isArray(route.query.orderId) ? route.query.orderId[0] : route.query.orderId
  const orderId = rawOrderId ? Number(rawOrderId) : undefined
  const validOrderId = orderId && Number.isSafeInteger(orderId) && orderId > 0 ? orderId : undefined
  query.orderId = validOrderId
  void load()
  if (route.query.create === '1') openCreate(validOrderId)
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="退款管理" description="申请、异人审批、通道执行与真实成功分开处理；只有成功回执才更新现金与权益">
      <template #actions><el-button v-if="canCreate" type="primary" data-testid="refund-create" @click="openCreate()">＋ 新建退款申请</el-button></template>
    </PageHeader>
    <PageCard>
      <div class="proto-toolbar">
        <div class="proto-toolbar-left">
          <el-input-number v-model="query.orderId" :min="1" :precision="0" :controls="false" placeholder="订单 ID" class="finance-filter" style="width:150px" @keyup.enter="search" />
          <el-input-number v-model="query.customerId" :min="1" :precision="0" :controls="false" placeholder="客户 ID" class="finance-filter" style="width:150px" @keyup.enter="search" />
          <el-select v-model="query.status" placeholder="全部退款状态" clearable class="finance-filter" style="width:170px" data-testid="refund-status-filter">
            <el-option label="待审批" value="REQUESTED" /><el-option label="已批准 · 待执行" value="APPROVED" /><el-option label="已驳回" value="REJECTED" /><el-option label="退款执行中" value="PROCESSING" /><el-option label="退款成功" value="SUCCEEDED" /><el-option label="退款失败" value="FAILED" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button><el-button @click="reset">重置</el-button>
        </div>
        <span class="proto-note">审批或执行中不代表已退款</span>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
      <div v-loading="loading" class="proto-table-wrap finance-table-desktop">
        <app-table :data="refunds" row-key="id">
          <el-table-column label="退款单 / 订单" min-width="185"><template #default="{ row }"><strong>{{ row.refundNo }}</strong><div class="trading-cell-sub">订单 #{{ row.orderId }} · 客户 #{{ row.customerId }}</div></template></el-table-column>
          <el-table-column label="申请金额" width="115" align="right"><template #default="{ row }"><span class="finance-amount">¥{{ fenToYuan(row.requestedFen) }}</span></template></el-table-column>
          <el-table-column label="批准金额" width="115" align="right"><template #default="{ row }">{{ row.approvedFen > 0 ? `¥${fenToYuan(row.approvedFen)}` : '-' }}</template></el-table-column>
          <el-table-column label="真实成功" width="115" align="right"><template #default="{ row }"><span class="finance-amount">¥{{ fenToYuan(row.succeededFen) }}</span></template></el-table-column>
          <el-table-column label="状态" width="140"><template #default="{ row }"><StatusPill :text="refundStatusLabel(row.status)" :tone="refundStatusTone(row.status)" /></template></el-table-column>
          <el-table-column label="申请人 / 时间" min-width="155"><template #default="{ row }">用户 #{{ row.requestedBy }}<div class="trading-cell-sub">{{ formatDateTime(row.createdAt) }}</div></template></el-table-column>
          <el-table-column label="操作" min-width="240" fixed="right">
            <template #default="{ row }">
              <el-button text size="small" :loading="detailLoadingId === row.id" data-testid="refund-detail" @click="openDetail(row.id)">详情</el-button>
              <el-button v-if="canReview(row)" text size="small" type="primary" data-testid="refund-review" @click="openReview(row)">审批</el-button>
              <el-button v-if="canExecute(row)" text size="small" type="primary" data-testid="refund-execute" @click="openExecution(row, 'execute')">登记执行</el-button>
              <el-button v-if="canConfirmSuccess(row)" text size="small" type="primary" data-testid="refund-success" @click="openExecution(row, 'success')">复核成功</el-button>
              <span v-if="row.status === 'REQUESTED' && row.requestedBy === auth.user?.userId && canApprove" class="crm-muted">需异人审批</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无退款记录" /></template>
        </app-table>
      </div>
      <div v-loading="loading" class="finance-mobile-list">
        <article v-for="row in refunds" :key="row.id" class="finance-mobile-item">
          <div class="finance-mobile-head"><strong>{{ row.refundNo }}</strong><StatusPill :text="refundStatusLabel(row.status)" :tone="refundStatusTone(row.status)" /></div>
          <div class="finance-mobile-row"><span>订单 / 客户</span><strong>#{{ row.orderId }} / #{{ row.customerId }}</strong></div>
          <div class="finance-mobile-row"><span>申请金额</span><strong>¥{{ fenToYuan(row.requestedFen) }}</strong></div>
          <div class="finance-mobile-row"><span>真实成功</span><strong>¥{{ fenToYuan(row.succeededFen) }}</strong></div>
          <div class="finance-mobile-actions"><el-button text @click="openDetail(row.id)">查看详情</el-button><el-button v-if="canReview(row)" text type="primary" @click="openReview(row)">审批</el-button><el-button v-if="canExecute(row)" text type="primary" @click="openExecution(row, 'execute')">登记执行</el-button><el-button v-if="canConfirmSuccess(row)" text type="primary" @click="openExecution(row, 'success')">复核成功</el-button></div>
        </article>
        <el-empty v-if="!loading && refunds.length === 0" description="暂无退款记录" />
      </div>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()" />
    </PageCard>

    <RefundCreateDrawer v-if="canCreate" v-model="createVisible" :initial-order-id="createOrderId" @saved="handleSaved" />
    <RefundDetailDrawer v-model="detailVisible" :detail="detail" :can-review="detail ? canReview(detail.refund) : false" :can-execute="detail ? canExecute(detail.refund) : false" :can-confirm-success="detail ? canConfirmSuccess(detail.refund) : false" @review="reviewFromDetail" @execute="executeFromDetail" @success="successFromDetail" />
    <RefundReviewDrawer v-model="reviewVisible" :refund="reviewRefundRow" @saved="handleSaved" />
    <RefundExecutionDrawer v-model="executionVisible" :mode="executionMode" :detail="executionDetail" @saved="handleSaved" />
  </div>
</template>
