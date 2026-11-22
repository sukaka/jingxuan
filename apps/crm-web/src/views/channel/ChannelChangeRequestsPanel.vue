<script setup lang="ts">
import type { ChannelFinancialChangeRequest } from '../../api/channel'
import StatusPill from '../customer/StatusPill.vue'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{
  requests: ChannelFinancialChangeRequest[]
  loading: boolean
  canApproveFinance: boolean
  currentUserId: number
}>()

const emit = defineEmits<{
  review: [request: ChannelFinancialChangeRequest, decision: 'APPROVE' | 'REJECT']
}>()

function statusLabel(status: string): string {
  return ({
    PENDING_REVIEW: '待财务审批',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    CANCELLED: '已取消',
    CONFLICT_REVALIDATE: '版本冲突',
  } as Record<string, string>)[status] ?? status
}

function statusTone(status: string): 'warning' | 'success' | 'danger' | 'dark' {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING_REVIEW' || status === 'CONFLICT_REVALIDATE') return 'warning'
  return 'dark'
}

function formatMaskedSummary(value: Record<string, string | number | null> | string | null | undefined): string {
  if (!value) return '—'
  if (typeof value === 'string') return value
  const entries = Object.entries(value).filter(([, v]) => v !== null && v !== undefined && v !== '')
  return entries.length ? entries.map(([k, v]) => `${k}: ${String(v)}`).join(' · ') : '—'
}

function canReview(row: ChannelFinancialChangeRequest): boolean {
  return props.canApproveFinance && row.status === 'PENDING_REVIEW' && row.requestedBy !== props.currentUserId
}
</script>

<template>
  <app-table v-loading="loading" :data="requests" row-key="id" data-testid="channel-change-requests-table">
    <el-table-column label="申请 / 渠道" min-width="180">
      <template #default="{ row }">
        <strong>#{{ row.id }} · {{ row.channelName || `渠道 #${row.channelId}` }}</strong>
        <div class="proto-subcell">{{ row.channelNo || `ID ${row.channelId}` }} · {{ formatDateTime(row.requestedAt) }}</div>
      </template>
    </el-table-column>
    <el-table-column label="变更前 → 变更后（脱敏）" min-width="260">
      <template #default="{ row }">
        <div class="proto-subcell">原：{{ formatMaskedSummary(row.beforeMasked) }}</div>
        <div class="change-after">新：{{ formatMaskedSummary(row.afterMasked) }}</div>
      </template>
    </el-table-column>
    <el-table-column prop="reason" label="变更原因" min-width="180" show-overflow-tooltip />
    <el-table-column label="申请人" min-width="120">
      <template #default="{ row }">{{ row.requestedByName || `用户 #${row.requestedBy}` }}</template>
    </el-table-column>
    <el-table-column label="审批状态" width="130">
      <template #default="{ row }">
        <StatusPill :text="statusLabel(row.status)" :tone="statusTone(row.status)" />
        <div class="proto-subcell">基准 v{{ row.baseVersion }}</div>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="180" fixed="right">
      <template #default="{ row }">
        <template v-if="canReview(row)">
          <el-button text type="primary" size="small" :data-testid="`channel-change-approve-${row.id}`" @click="emit('review', row, 'APPROVE')">通过</el-button>
          <el-button text type="danger" size="small" :data-testid="`channel-change-reject-${row.id}`" @click="emit('review', row, 'REJECT')">驳回</el-button>
        </template>
        <span v-else-if="row.status === 'PENDING_REVIEW' && row.requestedBy === currentUserId" class="proto-subcell">本人不可自审</span>
        <span v-else class="proto-subcell">{{ row.reviewComment || '—' }}</span>
      </template>
    </el-table-column>
    <template #empty>暂无财务资料变更申请</template>
  </app-table>
</template>

<style scoped>
.change-after { color: #0f948b; font-weight: 600; font-size: 12px; }
</style>
