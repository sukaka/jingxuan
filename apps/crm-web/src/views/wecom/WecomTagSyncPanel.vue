<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  WecomTagMappingItem,
  WecomTagMappingOverview,
  WecomTagPushTask,
  WecomTagSyncDirection,
  WecomTagSyncRun,
} from '../../api/wecom'
import {
  getWecomTagMappings,
  listWecomTagPushTasks,
  listWecomTagSyncRuns,
  retryWecomTagPushTask,
  syncWecomCustomerTags,
  syncWecomTagCatalog,
  updateWecomTagMapping,
} from '../../api/wecom'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { tradingErrorText } from '../trading/tradingUi'
import { createWecomKey } from './wecomUi'
import type { WecomTone } from './wecomUi'

const auth = useAuthStore()
const isInstitution = computed(() => auth.user?.userType.startsWith('INSTITUTION') ?? false)
const canRead = computed(() => !isInstitution.value && auth.hasPermission('wecom:read'))
const canManageCatalog = computed(() => !isInstitution.value && auth.hasPermission('customer:tag:manage'))
const canMonitor = computed(() => canRead.value && auth.hasPermission('wecom:monitor'))
const canView = computed(() => canRead.value || canManageCatalog.value)
const canSyncCatalog = computed(() => canManageCatalog.value || canMonitor.value)
const canManageMappings = computed(() => canManageCatalog.value || canMonitor.value)

const overview = ref<WecomTagMappingOverview | null>(null)
const modePillText = computed(() => {
  if (!overview.value) return ''
  if (overview.value.officialEnabled) return '官方企微通道'
  if (overview.value.adapterName === 'DISABLED') return '未启用'
  return '模拟演示模式'
})
const modePillTone = computed<WecomTone>(() => {
  if (!overview.value) return 'info'
  if (overview.value.officialEnabled) return 'success'
  if (overview.value.adapterName === 'DISABLED') return 'info'
  return 'warning'
})
const mappingsLoading = ref(false)
const mappingsError = ref('')

const runs = ref<WecomTagSyncRun[]>([])
const runsLoading = ref(false)
const runsError = ref('')

const tasks = ref<WecomTagPushTask[]>([])
const tasksLoading = ref(false)
const tasksError = ref('')
const taskStatusFilter = ref('')

const catalogSyncing = ref(false)
const customerSyncing = ref(false)
const unlinkingCode = ref<string | null>(null)
const retryingTaskId = ref<number | null>(null)

const CRM_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
  ARCHIVED: '已归档',
}

const MAPPING_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '已映射',
  UNLINKED: '已解除',
  DISABLED: '已停用',
  UNMAPPED: '未映射',
}

const SYNC_SCOPE_LABELS: Record<string, string> = {
  CATALOG: '标签库',
  CUSTOMER: '客户标签',
}

const DIRECTION_LABELS: Record<string, string> = {
  BIDIRECTIONAL: '双向同步',
  PULL: '从企微拉取',
  PUSH: '推送至企微',
}

const TRIGGER_SOURCE_LABELS: Record<string, string> = {
  MANUAL: '人工触发',
  CALLBACK: '企微回调',
  SCHEDULED: '定时调度',
}

const RUN_STATUS_LABELS: Record<string, string> = {
  RUNNING: '进行中',
  SUCCESS: '成功',
  PARTIAL: '部分成功',
  ERROR: '失败',
}

const TASK_STATUS_LABELS: Record<string, string> = {
  PENDING: '待执行',
  PROCESSING: '执行中',
  SUCCEEDED: '已成功',
  RETRYABLE: '待重试',
  FAILED: '失败',
}

function mappingStatusLabel(status: string): string {
  return MAPPING_STATUS_LABELS[status] ?? '未知状态'
}

function mappingStatusTone(status: string): WecomTone {
  if (status === 'ACTIVE') return 'success'
  if (status === 'UNLINKED' || status === 'DISABLED') return 'warning'
  return 'info'
}

function runStatusLabel(status: string): string {
  return RUN_STATUS_LABELS[status] ?? '未知状态'
}

function runStatusTone(status: string): WecomTone {
  if (status === 'SUCCESS') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'ERROR') return 'danger'
  return 'info'
}

function taskStatusLabel(status: string): string {
  return TASK_STATUS_LABELS[status] ?? '未知状态'
}

function taskStatusTone(status: string): WecomTone {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'RETRYABLE') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function crmTagStatusLabel(status: string): string {
  return CRM_STATUS_LABELS[status] ?? status
}

async function loadMappings() {
  if (!canView.value) return
  mappingsLoading.value = true
  mappingsError.value = ''
  try {
    overview.value = await getWecomTagMappings()
  } catch (error) {
    overview.value = null
    mappingsError.value = tradingErrorText(error)
  } finally {
    mappingsLoading.value = false
  }
}

