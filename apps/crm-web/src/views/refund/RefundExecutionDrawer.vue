<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { RefundDetail } from '../../api/refund'
import { confirmRefundSuccess, executeRefund } from '../../api/refund'
import { fenToYuan } from '../../utils/money'
import FileUploadField from '../institution/FileUploadField.vue'
import { createFinanceIdempotencyKey } from '../finance/financeUi'
import { providerLabel } from './refundUi'
import { tradingErrorText } from '../trading/tradingUi'

const props = defineProps<{
  modelValue: boolean
  mode: 'execute' | 'success'
  detail: RefundDetail | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: RefundDetail]
}>()
const form = reactive({
  provider: 'BANK_TRANSFER' as 'BANK_TRANSFER' | 'OFFLINE' | 'MOCK',
  externalRefundNo: '',
  amountFen: 0,
  succeededAt: null as Date | null,
  evidenceRef: '',
  voucherFileId: undefined as number | undefined,
  verified: false,
})
const saving = ref(false)
let idempotencyKey = ''
const refund = computed(() => props.detail?.refund ?? null)
const pendingPayments = computed(() => props.detail?.payments.filter((payment) => payment.status === 'PENDING' && payment.verificationMethod === 'MANUAL_REVIEW') ?? [])

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  const pending = pendingPayments.value[0]
  form.provider = 'BANK_TRANSFER'
  form.externalRefundNo = props.mode === 'success' ? (pending?.externalRefundNo ?? '') : ''
  form.amountFen = props.mode === 'success' ? (pending?.amountFen ?? refund.value?.approvedFen ?? 0) : (refund.value?.approvedFen ?? 0)
  form.succeededAt = null
  form.evidenceRef = ''
  form.voucherFileId = undefined
  form.verified = false
  idempotencyKey = createFinanceIdempotencyKey(props.mode === 'execute' ? 'refund.execute' : 'refund.success')
})

