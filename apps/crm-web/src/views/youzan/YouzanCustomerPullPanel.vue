<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerOwnerOption } from '../../api/customer'
import { listCustomerOwnerOptions } from '../../api/customer'
import type {
  YouzanCustomerCursorSummary,
  YouzanCustomerPullInput,
  YouzanCustomerSourceMode,
  YouzanCustomerSyncItem,
  YouzanCustomerSyncOutcome,
  YouzanCustomerSyncRun,
  YouzanCustomerSyncStatus,
} from '../../api/youzan'
import {
  getYouzanCustomerSyncStatus,
  listYouzanCustomerSyncRunItems,
  listYouzanCustomerSyncRuns,
  pullYouzanCustomers,
  resetYouzanCustomerSyncCursor,
} from '../../api/youzan'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime, useDictionaries } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { tradingErrorText } from '../trading/tradingUi'
import type { YouzanTone } from './youzanUi'
import { createYouzanKey } from './youzanUi'

const LIMIT_PRESETS = [10, 20, 50, 100] as const
const PULL_ALL_BATCH_SIZE = 200
const PULL_ALL_MAX_BATCHES = 500

const props = defineProps<{
  shopId: string
}>()

const emit = defineEmits<{
  'link-external': [externalCustomerId: string]
  imported: []
}>()

const auth = useAuthStore()
const dicts = useDictionaries()

const isInstitution = computed(() => auth.user?.userType.startsWith('INSTITUTION') ?? false)
const canManage = computed(() => !isInstitution.value && auth.hasPermission('integration:youzan:manage'))
const canExecute = computed(() => canManage.value && auth.hasPermission('customer:write'))

const normalizedShopId = computed(() => props.shopId.trim())

const form = reactive<{
  sourceMode: YouzanCustomerSourceMode
  ownerStaffId: number | undefined
  sourceCode: string
  channelCode: string
  startDate: string
  endDate: string
  enrichProfile: boolean
  limit: number
}>({
  sourceMode: 'SCRM_LIST',
  ownerStaffId: undefined,
  sourceCode: '',
  channelCode: '',
  startDate: '',
  endDate: '',
  enrichProfile: true,
  limit: 20,
})

const effectiveLimit = computed(() => {
  const parsed = Math.floor(Number(form.limit))
  return Number.isFinite(parsed) ? Math.min(500, Math.max(1, parsed)) : 20
})

const ownerOptions = ref<CustomerOwnerOption[]>([])
const ownersLoading = ref(false)
const ownersError = ref('')

const status = ref<YouzanCustomerSyncStatus | null>(null)
const statusLoading = ref(false)
const statusError = ref('')

const runs = ref<YouzanCustomerSyncRun[]>([])
const runsTotal = ref(0)
const runsLoading = ref(false)
const runsError = ref('')
const runsQuery = reactive<{
  sourceMode: '' | YouzanCustomerSourceMode
  page: number
  size: number
}>({
  sourceMode: '',
  page: 1,
  size: 10,
})

const selectedRun = ref<YouzanCustomerSyncRun | null>(null)
const runItems = ref<YouzanCustomerSyncItem[]>([])
const itemTotal = ref(0)
const itemsLoading = ref(false)
const itemsError = ref('')
const itemQuery = reactive<{
  outcome: '' | YouzanCustomerSyncOutcome
  page: number
  size: number
}>({
  outcome: '',
  page: 1,
  size: 20,
})

const pullingBatch = ref(false)
const pullingAll = ref(false)
const stopRequested = ref(false)
const resettingCursor = ref(false)
const isPulling = computed(() => pullingBatch.value || pullingAll.value)

interface PullAllProgress {
  batches: number
  fetchedCount: number
  createdCount: number
  skippedExistingCount: number
  mobileConflictCount: number
  failedCount: number
  hasMore: boolean
  stopped: boolean
  hitBatchLimit: boolean
  errorMessage: string
}

const allProgress = ref<PullAllProgress | null>(null)
let batchAttempt: { signature: string; key: string } | null = null
let statusRequestSeq = 0
let runsRequestSeq = 0
let itemsRequestSeq = 0
let unmounted = false

const activeCursor = computed<YouzanCustomerCursorSummary | null>(() => {
  if (!status.value) return null
  return form.sourceMode === 'SCRM_LIST' ? status.value.scrmCursor : status.value.orderBuyersCursor
})

const channelPillText = computed(() => {
  if (!status.value) return '未加载'
  if (status.value.officialEnabled) return '真实有赞通道'
  if (status.value.adapterName === 'DISABLED') return '未启用'
  return '本地模拟演示'
})

const channelPillTone = computed<YouzanTone>(() => {
  if (!status.value) return 'info'
  if (status.value.officialEnabled) return 'success'
  if (status.value.adapterName === 'DISABLED') return 'info'
  return 'warning'
})

