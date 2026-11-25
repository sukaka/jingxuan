<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Project } from '../api/catalog'
import { listProjects } from '../api/catalog'
import type { CustomerSegment, CustomerSegmentBatch, CustomerSegmentFilters } from '../api/customerSegment'
import { listCustomerSegments } from '../api/customerSegment'
import type {
  BatchTagItemStatus,
  BatchTagJobDetail,
  BatchTagJobStatus,
  BatchTagOperation,
  BatchTagPreview,
  BatchTagPreviewInput,
  BatchTagSourceInput,
  BatchTagSourceType,
} from '../api/batchTagJob'
import {
  createBatchTagJob,
  createBatchTagOperationBatch,
  executeBatchTagJob,
  getBatchTagJob,
  listBatchTagJobs,
  previewBatchTagJob,
  retryBatchTagJob,
  rollbackBatchTagJob,
} from '../api/batchTagJob'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { customerTagLabel, useDictionaries } from './customer/shared'

const auth = useAuthStore()
const dicts = useDictionaries()
const canManage = computed(() => auth.user?.userType !== 'INSTITUTION' && auth.hasPermission('customer:write'))
const jobs = ref<BatchTagJobDetail['job'][]>([])
const segments = ref<CustomerSegment[]>([])
const projects = ref<Project[]>([])
const preview = ref<BatchTagPreview | null>(null)
const detail = ref<BatchTagJobDetail | null>(null)
const operationBatch = ref<CustomerSegmentBatch | null>(null)
const pendingAction = ref('')
const errorMessage = ref('')
const statusFilter = ref<BatchTagJobStatus | ''>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const itemPage = ref(1)
const operationKeys = new Map<string, string>()
const tiers = ['S', 'A', 'B', 'C', 'D']
const statuses: BatchTagJobStatus[] = ['PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL_FAILED', 'FAILED']

const OPERATION_LABELS: Record<BatchTagOperation, string> = {
  ADD: '批量添加标签',
  REMOVE: '批量移除标签',
}
const SOURCE_TYPE_LABELS: Record<BatchTagSourceType, string> = {
  FILTER: '筛选条件',
  SEGMENT: '动态分组',
  OPERATION_BATCH: '操作批次',
  ROLLBACK: '撤销任务',
}
const JOB_STATUS_LABELS: Record<BatchTagJobStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '执行失败',
}
const ITEM_STATUS_LABELS: Record<BatchTagItemStatus, string> = {
  PENDING: '待处理',
  SUCCEEDED: '已更新',
  NO_CHANGE: '无需变更',
  SKIPPED: '已跳过',
  FAILED: '失败',
}
const FAILURE_CODE_LABELS: Record<string, string> = {
  TAG_DICTIONARY_INACTIVE: '标签已停用或不存在',
  PERMISSION_REVOKED: '无权操作该客户',
  CUSTOMER_NOT_FOUND: '客户不存在或不可见',
  MERGE_CONFLICT: '客户已合并归档',
  OWNERSHIP_OUT_OF_SCOPE: '客户归属已变更',
  ITEM_EXECUTION_FAILED: '标签更新失败',
}
const SCOPE_TYPE_LABELS: Record<string, string> = {
  ALL: '全部客户',
  TEAM: '所属团队客户',
  SELF: '本人名下客户',
  NONE: '无可见客户',
}

function operationLabel(op: string): string {
  return OPERATION_LABELS[op as BatchTagOperation] ?? '批量打标'
}

function sourceTypeLabel(type: string): string {
  return SOURCE_TYPE_LABELS[type as BatchTagSourceType] ?? '其他来源'
}

function jobStatusLabel(status: string): string {
  return JOB_STATUS_LABELS[status as BatchTagJobStatus] ?? '未知状态'
}

function itemStatusLabel(status: string): string {
  return ITEM_STATUS_LABELS[status as BatchTagItemStatus] ?? '未知状态'
}

function failureCodeLabel(code: string): string {
  return FAILURE_CODE_LABELS[code] ?? '执行异常'
}

function tagDisplayLabel(code: string): string {
  const hit = dicts.options('customerTags').find((item) => item.code === code)
  if (hit) return hit.label
  const fallback = customerTagLabel(code)
  return fallback !== '其他标签' ? fallback : `${code}（已停用）`
}

