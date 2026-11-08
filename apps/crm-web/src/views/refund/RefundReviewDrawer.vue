<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Refund, RefundDetail } from '../../api/refund'
import { reviewRefund } from '../../api/refund'
import { fenToYuan } from '../../utils/money'
import { createFinanceIdempotencyKey } from '../finance/financeUi'
import { tradingErrorText } from '../trading/tradingUi'

const props = defineProps<{ modelValue: boolean; refund: Refund | null }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: RefundDetail]
}>()
const form = reactive({ decision: 'APPROVED' as 'APPROVED' | 'REJECTED', approvedFen: 0, reason: '' })
const saving = ref(false)
let idempotencyKey = ''

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  form.decision = 'APPROVED'
  form.approvedFen = props.refund?.requestedFen ?? 0
  form.reason = ''
  idempotencyKey = createFinanceIdempotencyKey('refund.review')
})

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function save() {
  const refund = props.refund
  if (!refund || refund.status !== 'REQUESTED') {
    ElMessage.warning('退款状态已变化，请刷新后重试')
    return
  }
  if (!form.reason.trim()) {
    ElMessage.warning('请填写审批理由')
    return
  }
  if (form.decision === 'APPROVED' && form.approvedFen !== refund.requestedFen) {
    ElMessage.warning('当前权益现金分摊按申请金额全额审批；如需调整请重新申请')
    return
  }
  saving.value = true
  try {
    const saved = await reviewRefund(refund.id, {
      version: refund.version,
      decision: form.decision,
      approvedFen: form.decision === 'APPROVED' ? form.approvedFen : undefined,
      reason: form.reason.trim(),
    }, idempotencyKey)
    ElMessage.success(form.decision === 'APPROVED' ? '退款申请已批准，尚未执行退款' : '退款申请已驳回')
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
  <el-drawer :model-value="modelValue" title="退款审批" size="min(580px, 100vw)" @update:model-value="updateVisible">
    <template v-if="refund">
      <el-alert type="warning" :closable="false" title="审批通过不等于退款成功，不更新订单成功退款额或客户权益。" class="finance-action-note" />
      <div class="finance-order-facts"><strong>{{ refund.refundNo }}</strong><span>申请金额 ¥{{ fenToYuan(refund.requestedFen) }} · 申请人 #{{ refund.requestedBy }}</span></div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="审批决策" required>
          <el-radio-group v-model="form.decision" data-testid="refund-review-decision">
            <el-radio-button value="APPROVED">批准</el-radio-button>
            <el-radio-button value="REJECTED">驳回</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.decision === 'APPROVED'" label="批准金额（分）" required>
          <el-input-number v-model="form.approvedFen" :min="1" :max="refund.requestedFen" :precision="0" :controls="false" class="finance-field" data-testid="refund-approved-fen" disabled />
          <div class="finance-field-help">当前按申请分摊全额批准，折合 ¥{{ fenToYuan(form.approvedFen) }}；调整金额需重新申请。</div>
        </el-form-item>
        <el-form-item label="审批理由" required>
          <el-input v-model="form.reason" type="textarea" :rows="4" maxlength="1000" show-word-limit data-testid="refund-review-reason" placeholder="填写人工审批依据" />
        </el-form-item>
      </el-form>
    </template>
    <template #footer><div class="finance-drawer-footer"><el-button :disabled="saving" @click="updateVisible(false)">取消</el-button><el-button :type="form.decision === 'REJECTED' ? 'danger' : 'primary'" :loading="saving" data-testid="refund-review-submit" @click="save">提交审批结果</el-button></div></template>
  </el-drawer>
</template>
