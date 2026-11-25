<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  createMonthlyDispatch,
  executeMonthlyDispatch,
  previewMonthlyDispatch,
  retryMonthlyDispatch,
} from '../api/followup-automation'
import type { MonthlyDispatchBatch, MonthlyDispatchPreview } from '../api/followup-automation'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { formatDateTime } from './customer/shared'

const BATCH_STATUS_LABELS: Record<string, string> = {
  READY: '待执行',
  RUNNING: '执行中',
  COMPLETED: '已完成',
  FAILED: '执行失败',
}
const ITEM_STATUS_LABELS: Record<string, string> = {
  PENDING: '待创建',
  CREATED: '已创建任务',
  SKIPPED: '已跳过',
  FAILED: '创建失败',
}

function batchStatusLabel(status: string): string {
  return BATCH_STATUS_LABELS[status] ?? '未知状态'
}

function itemStatusLabel(status: string): string {
  return ITEM_STATUS_LABELS[status] ?? '未知状态'
}

const orderMonth = ref('')
const preview = ref<MonthlyDispatchPreview | null>(null)
const batch = ref<MonthlyDispatchBatch | null>(null)
const page = ref(1)
const pendingAction = ref('')
const errorMessage = ref('')
const operationKeys = new Map<string, string>()

const canCreate = computed(() => Boolean(preview.value?.items.length) && !pendingAction.value)
const pageCount = computed(() => Math.max(1, Math.ceil((preview.value?.total ?? 0) / 20)))

function operationKey(action: string, payload: string): string {
  const mapKey = `${action}:${payload}`
  const existing = operationKeys.get(mapKey)
  if (existing) return existing
  const created = crypto.randomUUID()
  operationKeys.set(mapKey, created)
  return created
}

function errorText(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '操作失败，请稍后重试'
}

async function runPreview(targetPage = 1) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(orderMonth.value)) {
    errorMessage.value = '请输入“年-月”格式的订单月份，例如 2026-08'
    return
  }
  pendingAction.value = 'preview'
  errorMessage.value = ''
  try {
    const result = await previewMonthlyDispatch({ orderMonth: orderMonth.value, page: targetPage, size: 20 })
    preview.value = result
    page.value = result.page
    batch.value = null
  } catch (error) { errorText(error) }
  finally { pendingAction.value = '' }
}

async function createBatch() {
  const month = preview.value?.orderMonth
  if (!month || pendingAction.value) return
  pendingAction.value = 'create'
  errorMessage.value = ''
  try {
    batch.value = await createMonthlyDispatch({ orderMonth: month }, operationKey('create', month))
  } catch (error) { errorText(error) }
  finally { pendingAction.value = '' }
}

async function executeBatch() {
  const current = batch.value
  if (!current || pendingAction.value || !['READY', 'FAILED'].includes(current.status)) return
  const action = current.status === 'FAILED' ? 'retry' : 'execute'
  pendingAction.value = action
  errorMessage.value = ''
  try {
    const key = operationKey(action, `${current.id}:${current.version}`)
    batch.value = action === 'retry'
      ? await retryMonthlyDispatch(current.id, current.version, key)
      : await executeMonthlyDispatch(current.id, current.version, key)
  } catch (error) { errorText(error) }
  finally { pendingAction.value = '' }
}

function changePage(target: number) {
  if (target >= 1 && target <= pageCount.value && target !== page.value) void runPreview(target)
}
</script>

<template>
  <div class="proto-page">
    <PageHeader title="月度回访批次" description="按下单所在自然月筛选客户，自动安排 3 个月后的回访任务；同一客户同批次仅生成一个任务并关联全部订单" />
    <PageCard title="1. 预览候选" subtitle="草稿和已取消订单不会纳入候选名单，系统将自动按权限范围筛选">
      <div class="dispatch-toolbar">
        <el-input v-model="orderMonth" placeholder="订单月份，例如 2026-08" style="width:220px" data-testid="order-month" />
        <el-button type="primary" :loading="pendingAction === 'preview'" data-testid="preview-batch" @click="runPreview(1)">预览候选</el-button>
        <span v-if="preview">预计回访月 <strong>{{ preview.dispatchMonth }}</strong> · 共 {{ preview.total }} 位客户</span>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" role="alert" />
    </PageCard>

    <PageCard title="2. 创建并执行" subtitle="先预览并确认候选名单，再手动执行派单；失败的批次可直接重试">
      <div class="batch-actions">
        <el-button :disabled="!canCreate" :loading="pendingAction === 'create'" data-testid="create-batch" @click="createBatch">创建批次</el-button>
        <el-button v-if="batch" type="primary" :disabled="!['READY','FAILED'].includes(batch.status)" :loading="['execute','retry'].includes(pendingAction)" data-testid="execute-batch" @click="executeBatch">
          {{ batch.status === 'FAILED' ? '重试失败批次' : '执行批次' }}
        </el-button>
        <span v-if="batch" class="proto-pill info">{{ batchStatusLabel(batch.status) }}</span>
        <span v-if="batch">批次 #{{ batch.id }} · 第 {{ batch.version }} 版 · 已失败 {{ batch.retryCount }} 次</span>
      </div>
      <el-alert v-if="batch?.lastErrorMasked" type="warning" :title="batch.lastErrorMasked" :closable="false" />
      <div class="proto-table-wrap">
        <app-table :data="batch?.items ?? preview?.items ?? []">
          <el-table-column label="客户" min-width="140"><template #default="{row}">{{ row.customerName }}<div class="proto-subcell">客户 #{{ row.customerId }}</div></template></el-table-column>
          <el-table-column label="关联订单" width="110"><template #default="{row}">{{ row.orderIds.length }} 笔订单</template></el-table-column>
          <el-table-column label="计划回访时间" min-width="180"><template #default="{row}">{{ formatDateTime(row.dueAt) }}</template></el-table-column>
          <el-table-column v-if="batch" label="明细状态" width="110"><template #default="{row}">{{ itemStatusLabel(row.status) }}</template></el-table-column>
          <el-table-column v-if="batch" label="任务 / 原因" min-width="170"><template #default="{row}"><span v-if="row.taskId">任务 #{{ row.taskId }}</span><div v-if="row.reason" class="proto-subcell">{{ row.reason }}</div></template></el-table-column>
          <template #empty>该月暂无候选，请调整订单月份</template>
        </app-table>
      </div>
      <div v-if="!batch && (preview?.total ?? 0) > 20" class="pagination">
        <el-button :disabled="page <= 1 || pendingAction === 'preview'" @click="changePage(page - 1)">上一页</el-button>
        <span>{{ page }} / {{ pageCount }}</span>
        <el-button :disabled="page >= pageCount || pendingAction === 'preview'" @click="changePage(page + 1)">下一页</el-button>
      </div>
    </PageCard>
  </div>
</template>

<style scoped>
.dispatch-toolbar,.batch-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px;color:#67818f;font-size:13px}.el-alert{margin-bottom:14px}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:14px}.proto-subcell{margin-top:4px}.batch-actions .el-button{margin-left:0}
</style>
