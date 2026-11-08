<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { EntitlementCorrectionView, Redemption, RedemptionDetail, RedemptionReversal, ReversalDetail } from '../api/fulfillment'
import {
  approveRedemptionReversal,
  getRedemption,
  getRedemptionReversal,
  listRedemptionReversals,
  listRedemptions,
  rejectRedemptionReversal,
} from '../api/fulfillment'
import { useAuthStore } from '../stores/auth'
import { useReferenceNames } from './references'
import { formatDateTime } from './customer/shared'
import StatusPill from './customer/StatusPill.vue'
import RedemptionDetailDrawer from './fulfillment/RedemptionDetailDrawer.vue'
import ReversalDetailDrawer from './fulfillment/ReversalDetailDrawer.vue'
import ReversalRequestDialog from './fulfillment/ReversalRequestDialog.vue'
import RedemptionPrototypeTable from './fulfillment/RedemptionPrototypeTable.vue'
import RedemptionStats from './fulfillment/RedemptionStats.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import {
  createFulfillmentIdempotencyKey,
  isReversalPending,
  reversalEffectLabel,
  reversalStatusLabel,
  reversalStatusTone,
} from './fulfillment/fulfillmentUi'
import { tradingErrorText } from './trading/tradingUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './fulfillment/fulfillment.css'

const auth = useAuthStore()
const { institutionLabel } = useReferenceNames()
const canReverse = computed(() => auth.hasPermission('redemption:reverse'))
const canApprove = computed(() => auth.hasPermission('redemption:approve'))
const activeTab = ref('records')
const institutionSummaries = computed(() => Object.values(redemptions.value.reduce((groups, row) => {
  const current = groups[row.institutionId] ?? { institutionId: row.institutionId, records: 0, pending: 0, latest: null as string | null }
  current.records++
  if (row.status !== 'POSTED') current.pending++
  if (!current.latest || (row.serviceAt && row.serviceAt > current.latest)) current.latest = row.serviceAt
  groups[row.institutionId] = current
  return groups
}, {} as Record<number, { institutionId: number; records: number; pending: number; latest: string | null }>)))

const redemptions = ref<Redemption[]>([])
const redemptionTotal = ref(0)
const redemptionLoading = ref(false)
const redemptionError = ref('')
const redemptionQuery = reactive({
  page: 1,
  size: 20,
  institutionId: undefined as number | undefined,
  customerId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  status: 'POSTED',
})

async function loadRedemptions() {
  redemptionLoading.value = true
  redemptionError.value = ''
  try {
    const result = await listRedemptions({
      page: redemptionQuery.page,
      size: redemptionQuery.size,
      institutionId: redemptionQuery.institutionId,
      customerId: redemptionQuery.customerId,
      projectId: redemptionQuery.projectId,
      status: redemptionQuery.status || undefined,
    })
    redemptions.value = result.items
    redemptionTotal.value = result.total
    redemptionQuery.page = result.page
    redemptionQuery.size = result.size
  } catch (error) {
    redemptions.value = []
    redemptionTotal.value = 0
    redemptionError.value = tradingErrorText(error)
  } finally {
    redemptionLoading.value = false
  }
}

function searchRedemptions() {
  redemptionQuery.page = 1
  void loadRedemptions()
}

function resetRedemptions() {
  redemptionQuery.institutionId = undefined
  redemptionQuery.customerId = undefined
  redemptionQuery.projectId = undefined
  redemptionQuery.status = 'POSTED'
  redemptionQuery.page = 1
  void loadRedemptions()
}

const reversals = ref<RedemptionReversal[]>([])
const reversalTotal = ref(0)
const reversalLoading = ref(false)
const reversalError = ref('')
const reversalQuery = reactive({ page: 1, size: 20, status: '' })
const correctionDetails = ref<Record<number, EntitlementCorrectionView[]>>({})

function correctionsFor(reversal: RedemptionReversal): EntitlementCorrectionView[] {
  return correctionDetails.value[reversal.id] ?? []
}

