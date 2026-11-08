<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { SettlementBatch, SettlementBatchDetail } from '../../api/settlement'
import { verifySettlementBatch } from '../../api/settlement'
import { fenToYuan } from '../../utils/money'
import { createFinanceIdempotencyKey, settlementPeriodLabel } from '../finance/financeUi'
import { tradingErrorText } from '../trading/tradingUi'

const props = defineProps<{ modelValue: boolean; batch: SettlementBatch | null }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: SettlementBatchDetail]
}>()
const form = reactive({ ruleVersion: '', grossFen: 0, commissionFen: 0, netPayableFen: 0, verificationEvidence: '' })
const saving = ref(false)
let idempotencyKey = ''
const balanced = computed(() => form.grossFen - form.commissionFen === form.netPayableFen)

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  form.ruleVersion = ''
  form.grossFen = 0
  form.commissionFen = 0
  form.netPayableFen = 0
  form.verificationEvidence = ''
  idempotencyKey = createFinanceIdempotencyKey('settlement.verification')
})

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function save() {
  const batch = props.batch
  if (!batch || batch.amountStatus !== 'PENDING_VERIFICATION') {
    ElMessage.warning('批次金额状态已变化，请刷新')
    return
  }
  if (!form.ruleVersion.trim() || !form.verificationEvidence.trim()) {
    ElMessage.warning('请填写线下核准规则版本与核准证据')
    return
  }
  if (![form.grossFen, form.commissionFen, form.netPayableFen].every((value) => Number.isInteger(value) && value >= 0) || !balanced.value) {
    ElMessage.warning('金额必须为非负整数分，且核销基数 - 佣金 = 应结净额')
    return
  }
  saving.value = true
  try {
    const saved = await verifySettlementBatch(batch.id, { version: batch.version, ...form, ruleVersion: form.ruleVersion.trim(), verificationEvidence: form.verificationEvidence.trim() }, idempotencyKey)
    ElMessage.success('人工核准金额已保存，可登记真实付款')
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
  <el-drawer :model-value="modelValue" title="人工核准结算金额" size="min(620px, 100vw)" @update:model-value="updateVisible">
    <template v-if="batch">
      <el-alert type="warning" :closable="false" title="只有已线下核准的规则版本、金额与证据才能解除待核实状态；不要按页面费率快照自动推算。" class="finance-action-note" />
      <div class="finance-order-facts"><strong>批次 #{{ batch.id }} · 机构 #{{ batch.institutionId }}</strong><span>{{ settlementPeriodLabel(batch.settlementType, batch.periodKey) }} · 当前金额待核实</span></div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="线下核准规则版本" required><el-input v-model="form.ruleVersion" maxlength="96" placeholder="填写真实核准的规则版本" data-testid="settlement-rule-version" /></el-form-item>
        <div class="finance-form-grid">
          <el-form-item label="核销基数（分）" required><el-input-number v-model="form.grossFen" :min="0" :precision="0" :controls="false" class="finance-field" data-testid="settlement-gross-fen" /></el-form-item>
          <el-form-item label="佣金（分）" required><el-input-number v-model="form.commissionFen" :min="0" :precision="0" :controls="false" class="finance-field" data-testid="settlement-commission-fen" /></el-form-item>
          <el-form-item label="应结净额（分）" required class="finance-full"><el-input-number v-model="form.netPayableFen" :min="0" :precision="0" :controls="false" class="finance-field" data-testid="settlement-net-fen" /></el-form-item>
        </div>
        <el-alert :type="balanced ? 'success' : 'error'" :closable="false" :title="`核销基数 ¥${fenToYuan(form.grossFen)} - 佣金 ¥${fenToYuan(form.commissionFen)} = 净额 ¥${fenToYuan(form.netPayableFen)}${balanced ? '，等式一致' : '，等式不一致'}`" class="finance-action-note" data-testid="settlement-balance-check" />
        <el-form-item label="人工核准证据" required><el-input v-model="form.verificationEvidence" maxlength="192" placeholder="计费核准单或财务复核记录编号" data-testid="settlement-verification-evidence" /></el-form-item>
      </el-form>
    </template>
    <template #footer><div class="finance-drawer-footer"><el-button :disabled="saving" @click="updateVisible(false)">取消</el-button><el-button type="primary" :loading="saving" :disabled="!balanced" data-testid="settlement-verify-submit" @click="save">保存人工核准金额</el-button></div></template>
  </el-drawer>
</template>