const cursorPositionText = computed(() => {
  const cursor = activeCursor.value
  if (!cursor) return '尚未加载'
  if (cursor.hasCursor && (cursor.hasMore || cursor.pageNo != null || cursor.itemOffset != null)) {
    return `第 ${cursor.pageNo ?? 1} 页 · 偏移 ${cursor.itemOffset ?? 0}`
  }
  if (cursor.lastAnchorAt) {
    return '本轮已完成（下次自动增量）'
  }
  return '初始位置（从头拉取）'
})

const cursorHasMoreText = computed(() => {
  const cursor = activeCursor.value
  if (!cursor) return '—'
  if (cursor.hasMore) return '还有更多待续拉'
  if (cursor.lastSyncedAt) return '已同步至最新'
  return '待首次拉取'
})

const cursorWindowText = computed(() => {
  const cursor = activeCursor.value
  if (!cursor) return ''
  if (cursor.windowStart || cursor.windowEnd) {
    return `${cursor.windowStart ? formatDateTime(cursor.windowStart) : '起点'} ~ ${cursor.windowEnd ? formatDateTime(cursor.windowEnd) : '当前'}`
  }
  if (cursor.lastAnchorAt) {
    return `增量锚点：${formatDateTime(cursor.lastAnchorAt)}`
  }
  return ''
})

function sourceModeLabel(mode: YouzanCustomerSourceMode): string {
  return mode === 'SCRM_LIST' ? '有赞全量客户' : '订单买家拉取'
}

function pullModeLabel(mode: 'BATCH' | 'ALL'): string {
  return mode === 'ALL' ? '全部拉取' : '分批拉取'
}

function runStatusLabel(state: string): string {
  return ({
    RUNNING: '执行中',
    SUCCEEDED: '已完成',
    SUCCESS: '已完成',
    PARTIAL: '部分异常',
    FAILED: '失败',
    ERROR: '失败',
  } as Record<string, string>)[state] ?? state
}

function runStatusTone(state: string): YouzanTone {
  if (state === 'SUCCEEDED' || state === 'SUCCESS') return 'success'
  if (state === 'PARTIAL' || state === 'RUNNING') return 'warning'
  if (state === 'FAILED' || state === 'ERROR') return 'danger'
  return 'info'
}

function outcomeLabel(outcome: YouzanCustomerSyncOutcome): string {
  return ({
    CREATED: '已新建',
    SKIPPED_EXISTING: '已存在跳过',
    MOBILE_CONFLICT: '手机号冲突',
    FAILED: '失败',
  } as Record<YouzanCustomerSyncOutcome, string>)[outcome] ?? outcome
}

function outcomeTone(outcome: YouzanCustomerSyncOutcome): YouzanTone {
  if (outcome === 'CREATED') return 'success'
  if (outcome === 'SKIPPED_EXISTING') return 'info'
  if (outcome === 'MOBILE_CONFLICT') return 'warning'
  return 'danger'
}

function ownerOptionLabel(option: CustomerOwnerOption): string {
  return option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName
}

function conflictCustomersText(row: YouzanCustomerSyncItem): string {
  if (row.conflictCustomerNos?.length) return row.conflictCustomerNos.join('、')
  if (row.conflictCustomerIds?.length) return row.conflictCustomerIds.map((id) => `#${id}`).join('、')
  return '—'
}

function applyLimitPreset(preset: number) {
  form.limit = preset
  batchAttempt = null
}

async function loadOwnerOptions() {
  if (!canExecute.value) return
  ownersLoading.value = true
  ownersError.value = ''
  try {
    ownerOptions.value = await listCustomerOwnerOptions()
    if (form.ownerStaffId === undefined && ownerOptions.value.length === 1) {
      form.ownerStaffId = ownerOptions.value[0]?.id
    }
  } catch (error) {
    ownerOptions.value = []
    ownersError.value = tradingErrorText(error)
  } finally {
    ownersLoading.value = false
  }
}

async function loadStatus() {
  const targetShopId = normalizedShopId.value
  const seq = ++statusRequestSeq
  if (!canManage.value || !targetShopId) {
    status.value = null
    statusError.value = ''
    statusLoading.value = false
    return
  }
  statusLoading.value = true
  statusError.value = ''
  try {
    const result = await getYouzanCustomerSyncStatus(targetShopId)
    if (unmounted || seq !== statusRequestSeq || normalizedShopId.value !== targetShopId) return
    status.value = result
  } catch (error) {
    if (unmounted || seq !== statusRequestSeq || normalizedShopId.value !== targetShopId) return
    status.value = null
    statusError.value = tradingErrorText(error)
  } finally {
    if (!unmounted && seq === statusRequestSeq) {
      statusLoading.value = false
    }
  }
}

async function loadRuns(page = runsQuery.page) {
  const targetShopId = normalizedShopId.value
  const seq = ++runsRequestSeq
  if (!canManage.value || !targetShopId) {
    runs.value = []
    runsTotal.value = 0
    runsError.value = ''
    runsLoading.value = false
    return
  }
  runsLoading.value = true
  runsError.value = ''
  try {
    const result = await listYouzanCustomerSyncRuns({
      shopId: targetShopId,
      sourceMode: runsQuery.sourceMode || undefined,
      page,
      size: runsQuery.size,
    })
    if (unmounted || seq !== runsRequestSeq || normalizedShopId.value !== targetShopId) return
    runs.value = result.items
    runsTotal.value = result.total
    runsQuery.page = result.page
    runsQuery.size = result.size
  } catch (error) {
    if (unmounted || seq !== runsRequestSeq || normalizedShopId.value !== targetShopId) return
    runs.value = []
    runsTotal.value = 0
    runsError.value = tradingErrorText(error)
  } finally {
    if (!unmounted && seq === runsRequestSeq) {
      runsLoading.value = false
    }
  }
}