function selectExecution(externalRefundNo: string) {
  const payment = pendingPayments.value.find((item) => item.externalRefundNo === externalRefundNo)
  if (payment) form.amountFen = payment.amountFen
}

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function saveExecution() {
  const current = refund.value
  if (!current || !['APPROVED', 'FAILED'].includes(current.status)) {
    ElMessage.warning('仅已批准或失败重试的退款可登记执行')
    return
  }
  if (!form.externalRefundNo.trim() || form.amountFen !== current.approvedFen) {
    ElMessage.warning('请填写退款流水号，执行金额必须与批准金额一致')
    return
  }
  saving.value = true
  try {
    const saved = await executeRefund(current.id, {
      version: current.version,
      provider: form.provider,
      externalRefundNo: form.externalRefundNo.trim(),
      amountFen: form.amountFen,
      evidenceRef: form.evidenceRef.trim() || undefined,
    }, idempotencyKey)
    ElMessage.success('退款执行已登记，当前仍为处理中，资产未变化')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}

async function saveSuccess() {
  const current = refund.value
  if (!current || current.status !== 'PROCESSING') {
    ElMessage.warning('仅执行中的退款可确认成功')
    return
  }
  if (!form.externalRefundNo || !form.evidenceRef.trim() || !form.voucherFileId || !(form.succeededAt instanceof Date)) {
    ElMessage.warning('请选择执行记录，填写成功时间和证据，并上传扫描通过的凭证')
    return
  }
  if (!form.verified) {
    ElMessage.warning('请确认已人工核对真实退款结果')
    return
  }
  saving.value = true
  try {
    const saved = await confirmRefundSuccess(current.id, {
      externalRefundNo: form.externalRefundNo,
      amountFen: form.amountFen,
      succeededAt: form.succeededAt.toISOString(),
      evidenceRef: form.evidenceRef.trim(),
      voucherFileId: form.voucherFileId,
    }, idempotencyKey)
    ElMessage.success('真实退款成功已确认，订单退款额与权益回收已按后端事务处理')
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
  <el-drawer :model-value="modelValue" :title="mode === 'execute' ? '登记退款执行' : '人工复核退款成功'" size="min(680px, 100vw)" class="crm-ui" @update:model-value="updateVisible">
    <template v-if="refund">
      <el-alert :type="mode === 'execute' ? 'info' : 'warning'" :closable="false" :title="mode === 'execute' ? '本步骤只登记执行，不代表资金已退回，也不会回收权益。' : '仅在真实退款结果、金额和凭证均已人工核对后确认；此操作不是供应商回调。'" class="finance-action-note" />
      <div class="finance-order-facts"><strong>{{ refund.refundNo }}</strong><span>批准金额 ¥{{ fenToYuan(refund.approvedFen) }} · 当前成功金额 ¥{{ fenToYuan(refund.succeededFen) }}</span></div>
      <el-form label-position="top" @submit.prevent>
        <template v-if="mode === 'execute'">
          <el-form-item label="退款通道" required><el-select v-model="form.provider" class="finance-field" data-testid="refund-provider"><el-option label="银行转账" value="BANK_TRANSFER" /><el-option label="线下退款" value="OFFLINE" /><el-option label="模拟验签通道（仅本地测试）" value="MOCK" /></el-select></el-form-item>
          <el-form-item label="退款流水号" required><el-input v-model="form.externalRefundNo" maxlength="128" placeholder="银行转账、线下或模拟退款编号" data-testid="refund-external-no" /></el-form-item>
          <el-form-item label="执行金额（分）" required><el-input-number v-model="form.amountFen" :min="1" :max="refund.approvedFen" :precision="0" :controls="false" class="finance-field" disabled /><div class="finance-field-help">折合 ¥{{ fenToYuan(form.amountFen) }}，必须与批准金额一致。</div></el-form-item>
          <el-form-item label="执行凭证编号（可选）"><el-input v-model="form.evidenceRef" maxlength="192" placeholder="执行登记或人工核验编号" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="待确认执行记录" required>
            <el-select v-model="form.externalRefundNo" class="finance-field" data-testid="refund-success-execution" @change="selectExecution">
              <el-option v-for="payment in pendingPayments" :key="payment.id" :label="`${providerLabel(payment.provider)} · ${payment.externalRefundNo} · ¥${fenToYuan(payment.amountFen)}`" :value="payment.externalRefundNo" />
            </el-select>
            <div v-if="pendingPayments.length === 0" class="finance-field-help">没有可人工确认的执行记录；模拟退款必须通过验签回调确认。</div>
          </el-form-item>
          <el-form-item label="成功金额（分）" required><el-input-number v-model="form.amountFen" :min="1" :precision="0" :controls="false" class="finance-field" disabled /><div class="finance-field-help">以执行记录金额为准，折合 ¥{{ fenToYuan(form.amountFen) }}。</div></el-form-item>
          <el-form-item label="实际成功时间（本地时间）" required><el-date-picker v-model="form.succeededAt" type="datetime" :clearable="false" class="finance-field" data-testid="refund-succeeded-at" /></el-form-item>
          <el-form-item label="人工复核证据" required><el-input v-model="form.evidenceRef" maxlength="192" placeholder="真实退款结果核验记录编号" data-testid="refund-success-evidence" /></el-form-item>
          <el-form-item label="退款成功凭证（须扫描通过）" required><FileUploadField v-model="form.voucherFileId" classification="PAYMENT" label="上传 PDF、JPEG 或 PNG 凭证" /></el-form-item>
          <el-checkbox v-model="form.verified" data-testid="refund-success-verified">我已核对真实退款结果、退款流水号、金额与凭证，确认一致</el-checkbox>
        </template>
      </el-form>
    </template>
    <template #footer><div class="finance-drawer-footer"><el-button :disabled="saving" @click="updateVisible(false)">取消</el-button><el-button v-if="mode === 'execute'" type="primary" :loading="saving" data-testid="refund-execute-submit" @click="saveExecution">登记为执行中</el-button><el-button v-else type="primary" :loading="saving" :disabled="pendingPayments.length === 0" data-testid="refund-success-submit" @click="saveSuccess">确认真实退款成功</el-button></div></template>
  </el-drawer>
</template>
