<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { InstitutionChangeRequest, InstitutionProfile } from '../../api/institution'
import { createInstitutionChange } from '../../api/institution'
import { useDictionaries } from '../customer/shared'
import { tradingErrorText } from '../trading/tradingUi'
import FileUploadField from './FileUploadField.vue'
import { changeValueLabel, createInstitutionKey, rateDisplay, requiresBothSteps, SENSITIVE_FIELDS } from './institutionUi'
import { fenToYuanExact, percentToDecimalRate, yuanToFenExact } from './number'

const props = defineProps<{
  modelValue: boolean
  profile: InstitutionProfile
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [request: InstitutionChangeRequest]
}>()

const { options, load } = useDictionaries()
const form = reactive({
  fieldCode: '',
  newText: '',
  newBoolean: false,
  reason: '',
  evidenceFileId: undefined as number | undefined,
})
const saving = ref(false)
const idempotencyKey = ref('')
const meta = computed(() => SENSITIVE_FIELDS.find((item) => item.code === form.fieldCode))
const reviewPath = computed(() => {
  if (!form.fieldCode) return '按字段确定审核路径'
  const first = ['bankName', 'bankAccount', 'taxId', 'settlementName'].includes(form.fieldCode) ? '财务审核' : '市场审核'
  return requiresBothSteps({ changeType: 'PROFILE_FIELD', fieldCode: form.fieldCode }) ? `${first} → 另一岗位复核` : `仅需${first}`
})

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, { fieldCode: '', newText: '', newBoolean: false, reason: '', evidenceFileId: undefined })
    idempotencyKey.value = createInstitutionKey('institution.profile.field-change')
    void load('institutionBusinessTypes', 'institutionOwnerships', 'merchantGrades', 'settlementTypes')
  },
)

watch(
  () => form.fieldCode,
  () => {
    form.newText = ''
    form.newBoolean = false
  },
)

function currentMasked(): string {
  const profile = props.profile
  switch (form.fieldCode) {
    case 'fullName': return profile.fullName
    case 'businessTypeCode': return changeValueLabel('businessTypeCode', profile.businessTypeCode)
    case 'ownershipCode': return changeValueLabel('ownershipCode', profile.ownershipCode)
    case 'gradeCode': return changeValueLabel('gradeCode', profile.gradeCode)
    case 'settlementType': return changeValueLabel('settlementType', profile.settlementType)
    case 'onlineRate': return rateDisplay(profile.onlineRate)
    case 'offlineRate': return rateDisplay(profile.offlineRate)
    case 'platformRate': return rateDisplay(profile.platformRate)
    case 'otherRateRule': return profile.otherRateRule ?? '-'
    case 'chargesFee': return changeValueLabel('chargesFee', profile.chargesFee)
    case 'depositFen': return fenToYuanExact(profile.depositFen)
    case 'guaranteeRoi': return changeValueLabel('guaranteeRoi', profile.guaranteeRoi)
    case 'roiTerms': return profile.roiTerms ?? '-'
    case 'settlementName': return profile.settlementName ?? '-'
    case 'bankName': return profile.bankNameMasked ?? '-'
    case 'bankAccount': return profile.bankAccountMasked ?? '-'
    case 'taxId': return profile.taxIdMasked ?? '-'
    default: return '-'
  }
}

function newValue(): unknown {
  const kind = meta.value?.kind
  if (kind === 'rate') return Number(percentToDecimalRate(form.newText))
  if (kind === 'fen') return yuanToFenExact(form.newText)
  if (kind === 'boolean') return form.newBoolean
  return form.newText.trim()
}

async function save() {
  if (!meta.value) {
    ElMessage.warning('请选择允许变更的敏感字段')
    return
  }
  if (meta.value.kind !== 'boolean' && meta.value.kind !== 'textarea' && !form.newText.trim()) {
    ElMessage.warning('请填写申请新值')
    return
  }
  if (meta.value.kind === 'rate' && !form.newText.trim()) {
    ElMessage.warning('请填写佣金率百分比')
    return
  }
  if (!form.reason.trim()) {
    ElMessage.warning('请填写申请原因')
    return
  }
  saving.value = true
  try {
    const saved = await createInstitutionChange({
      institutionId: props.profile.id,
      fieldCode: meta.value.code,
      newValue: newValue(),
      baseVersion: props.profile.version,
      reason: form.reason.trim(),
      evidenceFileId: form.evidenceFileId,
    }, idempotencyKey.value)
    ElMessage.success('敏感变更申请已提交，正式档案尚未修改')
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
  <el-drawer :model-value="modelValue" title="申请敏感资料变更" size="min(760px, 96vw)" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="warning" :closable="false" title="本表单只创建变更申请，不直接编辑正式机构档案；审批值仅以后端脱敏结果展示。" class="form-tip" />
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="变更字段" required>
        <el-select v-model="form.fieldCode" filterable class="full-width" data-testid="sensitive-field">
          <el-option v-for="item in SENSITIVE_FIELDS" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>
      </el-form-item>
      <div v-if="meta" class="institution-sensitive">当前掩码值：{{ currentMasked() }} · 审核路径：{{ reviewPath }}</div>
      <el-form-item v-if="meta" :label="`申请新值（${meta.label}）`" required>
        <el-select v-if="meta.kind === 'businessType'" v-model="form.newText" class="full-width"><el-option v-for="item in options('institutionBusinessTypes')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
        <el-select v-else-if="meta.kind === 'ownership'" v-model="form.newText" class="full-width"><el-option v-for="item in options('institutionOwnerships')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
        <el-select v-else-if="meta.kind === 'grade'" v-model="form.newText" class="full-width"><el-option v-for="item in options('merchantGrades')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
        <el-select v-else-if="meta.kind === 'settlementType'" v-model="form.newText" class="full-width"><el-option v-for="item in options('settlementTypes')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
        <el-checkbox v-else-if="meta.kind === 'boolean'" v-model="form.newBoolean">{{ meta.label }}</el-checkbox>
        <el-input v-else-if="meta.kind === 'rate'" v-model="form.newText" placeholder="输入百分比，如 18.25" data-testid="sensitive-rate-value" />
        <el-input v-else-if="meta.kind === 'fen'" v-model="form.newText" placeholder="输入元，如 30000.00" data-testid="sensitive-fen-value" />
        <el-input v-else-if="meta.kind === 'textarea'" v-model="form.newText" type="textarea" :rows="3" maxlength="2000" />
        <el-input v-else v-model="form.newText" :type="meta.code === 'bankAccount' || meta.code === 'taxId' ? 'text' : 'text'" autocomplete="off" data-testid="sensitive-text-value" />
      </el-form-item>
      <el-form-item label="申请原因" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="1000" data-testid="sensitive-reason" /></el-form-item>
      <el-form-item label="申请凭证（可选，必须扫描通过）">
        <FileUploadField v-model="form.evidenceFileId" :institution-id="profile.id" classification="CHANGE_EVIDENCE" label="上传变更凭证" />
      </el-form-item>
      <div class="institution-version">基准版本 {{ profile.version }}</div>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="sensitive-submit" @click="save">提交变更申请</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.form-tip { margin-bottom: 16px; }
.institution-sensitive { margin-bottom: 14px; }
</style>
