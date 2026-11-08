<script setup lang="ts">
import { computed, watch } from 'vue'
import type { RedemptionDetail } from '../../api/fulfillment'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { useReferenceNames } from '../references'
import { redemptionAmountLabel, redemptionCount, redemptionSettlementStatusLabel, redemptionStatusLabel, redemptionStatusTone } from './fulfillmentUi'

const props = defineProps<{
  modelValue: boolean
  detail: RedemptionDetail | null
  canReverse: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'request-reversal': []
}>()

const { loadCustomerNames, customerLabel, institutionLabel, projectLabel } = useReferenceNames()

const redemption = computed(() => props.detail?.redemption ?? null)

watch(redemption, (value) => {
  if (value) void loadCustomerNames([value.customerId])
}, { immediate: true })
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="redemption ? `核销记录 #${redemption.id}` : '核销详情'"
    size="min(780px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="detail && redemption">
      <div class="fulfillment-detail-head">
        <div>
          <h3 class="fulfillment-detail-title">核销记录 #{{ redemption.id }}</h3>
          <div class="crm-muted">预约 #{{ redemption.appointmentId }} · {{ customerLabel(redemption.customerId) }} · {{ institutionLabel(redemption.institutionId) }}</div>
        </div>
        <StatusPill :text="redemptionStatusLabel(redemption.status)" :tone="redemptionStatusTone(redemption.status)" />
      </div>
      <div class="fulfillment-readonly">这是不可直接编辑的原始核销事实。冲正申请不会更改原正式核销状态；审核补偿以真实补偿流水为准。</div>
      <el-descriptions :column="2" border class="trading-descriptions">
        <el-descriptions-item label="正式核销次数">{{ redemptionCount(detail) }}</el-descriptions-item>
        <el-descriptions-item label="核销金额（元）"><span class="fulfillment-pending-amount">{{ redemptionAmountLabel() }}</span></el-descriptions-item>
        <el-descriptions-item label="实际服务时间">{{ formatDateTime(redemption.serviceAt) }}</el-descriptions-item>
        <el-descriptions-item label="正式核销时间">{{ formatDateTime(redemption.postedAt) }}</el-descriptions-item>
        <el-descriptions-item label="机构确认人">{{ redemption.postedBy === null ? '-' : `用户 #${redemption.postedBy}` }}</el-descriptions-item>
        <el-descriptions-item label="凭证文件">{{ redemption.voucherFileId === null ? '-' : `文件 #${redemption.voucherFileId}` }}</el-descriptions-item>
        <el-descriptions-item label="结算状态">{{ redemptionSettlementStatusLabel(redemption.settlementStatus) }}</el-descriptions-item>
        <el-descriptions-item label="事实版本">{{ redemption.version }}</el-descriptions-item>
        <el-descriptions-item label="凭证引用" :span="2"><span class="fulfillment-evidence">{{ redemption.evidenceRef }}</span></el-descriptions-item>
        <el-descriptions-item label="幂等键" :span="2"><span class="fulfillment-evidence">{{ redemption.idempotencyKey }}</span></el-descriptions-item>
      </el-descriptions>

      <h4 class="trading-section-title">逐项目核销行</h4>
      <app-table :data="detail.lines" size="small">
        <el-table-column label="项目" min-width="130"><template #default="{ row }">{{ projectLabel(row.projectId) }}</template></el-table-column>
        <el-table-column label="权益批次" width="120"><template #default="{ row }">#{{ row.entitlementId }}</template></el-table-column>
        <el-table-column label="预约项目" width="120"><template #default="{ row }">#{{ row.appointmentItemId }}</template></el-table-column>
        <el-table-column prop="count" label="核销次数" width="100" align="right" />
      </app-table>

      <h4 class="trading-section-title">核销状态历史</h4>
      <el-timeline>
        <el-timeline-item v-for="item in detail.history" :key="item.id" :timestamp="formatDateTime(item.createdAt)">
          <strong>{{ redemptionStatusLabel(item.fromStatus) }} → {{ redemptionStatusLabel(item.toStatus) }}</strong>
          <div class="crm-muted">{{ item.actorUserId === null ? '-' : `用户 #${item.actorUserId}` }}<span v-if="item.reason"> · {{ item.reason }}</span></div>
        </el-timeline-item>
      </el-timeline>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button v-if="redemption?.status === 'POSTED' && canReverse" type="primary" data-testid="redemption-request-reversal" @click="emit('request-reversal')">申请冲正</el-button>
    </template>
  </el-drawer>
</template>
