<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { ReconciliationIssue, ReconciliationResponse, SettlementBatch, SettlementBatchDetail } from '../api/settlement'
import { getSettlementBatch, getSettlementReconciliation, listSettlementBatches } from '../api/settlement'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatusPill from './customer/StatusPill.vue'
import { formatDateTime } from './customer/shared'
import SettlementBatchCreateDrawer from './settlement/SettlementBatchCreateDrawer.vue'
import SettlementDetailDrawer from './settlement/SettlementDetailDrawer.vue'
import SettlementVerificationDrawer from './settlement/SettlementVerificationDrawer.vue'
import SettlementPaymentDrawer from './settlement/SettlementPaymentDrawer.vue'
import { fenToYuan } from '../utils/money'
import { amountStatusLabel, settlementAmount, settlementPeriodLabel, settlementStatusLabel, settlementStatusTone, settlementTypeLabel } from './finance/financeUi'
import { tradingErrorText } from './trading/tradingUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './institution/institution.css'
import './finance/finance.css'

const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('settlement:write') && auth.user?.userType !== 'INSTITUTION')
const activeTab = ref<'batches' | 'reconciliation'>('batches')
const batches = ref<SettlementBatch[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({ page: 1, size: 20, institutionId: undefined as number | undefined, status: '' })

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listSettlementBatches({ page: query.page, size: query.size, institutionId: query.institutionId, status: query.status || undefined })
    batches.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    batches.value = []
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
  query.institutionId = undefined
  query.status = ''
  query.page = 1
  void load()
}

function canVerify(row: SettlementBatch): boolean {
  return canWrite.value && row.amountStatus === 'PENDING_VERIFICATION' && row.status === 'DRAFT'
}

function canPay(row: SettlementBatch): boolean {
  return canWrite.value && row.amountStatus === 'VERIFIED' && ['CONFIRMED', 'PARTIALLY_PAID'].includes(row.status) && row.paidFen < row.netPayableFen
}

