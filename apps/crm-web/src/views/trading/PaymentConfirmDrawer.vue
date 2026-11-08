<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CrmOrder, OrderFulfillmentView } from '../../api/trading'
import { confirmPayment } from '../../api/trading'
import { fenToYuan } from '../../utils/money'
import { createPaymentIdempotencyKey, tradingErrorText } from './tradingUi'

const props = defineProps<{
  modelValue: boolean
  order: CrmOrder | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [view: OrderFulfillmentView]
}>()

const form = reactive({
  externalTradeNo: '',
  paymentChannel: 'BANK_TRANSFER',
  paidAt: null as Date | null,
  evidenceRef: '',
  voucherFileId: undefined as number | undefined,
  verified: false,
})
const saving = ref(false)
const idempotencyKey = ref('')
const payableYuan = computed(() => fenToYuan(props.order?.payableFen))

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, {
      externalTradeNo: '',
      paymentChannel: 'BANK_TRANSFER',
      paidAt: null,
      evidenceRef: '',
      voucherFileId: undefined,
      verified: false,
    })
    idempotencyKey.value = createPaymentIdempotencyKey()
  },
)

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function save() {
  const order = props.order
  if (!order || order.status !== 'PENDING_PAYMENT') {
    ElMessage.warning('仅待支付订单可确认支付，请刷新订单状态')
    return
  }
  if (!form.externalTradeNo.trim() || !form.evidenceRef.trim()) {
    ElMessage.warning('请填写真实交易号与支付凭证引用')
    return
  }
  if (!(form.paidAt instanceof Date) || Number.isNaN(form.paidAt.getTime())) {
    ElMessage.warning('请选择实际支付时间')
    return
  }
  if (!form.verified) {
    ElMessage.warning('请先确认已核对实际到账与支付凭证')
    return
  }

  saving.value = true
  try {
    const saved = await confirmPayment(order.id, {
      externalTradeNo: form.externalTradeNo.trim(),
      paymentChannel: form.paymentChannel,
      amountFen: order.payableFen,
      paidAt: form.paidAt.toISOString(),
      evidenceRef: form.evidenceRef.trim(),
      voucherFileId: form.voucherFileId,
    }, idempotencyKey.value)
    ElMessage.success('人工支付确认已记录')
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
  <el-drawer
    :model-value="modelValue"
    title="人工支付确认"
    size="min(680px, 96vw)"
    @update:model-value="updateVisible"
  >
    <template v-if="order">
      <el-alert
        type="warning"
        :closable="false"
        title="仅在实际到账并人工核验凭证后提交。本操作不是有赞或支付网关回调，也不代替支付渠道验签。"
        class="payment-warning"
      />
      <el-descriptions :column="2" border class="payment-summary">
        <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ order.customerName || `客户 #${order.customerId}` }}</el-descriptions-item>
        <el-descriptions-item label="收款方">{{ order.receiverType === 'PLATFORM' ? '平台收款' : '机构收款' }}</el-descriptions-item>
        <el-descriptions-item label="本次确认金额">¥ {{ payableYuan }}</el-descriptions-item>
      </el-descriptions>
      <p class="crm-muted payment-hint">当前阶段仅支持按订单应付金额全额确认，不支持部分支付。</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="支付渠道" required>
          <el-select v-model="form.paymentChannel" class="full-width" data-testid="payment-channel">
            <el-option label="银行转账" value="BANK_TRANSFER" />
            <el-option label="线下收款" value="OFFLINE" />
          </el-select>
        </el-form-item>
        <el-form-item label="真实支付交易号" required>
          <el-input v-model="form.externalTradeNo" maxlength="128" show-word-limit placeholder="填写银行或线下收款的实际交易号" data-testid="payment-trade-no" />
        </el-form-item>
        <el-form-item label="实际支付时间" required>
          <el-date-picker v-model="form.paidAt" type="datetime" :clearable="false" class="full-width" data-testid="payment-paid-at" />
          <div class="crm-muted field-help">提交时会保留支付时间的时区信息，不伪造渠道回执时间。</div>
        </el-form-item>
        <el-form-item label="支付凭证引用" required>
          <el-input v-model="form.evidenceRef" maxlength="192" show-word-limit placeholder="填写可审计的凭证或人工核验记录编号" data-testid="payment-evidence" />
        </el-form-item>
        <el-form-item label="已上传凭证文件 ID（可选）">
          <el-input-number v-model="form.voucherFileId" :min="1" :precision="0" :controls="false" class="full-width" placeholder="仅填写系统中已存在的文件 ID" />
          <div class="crm-muted field-help">本阶段不提供模拟上传；凭证引用始终必填。</div>
        </el-form-item>
        <el-checkbox v-model="form.verified" class="verification-checkbox" data-testid="payment-verified">
          我已核对实际到账、交易号、金额与凭证，确认信息一致
        </el-checkbox>
      </el-form>
    </template>
    <template #footer>
      <el-button :disabled="saving" @click="updateVisible(false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!order" data-testid="payment-submit" @click="save">
        确认支付并发放权益
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.payment-warning { margin-bottom: 18px; }
.payment-summary { margin-bottom: 8px; }
.payment-hint { margin: 8px 0 20px; line-height: 1.7; }
.field-help { margin-top: 6px; line-height: 1.6; }
.verification-checkbox { margin: 4px 0 12px; white-space: normal; line-height: 1.6; }
</style>