async function loadRuns() {
  if (!canView.value) return
  runsLoading.value = true
  runsError.value = ''
  try {
    runs.value = await listWecomTagSyncRuns()
  } catch (error) {
    runs.value = []
    runsError.value = tradingErrorText(error)
  } finally {
    runsLoading.value = false
  }
}

async function loadTasks() {
  if (!canMonitor.value) return
  tasksLoading.value = true
  tasksError.value = ''
  try {
    tasks.value = await listWecomTagPushTasks(taskStatusFilter.value || undefined)
  } catch (error) {
    tasks.value = []
    tasksError.value = tradingErrorText(error)
  } finally {
    tasksLoading.value = false
  }
}

async function loadAll() {
  const jobs: Promise<void>[] = [loadMappings(), loadRuns()]
  if (canMonitor.value) jobs.push(loadTasks())
  await Promise.allSettled(jobs)
}

function reportRunResult(prefix: string, run: WecomTagSyncRun) {
  const summary = `拉取 ${run.pulledCount}，推送 ${run.pushedCount}，跳过 ${run.skippedCount}，失败 ${run.failedCount}`
  if (run.status === 'ERROR' || run.status === 'PARTIAL' || run.failedCount > 0) {
    ElMessage.warning(`${prefix}完成（${runStatusLabel(run.status)}）：${summary}${run.errorMasked ? `；${run.errorMasked}` : ''}`)
    return
  }
  ElMessage.success(`${prefix}成功：${summary}`)
}

async function runCatalogSync(direction: WecomTagSyncDirection) {
  if (!canSyncCatalog.value || catalogSyncing.value) return
  catalogSyncing.value = true
  try {
    const run = await syncWecomTagCatalog(direction, createWecomKey(`wecom.tag.catalog.${direction.toLowerCase()}`))
    reportRunResult(`标签库${DIRECTION_LABELS[direction] ?? '同步'}`, run)
    await loadAll()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    catalogSyncing.value = false
  }
}

async function runCustomerSync(direction: WecomTagSyncDirection) {
  if (!canMonitor.value || customerSyncing.value) return
  customerSyncing.value = true
  try {
    const run = await syncWecomCustomerTags(direction, createWecomKey(`wecom.tag.customer.${direction.toLowerCase()}`))
    reportRunResult(`客户标签${DIRECTION_LABELS[direction] ?? '同步'}`, run)
    await loadAll()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    customerSyncing.value = false
  }
}