const createVisible = ref(false)
const detailVisible = ref(false)
const detail = ref<SettlementBatchDetail | null>(null)
const detailLoadingId = ref<number | null>(null)
async function openDetail(id: number) {
  detailLoadingId.value = id
  try {
    detail.value = await getSettlementBatch(id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

const verificationVisible = ref(false)
const verificationBatch = ref<SettlementBatch | null>(null)
function openVerification(row: SettlementBatch) {
  if (!canVerify(row)) return
  verificationBatch.value = row
  verificationVisible.value = true
}

const paymentVisible = ref(false)
const paymentBatch = ref<SettlementBatch | null>(null)
function openPayment(row: SettlementBatch) {
  if (!canPay(row)) return
  paymentBatch.value = row
  paymentVisible.value = true
}

function handleSaved(saved: SettlementBatchDetail) {
  detail.value = saved
  verificationBatch.value = saved.batch
  paymentBatch.value = saved.batch
  const index = batches.value.findIndex((item) => item.id === saved.batch.id)
  if (index >= 0) batches.value[index] = saved.batch
  void load()
}

const reconciliation = ref<ReconciliationResponse | null>(null)
const reconciliationLoading = ref(false)
const reconciliationError = ref('')
async function loadReconciliation() {
  reconciliationLoading.value = true
  reconciliationError.value = ''
  try {
    reconciliation.value = await getSettlementReconciliation({ institutionId: query.institutionId })
  } catch (error) {
    reconciliation.value = null
    reconciliationError.value = tradingErrorText(error)
  } finally {
    reconciliationLoading.value = false
  }
}

function changeTab(value: string | number) {
  activeTab.value = String(value) as 'batches' | 'reconciliation'
  if (activeTab.value === 'reconciliation') void loadReconciliation()
}

const reconciliationGroups = computed(() => {
  const issues = reconciliation.value?.issues ?? []
  return [
    { label: '全部差异', count: reconciliation.value?.issueCount ?? 0, types: [] },
    { label: '权益账本差异', count: issues.filter((item) => item.checkType === 'ENTITLEMENT_LEDGER').length, types: [] },
    { label: '现金 / 退款差异', count: issues.filter((item) => ['ORDER_PAYMENT_CASH', 'ORDER_REFUND_CASH', 'REFUND_ALLOCATION', 'SETTLEMENT_PAYMENT'].includes(item.checkType)).length, types: [] },
    { label: '未入批正式核销', count: issues.filter((item) => item.checkType === 'UNBATCHED_REDEMPTION').length, types: [] },
  ]
})

const CHECK_TYPE_LABELS: Record<string, string> = {
  ENTITLEMENT_LEDGER: '权益账本',
  ORDER_PAYMENT_CASH: '订单支付现金',
  ORDER_REFUND_CASH: '订单退款现金',
  REFUND_ALLOCATION: '退款现金分摊',
  SETTLEMENT_PAYMENT: '批次付款',
  UNBATCHED_REDEMPTION: '未入批核销',
}

function reconciliationValue(issue: ReconciliationIssue, expected: boolean): string {
  const fen = expected ? issue.expectedFen : issue.actualFen
  if (fen !== null) return `¥${fenToYuan(fen)}（${fen} 分）`
  const count = expected ? issue.expectedCount : issue.actualCount
  return count === null ? '-' : `${count} 次`
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page">
    <PageHeader title="机构结算与对账" description="月结与次卡次结按真实正式核销入批；人工核准金额、分次登记付款，对账只报告差异不自动修账">
      <template #actions><el-button v-if="canWrite" type="primary" data-testid="settlement-create" @click="createVisible = true">＋ 创建结算批次</el-button></template>
    </PageHeader>
    <PageCard>
      <el-tabs v-model="activeTab" @tab-change="changeTab">
        <el-tab-pane label="结算批次" name="batches" />
        <el-tab-pane label="风险对账" name="reconciliation" />
      </el-tabs>
      <template v-if="activeTab === 'batches'">
        <div class="proto-toolbar">
          <div class="proto-toolbar-left">
            <el-input-number v-model="query.institutionId" :min="1" :precision="0" :controls="false" placeholder="机构 ID" class="finance-filter" style="width:170px" @keyup.enter="search" />
            <el-select v-model="query.status" placeholder="全部批次状态" clearable class="finance-filter" style="width:170px"><el-option label="待核准" value="DRAFT" /><el-option label="已核准 · 待付款" value="CONFIRMED" /><el-option label="部分付款" value="PARTIALLY_PAID" /><el-option label="已完成付款" value="PAID" /></el-select>
            <el-button type="primary" :loading="loading" @click="search">查询</el-button><el-button @click="reset">重置</el-button>
          </div>
          <span class="proto-note">待核实不是 0 元，不可登记付款</span>
        </div>
        <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
        <div v-loading="loading" class="proto-table-wrap finance-table-desktop">
          <app-table :data="batches" row-key="id">
            <el-table-column label="批次 / 机构" min-width="160"><template #default="{ row }"><strong>批次 #{{ row.id }}</strong><div class="trading-cell-sub">机构 #{{ row.institutionId }} · {{ settlementTypeLabel(row.settlementType) }}</div></template></el-table-column>
            <el-table-column label="结算周期" min-width="150"><template #default="{ row }">{{ settlementPeriodLabel(row.settlementType, row.periodKey) }}</template></el-table-column>
            <el-table-column label="核销基数" width="110" align="right"><template #default="{ row }"><span class="finance-amount" :class="{ pending: row.amountStatus === 'PENDING_VERIFICATION' }">{{ settlementAmount(row.amountStatus, row.grossFen) }}</span></template></el-table-column>
            <el-table-column label="应结净额" width="110" align="right"><template #default="{ row }"><span class="finance-amount" :class="{ pending: row.amountStatus === 'PENDING_VERIFICATION' }">{{ settlementAmount(row.amountStatus, row.netPayableFen) }}</span></template></el-table-column>
            <el-table-column label="已付款" width="105" align="right"><template #default="{ row }">¥{{ fenToYuan(row.paidFen) }}</template></el-table-column>
            <el-table-column label="批次状态" width="140"><template #default="{ row }"><StatusPill :text="settlementStatusLabel(row.status)" :tone="settlementStatusTone(row.status)" /></template></el-table-column>
            <el-table-column label="金额状态" width="125"><template #default="{ row }">{{ amountStatusLabel(row.amountStatus) }}</template></el-table-column>
            <el-table-column label="操作" min-width="190" fixed="right"><template #default="{ row }"><el-button text size="small" :loading="detailLoadingId === row.id" data-testid="settlement-detail" @click="openDetail(row.id)">详情</el-button><el-button v-if="canVerify(row)" text size="small" type="primary" data-testid="settlement-verify" @click="openVerification(row)">核准金额</el-button><el-button v-if="canPay(row)" text size="small" type="primary" data-testid="settlement-pay" @click="openPayment(row)">登记付款</el-button></template></el-table-column>
            <template #empty><el-empty description="暂无结算批次" /></template>
          </app-table>
        </div>
        <div v-loading="loading" class="finance-mobile-list">
          <article v-for="row in batches" :key="row.id" class="finance-mobile-item">
            <div class="finance-mobile-head"><strong>批次 #{{ row.id }} · {{ settlementPeriodLabel(row.settlementType, row.periodKey) }}</strong><StatusPill :text="settlementStatusLabel(row.status)" :tone="settlementStatusTone(row.status)" /></div>
            <div class="finance-mobile-row"><span>机构 / 方式</span><strong>#{{ row.institutionId }} · {{ settlementTypeLabel(row.settlementType) }}</strong></div>
            <div class="finance-mobile-row"><span>应结净额</span><strong :class="{ 'finance-amount pending': row.amountStatus === 'PENDING_VERIFICATION' }">{{ settlementAmount(row.amountStatus, row.netPayableFen) }}</strong></div>
            <div class="finance-mobile-row"><span>已付款</span><strong>¥{{ fenToYuan(row.paidFen) }}</strong></div>
            <div class="finance-mobile-actions"><el-button text @click="openDetail(row.id)">查看详情</el-button><el-button v-if="canVerify(row)" text type="primary" @click="openVerification(row)">核准金额</el-button><el-button v-if="canPay(row)" text type="primary" @click="openPayment(row)">登记付款</el-button></div>
          </article>
          <el-empty v-if="!loading && batches.length === 0" description="暂无结算批次" />
        </div>
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()" />
      </template>
      <template v-else>
        <div class="proto-toolbar"><div class="proto-toolbar-left"><el-input-number v-model="query.institutionId" :min="1" :precision="0" :controls="false" placeholder="机构 ID（可选）" class="finance-filter" style="width:190px" /><el-button type="primary" :loading="reconciliationLoading" data-testid="reconciliation-refresh" @click="loadReconciliation">重新对账</el-button></div><span class="proto-note">只读检查，不自动修账或补流水</span></div>
        <el-alert v-if="reconciliationError" type="error" :title="reconciliationError" :closable="false" />
        <template v-else-if="reconciliation">
          <div class="finance-recon-grid"><div v-for="group in reconciliationGroups" :key="group.label" class="finance-recon-metric" :class="{ 'has-issue': group.count > 0 }"><small>{{ group.label }}</small><strong>{{ group.count }}</strong></div></div>
          <div class="finance-recon-time">生成时间：{{ formatDateTime(reconciliation.generatedAt) }} · 数据来自当前账本、现金流水与结算批次的只读检查</div>
          <el-alert v-if="reconciliation.issueCount === 0" type="success" :closable="false" title="本次检查未发现差异" style="margin:0 20px 16px" />
          <div v-else class="proto-table-wrap" data-testid="reconciliation-issues"><app-table :data="reconciliation.issues" size="small"><el-table-column label="检查类型" width="130"><template #default="{ row }">{{ CHECK_TYPE_LABELS[row.checkType] ?? '未知检查类型' }}</template></el-table-column><el-table-column label="资源 / 机构" min-width="140"><template #default="{ row }">资源 #{{ row.resourceId }}<div class="trading-cell-sub">{{ row.institutionId === null ? '内部账目' : `机构 #${row.institutionId}` }}</div></template></el-table-column><el-table-column prop="detail" label="真实差异" min-width="240" /><el-table-column label="账面期望" min-width="145"><template #default="{ row }">{{ reconciliationValue(row, true) }}</template></el-table-column><el-table-column label="流水实际" min-width="145"><template #default="{ row }">{{ reconciliationValue(row, false) }}</template></el-table-column></app-table></div>
        </template>
      </template>
    </PageCard>

    <SettlementBatchCreateDrawer v-if="canWrite" v-model="createVisible" @saved="handleSaved" />
    <SettlementDetailDrawer v-model="detailVisible" :detail="detail" :can-verify="detail ? canVerify(detail.batch) : false" :can-pay="detail ? canPay(detail.batch) : false" @verify="detail && openVerification(detail.batch)" @pay="detail && openPayment(detail.batch)" />
    <SettlementVerificationDrawer v-model="verificationVisible" :batch="verificationBatch" @saved="handleSaved" />
    <SettlementPaymentDrawer v-model="paymentVisible" :batch="paymentBatch" @saved="handleSaved" />
  </div>
</template>