async function loadRunItems(runId: number, page = itemQuery.page) {
  const targetShopId = normalizedShopId.value
  const seq = ++itemsRequestSeq
  if (!canManage.value) {
    itemsLoading.value = false
    return
  }
  itemsLoading.value = true
  itemsError.value = ''
  try {
    const result = await listYouzanCustomerSyncRunItems(runId, {
      outcome: itemQuery.outcome || undefined,
      page,
      size: itemQuery.size,
    })
    if (unmounted || seq !== itemsRequestSeq || normalizedShopId.value !== targetShopId || selectedRun.value?.id !== runId) {
      return
    }
    runItems.value = result.items
    itemTotal.value = result.total
    itemQuery.page = result.page
    itemQuery.size = result.size
  } catch (error) {
    if (unmounted || seq !== itemsRequestSeq || normalizedShopId.value !== targetShopId || selectedRun.value?.id !== runId) {
      return
    }
    runItems.value = []
    itemTotal.value = 0
    itemsError.value = tradingErrorText(error)
  } finally {
    if (!unmounted && seq === itemsRequestSeq) {
      itemsLoading.value = false
    }
  }
}

function viewRunItems(run: YouzanCustomerSyncRun) {
  selectedRun.value = run
  itemQuery.outcome = ''
  itemQuery.page = 1
  void loadRunItems(run.id, 1)
}

function onItemOutcomeChange() {
  if (!selectedRun.value) return
  itemQuery.page = 1
  void loadRunItems(selectedRun.value.id, 1)
}

function validatePullConfig(): boolean {
  if (!normalizedShopId.value) {
    ElMessage.warning('请先输入或选择有赞店铺编号（kdt_id）')
    return false
  }
  if (!form.ownerStaffId) {
    ElMessage.warning('请先选择建档后的目标专属管家')
    return false
  }
  if (form.startDate && form.endDate && form.startDate > form.endDate) {
    ElMessage.warning('开始日期不能晚于结束日期')
    return false
  }
  return true
}

function buildPullPayload(pullMode: 'BATCH' | 'ALL', limit: number): YouzanCustomerPullInput {
  return {
    shopId: normalizedShopId.value,
    sourceMode: form.sourceMode,
    pullMode,
    limit,
    ownerStaffId: form.ownerStaffId!,
    ...(form.sourceCode ? { sourceCode: form.sourceCode } : {}),
    ...(form.channelCode ? { channelCode: form.channelCode } : {}),
    ...(form.startDate ? { startDate: form.startDate } : {}),
    ...(form.endDate ? { endDate: form.endDate } : {}),
    enrichProfile: form.enrichProfile,
  }
}

function totalOutcomesOf(run: YouzanCustomerSyncRun): number {
  return run.createdCount + run.skippedExistingCount + run.mobileConflictCount + run.failedCount
}

async function applyRunSelection(run: YouzanCustomerSyncRun) {
  selectedRun.value = run
  itemQuery.outcome = ''
  itemQuery.page = 1
  itemsError.value = ''
  const expectedTotal = totalOutcomesOf(run)
  if (Array.isArray(run.items)) {
    const total = Math.max(run.items.length, expectedTotal)
    itemsRequestSeq += 1
    runItems.value = run.items.length > itemQuery.size ? run.items.slice(0, itemQuery.size) : run.items
    itemTotal.value = total
    if (run.items.length < expectedTotal) {
      await loadRunItems(run.id, 1)
    }
    return
  }
  await loadRunItems(run.id, 1)
}

