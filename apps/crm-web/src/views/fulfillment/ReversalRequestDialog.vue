<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Redemption, ReversalDetail } from '../../api/fulfillment'
import { requestRedemptionReversal } from '../../api/fulfillment'
import { tradingErrorText } from '../trading/tradingUi'
import { useReferenceNames } from '../references'
import { createFulfillmentIdempotencyKey } from './fulfillmentUi'

const { loadCustomerNames, customerLabel, institutionLabel } = useReferenceNames()

const props = defineProps<{
  modelValue: boolean
  redemption: Redemption | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: ReversalDetail]
}>()

const reason = ref('')
const saving = ref(false)
const idempotencyKey = ref('')

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    reason.value = ''
    idempotencyKey.value = createFulfillmentIdempotencyKey('redemption.reversal.request')
    if (props.redemption) void loadCustomerNames([props.redemption.customerId])
  },
)

async function save() {
  const redemption = props.redemption
  if (!redemption || redemption.status !== 'POSTED') {
    ElMessage.warning('只有原正式核销记录可以申请冲正')
    return
  }
  if (!reason.value.trim()) {
    ElMessage.warning('请填写冲正原因')
    return
  }
  saving.value = true
  try {
    const saved = await requestRedemptionReversal(redemption.id, {
      redemptionVersion: redemption.version,
      reason: reason.value.trim(),
    }, idempotencyKey.value)
    ElMessage.success('冲正申请已提交；原正式核销记录未修改，申请本身不改变余额')
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
  <el-dialog :model-value="modelValue" title="申请核销冲正" width="min(560px, 94vw)" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="warning" :closable="false" title="这里只创建冲正申请，不直接更正原核销或增加权益。审核通过后应新增独立补偿流水。" class="reversal-tip" />
    <div v-if="redemption" class="fulfillment-readonly">原核销 #{{ redemption.id }} · {{ customerLabel(redemption.customerId) }} · {{ institutionLabel(redemption.institutionId) }} · 事实版本 {{ redemption.version }}</div>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="冲正原因" required>
        <el-input v-model="reason" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="说明需要复核的真实履约异常与依据" data-testid="reversal-reason" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="reversal-request-submit" @click="save">提交冲正申请</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reversal-tip { margin-bottom: 14px; }
</style>
