<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FileClassification, FileMetadata, UploadInitResponse } from '../../api/file'
import {
  completeFileUpload,
  getFileMetadata,
  initFileUpload,
  putPresignedFile,
  sha256File,
  waitForFileClean,
} from '../../api/file'
import { tradingErrorText } from '../trading/tradingUi'

const props = withDefaults(defineProps<{
  modelValue?: number
  institutionId?: number
  classification: FileClassification
  label?: string
}>(), {
  modelValue: undefined,
  institutionId: undefined,
  label: '上传文件',
})
const emit = defineEmits<{
  'update:modelValue': [value: number | undefined]
  uploaded: [metadata: FileMetadata]
}>()

type Stage = 'IDLE' | 'HASHING' | 'INIT' | 'PUT' | 'COMPLETE' | 'SCANNING' | 'CLEAN' | 'ERROR'

const selectedFile = ref<File | null>(null)
const init = ref<UploadInitResponse | null>(null)
const etag = ref('')
const metadata = ref<FileMetadata | null>(null)
const stage = ref<Stage>('IDLE')
const failedStage = ref<Stage | null>(null)
const errorMessage = ref('')
const inputKey = ref(0)
const busy = computed(() => ['HASHING', 'INIT', 'PUT', 'COMPLETE', 'SCANNING'].includes(stage.value))
const accept = computed(() => props.classification === 'MATERIAL' ? '.pdf,.txt,.md,.markdown,image/jpeg,image/png' : '.pdf,image/jpeg,image/png')
const stageText = computed(() => {
  if (stage.value === 'HASHING') return '正在计算文件 SHA-256'
  if (stage.value === 'INIT') return '正在申请真实上传地址'
  if (stage.value === 'PUT') return '正在上传私有对象'
  if (stage.value === 'COMPLETE') return '正在校验文件标识、内容与大小'
  if (stage.value === 'SCANNING') return '正在查询安全扫描结果'
  if (stage.value === 'CLEAN') return '文件已通过安全扫描，可引用'
  if (stage.value === 'ERROR') return '上传或扫描未完成，请按阶段重试'
  return props.classification === 'MATERIAL' ? '选择 PDF、图片、TXT 或 Markdown 后执行真实上传与安全扫描' : '选择 PDF 或图片后执行真实上传与安全扫描'
})

function hasControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => (character.codePointAt(0) ?? 0) < 32)
}

function resolveMimeType(file: File): string {
  if (props.classification === 'MATERIAL' && /\.(md|markdown)$/i.test(file.name)
    && (!file.type || ['text/plain', 'text/x-markdown', 'application/octet-stream'].includes(file.type))) {
    return 'text/markdown'
  }
  if (props.classification === 'MATERIAL' && /\.txt$/i.test(file.name)
    && (!file.type || file.type === 'application/octet-stream')) {
    return 'text/plain'
  }
  return file.type
}

function validateFile(file: File): string {
  if (!file.name || file.name.includes('/') || file.name.includes('\\') || hasControlCharacter(file.name)) return '文件名不合法'
  if (file.size <= 0) return '不能上传空文件'
  const pdfOnly = props.classification === 'CONTRACT' || props.classification === 'QUALIFICATION' || props.classification === 'CHANGE_EVIDENCE'
  const allowed = props.classification === 'MATERIAL'
    ? ['application/pdf', 'image/jpeg', 'image/png', 'text/plain', 'text/markdown', 'text/x-markdown']
    : pdfOnly ? ['application/pdf'] : ['application/pdf', 'image/jpeg', 'image/png']
  if (!allowed.includes(resolveMimeType(file))) {
    return pdfOnly ? '该文件类别仅接受 PDF' : props.classification === 'MATERIAL' ? '素材仅接受 PDF、JPEG、PNG、TXT 或 Markdown' : '仅接受 PDF、JPEG 或 PNG 文件'
  }
  return ''
}

function clearSelection() {
  selectedFile.value = null
  init.value = null
  etag.value = ''
  metadata.value = null
  stage.value = 'IDLE'
  failedStage.value = null
  errorMessage.value = ''
  inputKey.value++
  emit('update:modelValue', undefined)
}

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  clearSelection()
  selectedFile.value = file
  const validation = validateFile(file)
  if (validation) {
    stage.value = 'ERROR'
    errorMessage.value = validation
  }
}

function finish(saved: FileMetadata) {
  metadata.value = saved
  stage.value = 'CLEAN'
  failedStage.value = null
  errorMessage.value = ''
  emit('update:modelValue', saved.id)
  emit('uploaded', saved)
}

