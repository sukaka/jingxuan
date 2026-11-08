<script setup lang="ts">
import { computed, watch } from 'vue'
import type { ReversalDetail } from '../../api/fulfillment'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { formatDeltaCount } from '../trading/tradingUi'
import { useReferenceNames } from '../references'
import { correctionTxnTypeLabel, reversalEffectLabel, reversalStatusLabel, reversalStatusTone } from './fulfillmentUi'

const props = defineProps<{
  modelValue: boolean
  detail: ReversalDetail | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const { loadCustomerNames, customerLabel, institutionLabel } = useReferenceNames()

const reversal = computed(() => props.detail?.reversal ?? null)
const corrections = computed(() => props.detail?.corrections ?? [])

watch(reversal, (value) => {
  if (value) void loadCustomerNames([value.customerId])
}, { immediate: true })
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="reversal ? `冲正申请 #${reversal.id}` : '冲正详情'"
    size="min(820px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="detail && reversal">
      <div class="fulfillment-detail-head">
        <div>
          <h3 class="fulfillment-detail-title">原核销 #{{ reversal.redemptionId }}</h3>
          <div class="crm-muted">{{ customerLabel(reversal.customerId) }} · {{ institutionLabel(reversal.institutionId) }}</div>
        </div>
        <StatusPill :text="reversalStatusLabel(reversal.status)" :tone="reversalStatusTone(reversal.status)" />
      </div>
      <div class="fulfillment-readonly">{{ reversalEffectLabel(reversal, corrections) }}。原正式核销记录始终保持只读。</div>
      <el-descriptions :column="2" border class="trading-descriptions">
        <el-descriptions-item label="申请原因" :span="2">{{ reversal.reason }}</el-descriptions-item>
        <el-descriptions-item label="申请人">用户 #{{ reversal.requestedBy }}</el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ formatDateTime(reversal.requestedAt) }}</el-descriptions-item>
        <el-descriptions-item label="审核人">{{ reversal.reviewedBy === null ? '-' : `用户 #${reversal.reviewedBy}` }}</el-descriptions-item>
        <el-descriptions-item label="审核时间">{{ formatDateTime(reversal.reviewedAt) }}</el-descriptions-item>
        <el-descriptions-item label="审核原因" :span="2">{{ reversal.reviewReason || '-' }}</el-descriptions-item>
      </el-descriptions>

      <h4 class="trading-section-title">真实补偿流水</h4>
      <app-table :data="corrections" size="small" data-testid="reversal-corrections">
        <el-table-column label="流水" width="90"><template #default="{ row }">#{{ row.ledgerId }}</template></el-table-column>
        <el-table-column label="类型" width="140"><template #default="{ row }">{{ correctionTxnTypeLabel(row.txnType) }}</template></el-table-column>
        <el-table-column label="变动次数" width="100" align="right"><template #default="{ row }">{{ formatDeltaCount(row.deltaCount) }}</template></el-table-column>
        <el-table-column label="权益批次" width="120"><template #default="{ row }">#{{ row.entitlementId }}</template></el-table-column>
        <el-table-column label="来源引用" min-width="130"><template #default="{ row }">{{ row.sourceRef }}</template></el-table-column>
        <el-table-column label="记账时间" width="160"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <template #empty><el-empty :description="reversal.status === 'APPROVED' ? '审核通过但未返回补偿流水，需进一步核对' : '当前没有补偿流水，申请本身未改变余额'" /></template>
      </app-table>

      <h4 class="trading-section-title">申请与审核历史</h4>
      <el-timeline>
        <el-timeline-item v-for="item in detail.history" :key="item.id" :timestamp="formatDateTime(item.createdAt)">
          <strong>{{ reversalStatusLabel(item.fromStatus) }} → {{ reversalStatusLabel(item.toStatus) }}</strong>
          <div class="crm-muted">{{ item.actorUserId === null ? '-' : `用户 #${item.actorUserId}` }}<span v-if="item.reason"> · {{ item.reason }}</span></div>
        </el-timeline-item>
      </el-timeline>
    </template>
    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </el-drawer>
</template>
