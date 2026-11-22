<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getFileMetadata } from '../api/file'
import type { FileMetadata } from '../api/file'
import { getMaterialRevisionDownload, listMaterials } from '../api/material'
import type { Material } from '../api/material'
import { uploadWecomMaterialMedia } from '../api/wecomSidebar'
import { sendFileToCurrentChat, sendImageToCurrentChat } from '../services/wecomSdk'
import type { WecomSendTarget } from '../services/wecomSdk'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

type MaterialFilterType = 'ALL' | 'IMAGE' | 'DOC'
type MaterialKind = 'IMAGE' | 'DOC' | 'UNKNOWN'

const IMAGE_EXT_RE = /\.(png|jpe?g|gif|webp|bmp)$/i
const DOC_EXT_RE = /\.(pdf|docx?|xlsx?|pptx?|txt|md|csv)$/i

const CATEGORY_LABELS: Record<string, string> = {
  CASE: '案例',
  GUIDE: '指南',
  INSTITUTION: '机构',
  DOCTOR: '医生',
  DEVICE: '仪器',
  INSTRUMENT: '仪器',
  PRODUCT: '产品',
  REVIEW: '好评',
}

const props = defineProps<{
  customerId?: number | null
  externalUserid?: string
  chatId?: string
  chatEntry: string
}>()

const emit = defineEmits<{
  'context-mismatch': []
}>()

const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('wecom:sidebar:view') && auth.hasPermission('material:read'))
const isGroupChat = computed(() => props.chatEntry === 'group_chat_tools')
const isSendableEntry = computed(() => props.chatEntry === 'single_chat_tools' || props.chatEntry === 'group_chat_tools')

const materials = ref<Material[]>([])
const fileMetaMap = ref<Record<number, FileMetadata>>({})
const keyword = ref('')
const typeFilter = ref<MaterialFilterType>('ALL')
const selectedId = ref<number | null>(null)
const loading = ref(false)
const sendingId = ref<number | null>(null)
const previewingId = ref<number | null>(null)
const errorMessage = ref('')
const feedback = ref('')
let loadSequence = 0

function approvedFileId(material: Material): number | null {
  const rev = material.revisions?.find((item) => item.id === material.currentRevisionId) ?? material.revisions?.[0]
  return rev?.fileId ?? null
}

function fileMetaFor(material: Material): FileMetadata | null {
  const fileId = approvedFileId(material)
  if (!fileId) return null
  return fileMetaMap.value[fileId] ?? null
}

function classifyMaterial(material: Material): MaterialKind {
  const meta = fileMetaFor(material)
  if (!meta) return 'UNKNOWN'
  const mime = (meta.mimeType || '').toLowerCase()
  const name = meta.originalName || ''
  if (mime.startsWith('image/') || IMAGE_EXT_RE.test(name)) return 'IMAGE'
  if (mime === 'application/pdf' || mime.startsWith('text/') || mime.startsWith('application/') || DOC_EXT_RE.test(name)) {
    return 'DOC'
  }
  return 'UNKNOWN'
}

function kindLabel(material: Material): string {
  const kind = classifyMaterial(material)
  if (kind === 'IMAGE') return '图片'
  if (kind === 'DOC') {
    const meta = fileMetaFor(material)
    return meta?.mimeType?.toLowerCase() === 'application/pdf' ? 'PDF 文档' : '文档'
  }
  return '已审素材'
}

function categoryLabel(code: string): string {
  return CATEGORY_LABELS[code] ?? code
}

function formatBytes(sizeBytes: number | undefined | null): string {
  if (typeof sizeBytes !== 'number' || Number.isNaN(sizeBytes) || sizeBytes < 0) return '—'
  if (sizeBytes < 1024) return `${sizeBytes} B`
  if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
  return `${(sizeBytes / (1024 * 1024)).toFixed(2)} MB`
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Shanghai',
  }).format(date)
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'PENDING_BUSINESS_RULE') {
      return `企微官方临时素材通道或文件存储尚未配置，暂不可发送：${error.message}`
    }
    if (error.code === 'CONFLICT') {
      return `${error.message}（素材可能已下架、未审核或未通过安全扫描）`
    }
    if (error.code === 'VALIDATION_FAILED') {
      return `${error.message}（企微限制：图片不超过 10MB，文件不超过 20MB）`
    }
    return error.message
  }
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function buildSendTarget(): WecomSendTarget | undefined {
  if (props.chatEntry === 'single_chat_tools' && props.externalUserid?.trim()) {
    return { expectedExternalUserid: props.externalUserid.trim() }
  }
  if (props.chatEntry === 'group_chat_tools' && props.chatId?.trim()) {
    return { expectedChatId: props.chatId.trim() }
  }
  return undefined
}

