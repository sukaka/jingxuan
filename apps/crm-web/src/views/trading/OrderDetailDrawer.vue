<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { OrderFulfillmentView, OrderItem } from '../../api/trading'
import type { Refund } from '../../api/refund'
import { listRefunds } from '../../api/refund'
import { fenToYuan } from '../../utils/money'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { refundStatusLabel, refundStatusTone } from '../finance/financeUi'
import { tradingErrorText } from './tradingUi'
import {
  entitlementStatusLabel,
  entitlementStatusTone,
  historyTriggerLabel,
  orderItemAllocationStatus,
  orderSourceLabel,
  orderStatusLabel,
  orderStatusTone,
  orderTypeLabel,
  paymentChannelLabel,
  paymentStatusLabel,
  paymentVerificationMethodLabel,
} from './tradingUi'

const props = withDefaults(defineProps<{
  modelValue: boolean
  view: OrderFulfillmentView | null
  canWrite: boolean
  canConfirmPayment: boolean
  canRequestRefund?: boolean
  canViewRefunds?: boolean
}>(), {
  canRequestRefund: false,
  canViewRefunds: false,
})
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: []
  cancel: []
  confirm: []
  requestRefund: []
}>()

const order = computed(() => props.view?.order.order ?? null)
const hasPendingAllocation = computed(() =>
  props.view?.order.items.some((item) => orderItemAllocationStatus(item) === 'PENDING_Q02') ?? false,
)
const entitlementEmptyText = computed(() => {
  if (order.value?.status === 'DRAFT') return '草稿不发放权益'
  if (order.value?.status === 'PENDING_PAYMENT') return '待支付订单尚未发放权益'
  if (order.value?.status === 'CANCELED') return '订单已取消，无可用权益'
  return '暂无权益记录，请核对后端处理结果'
})
const hasRefundableBalance = computed(() => {
  const current = order.value
  return !!current && ['PAID', 'PARTIALLY_REDEEMED', 'FULFILLED'].includes(current.status) && current.paidFen > current.refundedFen
})

const refunds = ref<Refund[]>([])
const refundsLoading = ref(false)
const refundsError = ref('')
let refundLoadSequence = 0

async function loadRefunds() {
  const orderId = order.value?.id
  const sequence = ++refundLoadSequence
  if (!props.modelValue || !props.canViewRefunds || !orderId) {
    refunds.value = []
    refundsError.value = ''
    return
  }
  refundsLoading.value = true
  refundsError.value = ''
  try {
    const result = await listRefunds({ page: 1, size: 100, orderId })
    if (sequence === refundLoadSequence) refunds.value = result.items
  } catch (error) {
    if (sequence === refundLoadSequence) {
      refunds.value = []
      refundsError.value = tradingErrorText(error)
    }
  } finally {
    if (sequence === refundLoadSequence) refundsLoading.value = false
  }
}

watch(() => [props.modelValue, order.value?.id, props.canViewRefunds], () => { void loadRefunds() }, { immediate: true })

function allocationText(item: OrderItem): string {
  const status = orderItemAllocationStatus(item)
  if (status === 'PENDING_Q02') return '待分摊'
  if (status === 'UNKNOWN') return '快照待核验'
  return fenToYuan(item.paidAllocatedFen)
}

