<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { InstitutionChangeRequest } from '../../api/institution'
import { requestContractActivation, requestInstitutionActivation, requestQualificationReview } from '../../api/institution'
import { tradingErrorText } from '../trading/tradingUi'
import FileUploadField from './FileUploadField.vue'
import { createInstitutionKey } from './institutionUi'

const props = defineProps<{
  modelValue: boolean
  mode: 'institution' | 'contract' | 'qualification'
  targetId: number
  baseVersion: number
  institutionId: number
  title: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [request: InstitutionChangeRequest]
}>()

const form = reactive({ reason: '', evidenceFileId: undefined as number | undefined })
const saving = ref(false)
const idempotencyKey = ref('')

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    form.reason = ''
    form.evidenceFileId = undefined
    idempotencyKey.value = createInstitutionKey(`institution.${props.mode}.workflow`)
  },
)

async function save() {
  if (!form.reason.trim()) {
    ElMessage.warning('请填写申请原因')
    return
  }
  saving.value = true
  try {
    const body = { baseVersion: props.baseVersion, reason: form.reason.trim(), evidenceFileId: form.evidenceFileId }
    const saved = props.mode === 'institution'
      ? await requestInstitutionActivation(props.targetId, body, idempotencyKey.value)
      : props.mode === 'contract'
        ? await requestContractActivation(props.targetId, body, idempotencyKey.value)
        : await requestQualificationReview(props.targetId, body, idempotencyKey.value)
    ElMessage.success('工作流申请已提交，请按实际审核步骤继续处理')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；请核对基准版本后重试`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="min(620px, 94vw)" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="warning" :closable="false" :title="mode === 'qualification' ? '这里只提交资质审核申请，市场审核通过前资质不会生效。' : '这里只提交激活申请，市场与财务双方审核完成前不会生效。'" class="dialog-tip" />
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="申请原因" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="1000" data-testid="workflow-reason" /></el-form-item>
      <el-form-item label="申请凭证（可选，必须扫描通过）">
        <FileUploadField v-model="form.evidenceFileId" :institution-id="institutionId" classification="CHANGE_EVIDENCE" label="上传工作流凭证" />
      </el-form-item>
      <div class="institution-version">基准版本 {{ baseVersion }}</div>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="workflow-submit" @click="save">提交工作流申请</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-tip { margin-bottom: 14px; }
</style>