function formatTagList(codes: string[] | null | undefined): string {
  if (!codes?.length) return '-'
  return codes.map(tagDisplayLabel).join('、')
}

function scopeSummary(scope: Record<string, unknown> | null | undefined): string {
  if (!scope) return '—'
  const scopeType = typeof scope.type === 'string' ? (SCOPE_TYPE_LABELS[scope.type] ?? '指定范围') : '指定范围'
  const teamIds = Array.isArray(scope.teamIds) && scope.teamIds.length ? `（团队 #${scope.teamIds.join('、#')}）` : ''
  return `${scopeType}${teamIds}`
}

function sourceSummaryItems(job: BatchTagJobDetail['job']): { label: string; value: string }[] {
  const source = job.sourceSnapshot ?? {}
  const items: { label: string; value: string }[] = [
    { label: '来源方式', value: sourceTypeLabel(job.sourceType) },
    { label: '权限范围', value: scopeSummary(job.scopeSnapshot) },
  ]
  if (typeof source.name === 'string' && source.name) {
    items.push({ label: '动态分组', value: source.name })
  }
  if (typeof source.batchNo === 'string' && source.batchNo) {
    items.push({ label: '操作批次号', value: source.batchNo })
  }
  if (typeof source.originalJobNo === 'string' && source.originalJobNo) {
    items.push({ label: '原任务编号', value: source.originalJobNo })
  }
  if (typeof source.originalOperation === 'string' && source.originalOperation) {
    items.push({ label: '原操作类型', value: operationLabel(source.originalOperation) })
  }
  const rawFilters = source.filters as Partial<CustomerSegmentFilters> | undefined
  if (rawFilters && typeof rawFilters === 'object') {
    const parts: string[] = []
    if (rawFilters.cityCodes?.length) {
      parts.push(`城市：${rawFilters.cityCodes.map((code) => dicts.label('cities', code)).join('、')}`)
    }
    if (rawFilters.tierCodes?.length) {
      parts.push(`等级：${rawFilters.tierCodes.map((code) => `${code} 级`).join('、')}`)
    }
    const projectText = (ids?: number[]) => (
      ids?.length ? ids.map((id) => projects.value.find((p) => p.id === id)?.projectName ?? `#${id}`).join('、') : ''
    )
    if (rawFilters.attentionProjectIds?.length) parts.push(`关注项目：${projectText(rawFilters.attentionProjectIds)}`)
    if (rawFilters.purchasedProjectIds?.length) parts.push(`已购项目：${projectText(rawFilters.purchasedProjectIds)}`)
    if (rawFilters.redeemedProjectIds?.length) {
      parts.push(`净核销项目：${projectText(rawFilters.redeemedProjectIds)}（至少 ${rawFilters.redeemedMinCount ?? 1} 次）`)
    }
    items.push({ label: '筛选条件', value: parts.length ? parts.join('；') : '不限条件（当前可见范围全部客户）' })
  }
  return items
}

const form = reactive<{
  operation: BatchTagOperation
  tagCodes: string[]
  sourceType: Exclude<BatchTagSourceType, 'ROLLBACK'>
  segmentId: number | null
  operationBatchId: number | null
  filters: CustomerSegmentFilters
}>({
  operation: 'ADD', tagCodes: [], sourceType: 'FILTER', segmentId: null, operationBatchId: null,
  filters: emptyFilters(),
})

const processedCount = computed(() => {
  const job = detail.value?.job
  return job ? job.succeededCount + job.noChangeCount + job.skippedCount + job.failedCount : 0
})
const progressPercent = computed(() => {
  const job = detail.value?.job
  if (!job || job.totalCount === 0) return 0
  return Math.floor(processedCount.value * 100 / job.totalCount)
})
const selectedSegment = computed(() => segments.value.find((item) => item.id === form.segmentId) ?? null)