async function loadReversals() {
  reversalLoading.value = true
  reversalError.value = ''
  try {
    const result = await listRedemptionReversals({
      page: reversalQuery.page,
      size: reversalQuery.size,
      status: reversalQuery.status || undefined,
    })
    reversals.value = result.items
    reversalTotal.value = result.total
    reversalQuery.page = result.page
    reversalQuery.size = result.size
  } catch (error) {
    reversals.value = []
    reversalTotal.value = 0
    reversalError.value = tradingErrorText(error)
  } finally {
    reversalLoading.value = false
  }
}

function changeTab(value: string | number) {
  activeTab.value = String(value)
  if (activeTab.value === 'reversals') void loadReversals()
}

const detailVisible = ref(false)
const detail = ref<RedemptionDetail | null>(null)
const detailLoadingId = ref<number | null>(null)

async function openDetail(id: number) {
  detailLoadingId.value = id
  try {
    detail.value = await getRedemption(id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

const reversalVisible = ref(false)
const reversalSource = ref<Redemption | null>(null)
const reversalLoadingId = ref<number | null>(null)

async function openReversal(redemption: Redemption) {
  reversalLoadingId.value = redemption.id
  try {
    const fullDetail = await getRedemption(redemption.id)
    reversalSource.value = fullDetail.redemption
    reversalVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    reversalLoadingId.value = null
  }
}

function requestFromDetail() {
  if (!detail.value) return
  reversalSource.value = detail.value.redemption
  reversalVisible.value = true
}

function handleReversalRequested(saved: ReversalDetail) {
  correctionDetails.value[saved.reversal.id] = saved.corrections ?? []
  activeTab.value = 'reversals'
  void loadReversals()
}

const reversalDetailVisible = ref(false)
const reversalDetail = ref<ReversalDetail | null>(null)
const reversalDetailLoadingId = ref<number | null>(null)

async function openReversalDetail(id: number) {
  reversalDetailLoadingId.value = id
  try {
    const saved = await getRedemptionReversal(id)
    correctionDetails.value[id] = saved.corrections ?? []
    reversalDetail.value = saved
    reversalDetailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    reversalDetailLoadingId.value = null
  }
}

function canReview(reversal: RedemptionReversal): boolean {
  return canApprove.value && auth.user?.userType !== 'INSTITUTION' && isReversalPending(reversal) && reversal.requestedBy !== auth.user?.userId
}

let reviewAttempt: { signature: string; key: string } | null = null

async function review(reversal: RedemptionReversal, action: 'approve' | 'reject') {
  if (!canReview(reversal)) {
    ElMessage.warning('当前账号不能审核该申请，或申请已被处理')
    return
  }
  let reason: string | undefined
  if (action === 'approve') {
    try {
      await ElMessageBox.confirm(
        `批准冲正申请 #${reversal.id} 后，将新增补偿流水；原核销 #${reversal.redemptionId} 的机构已确认事实仍保留。确认审核？`,
        '审核冲正申请',
        { type: 'warning', confirmButtonText: '确认批准', cancelButtonText: '返回' },
      )
    } catch {
      return
    }
    reason = '人工审核通过，按补偿流水处理'
  } else {
    try {
      const result = await ElMessageBox.prompt('请填写驳回原因，申请与审核历史均保留。', '驳回冲正申请', {
        type: 'warning',
        confirmButtonText: '确认驳回',
        cancelButtonText: '返回',
        inputType: 'textarea',
        inputValidator: (value) => {
          const trimmed = value.trim()
          if (!trimmed) return '请填写驳回原因'
          return trimmed.length <= 1000 || '驳回原因不能超过 1000 字'
        },
      })
      reason = result.value.trim()
    } catch {
      return
    }
  }

  const signature = `${reversal.id}:${action}:${reversal.version}:${reason ?? ''}`
  if (reviewAttempt?.signature !== signature) {
    reviewAttempt = { signature, key: createFulfillmentIdempotencyKey('redemption.reversal.review') }
  }
  const key = reviewAttempt.key
  try {
    const saved = action === 'approve'
      ? await approveRedemptionReversal(reversal.id, { version: reversal.version, reason }, key)
      : await rejectRedemptionReversal(reversal.id, { version: reversal.version, reason }, key)
    correctionDetails.value[saved.reversal.id] = saved.corrections ?? []
    const index = reversals.value.findIndex((item) => item.id === saved.reversal.id)
    if (index >= 0) reversals.value[index] = saved.reversal
    reviewAttempt = null
    await loadReversals()
    if (action === 'approve') {
      const corrections = saved.corrections?.filter((entry) => entry.txnType === 'CORRECTION_IN') ?? []
      if (corrections.length > 0) {
        ElMessage.success(reversalEffectLabel(saved.reversal, corrections))
      } else {
        ElMessage.warning('审核通过，但响应未包含补偿流水，请核对后端处理结果')
      }
    } else {
      ElMessage.success('冲正申请已驳回，未新增补偿流水')
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  }
}

onMounted(() => {
  void loadRedemptions()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="核销记录" description="按真实机构、项目、次数与机构确认状态展示；正式核销记录只读，异常通过冲正处理">
      <template #actions><el-button disabled title="独立核销申请接口待后续开放">提交核销申请 · 待接口</el-button></template>
    </PageHeader>
    <RedemptionStats />
    <PageCard>
      <el-tabs v-model="activeTab" class="fulfillment-status-tabs" @tab-change="changeTab">
        <el-tab-pane label="核销历史" name="records" />
        <el-tab-pane label="冲正申请与审核" name="reversals" />
      </el-tabs>

      <template v-if="activeTab === 'records'">
        <div class="crm-toolbar">
          <el-input-number v-model="redemptionQuery.institutionId" :min="1" :precision="0" :controls="false" placeholder="机构 ID" class="fulfillment-filter-id" data-testid="redemption-institution-filter" @keyup.enter="searchRedemptions" />
          <el-input-number v-model="redemptionQuery.customerId" :min="1" :precision="0" :controls="false" placeholder="客户 ID" class="fulfillment-filter-id" @keyup.enter="searchRedemptions" />
          <el-input-number v-model="redemptionQuery.projectId" :min="1" :precision="0" :controls="false" placeholder="项目 ID" class="fulfillment-filter-id" @keyup.enter="searchRedemptions" />
          <el-select v-model="redemptionQuery.status" placeholder="核销状态" clearable class="status-filter">
            <el-option label="机构正式核销" value="POSTED" />
            <el-option label="待机构确认" value="PENDING_INSTITUTION" />
            <el-option label="已提交申请" value="SUBMITTED" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
          <el-button type="primary" :loading="redemptionLoading" @click="searchRedemptions">查询</el-button>
          <el-button @click="resetRedemptions">重置</el-button>
          <span class="crm-muted">项目筛选由后端执行，逐项目明细在只读详情查看</span>
        </div>
        <el-alert v-if="redemptionError" type="error" :title="`核销加载失败：${redemptionError}`" :closable="false" class="crm-table-alert" />
        <div v-loading="redemptionLoading" class="proto-table-wrap"><RedemptionPrototypeTable :rows="redemptions" :can-reverse="canReverse" @detail="openDetail" @reverse="openReversal" /></div>
        <h3 class="institution-summary-title">按机构汇总</h3>
        <app-table :data="institutionSummaries">
          <el-table-column label="机构" min-width="120"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
          <el-table-column label="城市" width="90">—</el-table-column>
          <el-table-column label="核销流水" prop="records" width="100" align="right" />
          <el-table-column label="正式核销次数" prop="records" width="120" align="right" />
          <el-table-column label="最近核销" min-width="150"><template #default="{row}">{{ formatDateTime(row.latest) }}</template></el-table-column>
          <el-table-column label="待机构确认" prop="pending" width="110" align="right" />
        </app-table>
        <el-pagination
          v-model:current-page="redemptionQuery.page"
          v-model:page-size="redemptionQuery.size"
          :total="redemptionTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          class="crm-pager"
          @current-change="loadRedemptions"
          @size-change="redemptionQuery.page = 1; loadRedemptions()"
        />
      </template>

      <template v-else>
        <div class="crm-toolbar">
          <el-select v-model="reversalQuery.status" placeholder="全部审核状态" clearable class="status-filter" data-testid="reversal-status-filter">
            <el-option label="待审核" value="REQUESTED" />
            <el-option label="审核通过" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
          <el-button type="primary" :loading="reversalLoading" @click="reversalQuery.page = 1; loadReversals()">查询</el-button>
          <el-button @click="reversalQuery.status = ''; reversalQuery.page = 1; loadReversals()">重置</el-button>
          <span class="crm-spacer" />
          <span class="crm-muted">申请、审核和补偿流水分别留痕</span>
        </div>
        <el-alert v-if="reversalError" type="error" :title="`冲正申请加载失败：${reversalError}`" :closable="false" class="crm-table-alert" />
        <app-table v-else v-loading="reversalLoading" :data="reversals" row-key="id">
          <el-table-column label="申请 / 原核销" width="145">
            <template #default="{ row }"><strong>申请 #{{ row.id }}</strong><div class="trading-cell-sub">原核销 #{{ row.redemptionId }}</div></template>
          </el-table-column>
          <el-table-column prop="reason" label="冲正原因" min-width="180" show-overflow-tooltip />
          <el-table-column label="申请人 / 时间" width="170">
            <template #default="{ row }">用户 #{{ row.requestedBy }}<div class="trading-cell-sub">{{ formatDateTime(row.requestedAt) }}</div></template>
          </el-table-column>
          <el-table-column label="审核状态" width="105">
            <template #default="{ row }"><StatusPill :text="reversalStatusLabel(row.status)" :tone="reversalStatusTone(row.status)" /></template>
          </el-table-column>
          <el-table-column label="对权益的实际影响" min-width="220">
            <template #default="{ row }">{{ reversalEffectLabel(row, correctionsFor(row)) }}</template>
          </el-table-column>
          <el-table-column label="审核人 / 原因" min-width="155">
            <template #default="{ row }">{{ row.reviewedBy ? `用户 #${row.reviewedBy}` : '-' }}<div class="trading-cell-sub">{{ row.reviewReason || '-' }}</div></template>
          </el-table-column>
          <el-table-column label="操作" width="245" fixed="right">
            <template #default="{ row }">
              <el-button text size="small" :loading="reversalDetailLoadingId === row.id" data-testid="reversal-detail" @click="openReversalDetail(row.id)">详情 / 补偿</el-button>
              <template v-if="canReview(row)">
                <el-button text size="small" type="primary" data-testid="reversal-approve" @click="review(row, 'approve')">批准</el-button>
                <el-button text size="small" type="danger" data-testid="reversal-reject" @click="review(row, 'reject')">驳回</el-button>
              </template>
              <span v-else-if="isReversalPending(row)" class="crm-muted">待其他审核人处理</span>
              <span v-else class="crm-muted">审核已留痕</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无冲正申请" /></template>
        </app-table>
        <el-pagination
          v-model:current-page="reversalQuery.page"
          v-model:page-size="reversalQuery.size"
          :total="reversalTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          class="crm-pager"
          @current-change="loadReversals"
          @size-change="reversalQuery.page = 1; loadReversals()"
        />
      </template>
    </PageCard>

    <RedemptionDetailDrawer v-model="detailVisible" :detail="detail" :can-reverse="canReverse" @request-reversal="requestFromDetail" />
    <ReversalRequestDialog v-if="canReverse" v-model="reversalVisible" :redemption="reversalSource" @saved="handleReversalRequested" />
    <ReversalDetailDrawer v-model="reversalDetailVisible" :detail="reversalDetail" />
  </div>
</template>

<style scoped>
.status-filter { width: 155px; }
</style>