async function unlinkMapping(row: WecomTagMappingItem) {
  if (!canManageMappings.value || unlinkingCode.value) return
  try {
    await ElMessageBox.confirm(
      `解除 CRM 标签「${row.crmTagLabel}」与企微标签「${row.wecomTagName || row.wecomTagId || ''}」的映射关系后，双方历史标签不会被删除，后续同步不再自动关联。确认解除？`,
      '解除企微标签映射',
      { type: 'warning', confirmButtonText: '确认解除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  unlinkingCode.value = row.crmTagCode
  try {
    await updateWecomTagMapping(row.crmTagCode, { action: 'UNLINK' })
    ElMessage.success('企微标签映射已解除')
    await loadMappings()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    unlinkingCode.value = null
  }
}

async function retryTask(task: WecomTagPushTask) {
  if (!canMonitor.value || retryingTaskId.value !== null) return
  retryingTaskId.value = task.id
  try {
    const updated = await retryWecomTagPushTask(task.id)
    if (updated.status === 'SUCCEEDED') {
      ElMessage.success(`推送任务 #${task.id} 重试成功`)
    } else {
      ElMessage.warning(`推送任务 #${task.id} 已重跑，当前状态：${taskStatusLabel(updated.status)}${updated.errorMasked ? `（${updated.errorMasked}）` : ''}`)
    }
    await loadTasks()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    retryingTaskId.value = null
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <section class="wecom-tag-sync-panel" data-testid="wecom-tag-sync-panel">
    <el-card shadow="never" class="wecom-control-card">
      <div class="wecom-section-head">
        <div>
          <div class="tag-sync-title-row">
            <h3>企业微信标签双向同步</h3>
            <StatusPill
              v-if="overview"
              :text="modePillText"
              :tone="modePillTone"
              data-testid="wecom-tag-mode-pill"
            />
          </div>
          <p v-if="overview" data-testid="wecom-tag-adapter-meta">
            当前适配器：<strong>{{ overview.adapterName }}</strong> · 企业标识：<strong>{{ overview.corpId }}</strong> · 仅通过企业微信官方接口同步企业标签，绝不接入个人微信。
          </p>
          <p v-else>
            仅通过企业微信官方客户标签接口进行双向同步，绝不接入个人微信；未在映射表中的 CRM 内部标签在从企微拉取时保持原样。
          </p>
        </div>
        <el-button size="small" :loading="mappingsLoading || runsLoading || tasksLoading" data-testid="wecom-tag-refresh-btn" @click="loadAll">
          刷新
        </el-button>
      </div>

      <div v-if="canSyncCatalog || canMonitor" class="tag-sync-actions">
        <div v-if="canSyncCatalog" class="tag-sync-action-group" data-testid="wecom-tag-catalog-actions">
          <span class="action-group-label">标签库同步</span>
          <el-button type="primary" size="small" :loading="catalogSyncing" data-testid="wecom-tag-catalog-bidirectional" @click="runCatalogSync('BIDIRECTIONAL')">
            双向同步
          </el-button>
          <el-button size="small" :loading="catalogSyncing" data-testid="wecom-tag-catalog-pull" @click="runCatalogSync('PULL')">
            从企微拉取
          </el-button>
          <el-button size="small" :loading="catalogSyncing" data-testid="wecom-tag-catalog-push" @click="runCatalogSync('PUSH')">
            推送至企微
          </el-button>
        </div>
        <div v-if="canMonitor" class="tag-sync-action-group" data-testid="wecom-tag-customer-actions">
          <span class="action-group-label">客户标签批量同步</span>
          <el-button type="primary" plain size="small" :loading="customerSyncing" data-testid="wecom-tag-customer-bidirectional" @click="runCustomerSync('BIDIRECTIONAL')">
            双向同步
          </el-button>
          <el-button size="small" :loading="customerSyncing" data-testid="wecom-tag-customer-pull" @click="runCustomerSync('PULL')">
            从企微拉取
          </el-button>
          <el-button size="small" :loading="customerSyncing" data-testid="wecom-tag-customer-push" @click="runCustomerSync('PUSH')">
            推送至企微
          </el-button>
        </div>
      </div>
      <p v-else class="wecom-readonly-note" data-testid="wecom-tag-readonly-note">
        当前账号为只读视图；标签库同步需具备标签管理或企微监管权限，客户标签批量同步与推送队列重试需具备企微监管权限。
      </p>
    </el-card>

    <div class="tag-sync-section">
      <div class="wecom-section-head">
        <div>
          <h3>CRM ↔ 企微标签映射明细</h3>
          <p>展示全部 CRM 客户标签与企业微信企业标签的对应关系；从企微拉取不会删除历史停用或未映射的 CRM 标签。</p>
        </div>
      </div>
      <el-alert v-if="mappingsError" type="error" :closable="false" :title="`标签映射加载失败：${mappingsError}`" class="wecom-table-alert" data-testid="wecom-tag-mappings-error" />
      <app-table v-loading="mappingsLoading" :data="overview?.items ?? []" row-key="crmTagCode" size="small">
        <el-table-column prop="crmTagLabel" label="CRM 标签名" min-width="140" />
        <el-table-column prop="crmTagCode" label="编码" min-width="140" />
        <el-table-column label="CRM 状态" width="95">
          <template #default="{ row }">{{ crmTagStatusLabel(row.crmTagStatus) }}</template>
        </el-table-column>
        <el-table-column label="企微标签组" min-width="140">
          <template #default="{ row }">{{ row.wecomGroupName || row.wecomGroupId || '-' }}</template>
        </el-table-column>
        <el-table-column label="企微标签名" min-width="140">
          <template #default="{ row }">{{ row.wecomTagName || '-' }}</template>
        </el-table-column>
        <el-table-column label="企微 TagID" min-width="140">
          <template #default="{ row }">{{ row.wecomTagId || '-' }}</template>
        </el-table-column>
        <el-table-column label="映射状态" width="105">
          <template #default="{ row }">
            <StatusPill :text="mappingStatusLabel(row.syncStatus)" :tone="mappingStatusTone(row.syncStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="最近同步时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.lastSyncedAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canManageMappings" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.syncStatus === 'ACTIVE' && row.wecomTagId"
              text
              type="warning"
              size="small"
              :loading="unlinkingCode === row.crmTagCode"
              :data-testid="`wecom-tag-unlink-${row.crmTagCode}`"
              @click="unlinkMapping(row)"
            >
              解除映射
            </el-button>
            <span v-else class="wecom-readonly-note">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无标签映射记录" />
        </template>
      </app-table>
    </div>

    <div class="tag-sync-section">
      <div class="wecom-section-head">
        <div>
          <h3>最近标签同步批次记录</h3>
          <p>记录每次标签库与客户标签同步的方向、来源、结果计数与脱敏错误，不泄露企微密钥或原始令牌。</p>
        </div>
      </div>
      <el-alert v-if="runsError" type="error" :closable="false" :title="`同步批次加载失败：${runsError}`" class="wecom-table-alert" data-testid="wecom-tag-runs-error" />
      <app-table v-loading="runsLoading" :data="runs" row-key="id" size="small">
        <el-table-column label="同步范围" width="100">
          <template #default="{ row }">{{ SYNC_SCOPE_LABELS[row.syncScope] ?? row.syncScope }}</template>
        </el-table-column>
        <el-table-column label="方向" width="110">
          <template #default="{ row }">{{ DIRECTION_LABELS[row.direction] ?? row.direction }}</template>
        </el-table-column>
        <el-table-column label="触发来源" width="100">
          <template #default="{ row }">{{ TRIGGER_SOURCE_LABELS[row.triggerSource] ?? row.triggerSource }}</template>
        </el-table-column>
        <el-table-column prop="adapterName" label="适配器" min-width="150" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusPill :text="runStatusLabel(row.status)" :tone="runStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="同步计数（拉/推/跳/败）" min-width="180">
          <template #default="{ row }">
            拉取 {{ row.pulledCount }} / 推送 {{ row.pushedCount }} / 跳过 {{ row.skippedCount }} / 失败 {{ row.failedCount }}
          </template>
        </el-table-column>
        <el-table-column label="脱敏错误" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMasked" class="wecom-error-text">{{ row.errorMasked }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="开始 / 结束时间" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.startedAt) }}
            <div class="wecom-cell-sub">{{ formatDateTime(row.finishedAt) }}</div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无标签同步批次记录" />
        </template>
      </app-table>
    </div>

    <div v-if="canMonitor" class="tag-sync-section" data-testid="wecom-tag-tasks-section">
      <div class="wecom-section-head">
        <div>
          <h3>CRM → 企微推送任务队列</h3>
          <p>客户在 CRM 中修改标签后自动入队推送至已关联活跃企微联系人，支持失败与待重试任务人工重跑。</p>
        </div>
        <div class="task-filter-bar">
          <el-select
            v-model="taskStatusFilter"
            clearable
            placeholder="全部状态"
            size="small"
            style="width: 130px"
            data-testid="wecom-tag-task-status-filter"
            @change="loadTasks"
          >
            <el-option label="待执行" value="PENDING" />
            <el-option label="执行中" value="PROCESSING" />
            <el-option label="已成功" value="SUCCEEDED" />
            <el-option label="待重试" value="RETRYABLE" />
            <el-option label="失败" value="FAILED" />
          </el-select>
          <el-button size="small" :loading="tasksLoading" @click="loadTasks">刷新队列</el-button>
        </div>
      </div>
      <el-alert v-if="tasksError" type="error" :closable="false" :title="`推送任务加载失败：${tasksError}`" class="wecom-table-alert" data-testid="wecom-tag-tasks-error" />
      <app-table v-loading="tasksLoading" :data="tasks" row-key="id" size="small">
        <el-table-column label="客户" width="110">
          <template #default="{ row }">客户 #{{ row.customerId }}</template>
        </el-table-column>
        <el-table-column label="外部身份（脱敏）" min-width="170">
          <template #default="{ row }">
            {{ row.externalUseridMasked }}
            <div class="wecom-cell-sub">跟进成员：{{ row.wecomUserid }}</div>
          </template>
        </el-table-column>
        <el-table-column label="增 / 删标签数" width="120">
          <template #default="{ row }">+{{ row.addTagCount }} / -{{ row.removeTagCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusPill :text="taskStatusLabel(row.status)" :tone="taskStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="重试次数" width="95">
          <template #default="{ row }">{{ row.attempts }} / {{ row.maxAttempts }}</template>
        </el-table-column>
        <el-table-column label="错误摘要" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMasked" class="wecom-error-text">{{ row.errorMasked }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="95" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED' || row.status === 'RETRYABLE'"
              text
              type="primary"
              size="small"
              :loading="retryingTaskId === row.id"
              :data-testid="`wecom-tag-task-retry-${row.id}`"
              @click="retryTask(row)"
            >
              重试
            </el-button>
            <span v-else class="wecom-readonly-note">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无标签推送任务" />
        </template>
      </app-table>
    </div>
  </section>
</template>

<style scoped>
.wecom-tag-sync-panel {
  display: grid;
  gap: 18px;
}

.tag-sync-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.tag-sync-title-row h3 {
  margin: 0;
}

.tag-sync-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14px 24px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e5eeec;
}

.tag-sync-action-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.action-group-label {
  color: #365756;
  font-size: 12px;
  font-weight: 700;
}

.tag-sync-section {
  display: grid;
  gap: 8px;
}

.task-filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
