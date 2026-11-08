<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { DictionaryOption } from '../../api/dictionary'
import { createMaterial, createMaterialRevision } from '../../api/material'
import type { Material, MaterialRevision } from '../../api/material'
import { ApiError } from '../../types/api'
import FileUploadField from '../institution/FileUploadField.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  material: Material | null
  latestRevision?: MaterialRevision | null
  categories: DictionaryOption[]
  cities: DictionaryOption[]
}>(), {
  latestRevision: null,
})
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [material: Material]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const isCreate = computed(() => props.material === null)
const form = reactive({
  title: '',
  categoryCode: '',
  institutionId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  cityCode: '',
  summary: '',
  tagsText: '',
  changeNote: '',
  fileId: undefined as number | undefined,
})
const submitting = ref(false)
const errorMessage = ref('')
const uploadKey = ref(0)
let idempotencyKey = ''

function newKey() {
  idempotencyKey = crypto.randomUUID()
}

function reset() {
  const source = props.latestRevision ?? props.material
  Object.assign(form, {
    title: source?.title ?? '',
    categoryCode: source?.categoryCode ?? props.categories[0]?.code ?? '',
    institutionId: source?.institutionId ?? undefined,
    projectId: source?.projectId ?? undefined,
    cityCode: source?.cityCode ?? '',
    summary: source?.summary ?? '',
    tagsText: source?.tags?.join('，') ?? '',
    changeNote: '',
    fileId: undefined,
  })
  errorMessage.value = ''
  submitting.value = false
  uploadKey.value++
  newKey()
}

watch(
  () => [props.modelValue, props.material?.id, props.latestRevision?.id],
  ([visible]) => { if (visible) reset() },
  { immediate: true },
)
watch(form, () => { if (!submitting.value) newKey() }, { deep: true })

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  }
  return error instanceof Error ? error.message : '素材保存失败，请重试'
}

async function submit() {
  if (!form.title.trim() || !form.categoryCode || !form.changeNote.trim()) {
    errorMessage.value = '请填写素材标题、类型和版本变更说明'
    return
  }
  if (!form.fileId) {
    errorMessage.value = '请先完成真实文件上传，并等待 ClamAV 扫描结果为 CLEAN'
    return
  }
  const tags = Array.from(new Set(form.tagsText.split(/[,，\n]/).map((tag) => tag.trim()).filter(Boolean)))
  const metadata = {
    title: form.title.trim(),
    categoryCode: form.categoryCode,
    institutionId: form.institutionId,
    projectId: form.projectId,
    cityCode: form.cityCode || undefined,
    summary: form.summary.trim() || undefined,
    tags,
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const saved = props.material
      ? await createMaterialRevision(props.material.id, {
        ...metadata,
        version: props.material.version,
        fileId: form.fileId,
        changeNote: form.changeNote.trim(),
      }, idempotencyKey)
      : await createMaterial({
        ...metadata,
        fileId: form.fileId,
        changeNote: form.changeNote.trim(),
      }, idempotencyKey)
    emit('saved', saved)
    visible.value = false
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isCreate ? '新增素材与首个版本' : '追加素材版本'"
    width="min(760px,96vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
  >
    <div class="revision-intro">
      <strong>{{ isCreate ? '新素材 · 首个 DRAFT 版本' : `${material?.materialNo} · 新版本` }}</strong>
      <span>{{ isCreate ? '素材编号由服务端生成；CLEAN 文件与首个草稿版本一次提交。' : '只追加 DRAFT 版本，不覆盖已审核历史；机构归属不可在修订中变更。' }}送审后由具备权限的审核人处理。</span>
    </div>
    <el-alert
      v-if="errorMessage"
      type="error"
      :title="errorMessage"
      :closable="false"
      class="revision-error"
      data-testid="revision-error"
    />
    <el-form label-position="top" class="proto-form-grid" @submit.prevent>
      <el-form-item label="素材标题" required>
        <el-input v-model="form.title" maxlength="160" show-word-limit data-testid="revision-title" />
      </el-form-item>
      <el-form-item label="素材类型" required>
        <el-select v-model="form.categoryCode" placeholder="选择字典中的素材类型" data-testid="revision-category">
          <el-option v-for="item in categories" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>
      </el-form-item>
      <el-form-item :label="isCreate ? '所属机构 ID（留空为受控全局素材）' : '所属机构 ID（版本间不可变更）'">
        <el-input-number v-model="form.institutionId" :min="1" :controls="false" :disabled="!isCreate" placeholder="机构 ID" data-testid="revision-institution" />
      </el-form-item>
      <el-form-item label="关联项目 ID（可选）">
        <el-input-number v-model="form.projectId" :min="1" :controls="false" placeholder="项目 ID" data-testid="revision-project" />
      </el-form-item>
      <el-form-item label="城市">
        <el-select v-model="form.cityCode" clearable filterable placeholder="选择城市" data-testid="revision-city">
          <el-option v-for="item in cities" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>
      </el-form-item>
      <el-form-item label="标签（逗号分隔）">
        <el-input v-model="form.tagsText" placeholder="例如：案例，授权已核对" data-testid="revision-tags" />
      </el-form-item>
      <el-form-item label="内容摘要" class="proto-full">
        <el-input v-model="form.summary" type="textarea" :rows="3" maxlength="2000" show-word-limit data-testid="revision-summary" />
      </el-form-item>
      <el-form-item label="版本变更说明" required class="proto-full">
        <el-input v-model="form.changeNote" type="textarea" :rows="3" maxlength="1000" :placeholder="isCreate ? '说明首个版本的资料来源与用途' : '说明本版相对上一版的实际变化'" data-testid="revision-change-note" />
      </el-form-item>
    </el-form>
    <FileUploadField
      :key="uploadKey"
      v-model="form.fileId"
      classification="MATERIAL"
      :institution-id="form.institutionId"
      label="版本文件（PDF / JPEG / PNG / TXT / Markdown）"
    />
    <p class="upload-rule">文件须完成 SHA-256 校验、真实预签名上传、ETag 登记，并取得 ClamAV CLEAN；扫描中或失败均不能保存版本。</p>
    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" data-testid="revision-save" @click="submit">{{ isCreate ? '创建素材草稿' : '保存为新版本' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.revision-intro { display: grid; gap: 6px; margin-bottom: 18px; padding: 14px 16px; border-radius: 10px; color: #476373; background: #f2f8f7; font-size: 12px; line-height: 1.7; }
.revision-intro strong { color: #183d3a; }
.revision-error { margin-bottom: 16px; }
.upload-rule { margin: 10px 0 0; color: #8a7454; font-size: 11px; line-height: 1.7; }
:deep(.el-select), :deep(.el-input-number) { width: 100%; }
:deep(.institution-file-box) { padding: 16px; border: 1px solid #e4ecef; border-radius: 11px; background: #fafcfc; }
:deep(.institution-file-box input[type='file']) { width: 100%; color: #627b87; font-size: 12px; }
:deep(.institution-file-meta) { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 14px; }
:deep(.institution-file-name) { min-width: 0; overflow-wrap: anywhere; }
:deep(.institution-file-success) { margin-top: 10px; color: #218361; font-size: 12px; }
:deep(.institution-file-error) { margin-top: 10px; color: #b74957; font-size: 12px; line-height: 1.6; }
@media (max-width: 600px) {
  :deep(.institution-file-meta) { align-items: stretch; flex-direction: column; }
}
</style>