async function upload() {
  const file = selectedFile.value
  if (!file) {
    stage.value = 'ERROR'
    errorMessage.value = '请先选择真实文件'
    return
  }
  const validation = validateFile(file)
  if (validation) {
    stage.value = 'ERROR'
    errorMessage.value = validation
    return
  }

  errorMessage.value = ''
  failedStage.value = null
  try {
    if (!init.value) {
      stage.value = 'HASHING'
      const sha256 = await sha256File(file)
      stage.value = 'INIT'
      init.value = await initFileUpload({
        originalName: file.name,
        mimeType: resolveMimeType(file),
        sizeBytes: file.size,
        sha256,
        classification: props.classification,
        institutionId: props.institutionId,
      })
    }
    if (init.value.method !== 'PUT') throw new Error('上传地址的上传方式不符合要求')
    if (!etag.value) {
      stage.value = 'PUT'
      etag.value = await putPresignedFile({ url: init.value.uploadUrl, file })
    }
    stage.value = 'COMPLETE'
    const completed = await completeFileUpload({ fileId: init.value.fileId, etag: etag.value })
    metadata.value = completed
    if (completed.scanStatus === 'CLEAN') {
      finish(completed)
      return
    }
    stage.value = 'SCANNING'
    const clean = await waitForFileClean(init.value.fileId)
    finish(clean)
  } catch (error) {
    failedStage.value = stage.value
    stage.value = 'ERROR'
    errorMessage.value = tradingErrorText(error)
    if (failedStage.value === 'COMPLETE' && init.value) {
      try {
        const current = await getFileMetadata(init.value.fileId)
        metadata.value = current
        if (current.scanStatus === 'CLEAN') finish(current)
      } catch {
        errorMessage.value = tradingErrorText(error)
      }
    }
  }
}

async function retry() {
  if (failedStage.value === 'SCANNING' && init.value) {
    stage.value = 'SCANNING'
    errorMessage.value = ''
    try {
      finish(await waitForFileClean(init.value.fileId))
    } catch (error) {
      stage.value = 'ERROR'
      errorMessage.value = tradingErrorText(error)
    }
    return
  }
  await upload()
}

async function verifyExisting(id: number) {
  stage.value = 'SCANNING'
  errorMessage.value = ''
  try {
    const current = await getFileMetadata(id)
    if (current.scanStatus === 'CLEAN') {
      finish(current)
    } else {
      metadata.value = current
      stage.value = 'ERROR'
      errorMessage.value = '关联文件尚未通过安全扫描，不能引用'
      emit('update:modelValue', undefined)
    }
  } catch (error) {
    stage.value = 'ERROR'
    errorMessage.value = tradingErrorText(error)
  }
}

watch(
  () => props.modelValue,
  (id) => {
    if (id && metadata.value?.id !== id && stage.value !== 'CLEAN') void verifyExisting(id)
  },
  { immediate: true },
)
</script>

<template>
  <div class="institution-file-box" data-testid="file-upload-field">
    <label class="file-label">{{ label }}</label>
    <input :key="inputKey" type="file" :accept="accept" :disabled="busy" data-testid="file-input" @change="selectFile">
    <div class="institution-file-meta">
      <div class="institution-file-name">
        <strong>{{ selectedFile?.name || metadata?.originalName || '尚未选择文件' }}</strong>
        <div class="crm-muted">{{ stageText }}<span v-if="init"> · 文件 #{{ init.fileId }}</span></div>
      </div>
      <el-button v-if="stage !== 'CLEAN'" type="primary" plain :loading="busy" data-testid="file-upload-submit" @click="stage === 'ERROR' ? retry() : upload()">
        {{ stage === 'ERROR' ? '按阶段重试' : '开始上传' }}
      </el-button>
      <el-button v-else disabled>扫描通过</el-button>
    </div>
    <div v-if="errorMessage" class="institution-file-error" data-testid="file-upload-error">{{ errorMessage }}</div>
    <div v-if="stage === 'CLEAN' && metadata" class="institution-file-success" data-testid="file-upload-clean">
      {{ metadata.originalName }} · 扫描通过 · 文件 ID {{ metadata.id }}
    </div>
    <div v-if="selectedFile || init" class="file-reset">
      <el-button text size="small" :disabled="busy" @click="clearSelection">清除并重新选择</el-button>
    </div>
  </div>
</template>

<style scoped>
.file-label { display: block; margin-bottom: 8px; color: #526f7b; font-size: 12px; font-weight: 600; }
.file-reset { margin-top: 6px; }
</style>
