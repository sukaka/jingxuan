<script setup lang="ts">
import type { YouzanSyncIssue } from '../../api/youzan'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { extractUnmappedExternalSkuId, issueStatusLabel, issueStatusTone, issueTypeLabel } from './youzanUi'

const props = withDefaults(defineProps<{
  issues: YouzanSyncIssue[]
  loading: boolean
  canWrite: boolean
  processingId: number | null
  mappedSkuIds?: Set<string>
  batchRetrying?: boolean
}>(), {
  mappedSkuIds: () => new Set<string>(),
  batchRetrying: false,
})

const emit = defineEmits<{
  retry: [issue: YouzanSyncIssue]
  mapSku: [externalSkuId: string]
}>()

function unmappedSkuIdOf(row: YouzanSyncIssue): string | null {
  if (row.issueType !== 'MAPPING_FAILED' || row.status === 'RESOLVED' || row.status === 'DEAD') return null
  return extractUnmappedExternalSkuId(row.detailMasked) ?? extractUnmappedExternalSkuId(row.lastError)
}
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
    <el-table-column label="操作" width="185" fixed="right">
      <template #default="{ row }">
        <span v-if="canWrite && row?.id != null && row.status !== 'RESOLVED' && row.status !== 'DEAD'">
          <el-button
            v-if="unmappedSkuIdOf(row) && !props.mappedSkuIds.has(unmappedSkuIdOf(row)!)"
            text
            size="small"
            type="warning"
            data-testid="issue-map-sku"
            @click="emit('mapSku', unmappedSkuIdOf(row)!)"
          >
            去配映射
          </el-button>
          <span
            v-else-if="unmappedSkuIdOf(row) && props.mappedSkuIds.has(unmappedSkuIdOf(row)!)"
            class="youzan-mapped-hint"
            data-testid="issue-sku-mapped-hint"
          >
            已配映射
          </span>
          <el-button
            text
            size="small"
            type="primary"
            :loading="processingId === row.id"
            :disabled="props.batchRetrying || (processingId !== null && processingId !== row.id)"
            data-testid="issue-retry"
            @click="emit('retry', row)"
          >
            补偿重试
          </el-button>
        </span>
        <span v-else class="crm-muted">仅留痕</span>
      </template>
    </el-table-column>
    <template #empty><el-empty description="当前状态没有同步异常" /></template>
  </app-table>
</template>