const normalizedKeyword = computed(() => keyword.value.trim().toLowerCase())

const visibleMaterials = computed(() => {
  const kw = normalizedKeyword.value
  return materials.value.filter((item) => {
    const kind = classifyMaterial(item)
    if (typeFilter.value === 'IMAGE' && kind !== 'IMAGE') return false
    if (typeFilter.value === 'DOC' && kind !== 'DOC') return false
    if (!kw) return true
    const meta = fileMetaFor(item)
    return item.title.toLowerCase().includes(kw)
      || item.materialNo.toLowerCase().includes(kw)
      || (item.summary ?? '').toLowerCase().includes(kw)
      || item.tags.some((tag) => tag.toLowerCase().includes(kw))
      || (meta?.originalName ?? '').toLowerCase().includes(kw)
  })
})

const selectedMaterial = computed(() =>
  visibleMaterials.value.find((item) => item.id === selectedId.value) ?? null,
)

const selectedMeta = computed(() =>
  selectedMaterial.value ? fileMetaFor(selectedMaterial.value) : null,
)

async function hydrateFileMetadata(items: Material[], sequence: number): Promise<void> {
  const missingFileIds = Array.from(new Set(
    items
      .map((item) => approvedFileId(item))
      .filter((fileId): fileId is number => typeof fileId === 'number' && fileId > 0 && !(fileId in fileMetaMap.value)),
  ))
  if (!missingFileIds.length) return
  const results = await Promise.allSettled(missingFileIds.map((fileId) => getFileMetadata(fileId)))
  if (sequence !== loadSequence) return
  const updated: Record<number, FileMetadata> = { ...fileMetaMap.value }
  for (const result of results) {
    if (result.status === 'fulfilled' && result.value) {
      updated[result.value.id] = result.value
    }
  }
  fileMetaMap.value = updated
}

