<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { YouzanSyncJob } from '../../api/youzan'
import { listYouzanSyncJobs } from '../../api/youzan'
import { tradingErrorText } from '../trading/tradingUi'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{ shopId: string }>()
const jobs = ref<YouzanSyncJob[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const query = reactive({ page: 1, size: 20 })

async function load() {
  if (!props.shopId || props.shopId.startsWith('MOCK-')) {
    jobs.value = []
    total.value = 0
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await listYouzanSyncJobs({ shopId: props.shopId, page: query.page, size: query.size })
    jobs.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (cause) {
    error.value = tradingErrorText(cause)
    jobs.value = []
  } finally {
    loading.value = false
  }
}

function statusText(status: string) {
  return ({ RUNNING: '执行中', SUCCEEDED: '成功', PARTIAL: '部分异常', FAILED: '失败', PENDING: '待执行' } as Record<string, string>)[status] ?? status
}

function typeText(type: string) {
  return ({ FULL: '历史导入', INCREMENTAL: '增量同步', RECONCILE: '手动补偿', WEBHOOK: '消息补单' } as Record<string, string>)[type] ?? type
}

watch(() => props.shopId, () => { query.page = 1; void load() }, { immediate: true })
</script>

<template>
  <section>
    <div class="youzan-section-head"><div><h3>同步任务</h3><p>查看每次拉取的处理结果；部分异常不代表全部失败，可在异常队列继续处理。</p></div><el-button text :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <app-table v-loading="loading" :data="jobs" size="small">
      <el-table-column label="任务" min-width="125"><template #default="{ row }">#{{ row.id }} {{ typeText(row.jobType) }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }">{{ statusText(row.status) }}</template></el-table-column>
      <el-table-column label="接收 / 成功 / 重复 / 异常" min-width="185"><template #default="{ row }">{{ row.receivedCount }} / {{ row.processedCount }} / {{ row.duplicateCount }} / {{ row.issueCount }}</template></el-table-column>
      <el-table-column label="开始时间" min-width="150"><template #default="{ row }">{{ row.startedAt ? formatDateTime(row.startedAt) : '—' }}</template></el-table-column>
      <el-table-column label="完成时间" min-width="150"><template #default="{ row }">{{ row.finishedAt ? formatDateTime(row.finishedAt) : '—' }}</template></el-table-column>
      <el-table-column prop="lastError" label="错误摘要" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.lastError || '—' }}</template></el-table-column>
      <template #empty><el-empty description="暂无真实同步任务；保存店铺和策略后可手动拉取或等待增量调度" /></template>
    </app-table>
    <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="load" @size-change="query.page = 1; load()" />
  </section>
</template>