function emptyFilters(): CustomerSegmentFilters {
  return {
    cityCodes: [], tierCodes: [], attentionProjectIds: [], purchasedProjectIds: [],
    redeemedProjectIds: [], redeemedMinCount: 1,
  }
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function operationKey(action: string, payload: string): string {
  const mapKey = `${action}:${payload}`
  const existing = operationKeys.get(mapKey)
  if (existing) return existing
  const created = crypto.randomUUID()
  operationKeys.set(mapKey, created)
  return created
}

function invalidatePreview() {
  preview.value = null
}

watch(form, invalidatePreview, { deep: true })

function sourceInput(): BatchTagSourceInput {
  if (form.sourceType === 'SEGMENT') {
    if (!form.segmentId) throw new Error('请选择动态分组')
    return { type: 'SEGMENT', id: form.segmentId }
  }
  if (form.sourceType === 'OPERATION_BATCH') {
    if (!form.operationBatchId) throw new Error('请填写或生成操作批次')
    return { type: 'OPERATION_BATCH', id: form.operationBatchId }
  }
  return { type: 'FILTER', filters: { ...form.filters } }
}

async function runPreview() {
  if (!canManage.value || pendingAction.value) return
  if (!form.tagCodes.length) {
    errorMessage.value = '请至少选择一个标签'
    return
  }
  pendingAction.value = 'preview'
  errorMessage.value = ''
  try {
    const input: BatchTagPreviewInput = {
      operation: form.operation, tagCodes: [...form.tagCodes], source: sourceInput(), page: 1, size: 20,
    }
    preview.value = await previewBatchTagJob(input,
      operationKey('preview', JSON.stringify(input)))
  } catch (error) {
    preview.value = null
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

async function createJob() {
  const current = preview.value
  if (!current || !current.total || pendingAction.value) return
  pendingAction.value = 'create'
  errorMessage.value = ''
  try {
    detail.value = await createBatchTagJob(current.previewId, operationKey('create', current.previewId))
    itemPage.value = 1
    ElMessage.success('任务已按预览结果创建，确认后可执行')
    await loadJobs()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

async function createSnapshot() {
  const segment = selectedSegment.value
  if (!segment || pendingAction.value) return
  pendingAction.value = 'snapshot'
  errorMessage.value = ''
  try {
    operationBatch.value = await createBatchTagOperationBatch(segment.id, segment.version,
      operationKey('source-snapshot', `${segment.id}:${segment.version}`))
    form.operationBatchId = operationBatch.value.id
    ElMessage.success(`操作批次已生成，共锁定 ${operationBatch.value.memberCount} 位客户，可继续预览`)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

async function loadJobs() {
  pendingAction.value = 'list'
  try {
    const result = await listBatchTagJobs({
      status: statusFilter.value || undefined, page: page.value, size: size.value,
    })
    jobs.value = result?.items ?? []
    total.value = result?.total ?? 0
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

async function openDetail(id: number, targetPage = 1) {
  pendingAction.value = 'detail'
  errorMessage.value = ''
  try {
    detail.value = await getBatchTagJob(id, targetPage, 100)
    itemPage.value = detail.value.items.page
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

async function executeCurrent(retry = false) {
  const job = detail.value?.job
  if (!job || pendingAction.value) return
  const action = retry ? 'retry' : 'execute'
  pendingAction.value = action
  errorMessage.value = ''
  try {
    const key = operationKey(action, `${job.id}:${job.version}`)
    detail.value = retry
      ? await retryBatchTagJob(job.id, job.version, key)
      : await executeBatchTagJob(job.id, job.version, key)
    itemPage.value = 1
    await loadJobs()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

async function rollbackCurrent() {
  const job = detail.value?.job
  if (!job || pendingAction.value) return
  pendingAction.value = 'rollback'
  errorMessage.value = ''
  try {
    detail.value = await rollbackBatchTagJob(job.id, job.version,
      operationKey('rollback', `${job.id}:${job.version}`))
    itemPage.value = 1
    ElMessage.success('已创建撤销任务；原任务与历史记录保持不变，核对明细后可手动执行')
    await loadJobs()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingAction.value = ''
  }
}

function changeItemPage(target: number) {
  if (detail.value) void openDetail(detail.value.job.id, target)
}

onMounted(() => {
  void dicts.load('cities', 'customerTiers', 'customerTags')
  void loadJobs()
  const jobId = Number(new URLSearchParams(window.location.search).get('jobId'))
  if (Number.isSafeInteger(jobId) && jobId > 0) void openDetail(jobId)
  void listCustomerSegments({ page: 1, size: 100 }).then((result) => {
    segments.value = result.items
  }).catch((error) => { errorMessage.value = `动态分组加载失败：${errorText(error)}` })
  void listProjects({ page: 1, size: 100 }).then((result) => {
    projects.value = result.items
  }).catch((error) => { errorMessage.value = `项目选项加载失败：${errorText(error)}` })
})
</script>

<template>
  <div class="proto-page batch-tag-page">
    <PageHeader
      title="批量重打标"
      description="预览并锁定目标客户范围后执行批量添加或移除标签，不会覆盖客户其他已有标签，执行前自动校验权限与归属关系"
    />
    <el-alert v-if="!canManage" title="当前账号没有批量重打标权限；机构账号不可操作" type="warning" :closable="false" />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" role="alert" />

    <PageCard title="1. 预览并锁定目标客户" subtitle="添加标签仅支持当前启用的客户标签；移除标签可清理历史或已停用标签，未涉及的其他标签将自动保留">
      <el-form v-if="canManage" label-width="110px" class="tag-job-form">
        <div class="form-grid">
          <el-form-item label="操作" required>
            <el-radio-group v-model="form.operation" data-testid="operation-select">
              <el-radio-button value="ADD">批量添加标签</el-radio-button>
              <el-radio-button value="REMOVE">批量移除标签</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="客户标签" required>
            <el-select
              v-model="form.tagCodes" multiple filterable :allow-create="form.operation === 'REMOVE'"
              default-first-option data-testid="tag-select"
              :placeholder="form.operation === 'ADD' ? '请选择要添加的客户标签' : '请选择或输入要移除的客户标签'"
            >
              <el-option
                v-for="item in dicts.options('customerTags')" :key="item.code"
                :label="item.label" :value="item.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="客户来源" required>
            <el-radio-group v-model="form.sourceType" data-testid="source-type-select">
              <el-radio-button value="FILTER">筛选条件</el-radio-button>
              <el-radio-button value="SEGMENT">动态分组</el-radio-button>
              <el-radio-button value="OPERATION_BATCH">操作批次</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.sourceType !== 'FILTER'" label="动态分组" required>
            <el-select v-model="form.segmentId" filterable placeholder="请选择动态分组" data-testid="segment-select">
              <el-option v-for="item in segments" :key="item.id" :label="`${item.name}（第 ${item.version} 版）`" :value="item.id" />
            </el-select>
          </el-form-item>
        </div>

        <div v-if="form.sourceType === 'FILTER'" class="filter-grid">
          <el-form-item label="城市">
            <el-select v-model="form.filters.cityCodes" multiple clearable filterable>
              <el-option v-for="item in dicts.options('cities')" :key="item.code" :label="item.label" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="当前等级">
            <el-select v-model="form.filters.tierCodes" multiple clearable>
              <el-option v-for="tier in tiers" :key="tier" :label="dicts.label('customerTiers', tier)" :value="tier" />
            </el-select>
          </el-form-item>
          <el-form-item label="关注项目">
            <el-select v-model="form.filters.attentionProjectIds" multiple clearable filterable>
              <el-option v-for="item in projects" :key="item.id" :label="item.projectName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="已购项目">
            <el-select v-model="form.filters.purchasedProjectIds" multiple clearable filterable>
              <el-option v-for="item in projects" :key="item.id" :label="item.projectName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="净核销项目">
            <el-select v-model="form.filters.redeemedProjectIds" multiple clearable filterable>
              <el-option v-for="item in projects" :key="item.id" :label="item.projectName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="净核销至少">
            <el-input-number v-model="form.filters.redeemedMinCount" :min="1" :max="10000" />
          </el-form-item>
        </div>

        <div v-if="form.sourceType === 'OPERATION_BATCH'" class="snapshot-row">
          <el-button :disabled="!selectedSegment" :loading="pendingAction === 'snapshot'" @click="createSnapshot">
            从所选分组生成操作批次
          </el-button>
          <el-input-number v-model="form.operationBatchId" :min="1" :controls="false" placeholder="请输入操作批次 ID" />
          <span v-if="operationBatch">{{ operationBatch.batchNo }} · 已锁定 {{ operationBatch.memberCount }} 位客户</span>
        </div>
        <div class="action-row">
          <el-button type="primary" :loading="pendingAction === 'preview'" data-testid="preview-tag-job" @click="runPreview">
            预览目标客户
          </el-button>
          <el-button
            :disabled="!preview || preview.total === 0" :loading="pendingAction === 'create'"
            data-testid="create-tag-job" @click="createJob"
          >
            确认并创建任务
          </el-button>
          <span v-if="preview" data-testid="preview-summary">已锁定目标客户 {{ preview.total }} 人 · 预览单号 {{ preview.previewId }}</span>
        </div>
      </el-form>
      <el-table v-if="preview" :data="preview.items" size="small" max-height="300">
        <el-table-column prop="customerNo" label="客户编号" min-width="140" />
        <el-table-column label="客户" min-width="140"><template #default="{ row }">{{ row.customerName ?? '未命名客户' }}</template></el-table-column>
        <el-table-column prop="ownerStaffId" label="归属管家 ID" width="120" />
        <el-table-column prop="teamId" label="团队 ID" width="100" />
      </el-table>
    </PageCard>

    <PageCard title="2. 任务列表" subtitle="任务创建后将保留提交人、客户范围、标签与来源记录，执行前不会修改客户标签">
      <div class="list-toolbar">
        <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="page = 1; loadJobs()">
          <el-option v-for="status in statuses" :key="status" :label="jobStatusLabel(status)" :value="status" />
        </el-select>
        <el-button :loading="pendingAction === 'list'" @click="loadJobs">刷新</el-button>
      </div>
      <el-table v-loading="pendingAction === 'list'" :data="jobs">
        <el-table-column prop="jobNo" label="任务编号" min-width="190" />
        <el-table-column label="操作 / 标签" min-width="200">
          <template #default="{ row }">{{ operationLabel(row.operation) }} · {{ formatTagList(row.tagCodes) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="140">
          <template #default="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">{{ jobStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="进度" min-width="180">
          <template #default="{ row }">
            成功 {{ row.succeededCount }} / 无需变更 {{ row.noChangeCount }} / 跳过 {{ row.skippedCount }} / 失败 {{ row.failedCount }}，共 {{ row.totalCount }}
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }"><el-button text :data-testid="`tag-job-detail-${row.id}`" @click="openDetail(row.id)">详情</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="loadJobs"
      />
    </PageCard>

    <PageCard v-if="detail" title="3. 执行进度与逐项结果" :subtitle="`${detail.job.jobNo} · 第 ${detail.job.version} 版`">
      <div class="detail-summary">
        <el-tag>{{ operationLabel(detail.job.operation) }}</el-tag>
        <el-tag :type="detail.job.status === 'SUCCEEDED' ? 'success' : detail.job.status === 'PENDING' ? 'info' : 'warning'">
          {{ jobStatusLabel(detail.job.status) }}
        </el-tag>
        <span>标签：{{ formatTagList(detail.job.tagCodes) }}</span>
        <span>提交人 #{{ detail.job.requestedBy }}</span>
        <span>来源：{{ sourceTypeLabel(detail.job.sourceType) }}<template v-if="detail.job.sourceId"> #{{ detail.job.sourceId }}</template></span>
        <span v-if="detail.job.rollbackOfJobId">撤销任务 · 原任务 #{{ detail.job.rollbackOfJobId }}</span>
      </div>
      <el-progress :percentage="progressPercent" :status="detail.job.failedCount ? 'exception' : undefined" />
      <div class="count-line">
        已处理 {{ processedCount }} / {{ detail.job.totalCount }}（{{ progressPercent }}%）· 成功 {{ detail.job.succeededCount }} ·
        无需变更 {{ detail.job.noChangeCount }} · 跳过 {{ detail.job.skippedCount }} · 失败 {{ detail.job.failedCount }}
      </div>
      <el-alert v-if="detail.job.lastErrorMasked" :title="detail.job.lastErrorMasked" type="warning" :closable="false" />
      <div class="action-row">
        <el-button
          v-if="detail.job.status === 'PENDING' && canManage" type="primary"
          :loading="pendingAction === 'execute'" data-testid="execute-tag-job" @click="executeCurrent(false)"
        >
          执行任务
        </el-button>
        <el-button
          v-if="['PARTIAL_FAILED', 'FAILED'].includes(detail.job.status) && detail.job.failedCount > 0 && canManage"
          type="primary" :loading="pendingAction === 'retry'" data-testid="retry-tag-job" @click="executeCurrent(true)"
        >
          仅重试失败项
        </el-button>
        <el-button
          v-if="['SUCCEEDED', 'PARTIAL_FAILED'].includes(detail.job.status) && canManage"
          :loading="pendingAction === 'rollback'" data-testid="rollback-tag-job" @click="rollbackCurrent"
        >
          创建撤销任务
        </el-button>
      </div>
      <el-table :data="detail.items.items" max-height="480">
        <el-table-column prop="customerNo" label="客户编号" min-width="135" />
        <el-table-column label="客户" min-width="120"><template #default="{ row }">{{ row.customerName ?? '未命名客户' }}</template></el-table-column>
        <el-table-column label="处理标签" min-width="130"><template #default="{ row }">{{ formatTagList(row.tagCodes) }}</template></el-table-column>
        <el-table-column label="结果" width="110"><template #default="{ row }">{{ itemStatusLabel(row.status) }}</template></el-table-column>
        <el-table-column label="失败/跳过原因" min-width="260">
          <template #default="{ row }">
            <span v-if="row.failureCode" class="failure-code">{{ failureCodeLabel(row.failureCode) }}</span>
            <div v-if="row.reason" class="proto-subcell">{{ row.reason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="实际变更" min-width="150">
          <template #default="{ row }">{{ formatTagList(row.changedTagCodes) }}</template>
        </el-table-column>
        <el-table-column label="前后完整标签" min-width="240">
          <template #default="{ row }">
            <div>{{ formatTagList(row.beforeTagCodes) }}</div>
            <div class="proto-subcell">→ {{ formatTagList(row.afterTagCodes) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="attemptCount" label="尝试次数" width="90" />
      </el-table>
      <el-pagination
        v-if="detail.items.total > 100" :current-page="itemPage" :page-size="100" :total="detail.items.total"
        layout="total, prev, pager, next" @current-change="changeItemPage"
      />
      <details class="snapshot-details">
        <summary>查看任务来源与客户范围详情</summary>
        <dl class="snapshot-summary-list">
          <div v-for="entry in sourceSummaryItems(detail.job)" :key="entry.label">
            <dt>{{ entry.label }}</dt>
            <dd>{{ entry.value }}</dd>
          </div>
        </dl>
      </details>
    </PageCard>
  </div>
</template>

<style scoped>
.batch-tag-page { display: grid; gap: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 18px; }
.filter-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); column-gap: 18px; }
.el-select { width: 100%; }
.action-row, .snapshot-row, .detail-summary, .list-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.action-row { margin: 16px 0; }
.snapshot-row { margin: 8px 0 16px; color: #667085; }
.list-toolbar { margin-bottom: 14px; }
.list-toolbar .el-select { width: 220px; }
.detail-summary { margin-bottom: 14px; color: #475467; }
.count-line { margin: 8px 0 14px; color: #475467; }
.failure-code { color: #b42318; font-weight: 600; }
.proto-subcell { margin-top: 4px; }
.snapshot-details { margin-top: 16px; }
.snapshot-summary-list { display: grid; gap: 8px; margin: 12px 0 0; padding: 12px 14px; border-radius: 8px; background: #f8fafc; font-size: 13px; }
.snapshot-summary-list > div { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 12px; }
.snapshot-summary-list dt { color: #667085; }
.snapshot-summary-list dd { margin: 0; color: #1d2939; overflow-wrap: anywhere; }
.el-pagination { margin-top: 14px; }
.action-row .el-button { margin-left: 0; }
@media (max-width: 1000px) { .filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .form-grid, .filter-grid { grid-template-columns: 1fr; } }
</style>
