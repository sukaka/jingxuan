<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { OrderFulfillmentView } from '../../api/trading'
import { getOrder } from '../../api/trading'
import type { RefundDetail } from '../../api/refund'
import { createRefund } from '../../api/refund'
import { fenToYuan } from '../../utils/money'
import { createFinanceIdempotencyKey } from '../finance/financeUi'
import { tradingErrorText } from '../trading/tradingUi'

const props = defineProps<{
  modelValue: boolean
  initialOrderId?: number
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: RefundDetail]
}>()

const form = reactive({
  orderId: undefined as number | undefined,
  reasonCode: 'CUSTOMER_REQUEST',
  reasonDetail: '',
})
const orderView = ref<OrderFulfillmentView | null>(null)
const orderLoading = ref(false)
const saving = ref(false)
const selected = ref<Record<number, boolean>>({})
const revokeCounts = ref<Record<number, number>>({})
const allocatedFen = ref<Record<number, number>>({})
let idempotencyKey = ''

const order = computed(() => orderView.value?.order.order ?? null)
const entitlements = computed(() => orderView.value?.entitlements ?? [])
const selectedItems = computed(() => entitlements.value.filter((item) => selected.value[item.id]))
const requestedFen = computed(() => selectedItems.value.reduce((sum, item) => sum + (allocatedFen.value[item.id] ?? 0), 0))
const refundableFen = computed(() => order.value ? Math.max(0, order.value.paidFen - order.value.refundedFen) : 0)

function resetForm() {
  form.orderId = props.initialOrderId
  form.reasonCode = 'CUSTOMER_REQUEST'
  form.reasonDetail = ''
  orderView.value = null
  selected.value = {}
  revokeCounts.value = {}
  allocatedFen.value = {}
  idempotencyKey = createFinanceIdempotencyKey('refund.create')
}

