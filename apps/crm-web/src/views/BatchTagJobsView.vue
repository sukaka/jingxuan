<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Project } from '../api/catalog'
import { listProjects } from '../api/catalog'
import type { CustomerSegment, CustomerSegmentBatch, CustomerSegmentFilters } from '../api/customerSegment'
import { listCustomerSegments } from '../api/customerSegment'
import type {
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
import { useDictionaries } from './customer/shared'

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
    if (!form.segmentId) throw new Error('请选择 V33 动态分组')
    return { type: 'SEGMENT', id: form.segmentId }
  }
  if (form.sourceType === 'OPERATION_BATCH') {
    if (!form.operationBatchId) throw new Error('请填写或创建 V33 操作批次')
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
    ElMessage.success('任务已按固定预览快照创建，尚未执行')
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
    ElMessage.success(`V33 操作批次已固定 ${operationBatch.value.memberCount} 人，可继续预览`)
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
    ElMessage.success('已创建独立反向任务；原任务和历史结果未修改，请预览明细后显式执行')
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
  }).catch((error) => { errorMessage.value = `V33 分组加载失败：${errorText(error)}` })
  void listProjects({ page: 1, size: 100 }).then((result) => {
    projects.value = result.items
  }).catch((error) => { errorMessage.value = `项目选项加载失败：${errorText(error)}` })
})
</script>