async function pullBatch() {
  if (!canExecute.value || isPulling.value) return
  if (!validatePullConfig()) return

  const targetShopId = normalizedShopId.value
  const payload = buildPullPayload('BATCH', effectiveLimit.value)
  const signature = JSON.stringify(payload)
  if (batchAttempt?.signature !== signature) {
    batchAttempt = { signature, key: createYouzanKey('youzan.customer.pull.batch') }
  }
  const key = batchAttempt.key

  pullingBatch.value = true
  try {
    const run = await pullYouzanCustomers(payload, key)
    batchAttempt = null
    if (unmounted || normalizedShopId.value !== targetShopId) return
    allProgress.value = null
    await Promise.allSettled([applyRunSelection(run), loadStatus(), loadRuns(1)])
    if (run.createdCount > 0) {
      emit('imported')
    }
    const summary = `拉取 ${run.fetchedCount} 条：新建 ${run.createdCount}，跳过 ${run.skippedExistingCount}，冲突 ${run.mobileConflictCount}，失败 ${run.failedCount}`
    const moreHint = run.hasMore ? '；断点已保存，可继续拉取后续客户' : '；本轮已拉至最新，下次将自动增量同步'
    if (run.failedCount > 0 || run.mobileConflictCount > 0 || run.status === 'PARTIAL' || run.status === 'FAILED' || run.status === 'ERROR') {
      ElMessage.warning(`${summary}${moreHint}`)
    } else {
      ElMessage.success(`${summary}${moreHint}`)
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重新提交不会重复建档`)
  } finally {
    pullingBatch.value = false
  }
}

async function pullAll() {
  if (!canExecute.value || isPulling.value) return
  if (!validatePullConfig()) return

  const targetShopId = normalizedShopId.value
  pullingAll.value = true
  stopRequested.value = false
  batchAttempt = null
  const progress = reactive<PullAllProgress>({
    batches: 0,
    fetchedCount: 0,
    createdCount: 0,
    skippedExistingCount: 0,
    mobileConflictCount: 0,
    failedCount: 0,
    hasMore: true,
    stopped: false,
    hitBatchLimit: false,
    errorMessage: '',
  })
  allProgress.value = progress

  let lastRun: YouzanCustomerSyncRun | null = null
  let failedWithError = false

  try {
    while (!stopRequested.value && !unmounted && normalizedShopId.value === targetShopId && progress.batches < PULL_ALL_MAX_BATCHES) {
      const payload = buildPullPayload('ALL', PULL_ALL_BATCH_SIZE)
      const key = createYouzanKey('youzan.customer.pull.all')
      const run = await pullYouzanCustomers(payload, key)
      lastRun = run
      progress.batches += 1
      progress.fetchedCount += run.fetchedCount
      progress.createdCount += run.createdCount
      progress.skippedExistingCount += run.skippedExistingCount
      progress.mobileConflictCount += run.mobileConflictCount
      progress.failedCount += run.failedCount
      progress.hasMore = run.hasMore

      if (!unmounted && normalizedShopId.value === targetShopId) {
        selectedRun.value = run
        if (Array.isArray(run.items)) {
          itemsRequestSeq += 1
          runItems.value = run.items.length > itemQuery.size ? run.items.slice(0, itemQuery.size) : run.items
          itemTotal.value = Math.max(run.items.length, totalOutcomesOf(run))
        }
      }

      if (run.status === 'FAILED' || run.status === 'ERROR') {
        failedWithError = true
        progress.errorMessage = run.errorMasked || `批次 #${run.id} 执行失败（状态：${run.status}），已终止后续拉取`
        ElMessage.error(`全部拉取异常终止（已完成 ${progress.batches} 批）：${progress.errorMessage}`)
        break
      }

      if (!run.hasMore) {
        break
      }
    }
    if (!failedWithError && stopRequested.value && progress.hasMore) {
      progress.stopped = true
    } else if (!failedWithError && progress.hasMore && progress.batches >= PULL_ALL_MAX_BATCHES) {
      progress.hitBatchLimit = true
    }
  } catch (error) {
    failedWithError = true
    progress.errorMessage = tradingErrorText(error)
    ElMessage.error(`全部拉取中断（已完成 ${progress.batches} 批）：${progress.errorMessage}`)
  } finally {
    pullingAll.value = false
    const stoppedByUser = stopRequested.value
    stopRequested.value = false
    if (!unmounted && normalizedShopId.value === targetShopId) {
      const refreshTasks: Array<Promise<unknown>> = [loadStatus(), loadRuns(1)]
      if (lastRun) {
        refreshTasks.push(applyRunSelection(lastRun))
      }
      await Promise.allSettled(refreshTasks)
      if (progress.createdCount > 0) {
        emit('imported')
      }
      if (!failedWithError) {
        const summary = `共执行 ${progress.batches} 批，拉取 ${progress.fetchedCount} 条：新建 ${progress.createdCount}，跳过 ${progress.skippedExistingCount}，冲突 ${progress.mobileConflictCount}，失败 ${progress.failedCount}`
        if (stoppedByUser && progress.hasMore) {
          ElMessage.info(`已停止全部拉取（${summary}）；断点已保存，下次可继续拉取`)
        } else if (progress.hitBatchLimit) {
          ElMessage.warning(`已达单轮 ${PULL_ALL_MAX_BATCHES} 批上限（${summary}）；仍有剩余客户，断点已保存，请再次点击全部拉取继续`)
        } else if (progress.failedCount > 0 || progress.mobileConflictCount > 0) {
          ElMessage.warning(`全部拉取结束（${summary}）`)
        } else {
          ElMessage.success(`全部拉取完成（${summary}）`)
        }
      }
    }
  }
}

function stopPullAll() {
  if (!pullingAll.value) return
  stopRequested.value = true
}

