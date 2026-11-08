<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { SettlementBatch, SettlementBatchDetail } from '../../api/settlement'
import { recordSettlementPayment } from '../../api/settlement'
import { fenToYuan } from '../../utils/money'
import FileUploadField from '../institution/FileUploadField.vue'
import { createFinanceIdempotencyKey } from '../finance/financeUi'
import { tradingErrorText } from '../trading/tradingUi'

const props = defineProps<{ modelValue: boolean; batch: SettlementBatch | null }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: SettlementBatchDetail]
}>()
const form = reactive({ amountFen: 0, bankTradeNo: '', paidAt: null as Date | null, voucherFileId: undefined as number | undefined, evidenceRef: '', verified: false })
const saving = ref(false)
let idempotencyKey = ''
const remainingFen = computed(() => props.batch ? Math.max(0, props.batch.netPayableFen - props.batch.paidFen) : 0)

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  form.amountFen = remainingFen.value
  form.bankTradeNo = ''
  form.paidAt = null
  form.voucherFileId = undefined
  form.evidenceRef = ''
  form.verified = false
  idempotencyKey = createFinanceIdempotencyKey('settlement.payment')
})

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function save() {
  const batch = props.batch
  if (!batch || batch.amountStatus !== 'VERIFIED' || !['CONFIRMED', 'PARTIALLY_PAID'].includes(batch.status)) {
    ElMessage.warning('仅已核准且待付款的批次可登记付款')
    return
  }
  if (!Number.isInteger(form.amountFen) || form.amountFen < 1 || form.amountFen > remainingFen.value) {
    ElMessage.warning('付款金额必须大于 0 且不能超过剩余应结金额')
    return
  }
  if (!form.bankTradeNo.trim() || !form.evidenceRef.trim() || !form.voucherFileId || !(form.paidAt instanceof Date)) {
    ElMessage.warning('请填写真实银行流水、付款时间、证据并上传扫描通过的凭证')
    return
  }
  if (!form.verified) {
    ElMessage.warning('请确认已人工核对实际付款与凭证')
    return
  }
  saving.value = true
  try {
    const saved = await recordSettlementPayment(batch.id, {
      version: batch.version,
      amountFen: form.amountFen,
      bankTradeNo: form.bankTradeNo.trim(),
      paidAt: form.paidAt.toISOString(),
      voucherFileId: form.voucherFileId,
      evidenceRef: form.evidenceRef.trim(),
    }, idempotencyKey)
    ElMessage.success('付款记录已追加；这是人工登记，不是银行到账回调')
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
  <el-drawer :model-value="modelValue" title="登记机构结算付款" size="min(680px, 100vw)" class="crm-ui" @update:model-value="updateVisible">
    <template v-if="batch">
      <el-alert type="warning" :closable="false" title="仅登记已人工核验的真实付款。历史付款不可覆盖，分次累计不得超过核准净额。" class="finance-action-note" />
      <div class="finance-summary">
        <div class="finance-summary-item"><small>核准净额</small><strong>¥{{ fenToYuan(batch.netPayableFen) }}</strong></div>
        <div class="finance-summary-item"><small>已登记付款</small><strong>¥{{ fenToYuan(batch.paidFen) }}</strong></div>
        <div class="finance-summary-item"><small>剩余可登记</small><strong>¥{{ fenToYuan(remainingFen) }}</strong></div>
      </div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="本次付款金额（分）" required><el-input-number v-model="form.amountFen" :min="1" :max="remainingFen" :precision="0" :controls="false" class="finance-field" data-testid="settlement-payment-amount" /><div class="finance-field-help">折合 ¥{{ fenToYuan(form.amountFen) }}。</div></el-form-item>
        <el-form-item label="真实银行 / 线下流水号" required><el-input v-model="form.bankTradeNo" maxlength="128" data-testid="settlement-bank-trade-no" placeholder="不得填写虚构流水号" /></el-form-item>
        <el-form-item label="实际付款时间" required><el-date-picker v-model="form.paidAt" type="datetime" :clearable="false" class="finance-field" data-testid="settlement-paid-at" /></el-form-item>
        <el-form-item label="人工核验证据" required><el-input v-model="form.evidenceRef" maxlength="192" data-testid="settlement-payment-evidence" placeholder="付款复核记录编号" /></el-form-item>
        <el-form-item label="付款凭证（须扫描通过）" required><FileUploadField v-model="form.voucherFileId" classification="PAYMENT" :institution-id="batch.institutionId" label="上传 PDF、JPEG 或 PNG 付款凭证" /></el-form-item>
        <el-checkbox v-model="form.verified" data-testid="settlement-payment-verified">我已核对实际付款、流水号、金额与凭证，确认信息一致</el-checkbox>
      </el-form>
    </template>
    <template #footer><div class="finance-drawer-footer"><el-button :disabled="saving" @click="updateVisible(false)">取消</el-button><el-button type="primary" :loading="saving" :disabled="remainingFen <= 0" data-testid="settlement-payment-submit" @click="save">追加付款记录</el-button></div></template>
  </el-drawer>
</template>
