<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { InstitutionContract, InstitutionQualification } from '../../api/institution'
import { createInstitutionContract, createInstitutionQualification } from '../../api/institution'
import { tradingErrorText } from '../trading/tradingUi'
import FileUploadField from './FileUploadField.vue'
import { createInstitutionKey } from './institutionUi'
import { yuanToFenExact } from './number'

const props = defineProps<{
  modelValue: boolean
  mode: 'contract' | 'qualification'
  institutionId: number
  previousContract?: InstitutionContract | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [document: InstitutionContract | InstitutionQualification]
}>()

const form = reactive({
  previousContractId: undefined as number | undefined,
  contractNo: '',
  title: '',
  contractType: '',
  startDate: '',
  endDate: '',
  amountYuan: '',
  notes: '',
  qualificationType: '',
  licenseNo: '',
  validFrom: '',
  validTo: '',
  fileId: undefined as number | undefined,
})
const saving = ref(false)
const idempotencyKey = ref('')

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, {
      previousContractId: props.previousContract?.id,
      contractNo: props.previousContract?.contractNo ?? '',
      title: '',
      contractType: '',
      startDate: '',
      endDate: '',
      amountYuan: '',
      notes: '',
      qualificationType: '',
      licenseNo: '',
      validFrom: '',
      validTo: '',
      fileId: undefined,
    })
    idempotencyKey.value = createInstitutionKey(`institution.${props.mode}.create`)
  },
)

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  if (!form.fileId) {
    ElMessage.warning('必须先完成真实文件上传并通过安全扫描')
    return
  }
  saving.value = true
  try {
    if (props.mode === 'contract') {
      if (!form.contractNo.trim() || !form.contractType.trim() || !form.startDate) {
        ElMessage.warning('请完整填写合同编号、类型和开始日期')
        return
      }
      if (form.endDate && form.endDate < form.startDate) {
        ElMessage.warning('合同结束日期不能早于开始日期')
        return
      }
      const saved = await createInstitutionContract({
        institutionId: props.institutionId,
        previousContractId: form.previousContractId,
        contractNo: form.contractNo.trim(),
        title: form.title.trim(),
        contractType: form.contractType.trim(),
        startDate: form.startDate,
        endDate: form.endDate || undefined,
        fileId: form.fileId,
        amountFen: form.amountYuan.trim() ? yuanToFenExact(form.amountYuan) : undefined,
        notes: form.notes.trim() || undefined,
      }, idempotencyKey.value)
      ElMessage.success('合同新版本已保存为待审核，原合同未被覆盖；请继续提交激活申请')
      emit('saved', saved)
      emit('update:modelValue', false)
    } else {
      if (!form.qualificationType.trim()) {
        ElMessage.warning('请填写资质类型')
        return
      }
      if (form.validFrom && form.validTo && form.validTo < form.validFrom) {
        ElMessage.warning('资质结束日期不能早于开始日期')
        return
      }
      const saved = await createInstitutionQualification({
        institutionId: props.institutionId,
        qualificationType: form.qualificationType.trim(),
        title: form.title.trim(),
        licenseNo: form.licenseNo.trim() || undefined,
        fileId: form.fileId,
        validFrom: form.validFrom || undefined,
        validTo: form.validTo || undefined,
      }, idempotencyKey.value)
      ElMessage.success('资质已保存为待审核，请继续提交审核申请')
      emit('saved', saved)
      emit('update:modelValue', false)
    }
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer :model-value="modelValue" :title="mode === 'contract' ? (previousContract ? '创建合同下一版本' : '新建合同') : '新建机构资质'" size="min(780px, 96vw)" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="info" :closable="false" title="文件必须先真实上传并扫描通过；保存仅生成待审核记录，不等于合同激活或资质审核通过。" class="form-tip" />
    <div v-if="mode === 'contract' && previousContract" class="institution-sensitive">原合同 {{ previousContract.contractNo }} · 第 {{ previousContract.versionNo }} 版保留为历史，新记录由后端生成下一版本号。</div>
    <el-form label-position="top" @submit.prevent>
      <template v-if="mode === 'contract'">
        <div class="institution-profile-grid">
          <el-form-item label="合同编号" required><el-input v-model="form.contractNo" :disabled="!!previousContract" maxlength="96" /></el-form-item>
          <el-form-item label="合同类型" required><el-input v-model="form.contractType" maxlength="64" placeholder="按实际合同类型填写" data-testid="document-type" /></el-form-item>
          <el-form-item label="合同标题" required><el-input v-model="form.title" maxlength="192" data-testid="document-title" /></el-form-item>
          <el-form-item label="合同金额（元，可选）"><el-input v-model="form.amountYuan" placeholder="不推断金额，按合同原文填写" data-testid="document-amount" /></el-form-item>
          <el-form-item label="开始日期" required><el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" class="full-width" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" class="full-width" /></el-form-item>
          <el-form-item label="合同备注" class="institution-full"><el-input v-model="form.notes" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        </div>
      </template>
      <template v-else>
        <div class="institution-profile-grid">
          <el-form-item label="资质类型" required><el-input v-model="form.qualificationType" maxlength="64" placeholder="如 医疗机构执业许可证 / 营业执照" /></el-form-item>
          <el-form-item label="资质标题" required><el-input v-model="form.title" maxlength="192" /></el-form-item>
          <el-form-item label="证照编号（可选）"><el-input v-model="form.licenseNo" maxlength="96" autocomplete="off" /></el-form-item>
          <el-form-item label="生效日期"><el-date-picker v-model="form.validFrom" type="date" value-format="YYYY-MM-DD" class="full-width" /></el-form-item>
          <el-form-item label="到期日期"><el-date-picker v-model="form.validTo" type="date" value-format="YYYY-MM-DD" class="full-width" /></el-form-item>
        </div>
      </template>
      <el-form-item :label="mode === 'contract' ? '合同文件（必须扫描通过）' : '资质文件（必须扫描通过）'" required>
        <FileUploadField v-model="form.fileId" :institution-id="institutionId" :classification="mode === 'contract' ? 'CONTRACT' : 'QUALIFICATION'" :label="mode === 'contract' ? '上传合同 PDF' : '上传资质 PDF'" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="document-save" @click="save">保存待审核记录</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.form-tip { margin-bottom: 16px; }
</style>
