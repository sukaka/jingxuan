<script setup lang="ts">
import { computed, watch } from 'vue'
import type { EntitlementDetail } from '../../api/trading'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { useReferenceNames } from '../references'
import {
  entitlementStatusLabel,
  entitlementStatusTone,
  formatDeltaCount,
  ledgerSourceTypeLabel,
  ledgerTxnTypeLabel,
} from './tradingUi'

const props = defineProps<{
  modelValue: boolean
  detail: EntitlementDetail | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const { loadCustomerNames, customerLabel } = useReferenceNames()

const entitlement = computed(() => props.detail?.entitlement ?? null)

watch(entitlement, (value) => {
  if (value) void loadCustomerNames([value.customerId])
}, { immediate: true })
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="entitlement ? `权益流水 · ${entitlement.projectName || `项目 #${entitlement.projectId}`}` : '权益流水详情'"
    size="min(860px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="detail && entitlement">
      <el-alert
        type="info"
        :closable="false"
        title="权益流水只追加，正式核销须由合作机构授权操作员在履约流程确认；当前不开放管家正式核销。"
        class="ledger-alert"
      />
      <div class="trading-balance-card">
        <small>权威流水汇总可用次数</small>
        <strong>{{ detail.availableBalance }}</strong>
        <span> 次</span>
      </div>
      <el-descriptions :column="3" border class="trading-descriptions">
        <el-descriptions-item label="权益批次">#{{ entitlement.id }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ customerLabel(entitlement.customerId) }}</el-descriptions-item>
        <el-descriptions-item label="来源订单行">#{{ entitlement.orderItemId }}</el-descriptions-item>
        <el-descriptions-item label="发放次数">{{ entitlement.grantedCount }}</el-descriptions-item>
        <el-descriptions-item label="同事务余额快照">{{ entitlement.availableCount }}</el-descriptions-item>
        <el-descriptions-item label="销售版本">V{{ entitlement.versionNo }}</el-descriptions-item>
        <el-descriptions-item label="生效日期">{{ entitlement.validFrom || '-' }}</el-descriptions-item>
        <el-descriptions-item label="到期日期">{{ entitlement.validTo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusPill :text="entitlementStatusLabel(entitlement.status)" :tone="entitlementStatusTone(entitlement.status)" />
        </el-descriptions-item>
      </el-descriptions>

      <h4 class="trading-section-title">权益流水（不可覆盖历史）</h4>
      <app-table :data="detail.ledger" size="small">
        <el-table-column label="记账时间" width="158">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="105">
          <template #default="{ row }">{{ ledgerTxnTypeLabel(row.txnType) }}</template>
        </el-table-column>
        <el-table-column label="变动" width="72" align="right">
          <template #default="{ row }">
            <span :class="row.deltaCount >= 0 ? 'trading-delta-positive' : 'trading-delta-negative'">{{ formatDeltaCount(row.deltaCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="135">
          <template #default="{ row }">
            <div>{{ ledgerSourceTypeLabel(row.sourceType) }}</div>
            <span class="crm-muted">{{ row.sourceRef }}</span>
          </template>
        </el-table-column>
        <el-table-column label="幂等键" min-width="190">
          <template #default="{ row }"><span class="trading-ledger-key">{{ row.idempotencyKey }}</span></template>
        </el-table-column>
        <el-table-column label="操作人 / 原因" min-width="135">
          <template #default="{ row }">
            <div>{{ row.actorUserId === null ? '-' : `用户 #${row.actorUserId}` }}</div>
            <span class="crm-muted">{{ row.reason || '-' }}</span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无流水记录，请核对后端数据" /></template>
      </app-table>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.ledger-alert { margin-bottom: 16px; }
</style>
