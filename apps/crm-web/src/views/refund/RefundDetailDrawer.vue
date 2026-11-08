<script setup lang="ts">
import { computed } from 'vue'
import type { RefundDetail } from '../../api/refund'
import { fenToYuan } from '../../utils/money'
import StatusPill from '../customer/StatusPill.vue'
import { formatDateTime } from '../customer/shared'
import FileDownloadButton from '../institution/FileDownloadButton.vue'
import { refundStatusTone } from '../finance/financeUi'
import { providerLabel, refundPaymentStatusLabel, refundReasonLabel, refundStatusLabel, verificationMethodLabel } from './refundUi'

const props = defineProps<{
  modelValue: boolean
  detail: RefundDetail | null
  canReview: boolean
  canExecute: boolean
  canConfirmSuccess: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  review: []
  execute: []
  success: []
}>()
const refund = computed(() => props.detail?.refund ?? null)
</script>

<template>
  <el-drawer :model-value="modelValue" :title="refund ? `退款 ${refund.refundNo}` : '退款详情'" size="min(960px, 100vw)" @update:model-value="emit('update:modelValue', $event)">
    <template v-if="detail && refund">
      <div class="order-head">
        <div><div class="customer-name">订单 #{{ refund.orderId }} · 客户 #{{ refund.customerId }}</div><div class="crm-muted">申请人 #{{ refund.requestedBy }} · {{ formatDateTime(refund.createdAt) }}</div></div>
        <StatusPill :text="refundStatusLabel(refund.status)" :tone="refundStatusTone(refund.status)" />
      </div>
      <el-alert v-if="refund.status !== 'SUCCEEDED'" type="info" :closable="false" title="当前不是退款成功事实；订单成功退款额与客户可用权益不因申请、审批或执行登记而改变。" class="finance-action-note" data-testid="refund-not-succeeded" />
      <div class="finance-summary">
        <div class="finance-summary-item"><small>申请金额</small><strong>¥{{ fenToYuan(refund.requestedFen) }}</strong></div>
        <div class="finance-summary-item"><small>批准金额</small><strong>{{ refund.approvedFen > 0 ? `¥${fenToYuan(refund.approvedFen)}` : '尚未批准' }}</strong></div>
        <div class="finance-summary-item"><small>真实成功退款</small><strong>¥{{ fenToYuan(refund.succeededFen) }}</strong></div>
      </div>
      <el-descriptions :column="3" border class="trading-descriptions">
        <el-descriptions-item label="退款原因">{{ refundReasonLabel(refund.reasonCode) }}</el-descriptions-item>
        <el-descriptions-item label="退款说明">{{ refund.reasonDetail || '-' }}</el-descriptions-item>
        <el-descriptions-item label="实际成功时间">{{ formatDateTime(refund.succeededAt) }}</el-descriptions-item>
        <el-descriptions-item label="审批人">{{ refund.approvedBy === null ? '-' : `用户 #${refund.approvedBy}` }}</el-descriptions-item>
        <el-descriptions-item label="执行人">{{ refund.executedBy === null ? '-' : `用户 #${refund.executedBy}` }}</el-descriptions-item>
        <el-descriptions-item label="数据版本">{{ refund.version }}</el-descriptions-item>
      </el-descriptions>

      <section class="finance-section">
        <div class="finance-section-head"><div><h3>权益回收与现金分摊</h3><p>成功事务按以下人工决策追加回收流水，已消费项目不自动返还。</p></div></div>
        <app-table :data="detail.items" size="small">
          <el-table-column label="权益批次" min-width="130"><template #default="{ row }">权益 #{{ row.entitlementId }}</template></el-table-column>
          <el-table-column prop="revokeCount" label="拟回收次数" width="120" align="right" />
          <el-table-column label="现金分摊" min-width="150" align="right"><template #default="{ row }"><span class="finance-amount">{{ row.allocatedFen }} 分 · ¥{{ fenToYuan(row.allocatedFen) }}</span></template></el-table-column>
        </app-table>
      </section>

      <section class="finance-section">
        <div class="finance-section-head"><div><h3>退款通道结果</h3><p>只有已确认成功的记录才构成真实退款事实。</p></div></div>
        <app-table :data="detail.payments" size="small">
          <el-table-column prop="externalRefundNo" label="退款流水号" min-width="160" />
          <el-table-column label="通道" width="120"><template #default="{ row }">{{ providerLabel(row.provider) }}</template></el-table-column>
          <el-table-column label="金额" width="110" align="right"><template #default="{ row }">¥{{ fenToYuan(row.amountFen) }}</template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }">{{ refundPaymentStatusLabel(row.status) }}</template></el-table-column>
          <el-table-column label="确认方式 / 时间" min-width="170"><template #default="{ row }">{{ verificationMethodLabel(row.verificationMethod) }}<div class="trading-cell-sub">{{ formatDateTime(row.succeededAt) }}</div></template></el-table-column>
          <el-table-column label="证据 / 文件" min-width="150"><template #default="{ row }">{{ row.evidenceRef || '-' }}<FileDownloadButton v-if="row.voucherFileId" :file-id="row.voucherFileId" /></template></el-table-column>
          <template #empty><el-empty description="尚无退款执行记录" /></template>
        </app-table>
      </section>

      <section class="finance-section">
        <div class="finance-section-head"><div><h3>状态历史（只追加）</h3></div></div>
        <el-timeline>
          <el-timeline-item v-for="item in detail.history" :key="item.id" :timestamp="formatDateTime(item.createdAt)">
            <strong>{{ item.fromStatus === 'NONE' ? '未创建' : refundStatusLabel(item.fromStatus) }} → {{ refundStatusLabel(item.toStatus) }}</strong>
            <div class="crm-muted">{{ item.actorUserId === null ? '供应商回调' : `用户 #${item.actorUserId}` }}<span v-if="item.reason"> · {{ item.reason }}</span></div>
          </el-timeline-item>
        </el-timeline>
      </section>
    </template>
    <template #footer>
      <div class="finance-drawer-footer">
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        <el-button v-if="canReview" type="primary" data-testid="refund-detail-review" @click="emit('review')">审批退款</el-button>
        <el-button v-if="canExecute" type="primary" data-testid="refund-detail-execute" @click="emit('execute')">登记执行</el-button>
        <el-button v-if="canConfirmSuccess" type="primary" data-testid="refund-detail-success" @click="emit('success')">复核退款成功</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.order-head { display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:18px; }
.customer-name { margin-bottom:5px;font-size:18px;font-weight:700; }
</style>