async function loadOrder(id?: number) {
  const orderId = id ?? form.orderId
  if (!orderId || orderId < 1) {
    ElMessage.warning('请输入有效订单 ID')
    return
  }
  orderLoading.value = true
  try {
    const view = await getOrder(orderId)
    if (!['PAID', 'PARTIALLY_REDEEMED', 'FULFILLED'].includes(view.order.order.status)) {
      orderView.value = null
      ElMessage.warning('仅已支付订单可申请退款')
      return
    }
    orderView.value = view
    const nextSelected: Record<number, boolean> = {}
    const nextCounts: Record<number, number> = {}
    const nextAmounts: Record<number, number> = {}
    view.entitlements.forEach((item) => {
      nextSelected[item.id] = false
      nextCounts[item.id] = 1
      nextAmounts[item.id] = 0
    })
    selected.value = nextSelected
    revokeCounts.value = nextCounts
    allocatedFen.value = nextAmounts
  } catch (error) {
    orderView.value = null
    ElMessage.error(tradingErrorText(error))
  } finally {
    orderLoading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    resetForm()
    if (props.initialOrderId) void loadOrder(props.initialOrderId)
  },
)

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function save() {
  const currentOrder = order.value
  if (!currentOrder || selectedItems.value.length === 0) {
    ElMessage.warning('请选择至少一个需要回收的权益批次')
    return
  }
  if (!form.reasonDetail.trim()) {
    ElMessage.warning('请填写退款说明')
    return
  }
  if (requestedFen.value <= 0 || requestedFen.value > refundableFen.value) {
    ElMessage.warning('退款金额必须大于 0 且不超过订单可退金额')
    return
  }
  const invalidItem = selectedItems.value.find((item) => {
    const count = revokeCounts.value[item.id]
    const amount = allocatedFen.value[item.id]
    return !Number.isInteger(count) || (count ?? 0) < 1 || (count ?? 0) > item.availableCount
      || !Number.isInteger(amount) || (amount ?? 0) < 0
  })
  if (invalidItem) {
    ElMessage.warning('请检查回收次数与现金分摊；回收次数不得超过可用次数')
    return
  }

  saving.value = true
  try {
    const saved = await createRefund({
      orderId: currentOrder.id,
      requestedFen: requestedFen.value,
      reasonCode: form.reasonCode,
      reasonDetail: form.reasonDetail.trim(),
      items: selectedItems.value.map((item) => ({
        entitlementId: item.id,
        revokeCount: revokeCounts.value[item.id]!,
        allocatedFen: allocatedFen.value[item.id]!,
      })),
    }, idempotencyKey)
    ElMessage.success('退款申请已提交，尚未产生退款或权益回收')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer :model-value="modelValue" title="新建退款申请" size="min(860px, 100vw)" @update:model-value="updateVisible">
    <el-alert type="warning" :closable="false" title="申请不代表退款成功。请逐笔明确权益回收次数和现金分摊，不按比例自动推断。" class="finance-action-note" />
    <el-form label-position="top" @submit.prevent>
      <div class="finance-form-grid">
        <el-form-item label="订单 ID" required>
          <div class="finance-field" style="display:flex;gap:8px">
            <el-input-number v-model="form.orderId" :min="1" :precision="0" :controls="false" class="finance-field" placeholder="输入真实订单 ID" data-testid="refund-order-id" />
            <el-button :loading="orderLoading" @click="loadOrder()">读取订单</el-button>
          </div>
        </el-form-item>
        <el-form-item label="退款原因" required>
          <el-select v-model="form.reasonCode" class="finance-field" data-testid="refund-reason-code">
            <el-option label="客户主动申请" value="CUSTOMER_REQUEST" />
            <el-option label="服务问题" value="SERVICE_ISSUE" />
            <el-option label="其他人工核实原因" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="退款说明" required class="finance-full">
          <el-input v-model="form.reasonDetail" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="说明人工核实情况，将写入退款记录与审计" data-testid="refund-reason-detail" />
        </el-form-item>
      </div>
    </el-form>

    <div v-if="order" class="finance-order-facts" data-testid="refund-order-facts">
      <strong>{{ order.orderNo }} · {{ order.customerName || `客户 #${order.customerId}` }}</strong>
      <span>已确认实付 ¥{{ fenToYuan(order.paidFen) }} · 已成功退款 ¥{{ fenToYuan(order.refundedFen) }} · 本次最多可申请 ¥{{ fenToYuan(refundableFen) }}</span>
    </div>
    <template v-if="order">
      <div class="finance-section-head">
        <div><h3>选择权益并确定现金分摊</h3><p>已消费次数不会自动返还；此处仅回收当前可用权益。</p></div>
      </div>
      <app-table :data="entitlements" size="small">
        <el-table-column label="选择" width="58" align="center">
          <template #default="{ row }"><el-checkbox v-model="selected[row.id]" :disabled="row.availableCount < 1" :aria-label="`选择权益 ${row.id}`" /></template>
        </el-table-column>
        <el-table-column label="权益批次 / 项目" min-width="170">
          <template #default="{ row }"><strong>#{{ row.id }} · {{ row.projectName || `项目 #${row.projectId}` }}</strong><div class="trading-cell-sub">可用 {{ row.availableCount }} / 已发放 {{ row.grantedCount }} 次</div></template>
        </el-table-column>
        <el-table-column label="回收次数" width="150">
          <template #default="{ row }"><el-input-number v-model="revokeCounts[row.id]" :min="1" :max="row.availableCount" :precision="0" :disabled="!selected[row.id]" class="finance-field" /></template>
        </el-table-column>
        <el-table-column label="现金分摊（分）" min-width="170">
          <template #default="{ row }"><el-input-number v-model="allocatedFen[row.id]" :min="0" :precision="0" :controls="false" :disabled="!selected[row.id]" class="finance-field" placeholder="整数分" /></template>
        </el-table-column>
        <template #empty><el-empty description="该订单没有可选择的权益批次" /></template>
      </app-table>
      <div class="finance-allocation-total">
        <span>已选 {{ selectedItems.length }} 个权益批次</span>
        <strong>申请退款：{{ requestedFen }} 分（¥{{ fenToYuan(requestedFen) }}）</strong>
      </div>
    </template>
    <template #footer>
      <div class="finance-drawer-footer">
        <el-button :disabled="saving" @click="updateVisible(false)">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!order" data-testid="refund-create-submit" @click="save">提交退款申请</el-button>
      </div>
    </template>
  </el-drawer>
</template>