<template>
  <div class="proto-page batch-tag-page">
    <PageHeader
      title="批量重打标"
      description="服务端预览固定成员快照；只执行增量 ADD/REMOVE，不调用全量标签替换，执行时重验权限与当前归属"
    />
    <el-alert v-if="!canManage" title="当前账号没有批量重打标权限；机构账号不可操作" type="warning" :closable="false" />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" role="alert" />

    <PageCard title="1. 预览并固定成员" subtitle="ADD 仅允许有效 customerTags 字典；REMOVE 可处理历史/停用代码，其他未知历史标签始终保留">
      <el-form v-if="canManage" label-width="110px" class="tag-job-form">
        <div class="form-grid">
          <el-form-item label="操作" required>
            <el-radio-group v-model="form.operation" data-testid="operation-select">
              <el-radio-button value="ADD">批量 ADD</el-radio-button>
              <el-radio-button value="REMOVE">批量 REMOVE</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="标签" required>
            <el-select
              v-model="form.tagCodes" multiple filterable :allow-create="form.operation === 'REMOVE'"
              default-first-option data-testid="tag-select"
              :placeholder="form.operation === 'ADD' ? '选择有效标签字典' : '选择或输入需要移除的历史标签代码'"
            >
              <el-option
                v-for="item in dicts.options('customerTags')" :key="item.code"
                :label="`${item.label}（${item.code}）`" :value="item.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="成员来源" required>
            <el-radio-group v-model="form.sourceType" data-testid="source-type-select">
              <el-radio-button value="FILTER">筛选条件</el-radio-button>
              <el-radio-button value="SEGMENT">V33 动态分组</el-radio-button>
              <el-radio-button value="OPERATION_BATCH">V33 操作批次</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.sourceType !== 'FILTER'" label="V33 分组" required>
            <el-select v-model="form.segmentId" filterable placeholder="选择动态分组" data-testid="segment-select">
              <el-option v-for="item in segments" :key="item.id" :label="`${item.name}（v${item.version}）`" :value="item.id" />
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
            固定当前分组成员为 V33 操作批次
          </el-button>
          <el-input-number v-model="form.operationBatchId" :min="1" :controls="false" placeholder="操作批次 ID" />
          <span v-if="operationBatch">{{ operationBatch.batchNo }} · {{ operationBatch.memberCount }} 人不可变快照</span>
        </div>
        <div class="action-row">
          <el-button type="primary" :loading="pendingAction === 'preview'" data-testid="preview-tag-job" @click="runPreview">
            服务端预览
          </el-button>
          <el-button
            :disabled="!preview || preview.total === 0" :loading="pendingAction === 'create'"
            data-testid="create-tag-job" @click="createJob"
          >
            按预览创建任务
          </el-button>
          <span v-if="preview" data-testid="preview-summary">固定预览成员 {{ preview.total }} 人 · {{ preview.previewId }}</span>
        </div>
      </el-form>
      <el-table v-if="preview" :data="preview.items" size="small" max-height="300">
        <el-table-column prop="customerNo" label="客户编号" min-width="140" />
        <el-table-column label="客户" min-width="140"><template #default="{ row }">{{ row.customerName ?? '未命名客户' }}</template></el-table-column>
        <el-table-column prop="ownerStaffId" label="归属管家 ID" width="120" />
        <el-table-column prop="teamId" label="团队 ID" width="100" />
      </el-table>
    </PageCard>

    <PageCard title="2. 任务列表" subtitle="请求人、范围、标签、来源和创建幂等键均保存在不可变快照中">
      <div class="list-toolbar">
        <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="page = 1; loadJobs()">
          <el-option v-for="status in statuses" :key="status" :label="status" :value="status" />
        </el-select>
        <el-button :loading="pendingAction === 'list'" @click="loadJobs">刷新</el-button>
      </div>
      <el-table v-loading="pendingAction === 'list'" :data="jobs">
        <el-table-column prop="jobNo" label="任务编号" min-width="190" />
        <el-table-column label="操作 / 标签" min-width="180">
          <template #default="{ row }">{{ row.operation }} · {{ row.tagCodes.join('、') }}</template>
        </el-table-column>
        <el-table-column prop="sourceType" label="来源" width="140" />
        <el-table-column prop="status" label="状态" width="140" />
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

    <PageCard v-if="detail" title="3. 执行进度与逐项结果" :subtitle="`${detail.job.jobNo} · version ${detail.job.version}`">
      <div class="detail-summary">
        <el-tag>{{ detail.job.operation }}</el-tag>
        <el-tag :type="detail.job.status === 'SUCCEEDED' ? 'success' : detail.job.status === 'PENDING' ? 'info' : 'warning'">
          {{ detail.job.status }}
        </el-tag>
        <span>标签 {{ detail.job.tagCodes.join('、') }}</span>
        <span>请求人 #{{ detail.job.requestedBy }}</span>
        <span>来源 {{ detail.job.sourceType }}<template v-if="detail.job.sourceId"> #{{ detail.job.sourceId }}</template></span>
        <span v-if="detail.job.rollbackOfJobId">独立回滚 · rollbackOfJobId={{ detail.job.rollbackOfJobId }}</span>
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
          创建独立回滚
        </el-button>
      </div>
      <el-table :data="detail.items.items" max-height="480">
        <el-table-column prop="customerNo" label="客户编号" min-width="135" />
        <el-table-column label="客户" min-width="120"><template #default="{ row }">{{ row.customerName ?? '未命名客户' }}</template></el-table-column>
        <el-table-column label="处理标签" min-width="130"><template #default="{ row }">{{ row.tagCodes.join('、') }}</template></el-table-column>
        <el-table-column prop="status" label="结果" width="110" />
        <el-table-column label="失败/跳过原因" min-width="260">
          <template #default="{ row }">
            <span v-if="row.failureCode" class="failure-code">{{ row.failureCode }}</span>
            <div v-if="row.reason" class="proto-subcell">{{ row.reason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="实际变更" min-width="150">
          <template #default="{ row }">{{ row.changedTagCodes?.join('、') || '-' }}</template>
        </el-table-column>
        <el-table-column label="前后完整标签" min-width="240">
          <template #default="{ row }">
            <div>{{ row.beforeTagCodes?.join('、') || '-' }}</div>
            <div class="proto-subcell">→ {{ row.afterTagCodes?.join('、') || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="attemptCount" label="尝试" width="70" />
      </el-table>
      <el-pagination
        v-if="detail.items.total > 100" :current-page="itemPage" :page-size="100" :total="detail.items.total"
        layout="total, prev, pager, next" @current-change="changeItemPage"
      />
      <details class="snapshot-details">
        <summary>查看请求、范围与来源快照</summary>
        <pre>{{ JSON.stringify({ source: detail.job.sourceSnapshot, scope: detail.job.scopeSnapshot }, null, 2) }}</pre>
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
.snapshot-details pre { overflow: auto; max-height: 260px; padding: 12px; background: #f8fafc; }
.el-pagination { margin-top: 14px; }
.action-row .el-button { margin-left: 0; }
@media (max-width: 1000px) { .filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .form-grid, .filter-grid { grid-template-columns: 1fr; } }
</style>