async function handleResetCursor() {
  if (!canExecute.value || isPulling.value || resettingCursor.value || !normalizedShopId.value) return
  const targetShopId = normalizedShopId.value
  try {
    await ElMessageBox.confirm(
      `确认重置「${sourceModeLabel(form.sourceMode)}」的同步游标吗？重置后下次拉取将从头重新扫描；已建档并绑定有赞标识的客户会自动跳过，不会重复建档。`,
      '重置游标（从头重拉）',
      {
        type: 'warning',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  resettingCursor.value = true
  try {
    const nextStatus = await resetYouzanCustomerSyncCursor({
      shopId: targetShopId,
      sourceMode: form.sourceMode,
    })
    if (unmounted || normalizedShopId.value !== targetShopId) return
    statusRequestSeq += 1
    status.value = nextStatus
    batchAttempt = null
    ElMessage.success('同步游标已重置，下次拉取将从头开始扫描（已建档客户自动防重跳过）')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    resettingCursor.value = false
  }
}

function handleLinkExternal(externalCustomerId: string) {
  emit('link-external', externalCustomerId)
}

watch(
  () => [form.sourceMode, form.ownerStaffId, form.sourceCode, form.channelCode, form.startDate, form.endDate, form.enrichProfile, form.limit],
  () => {
    batchAttempt = null
  },
)

watch(
  normalizedShopId,
  () => {
    if (pullingAll.value) {
      stopRequested.value = true
    }
    itemsRequestSeq += 1
    batchAttempt = null
    allProgress.value = null
    selectedRun.value = null
    runItems.value = []
    itemTotal.value = 0
    itemsError.value = ''
    statusError.value = ''
    runsError.value = ''
    runsQuery.page = 1
    itemQuery.page = 1
    itemQuery.outcome = ''
    void Promise.allSettled([loadStatus(), loadRuns(1)])
  },
  { immediate: true },
)

onMounted(() => {
  void dicts.load('customerSources', 'customerChannels')
  void loadOwnerOptions()
})

onBeforeUnmount(() => {
  unmounted = true
  stopRequested.value = true
})
</script>

<template>
  <section class="yz-customer-pull" data-testid="yz-customer-pull-panel">
    <el-alert
      v-if="!canManage"
      type="error"
      title="当前账号暂无有赞接入管理权限，无法查看客户拉取与建档"
      :closable="false"
    />
    <template v-else>
      <div class="youzan-section-head">
        <div>
          <h3>有赞客户拉取与正式建档</h3>
          <p>支持从有赞全量客户或历史订单买家分批/全部拉取并写入 CRM 客户档案；自动记录游标断点并防重建档。</p>
        </div>
        <div class="yz-head-actions">
          <StatusPill :text="channelPillText" :tone="channelPillTone" data-testid="yz-customer-channel-pill" />
          <el-button text :loading="statusLoading" data-testid="yz-customer-status-refresh" @click="loadStatus">刷新状态</el-button>
        </div>
      </div>

      <div v-if="status" class="yz-adapter-meta" data-testid="yz-customer-adapter-meta">
        <span>店铺编号：<strong>{{ status.shopId }}</strong></span>
        <span>适配器：<code>{{ status.adapterName }}</code></span>
        <span>当前数据源：<strong>{{ sourceModeLabel(form.sourceMode) }}</strong></span>
      </div>

      <el-alert v-if="statusError" type="error" :title="`状态加载失败：${statusError}`" :closable="false" class="yz-block-gap" />

      <div v-if="activeCursor" class="yz-cursor-grid" data-testid="yz-customer-cursor-card">
        <div class="youzan-summary-card">
          <small>当前断点游标</small>
          <strong>{{ cursorPositionText }}</strong>
          <div v-if="cursorWindowText" class="yz-card-sub">{{ cursorWindowText }}</div>
        </div>
        <div class="youzan-summary-card">
          <small>拉取进度状态</small>
          <strong>{{ cursorHasMoreText }}</strong>
          <div class="yz-card-sub">最近同步：{{ activeCursor.lastSyncedAt ? formatDateTime(activeCursor.lastSyncedAt) : '尚未同步' }}</div>
        </div>
        <div class="youzan-summary-card">
          <small>累计已建档客户</small>
          <strong>{{ activeCursor.totalImportedCount }} 位</strong>
          <div class="yz-card-sub">已绑定有赞客户再次拉取自动跳过</div>
        </div>
      </div>

      <div class="yz-cursor-note" data-testid="yz-customer-cursor-note">
        <strong>断点续拉与防重说明：</strong>这次拉 10 条，下次自动接着第 11 条继续拉，不重复建档；拉完后自动转增量同步。若手机号命中 CRM 老客户，绝不覆盖原档案，会标记为手机号冲突供人工确认链接。
      </div>

      <el-alert
        v-if="!canExecute"
        type="info"
        :closable="false"
        title="当前账号仅具备查看权限，缺少客户写入权限（customer:write），暂不可执行客户拉取建档或重置游标。"
        class="yz-block-gap"
        data-testid="yz-customer-pull-readonly-note"
      />

      <div v-if="canExecute" class="yz-config-card" data-testid="yz-customer-config-card">
        <div class="yz-config-row">
          <div class="yz-field-label">数据源模式</div>
          <el-radio-group v-model="form.sourceMode" :disabled="isPulling" data-testid="yz-source-mode">
            <el-radio value="SCRM_LIST" data-testid="yz-source-mode-scrm">
              有赞全量客户（含未下单，需 scrm.customer.search 权限）
            </el-radio>
            <el-radio value="ORDER_BUYERS" data-testid="yz-source-mode-order">
              按有赞订单买家拉取（复用已开通订单与买家接口）
            </el-radio>
          </el-radio-group>
        </div>

        <div class="yz-config-grid">
          <div class="yz-field">
            <label class="yz-field-label">目标专属管家 <span class="yz-required">*</span></label>
            <el-select
              v-model="form.ownerStaffId"
              filterable
              clearable
              :loading="ownersLoading"
              :disabled="isPulling"
              placeholder="请选择建档归属的专属管家（必选）"
              class="full-width"
              data-testid="yz-pull-owner"
            >
              <el-option
                v-for="option in ownerOptions"
                :key="option.id"
                :label="ownerOptionLabel(option)"
                :value="option.id"
              />
            </el-select>
            <div v-if="ownersError" class="youzan-error-text">{{ ownersError }}</div>
          </div>

          <div class="yz-field">
            <label class="yz-field-label">默认客户来源（可选）</label>
            <el-select
              v-model="form.sourceCode"
              filterable
              clearable
              :disabled="isPulling"
              placeholder="不选则留空"
              class="full-width"
              data-testid="yz-pull-source-code"
            >
              <el-option
                v-for="option in dicts.options('customerSources')"
                :key="option.code"
                :label="option.label"
                :value="option.code"
              />
            </el-select>
          </div>

          <div class="yz-field">
            <label class="yz-field-label">默认客户渠道（可选）</label>
            <el-select
              v-model="form.channelCode"
              filterable
              clearable
              :disabled="isPulling"
              placeholder="不选则留空"
              class="full-width"
              data-testid="yz-pull-channel-code"
            >
              <el-option
                v-for="option in dicts.options('customerChannels')"
                :key="option.code"
                :label="option.label"
                :value="option.code"
              />
            </el-select>
          </div>

          <div class="yz-field">
            <label class="yz-field-label">日期范围（可选，按 Asia/Shanghai）</label>
            <div class="yz-date-range">
              <el-date-picker
                v-model="form.startDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled="isPulling"
                placeholder="开始日期"
                data-testid="yz-pull-start-date"
              />
              <span class="yz-date-sep">至</span>
              <el-date-picker
                v-model="form.endDate"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled="isPulling"
                placeholder="结束日期"
                data-testid="yz-pull-end-date"
              />
            </div>
          </div>
        </div>

        <div class="yz-switch-row">
          <el-switch
            v-model="form.enrichProfile"
            :disabled="isPulling"
            active-text="补全买家详细信息（users.info.query + scrm.customer.get）"
            data-testid="yz-pull-enrich"
          />
        </div>

        <div class="yz-action-bar">
          <div class="youzan-pull-control" data-testid="yz-customer-limit-control">
            <span class="youzan-pull-label">单次拉取条数</span>
            <div class="youzan-pull-presets" role="group" aria-label="快捷客户拉取条数">
              <button
                v-for="preset in LIMIT_PRESETS"
                :key="preset"
                type="button"
                class="youzan-pull-preset"
                :class="{ 'is-active': effectiveLimit === preset }"
                :disabled="isPulling"
                :data-testid="`yz-customer-preset-${preset}`"
                @click="applyLimitPreset(preset)"
              >
                {{ preset }}
              </button>
            </div>
            <el-input-number
              v-model="form.limit"
              :min="1"
              :max="500"
              :step="10"
              :precision="0"
              :disabled="isPulling"
              size="small"
              controls-position="right"
              class="youzan-pull-input"
              data-testid="yz-customer-limit"
              aria-label="自定义客户拉取条数"
            />
          </div>

          <div class="yz-action-buttons">
            <el-button
              type="primary"
              :loading="pullingBatch"
              :disabled="pullingAll || resettingCursor"
              data-testid="yz-customer-pull-batch"
              @click="pullBatch"
            >
              {{ activeCursor?.hasMore ? `继续拉取 ${effectiveLimit} 条` : `拉取 ${effectiveLimit} 条` }}
            </el-button>

            <el-button
              v-if="!pullingAll"
              type="success"
              :disabled="pullingBatch || resettingCursor"
              data-testid="yz-customer-pull-all"
              @click="pullAll"
            >
              全部拉取
            </el-button>
            <el-button
              v-else
              type="danger"
              :disabled="stopRequested"
              data-testid="yz-customer-pull-stop"
              @click="stopPullAll"
            >
              {{ stopRequested ? '正在停止...' : '停止拉取' }}
            </el-button>

            <el-button
              plain
              type="warning"
              :loading="resettingCursor"
              :disabled="isPulling"
              data-testid="yz-customer-reset-cursor"
              @click="handleResetCursor"
            >
              重置游标（从头重拉）
            </el-button>
          </div>
        </div>
      </div>

      <div v-if="allProgress" class="yz-progress-banner" data-testid="yz-pull-all-progress">
        <div class="yz-progress-title">
          <strong>
            {{
              pullingAll
                ? '正在执行全部拉取（每批 200 条自动续拉）'
                : allProgress.errorMessage
                  ? '全部拉取中途异常'
                  : allProgress.stopped
                    ? '全部拉取已手动停止（断点已保存）'
                    : allProgress.hitBatchLimit
                      ? '已达单轮最大批次数上限（断点已保存，可继续拉取）'
                      : '全部拉取已完成'
            }}
          </strong>
          <span>已执行 {{ allProgress.batches }} 批</span>
        </div>
        <div class="yz-progress-stats">
          <span>累计拉取：<strong>{{ allProgress.fetchedCount }}</strong></span>
          <span>新建建档：<strong>{{ allProgress.createdCount }}</strong></span>
          <span>跳过已存在：<strong>{{ allProgress.skippedExistingCount }}</strong></span>
          <span>手机号冲突：<strong>{{ allProgress.mobileConflictCount }}</strong></span>
          <span>失败：<strong>{{ allProgress.failedCount }}</strong></span>
        </div>
        <div v-if="allProgress.errorMessage" class="youzan-error-text">{{ allProgress.errorMessage }}</div>
      </div>

      <div v-if="selectedRun" class="yz-items-section" data-testid="yz-run-items-section">
        <div class="youzan-section-head">
          <div>
            <h3>批次 #{{ selectedRun.id }} 拉取明细（{{ sourceModeLabel(selectedRun.sourceMode) }} · {{ pullModeLabel(selectedRun.pullMode) }}）</h3>
            <p>
              状态：{{ runStatusLabel(selectedRun.status) }}；
              拉取 {{ selectedRun.fetchedCount }} 条，新建 {{ selectedRun.createdCount }}，跳过 {{ selectedRun.skippedExistingCount }}，手机号冲突 {{ selectedRun.mobileConflictCount }}，失败 {{ selectedRun.failedCount }}
            </p>
          </div>
          <div class="yz-items-toolbar">
            <el-select
              v-model="itemQuery.outcome"
              clearable
              placeholder="全部处理结果"
              size="small"
              class="youzan-filter-select"
              data-testid="yz-item-outcome-filter"
              @change="onItemOutcomeChange"
            >
              <el-option label="已新建" value="CREATED" />
              <el-option label="已存在跳过" value="SKIPPED_EXISTING" />
              <el-option label="手机号冲突" value="MOBILE_CONFLICT" />
              <el-option label="失败" value="FAILED" />
            </el-select>
            <el-button text size="small" :loading="itemsLoading" @click="loadRunItems(selectedRun.id, itemQuery.page)">刷新明细</el-button>
          </div>
        </div>

        <el-alert
          v-for="(warningText, index) in selectedRun.warnings"
          :key="index"
          type="warning"
          :title="warningText"
          :closable="false"
          class="yz-block-gap"
        />
        <el-alert
          v-if="selectedRun.errorMasked"
          type="error"
          :title="selectedRun.errorMasked"
          :closable="false"
          class="yz-block-gap"
        />
        <el-alert
          v-if="itemsError"
          type="error"
          :title="`明细加载失败：${itemsError}`"
          :closable="false"
          class="yz-block-gap"
        />

        <app-table v-loading="itemsLoading" :data="runItems" size="small" table-id="youzan-customer-sync-items">
          <el-table-column label="有赞外部客户标识" min-width="175">
            <template #default="{ row }">
              <span class="youzan-mono-id">{{ row.externalCustomerId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="脱敏姓名 / 手机" min-width="145">
            <template #default="{ row }">
              <div>{{ row.displayNameMasked || '—' }}</div>
              <div class="proto-subcell">{{ row.mobileMasked || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="处理结果" width="120">
            <template #default="{ row }">
              <StatusPill :text="outcomeLabel(row.outcome)" :tone="outcomeTone(row.outcome)" />
            </template>
          </el-table-column>
          <el-table-column label="CRM 客户编号" min-width="165">
            <template #default="{ row }">
              <span v-if="row.crmCustomerNo" class="youzan-mono-id">{{ row.crmCustomerNo }}</span>
              <span
                v-else-if="row.outcome === 'MOBILE_CONFLICT'"
                class="youzan-warning-text"
                data-testid="yz-item-conflict-nos"
              >
                命中老客户：{{ conflictCustomersText(row) }}
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="说明（已脱敏）" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">{{ row.detailMasked || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="125" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.outcome === 'MOBILE_CONFLICT'"
                size="small"
                type="warning"
                plain
                data-testid="yz-item-link-external"
                @click="handleLinkExternal(row.externalCustomerId)"
              >
                去人工链接
              </el-button>
              <span v-else class="proto-muted">—</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="该筛选条件下暂无明细记录" />
          </template>
        </app-table>

        <el-pagination
          v-model:current-page="itemQuery.page"
          v-model:page-size="itemQuery.size"
          :total="itemTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          class="crm-pager"
          @current-change="(page: number) => selectedRun && loadRunItems(selectedRun.id, page)"
          @size-change="itemQuery.page = 1; selectedRun && loadRunItems(selectedRun.id, 1)"
        />
      </div>

      <div class="yz-runs-section" data-testid="yz-runs-section">
        <div class="youzan-section-head">
          <div>
            <h3>最近拉取批次记录</h3>
            <p>保留每次拉取批次汇总统计与耗时，点击「查看明细」可切换核对历史批次每条结果。</p>
          </div>
          <div class="yz-items-toolbar">
            <el-select
              v-model="runsQuery.sourceMode"
              clearable
              placeholder="全部数据源"
              size="small"
              class="youzan-filter-select"
              data-testid="yz-runs-source-filter"
              @change="loadRuns(1)"
            >
              <el-option label="有赞全量客户" value="SCRM_LIST" />
              <el-option label="订单买家拉取" value="ORDER_BUYERS" />
            </el-select>
            <el-button text size="small" :loading="runsLoading" data-testid="yz-runs-refresh" @click="loadRuns(runsQuery.page)">刷新批次</el-button>
          </div>
        </div>

        <el-alert v-if="runsError" type="error" :title="`批次加载失败：${runsError}`" :closable="false" class="yz-block-gap" />

        <app-table v-loading="runsLoading" :data="runs" size="small" table-id="youzan-customer-sync-runs">
          <el-table-column label="批次" width="90">
            <template #default="{ row }">#{{ row.id }}</template>
          </el-table-column>
          <el-table-column label="数据源 / 模式" min-width="145">
            <template #default="{ row }">
              <div>{{ sourceModeLabel(row.sourceMode) }}</div>
              <div class="proto-subcell">{{ pullModeLabel(row.pullMode) }}{{ row.requestedLimit ? ` · 上限 ${row.requestedLimit}` : '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="归属管家" width="115">
            <template #default="{ row }">{{ row.ownerName || `#${row.ownerStaffId}` }}</template>
          </el-table-column>
          <el-table-column label="状态" width="105">
            <template #default="{ row }">
              <StatusPill :text="runStatusLabel(row.status)" :tone="runStatusTone(row.status)" />
            </template>
          </el-table-column>
          <el-table-column label="拉取 / 新建 / 跳过 / 冲突 / 失败" min-width="205">
            <template #default="{ row }">
              {{ row.fetchedCount }} / {{ row.createdCount }} / {{ row.skippedExistingCount }} / {{ row.mobileConflictCount }} / {{ row.failedCount }}
            </template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="150">
            <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                text
                size="small"
                :type="selectedRun?.id === row.id ? 'primary' : 'default'"
                data-testid="yz-run-view-items"
                @click="viewRunItems(row)"
              >
                {{ selectedRun?.id === row.id ? '查看中' : '查看明细' }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无客户拉取记录，配置归属管家后即可开始拉取" />
          </template>
        </app-table>

        <el-pagination
          v-model:current-page="runsQuery.page"
          v-model:page-size="runsQuery.size"
          :total="runsTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="crm-pager"
          @current-change="(page: number) => loadRuns(page)"
          @size-change="runsQuery.page = 1; loadRuns(1)"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.yz-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.yz-adapter-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-bottom: 12px;
  color: #4f6d6b;
  font-size: 12px;
}

.yz-adapter-meta code {
  padding: 1px 6px;
  border-radius: 4px;
  background: #edf5f4;
  color: #184945;
}

.yz-cursor-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.yz-card-sub {
  margin-top: 6px;
  color: #70888f;
  font-size: 11px;
  line-height: 1.5;
}

.yz-cursor-note {
  margin-bottom: 14px;
  padding: 11px 14px;
  border: 1px solid #cce3df;
  border-radius: 10px;
  background: #f3faf8;
  color: #214e49;
  font-size: 12px;
  line-height: 1.7;
}

.yz-block-gap {
  margin-bottom: 12px;
}

.yz-config-card {
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid #d9e7e5;
  border-radius: 12px;
  background: #fafdfc;
}

.yz-config-row {
  margin-bottom: 14px;
}

.yz-config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
  margin-bottom: 14px;
}

.yz-field {
  min-width: 0;
}

.yz-field-label {
  display: block;
  margin-bottom: 6px;
  color: #2c4a47;
  font-size: 12px;
  font-weight: 600;
}

.yz-required {
  color: #d64550;
}

.full-width {
  width: 100%;
}

.yz-date-range {
  display: flex;
  align-items: center;
  gap: 8px;
}

.yz-date-sep {
  color: #768f94;
  font-size: 12px;
}

.yz-switch-row {
  margin-bottom: 14px;
}

.yz-action-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px dashed #d5e5e2;
}

.yz-action-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.yz-progress-banner {
  margin-bottom: 16px;
  padding: 13px 16px;
  border: 1px solid #b8ded5;
  border-radius: 11px;
  background: #edf9f5;
  color: #184a43;
}

.yz-progress-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 13px;
}

.yz-progress-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  font-size: 12px;
}

.yz-items-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.yz-items-section,
.yz-runs-section {
  margin-top: 14px;
}

@media (max-width: 760px) {
  .yz-cursor-grid,
  .yz-config-grid {
    grid-template-columns: 1fr;
  }

  .yz-date-range {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
