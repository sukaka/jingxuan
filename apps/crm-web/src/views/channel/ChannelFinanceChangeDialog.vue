<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { Channel, CreateChannelFinancialChangeRequest } from '../../api/channel'
import { createChannelFinancialChangeRequest } from '../../api/channel'
import { ApiError } from '../../types/api'
import { hasMaskedWildcard } from '../../utils/channelValidation'

const props = defineProps<{ modelValue: boolean; channel: Channel | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; submitted: [] }>()

const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
let idempotencyKey = crypto.randomUUID()

const hasPaymentAccountConfigured = computed(() => Boolean(
  props.channel?.paymentAccountConfigured
  ?? (props.channel?.paymentAccountName || props.channel?.paymentAccountNo),
))

const form = reactive({
  commissionPercent: undefined as number | undefined,
  settlementMethod: '',
  paymentAccountName: '',
  paymentAccountNo: '',
  paymentBankName: '',
  reason: '',
})

const rules: FormRules = {
  paymentAccountName: [{
    validator: (_rule, value, callback) => {
      if (value && hasMaskedWildcard(String(value))) {
        callback(new Error('收款账户名称不能包含脱敏占位符 *，请留空或输入完整真实名称'))
      } else callback()
    },
    trigger: 'blur',
  }],
  paymentAccountNo: [{
    validator: (_rule, value, callback) => {
      if (value && hasMaskedWildcard(String(value))) {
        callback(new Error('收款账号不能包含脱敏占位符 *，请留空或输入完整真实账号'))
      } else callback()
    },
    trigger: 'blur',
  }],
}

const settlementOptions = computed(() => {
  const type = props.channel?.channelType ?? 'BLOGGER'
  return type === 'BLOGGER'
    ? [{ code: 'MONTHLY', label: '电汇月结' }]
    : [{ code: 'MONTHLY', label: '电汇月结' }, { code: 'TWICE_MONTHLY', label: '电汇每月两次' }]
})

function reset() {
  const item = props.channel
  form.commissionPercent = item?.commissionPercent ?? undefined
  form.settlementMethod = item?.settlementMethod ?? ''
  form.paymentAccountName = ''
  form.paymentAccountNo = ''
  form.paymentBankName = item?.paymentBankName ?? ''
  form.reason = ''
  errorMessage.value = ''
  idempotencyKey = crypto.randomUUID()
  void nextTick(() => formRef.value?.clearValidate())
}

watch(() => props.modelValue, (open) => {
  if (open) reset()
}, { immediate: true })

watch(
  () => [form.commissionPercent, form.settlementMethod, form.paymentAccountName, form.paymentAccountNo, form.paymentBankName, form.reason],
  () => {
    if (!submitting.value) idempotencyKey = crypto.randomUUID()
  },
)

async function submit() {
  if (!props.channel || submitting.value) return
  if (hasMaskedWildcard(form.paymentAccountName)) {
    errorMessage.value = '收款账户名称不能包含脱敏占位符 *，请留空或输入完整真实名称'
    await formRef.value?.validate().catch(() => false)
    return
  }
  if (hasMaskedWildcard(form.paymentAccountNo)) {
    errorMessage.value = '收款账号不能包含脱敏占位符 *，请留空或输入完整真实账号'
    await formRef.value?.validate().catch(() => false)
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (valid === false) return
  if (!form.reason.trim()) {
    errorMessage.value = '请填写财务资料变更原因'
    return
  }
  const body: CreateChannelFinancialChangeRequest = {
    baseVersion: props.channel.version,
    reason: form.reason.trim(),
  }
  let hasFieldChange = false
  if (form.commissionPercent !== undefined && form.commissionPercent !== props.channel.commissionPercent) {
    body.commissionPercent = form.commissionPercent
    hasFieldChange = true
  }
  if (form.settlementMethod.trim() && form.settlementMethod.trim() !== (props.channel.settlementMethod ?? '')) {
    body.settlementMethod = form.settlementMethod.trim()
    hasFieldChange = true
  }
  if (form.paymentAccountName.trim()) {
    body.paymentAccountName = form.paymentAccountName.trim()
    hasFieldChange = true
  }
  if (form.paymentAccountNo.trim()) {
    body.paymentAccountNo = form.paymentAccountNo.trim()
    hasFieldChange = true
  }
  if (form.paymentBankName.trim() && form.paymentBankName.trim() !== (props.channel.paymentBankName ?? '')) {
    body.paymentBankName = form.paymentBankName.trim()
    hasFieldChange = true
  }
  if (!hasFieldChange) {
    errorMessage.value = '请至少修改一项财务或收款资料'
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    await createChannelFinancialChangeRequest(props.channel.id, body, idempotencyKey)
    ElMessage.success('财务资料变更申请已提交，等待财务审批')
    visible.value = false
    emit('submitted')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : error instanceof Error ? error.message : '提交变更申请失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="channel ? `发起财务资料变更 · ${channel.name}` : '发起财务资料变更'" width="min(680px, 95vw)" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      class="finance-alert"
      title="财务与收款资料变更须经具有财务审批权限的他人复核生效，提交人不可自审。"
    />
    <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" class="finance-alert" data-testid="channel-finance-error" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <div class="finance-grid">
        <el-form-item label="分佣比例（%）" prop="commissionPercent">
          <el-input-number v-model="form.commissionPercent" :min="0" :max="100" :precision="2" controls-position="right" class="full-width" data-testid="finance-commission" />
        </el-form-item>
        <el-form-item label="结算方式" prop="settlementMethod">
          <el-select v-model="form.settlementMethod" clearable class="full-width" placeholder="请选择结算方式" data-testid="finance-settlement">
            <el-option v-for="option in settlementOptions" :key="option.code" :label="option.label" :value="option.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="新收款账户名称（留空不改）" prop="paymentAccountName">
          <el-input
            v-model="form.paymentAccountName"
            maxlength="160"
            :placeholder="hasPaymentAccountConfigured ? '已配置（留空保持不变，输入完整新账户名称覆盖）' : '输入新账户名称'"
            data-testid="finance-account-name"
          />
        </el-form-item>
        <el-form-item label="新收款账号（留空不改）" prop="paymentAccountNo">
          <el-input
            v-model="form.paymentAccountNo"
            maxlength="64"
            :placeholder="hasPaymentAccountConfigured ? '已配置（留空保持不变，输入完整新收款账号覆盖）' : '输入新收款账号'"
            data-testid="finance-account-no"
          />
        </el-form-item>
      </div>
      <el-form-item label="新收款开户行">
        <el-input v-model="form.paymentBankName" maxlength="255" placeholder="输入开户行" data-testid="finance-bank" />
      </el-form-item>
      <el-form-item label="变更原因" required>
        <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="500" placeholder="说明本次分佣比例或收款资料调整依据" data-testid="finance-reason" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" data-testid="finance-submit" @click="submit">提交财务审批</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.finance-alert { margin-bottom: 16px; }
.finance-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.full-width { width: 100%; }
@media (max-width: 640px) { .finance-grid { grid-template-columns: 1fr; } }
</style>