async function load(): Promise<void> {
  if (!allowed.value) return
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await listMaterials({
      page: 1,
      size: 50,
      status: 'APPROVED',
      keyword: keyword.value.trim() || undefined,
    })
    if (sequence !== loadSequence) return
    const activeApproved = page.items.filter(
      (item) => item.reviewStatus === 'APPROVED' && !item.offlineAt && item.currentRevisionId !== null,
    )
    materials.value = activeApproved
    await hydrateFileMetadata(activeApproved, sequence)
    if (sequence !== loadSequence) return
    if (selectedId.value && !activeApproved.some((item) => item.id === selectedId.value)) {
      selectedId.value = null
    }
  } catch (error) {
    if (sequence !== loadSequence) return
    errorMessage.value = errorText(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function clearSearch(): void {
  keyword.value = ''
  void load()
}

function selectMaterial(material: Material): void {
  selectedId.value = selectedId.value === material.id ? null : material.id
}

async function openPreview(material: Material): Promise<void> {
  if (!material.currentRevisionId || previewingId.value !== null) return
  const meta = fileMetaFor(material)
  if (meta && meta.scanStatus !== 'CLEAN') {
    errorMessage.value = meta.lastError || '文件尚未通过安全扫描（CLEAN），暂不可预览'
    return
  }
  previewingId.value = material.id
  errorMessage.value = ''
  try {
    const download = await getMaterialRevisionDownload(material.id, material.currentRevisionId)
    const target = new URL(download.downloadUrl, window.location.origin)
    if (target.protocol !== 'https:' && target.protocol !== 'http:') {
      throw new Error('服务端返回了不安全的预览地址')
    }
    window.open(target.toString(), '_blank', 'noopener,noreferrer')
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    previewingId.value = null
  }
}

async function sendMaterial(material: Material): Promise<void> {
  if (!allowed.value || !isSendableEntry.value || sendingId.value !== null) return
  sendingId.value = material.id
  errorMessage.value = ''
  feedback.value = ''
  try {
    const media = await uploadWecomMaterialMedia(material.id)
    const mediaId = media?.mediaId?.trim() ?? ''
    if (!mediaId) {
      errorMessage.value = '服务端未返回有效的企微临时素材 ID，已中止发送'
      return
    }
    const target = buildSendTarget()
    const result = media.mediaType === 'image'
      ? await sendImageToCurrentChat(mediaId, target)
      : await sendFileToCurrentChat(mediaId, target)
    if (result.mismatch) {
      errorMessage.value = '当前企微会话已切换，已阻止误发，正在刷新上下文'
      emit('context-mismatch')
      return
    }
    if (result.status === 'SDK_SUCCESS') {
      feedback.value = isGroupChat.value
        ? `企微已确认将「${material.title}」发送到当前群聊；这不代表群成员已读`
        : `企微已确认将「${material.title}」发送到当前会话；这不代表客户已读`
    } else if (result.status === 'SDK_FAILED') {
      errorMessage.value = `企微返回素材发送失败（${result.clientResult}），请核实后重试`
    } else {
      feedback.value = '企微发送结果未知，请先到会话核实是否已发送，不要直接重复发送'
    }
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    sendingId.value = null
  }
}

watch(
  () => [props.customerId, props.externalUserid, props.chatId, props.chatEntry],
  () => {
    errorMessage.value = ''
    feedback.value = ''
  },
)

onMounted(() => { void load() })
</script>

<template>
  <section class="material-panel" data-testid="wecom-material-panel">
    <div class="material-heading">
      <div>
        <strong>合规素材库</strong>
        <p>仅展示已审核且未下架的合规素材，点击发送前由服务端转存企微临时素材。</p>
      </div>
      <span>已审可用</span>
    </div>

    <div v-if="!allowed" class="material-empty" data-testid="material-no-permission">
      当前账号没有企微侧边栏或素材库查看权限
    </div>

    <template v-else>
      <div v-if="errorMessage" role="alert" class="material-alert" data-testid="material-error">{{ errorMessage }}</div>
      <div v-if="feedback" role="status" class="material-feedback" data-testid="material-feedback">{{ feedback }}</div>

      <div class="material-toolbar">
        <div class="material-search-row">
          <input
            v-model="keyword"
            type="search"
            class="material-search"
            data-testid="material-search"
            aria-label="搜索已审核素材"
            placeholder="搜索素材标题、编号、文件名或标签…"
            @keyup.enter="load"
          >
          <button
            v-if="keyword"
            type="button"
            class="search-clear"
            aria-label="清空搜索"
            @click="clearSearch"
          >
            清空
          </button>
          <button
            type="button"
            class="search-submit"
            data-testid="material-search-submit"
            :disabled="loading"
            @click="load"
          >
            查询
          </button>
        </div>

        <div class="material-filters" role="group" aria-label="素材文件类型筛选">
          <button
            type="button"
            :class="{ selected: typeFilter === 'ALL' }"
            :aria-pressed="typeFilter === 'ALL'"
            data-testid="material-filter-all"
            @click="typeFilter = 'ALL'"
          >
            全部
          </button>
          <button
            type="button"
            :class="{ selected: typeFilter === 'IMAGE' }"
            :aria-pressed="typeFilter === 'IMAGE'"
            data-testid="material-filter-image"
            @click="typeFilter = 'IMAGE'"
          >
            图片
          </button>
          <button
            type="button"
            :class="{ selected: typeFilter === 'DOC' }"
            :aria-pressed="typeFilter === 'DOC'"
            data-testid="material-filter-doc"
            @click="typeFilter = 'DOC'"
          >
            文档
          </button>
        </div>
      </div>

      <div v-if="!isSendableEntry" class="material-note-banner">
        当前是客户资料页，仅可查看素材信息与私有预览；请进入客户单聊或群聊后再发送素材。
      </div>

      <div v-if="loading" class="material-empty">正在读取已审核素材…</div>

      <template v-else>
        <div v-if="visibleMaterials.length" class="material-list">
          <article
            v-for="item in visibleMaterials"
            :key="item.id"
            class="material-item"
            :class="{ selected: selectedId === item.id }"
            :data-testid="`material-item-${item.id}`"
          >
            <button
              type="button"
              class="material-select"
              :data-testid="`material-select-${item.id}`"
              @click="selectMaterial(item)"
            >
              <div class="material-title-row">
                <strong>{{ item.title }}</strong>
                <span class="kind-pill" :class="classifyMaterial(item).toLowerCase()">{{ kindLabel(item) }}</span>
              </div>
              <p v-if="item.summary" class="material-summary">{{ item.summary }}</p>
              <div class="material-submeta">
                <span>{{ categoryLabel(item.categoryCode) }}</span>
                <span>{{ item.materialNo }}</span>
                <span v-if="fileMetaFor(item)">{{ fileMetaFor(item)?.originalName }}</span>
                <span v-if="fileMetaFor(item)">{{ formatBytes(fileMetaFor(item)?.sizeBytes) }}</span>
              </div>
            </button>

            <div class="material-item-actions">
              <button
                type="button"
                class="preview-meta-btn"
                :data-testid="`material-detail-toggle-${item.id}`"
                @click="selectMaterial(item)"
              >
                {{ selectedId === item.id ? '收起详情' : '查看元数据' }}
              </button>
              <button
                v-if="isSendableEntry"
                type="button"
                class="send-material-btn"
                :data-testid="`material-send-${item.id}`"
                :disabled="sendingId !== null"
                @click="sendMaterial(item)"
              >
                {{ sendingId === item.id ? '上传并发送中…' : isGroupChat ? '发送到当前群聊' : '发送到当前会话' }}
              </button>
            </div>
          </article>
        </div>

        <div v-else class="material-empty" data-testid="material-empty">
          <strong>暂无可发送的已审核素材</strong>
          <p>{{ normalizedKeyword || typeFilter !== 'ALL' ? '请调整关键词或切换文件类型筛选。' : '仅审核通过且未下架的合规图片/文档素材会展示在这里。' }}</p>
        </div>

        <section
          v-if="selectedMaterial"
          class="material-preview-card"
          data-testid="material-preview-card"
          aria-label="素材元数据预览"
        >
          <div class="preview-header">
            <strong>{{ selectedMaterial.title }}</strong>
            <span>{{ kindLabel(selectedMaterial) }}</span>
          </div>
          <p v-if="selectedMaterial.summary" class="preview-summary">{{ selectedMaterial.summary }}</p>
          <dl class="preview-meta-list">
            <div><dt>素材编号</dt><dd>{{ selectedMaterial.materialNo }}</dd></div>
            <div><dt>业务分类</dt><dd>{{ categoryLabel(selectedMaterial.categoryCode) }}</dd></div>
            <div><dt>文件名称</dt><dd>{{ selectedMeta?.originalName || '未读取到文件信息' }}</dd></div>
            <div><dt>文件格式</dt><dd>{{ selectedMeta?.mimeType || '—' }}</dd></div>
            <div><dt>文件大小</dt><dd>{{ formatBytes(selectedMeta?.sizeBytes) }}</dd></div>
            <div><dt>安全扫描</dt><dd>{{ selectedMeta ? (selectedMeta.scanStatus === 'CLEAN' ? 'CLEAN（已通过）' : selectedMeta.scanStatus) : '由服务端发送前校验' }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatDateTime(selectedMaterial.updatedAt) }}</dd></div>
          </dl>
          <div v-if="selectedMaterial.tags.length" class="preview-tags">
            <span v-for="tag in selectedMaterial.tags" :key="tag">{{ tag }}</span>
          </div>
          <div class="preview-actions">
            <button
              v-if="selectedMaterial.currentRevisionId"
              type="button"
              class="open-file-btn"
              data-testid="material-open-preview"
              :disabled="previewingId !== null"
              @click="openPreview(selectedMaterial)"
            >
              {{ previewingId === selectedMaterial.id ? '获取链接…' : '私有预览文件' }}
            </button>
            <button
              v-if="isSendableEntry"
              type="button"
              class="send-material-btn"
              data-testid="material-preview-send"
              :disabled="sendingId !== null"
              @click="sendMaterial(selectedMaterial)"
            >
              {{ sendingId === selectedMaterial.id ? '上传并发送中…' : isGroupChat ? '发送到当前群聊' : '发送到当前会话' }}
            </button>
          </div>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.material-panel { color: #29483d; }
.material-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.material-heading strong { font-size: 14px; }
.material-heading p { margin: 5px 0 0; color: #84978d; font-size: 11px; line-height: 1.7; }
.material-heading > span { padding: 4px 6px; border-radius: 5px; color: #517364; background: #e8f0eb; font-size: 9px; white-space: nowrap; }
.material-alert, .material-feedback, .material-note-banner { margin-bottom: 12px; padding: 10px 12px; border-radius: 8px; font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.material-alert { color: #a04f3f; background: #fff0eb; }
.material-feedback { color: #3d7259; background: #edf6f0; }
.material-note-banner { color: #6e7f77; background: #f2f6f4; }
.material-toolbar { display: grid; gap: 9px; margin-bottom: 12px; }
.material-search-row { display: flex; gap: 6px; align-items: center; }
.material-search { flex: 1; min-width: 0; min-height: 36px; padding: 7px 10px; border: 1px solid #d6e3dc; border-radius: 8px; outline: none; color: #29483d; background: #fcfefc; font: inherit; font-size: 12px; }
.material-search:focus { border-color: #76a58f; box-shadow: 0 0 0 3px rgb(51 111 84 / 8%); }
.material-search::placeholder { color: #97a89f; }
.search-clear, .search-submit { min-height: 36px; padding: 0 11px; border-radius: 8px; font: inherit; font-size: 11px; font-weight: 600; cursor: pointer; white-space: nowrap; }
.search-clear { border: 1px solid #d8e3dd; color: #6b8478; background: #f7faf8; }
.search-submit { border: 1px solid #276c59; color: #f5faf7; background: #276c59; }
.material-filters { display: flex; gap: 7px; }
.material-filters button { flex: 1; padding: 6px 8px; border: 1px solid #dce6e0; border-radius: 7px; color: #6c8579; background: #f9fbfa; font: inherit; font-size: 11px; cursor: pointer; }
.material-filters button.selected { border-color: #6ea089; color: #24604a; background: #e6f2ec; font-weight: 650; }
.material-list { display: grid; gap: 9px; }
.material-item { overflow: hidden; border: 1px solid #e1e9e5; border-radius: 10px; background: #fdfefd; }
.material-item.selected { border-color: #8fb7a4; background: #f2f7f4; }
.material-select { display: grid; width: 100%; gap: 6px; padding: 12px 13px 8px; border: 0; color: #36584a; background: transparent; font: inherit; text-align: left; cursor: pointer; }
.material-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.material-title-row strong { font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.kind-pill { flex: 0 0 auto; padding: 2px 6px; border-radius: 4px; color: #537164; background: #eaf1ed; font-size: 9px; font-weight: 600; }
.kind-pill.image { color: #256852; background: #e2f3ec; }
.kind-pill.doc { color: #3d5d84; background: #e9f0fa; }
.material-summary { display: -webkit-box; margin: 0; overflow: hidden; color: #80938a; font-size: 10px; line-height: 1.65; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.material-submeta { display: flex; gap: 6px 10px; flex-wrap: wrap; color: #8c9e95; font-size: 9px; }
.material-item-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 12px 10px; }
.preview-meta-btn { padding: 5px 8px; border: 1px solid #d5e2db; border-radius: 6px; color: #4c7261; background: #f7faf8; font: inherit; font-size: 10px; cursor: pointer; }
.send-material-btn { min-height: 32px; padding: 6px 11px; border: 1px solid #276c59; border-radius: 7px; color: #f5faf7; background: #276c59; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.material-preview-card { margin-top: 15px; padding: 14px; border: 1px solid #d4e3db; border-radius: 10px; background: #f8fbf9; }
.preview-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.preview-header strong { color: #25483b; font-size: 13px; overflow-wrap: anywhere; }
.preview-header span { flex: 0 0 auto; padding: 2px 6px; border-radius: 4px; color: #366a55; background: #e6f2ec; font-size: 10px; }
.preview-summary { margin: 0 0 10px; color: #6e857a; font-size: 11px; line-height: 1.7; white-space: pre-wrap; }
.preview-meta-list { margin: 0; border-top: 1px solid #e4ece7; }
.preview-meta-list > div { display: flex; justify-content: space-between; gap: 14px; padding: 7px 0; border-bottom: 1px solid #ebf1ee; font-size: 11px; }
.preview-meta-list dt { flex: 0 0 auto; color: #84978e; }
.preview-meta-list dd { margin: 0; color: #345648; text-align: right; overflow-wrap: anywhere; }
.preview-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 10px; }
.preview-tags span { padding: 3px 7px; border: 1px solid #dce7e1; border-radius: 5px; color: #547264; background: #eef4f1; font-size: 10px; }
.preview-actions { display: flex; gap: 8px; margin-top: 12px; }
.open-file-btn { min-height: 34px; padding: 6px 11px; border: 1px solid #a9c4b5; border-radius: 7px; color: #356c54; background: #f4f8f5; font: inherit; font-size: 11px; font-weight: 600; cursor: pointer; }
.preview-actions .send-material-btn { flex: 1; }
button:disabled { opacity: .45; cursor: not-allowed; }
.material-empty { padding: 28px 14px; color: #8b9e94; text-align: center; font-size: 11px; }
.material-empty strong { color: #4b6b5c; font-size: 12px; }
.material-empty p { margin: 7px 0 0; line-height: 1.7; }
</style>
