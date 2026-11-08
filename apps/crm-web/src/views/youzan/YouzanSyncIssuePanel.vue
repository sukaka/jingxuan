<script setup lang="ts">
import type { YouzanSyncIssue } from '../../api/youzan'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { issueStatusLabel, issueStatusTone, issueTypeLabel } from './youzanUi'

defineProps<{
  issues: YouzanSyncIssue[]
  loading: boolean
  canWrite: boolean
  processingId: number | null
}>()
const emit = defineEmits<{ retry: [issue: YouzanSyncIssue] }>()
</script>

<template>
  <app-table v-loading="loading" :data="issues" size="small">
    <el-table-column label="异常 / 事件" width="120"><template #default="{ row }">#{{ row.id }}<div class="trading-cell-sub">事件 #{{ row.eventId }}</div></template></el-table-column>
    <el-table-column label="异常类型" width="175"><template #default="{ row }">{{ issueTypeLabel(row.issueType) }}</template></el-table-column>
    <el-table-column label="脱敏原因" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ row.detailMasked || row.lastError || '-' }}</template></el-table-column>
    <el-table-column prop="attempts" label="尝试" width="65" align="right" />
    <el-table-column label="下次重试" width="160"><template #default="{ row }">{{ formatDateTime(row.nextRetryAt) }}</template></el-table-column>
    <el-table-column label="状态" width="115"><template #default="{ row }"><StatusPill :text="issueStatusLabel(row.status)" :tone="issueStatusTone(row.status)" /></template></el-table-column>
    <el-table-column label="解决信息" min-width="145"><template #default="{ row }">{{ row.resolvedBy ? `用户 #${row.resolvedBy}` : '-' }}<div class="trading-cell-sub">{{ formatDateTime(row.resolvedAt) }}</div></template></el-table-column>
    <el-table-column label="操作" width="115" fixed="right">
      <template #default="{ row }">
        <el-button v-if="canWrite && row.status !== 'RESOLVED' && row.status !== 'DEAD'" text size="small" type="primary" :loading="processingId === row.id" data-testid="issue-retry" @click="emit('retry', row)">补偿重试</el-button>
        <span v-else class="crm-muted">仅留痕</span>
      </template>
    </el-table-column>
    <template #empty><el-empty description="当前状态没有同步异常" /></template>
  </app-table>
</template>