function historyStatusLabel(status: string): string {
  return status === 'NONE' ? '未创建' : orderStatusLabel(status)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="order ? `订单 ${order.orderNo}` : '订单详情'"
    size="min(940px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="view && order">
      <div class="order-head">
        <div>
          <div class="customer-name">{{ order.customerName || `客户 #${order.customerId}` }}</div>
          <div class="crm-muted">{{ orderSourceLabel(order.sourceSystem) }} · {{ order.receiverType === 'PLATFORM' ? '平台收款' : '机构收款' }} · {{ orderTypeLabel(order.orderTypeCode) }}</div>
        </div>
        <StatusPill :text="orderStatusLabel(order.status)" :tone="orderStatusTone(order.status)" />
      </div>
      <el-alert
        v-if="order.status === 'DRAFT' || order.status === 'PENDING_PAYMENT'"
        type="warning"
        :closable="false"
        :title="order.status === 'DRAFT' ? '当前为草稿：未确认支付，不发放任何可用权益。' : '当前待支付：必须完成授权人员的人工凭证核验，才会发放权益。'"
        class="state-tip"
      />
      <el-descriptions :column="3" border class="summary trading-descriptions">
        <el-descriptions-item label="原价（元）">{{ fenToYuan(order.listAmountFen) }}</el-descriptions-item>
        <el-descriptions-item label="优惠（元）">{{ fenToYuan(order.discountAmountFen) }}</el-descriptions-item>
        <el-descriptions-item label="应付（元）">{{ fenToYuan(order.payableFen) }}</el-descriptions-item>
        <el-descriptions-item label="已确认实付（元）">{{ fenToYuan(order.paidFen) }}</el-descriptions-item>
        <el-descriptions-item label="已退款（元）">{{ fenToYuan(order.refundedFen) }}</el-descriptions-item>
        <el-descriptions-item label="乐观锁版本">{{ order.version }}</el-descriptions-item>
        <el-descriptions-item label="外部订单号">{{ order.externalOrderNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ formatDateTime(order.orderedAt) }}</el-descriptions-item>
        <el-descriptions-item label="实际支付时间">{{ formatDateTime(order.paidAt) }}</el-descriptions-item>
      </el-descriptions>

      <h4 class="trading-section-title">订单行与权益快照</h4>
      <el-alert
        v-if="hasPendingAllocation"
        type="warning"
        :closable="false"
        title="Q02 未确认：多项目实付金额待分摊。此处的 0 或订单行金额均不代表已确认的项目/机构结算金额。"
        class="allocation-alert"
        data-testid="q02-allocation-alert"
      />
      <app-table :data="view.order.items" size="small">
        <el-table-column prop="skuNameSnapshot" label="卡项 / 规格" min-width="200" />
        <el-table-column prop="skuCodeSnapshot" label="SKU 编码" min-width="145" />
        <el-table-column prop="purchaseQuantity" label="份数" width="70" align="right" />
        <el-table-column label="原价（元）" width="100" align="right">
          <template #default="{ row }">{{ fenToYuan(row.listAmountFen) }}</template>
        </el-table-column>
        <el-table-column label="分摊实付（元）" width="125" align="right">
          <template #default="{ row }">
            <span :class="{ 'trading-pending-allocation': allocationText(row) === '待分摊' }" class="allocation-value">{{ allocationText(row) }}</span>
          </template>
        </el-table-column>
      </app-table>

      <h4 class="trading-section-title">会员权益</h4>
      <app-table :data="view.entitlements" size="small">
        <el-table-column label="项目" min-width="150">
          <template #default="{ row }">{{ row.projectName || `项目 #${row.projectId}` }}</template>
        </el-table-column>
        <el-table-column prop="grantedCount" label="已发放" width="80" align="right" />
        <el-table-column prop="availableCount" label="可用" width="80" align="right" />
        <el-table-column prop="validTo" label="到期日" width="110">
          <template #default="{ row }">{{ row.validTo || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><StatusPill :text="entitlementStatusLabel(row.status)" :tone="entitlementStatusTone(row.status)" /></template>
        </el-table-column>
        <template #empty><el-empty :description="entitlementEmptyText" /></template>
      </app-table>

      <h4 class="trading-section-title">支付流水（可信人工确认）</h4>
      <app-table :data="view.order.payments" size="small">
        <el-table-column prop="externalTradeNo" label="真实交易号" min-width="145" />
        <el-table-column label="渠道" width="105">
          <template #default="{ row }">{{ paymentChannelLabel(row.paymentChannel) }}</template>
        </el-table-column>
        <el-table-column label="金额（元）" width="100" align="right">
          <template #default="{ row }">{{ fenToYuan(row.amountFen) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="85">
          <template #default="{ row }">{{ paymentStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="确认方式" width="125">
          <template #default="{ row }">{{ paymentVerificationMethodLabel(row.verificationMethod) }}</template>
        </el-table-column>
        <el-table-column label="确认时间 / 人" width="165">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.confirmedAt) }}</div>
            <span class="crm-muted">{{ row.confirmedBy === null ? '-' : `用户 #${row.confirmedBy}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="凭证" min-width="145">
          <template #default="{ row }">
            <div>{{ row.evidenceRef }}</div>
            <span class="crm-muted">{{ row.voucherFileId === null ? '未关联文件' : `文件 #${row.voucherFileId}` }}</span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="尚无支付确认流水" /></template>
      </app-table>

      <template v-if="canViewRefunds">
        <div class="finance-section-head refund-section-head">
          <div><h4 class="trading-section-title">退款记录与真实成功金额</h4><p>申请、审批和执行中均不计入已退款；上方“已退款”仅汇总真实成功金额。</p></div>
          <RouterLink :to="{ path: '/refunds', query: { orderId: order.id } }" class="el-button el-button--small">查看退款台账</RouterLink>
        </div>
        <el-alert v-if="refundsError" type="error" :title="`退款记录加载失败：${refundsError}`" :closable="false" />
        <app-table v-loading="refundsLoading" :data="refunds" size="small" data-testid="order-refund-records">
          <el-table-column prop="refundNo" label="退款单号" min-width="155" />
          <el-table-column label="申请金额" width="105" align="right"><template #default="{ row }">¥{{ fenToYuan(row.requestedFen) }}</template></el-table-column>
          <el-table-column label="真实成功" width="105" align="right"><template #default="{ row }">¥{{ fenToYuan(row.succeededFen) }}</template></el-table-column>
          <el-table-column label="状态" width="140"><template #default="{ row }"><StatusPill :text="refundStatusLabel(row.status)" :tone="refundStatusTone(row.status)" /></template></el-table-column>
          <el-table-column label="申请时间" min-width="160"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
          <template #empty><el-empty description="该订单暂无退款记录" /></template>
        </app-table>
      </template>

      <h4 class="trading-section-title">状态历史（只追加）</h4>
      <el-timeline>
        <el-timeline-item v-for="item in view.order.history" :key="item.id" :timestamp="formatDateTime(item.createdAt)">
          <strong>{{ historyStatusLabel(item.fromStatus) }} → {{ historyStatusLabel(item.toStatus) }}</strong>
          <div class="crm-muted">{{ historyTriggerLabel(item.triggerType) }}<span v-if="item.triggerRef"> · {{ item.triggerRef }}</span><span v-if="item.reason"> · {{ item.reason }}</span></div>
        </el-timeline-item>
      </el-timeline>
      <el-alert
        v-if="['PAID', 'PARTIALLY_REDEEMED', 'FULFILLED'].includes(order.status)"
        type="info"
        :closable="false"
        title="支付事实已冻结；后续退款、核销通过独立流水处理，不覆盖本订单。当前未开放管家正式核销。"
        class="immutable-tip"
      />
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <template v-if="order && canWrite">
        <el-button v-if="order.status === 'DRAFT' || order.status === 'PENDING_PAYMENT'" @click="emit('cancel')">取消订单</el-button>
        <el-button v-if="order.status === 'DRAFT'" type="primary" @click="emit('submit')">提交待支付</el-button>
      </template>
      <el-button
        v-if="order && order.status === 'PENDING_PAYMENT' && canConfirmPayment"
        type="primary"
        data-testid="detail-confirm-payment"
        @click="emit('confirm')"
      >
        核验支付凭证
      </el-button>
      <el-button v-if="hasRefundableBalance && canRequestRefund" type="primary" data-testid="order-request-refund" @click="emit('requestRefund')">申请退款</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.order-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; margin-bottom: 18px; }
.customer-name { font-size: 18px; font-weight: 700; margin-bottom: 5px; }
.state-tip { margin-bottom: 16px; }
.summary { margin-bottom: 20px; }
.allocation-alert { margin-bottom: 10px; }
.immutable-tip { margin-top: 20px; }
</style>
