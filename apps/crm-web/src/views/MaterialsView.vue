<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getFileMetadata } from '../api/file'
import {
  getMaterial,
  getMaterialRevisionDownload,
  listMaterials,
  offlineMaterial,
  reviewMaterialRevision,
  submitMaterialRevision,
} from '../api/material'
import type { Material, MaterialDetail, MaterialRevision } from '../api/material'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import MaterialRevisionDrawer from './material/MaterialRevisionDrawer.vue'
import { useDictionaries } from './customer/shared'
import { useReferenceNames } from './references'
import { materialCategoryTone } from '../utils/tagTone'

const auth = useAuthStore()
const dicts = useDictionaries()
const references = useReferenceNames()
const canRead = computed(() => ['material:read', 'material:write', 'material:review'].some((permission) => auth.hasPermission(permission)))
const canWrite = computed(() => auth.hasPermission('material:write'))
const canReview = computed(() => auth.hasPermission('material:review'))

const rows = ref<Material[]>([])
const total = ref(0)
const loading = ref(false)
const loaded = ref(false)
const errorMessage = ref('')
const query = reactive({
  keyword: '',
  categoryCode: '',
  reviewStatus: '',
  cityCode: '',
  projectId: undefined as number | undefined,
  page: 1,
  size: 20,
})
let listSequence = 0
let destroyed = false

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  }
  return error instanceof Error ? error.message : fallback
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hour12: false, timeZone: 'Asia/Shanghai',
  }).format(date)
}

function isOffline(material: Material | null | undefined): boolean {
  return Boolean(material?.offlineAt)
}

function displayStatus(material: Material): string {
  return isOffline(material) ? 'OFFLINE' : material.reviewStatus
}

function statusLabel(status: string): string {
  return ({ DRAFT: '草稿', PENDING: '待审核', APPROVED: '已审核', REJECTED: '已驳回', OFFLINE: '已下架' } as Record<string, string>)[status] ?? status
}

function statusTone(status: string): string {
  return ({ APPROVED: 'success', PENDING: 'warning', REJECTED: 'danger', DRAFT: 'dark', OFFLINE: 'info' } as Record<string, string>)[status] ?? 'info'
}

function categoryLabel(code: string): string {
  return dicts.options('materialTypes').find((item) => item.code === code)?.label ?? code
}

function cityLabel(code: string | null): string {
  if (!code) return '全国'
  return dicts.options('cities').find((item) => item.code === code)?.label ?? code
}

async function load(page = query.page) {
  if (!canRead.value) return
  const sequence = ++listSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listMaterials({
      page,
      size: query.size,
      keyword: query.keyword,
      categoryCode: query.categoryCode,
      status: query.reviewStatus,
      cityCode: query.cityCode,
      projectId: query.projectId,
    })
    if (destroyed || sequence !== listSequence) return
    rows.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
    loaded.value = true
  } catch (error) {
    if (destroyed || sequence !== listSequence) return
    rows.value = []
    total.value = 0
    loaded.value = true
    errorMessage.value = errorText(error, '素材加载失败，请重试')
  } finally {
    if (sequence === listSequence) loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { keyword: '', categoryCode: '', reviewStatus: '', cityCode: '', projectId: undefined, page: 1 })
  void load(1)
}

const revisionVisible = ref(false)
const revisionMaterial = ref<Material | null>(null)
const revisionBase = ref<MaterialRevision | null>(null)

function openCreate() {
  revisionMaterial.value = null
  revisionBase.value = null
  revisionVisible.value = true
}

function openRevision(material: Material, latest: MaterialRevision | null) {
  revisionMaterial.value = material
  revisionBase.value = latest
  revisionVisible.value = true
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<MaterialDetail | null>(null)
const previewingId = ref<number | null>(null)
const submittingRevisionId = ref<number | null>(null)
let detailSequence = 0
const submitKeys = new Map<number, string>()

const revisions = computed(() => [...(detail.value?.revisions ?? [])].sort((a, b) => b.revisionNo - a.revisionNo))

async function loadDetail(id: number) {
  const sequence = ++detailSequence
  detailLoading.value = true
  detailError.value = ''
  try {
    const result = await getMaterial(id)
    if (destroyed || sequence !== detailSequence) return
    detail.value = result
  } catch (error) {
    if (destroyed || sequence !== detailSequence) return
    detailError.value = errorText(error, '素材版本加载失败')
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}

function openDetail(item: Material) {
  detailVisible.value = true
  detail.value = null
  reviewTargetId.value = null
  void loadDetail(item.id)
}

watch(detailVisible, (visible) => { if (!visible) detailSequence++ })

async function refreshAfterAction(id: number) {
  await Promise.all([loadDetail(id), load()])
}

async function submitForReview(item: MaterialRevision) {
  try {
    await ElMessageBox.confirm('送审后该版本将进入待审核状态，不能覆盖修改。确认送审？', `送审第 ${item.revisionNo} 版`, { type: 'warning' })
  } catch {
    return
  }
  submittingRevisionId.value = item.id
  detailError.value = ''
  if (!submitKeys.has(item.id)) submitKeys.set(item.id, crypto.randomUUID())
  try {
    await submitMaterialRevision(item.materialId, item.id, item.version, submitKeys.get(item.id)!)
    ElMessage.success('版本已送审，正在重新读取服务端状态')
    await refreshAfterAction(item.materialId)
  } catch (error) {
    detailError.value = errorText(error, '送审失败，请重试')
  } finally {
    submittingRevisionId.value = null
  }
}

const reviewTargetId = ref<number | null>(null)
const reviewSubmitting = ref(false)
const reviewForm = reactive({ decision: 'APPROVED' as 'APPROVED' | 'REJECTED', note: '' })
let reviewKey = ''

watch(reviewForm, () => { if (!reviewSubmitting.value) reviewKey = crypto.randomUUID() }, { deep: true })

const reviewTarget = computed(() => revisions.value.find((item) => item.id === reviewTargetId.value) ?? null)
const canCreateRevision = computed(() => {
  const latest = revisions.value[0]
  const material = detail.value?.material
  return Boolean(latest && material && !isOffline(material)
    && ['APPROVED', 'REJECTED'].includes(latest.status)
    && material.reviewStatus === latest.status)
})

function startReview(item: MaterialRevision, decision: 'APPROVED' | 'REJECTED') {
  reviewTargetId.value = item.id
  reviewForm.decision = decision
  reviewForm.note = ''
  reviewKey = crypto.randomUUID()
  detailError.value = ''
}

async function submitReview() {
  const target = reviewTarget.value
  if (!target) return
  if (reviewForm.decision === 'REJECTED' && !reviewForm.note.trim()) {
    detailError.value = '驳回必须填写审核说明，供后续新版本核对'
    return
  }
  reviewSubmitting.value = true
  detailError.value = ''
  try {
    await reviewMaterialRevision(target.materialId, target.id, {
      version: target.version,
      decision: reviewForm.decision,
      reviewNote: reviewForm.note.trim() || undefined,
    }, reviewKey)
    reviewTargetId.value = null
    ElMessage.success('审核请求已受理，正在重新读取版本历史')
    await refreshAfterAction(target.materialId)
  } catch (error) {
    detailError.value = errorText(error, '审核失败，请重试')
  } finally {
    reviewSubmitting.value = false
  }
}

const offliningId = ref<number | null>(null)
const offlineKeys = new Map<string, string>()

async function offline(item: Material) {
  if (!canWrite.value || isOffline(item) || !item.currentRevisionId) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '下架后普通管家不能预览或使用；已审核修订和版本历史不会删除。请填写下架原因。',
      `下架素材：${item.title}`,
      {
        type: 'warning',
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '必填，500 字以内',
        inputValidator: (value: string) => (Boolean(value?.trim()) && value.trim().length <= 500)
          || '请填写 1 至 500 字的下架原因',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  const signature = JSON.stringify([item.id, item.version, reason])
  if (!offlineKeys.has(signature)) offlineKeys.set(signature, crypto.randomUUID())
  offliningId.value = item.id
  errorMessage.value = ''
  try {
    await offlineMaterial(item.id, { version: item.version, reason }, offlineKeys.get(signature)!)
    if (destroyed) return
    ElMessage.success('素材已下架，普通管家不可再预览或使用；历史版本已保留')
    await load()
  } catch (error) {
    if (!destroyed) errorMessage.value = errorText(error, '下架失败，请刷新后重试')
  } finally {
    offliningId.value = null
  }
}

function canPreview(item: MaterialRevision, material: Material | null | undefined = detail.value?.material): boolean {
  if (isOffline(material) && !canWrite.value && !canReview.value) return false
  return item.status === 'APPROVED'
    || (item.status === 'PENDING' && canReview.value)
    || (['DRAFT', 'REJECTED'].includes(item.status) && canWrite.value)
}

function safeOpen(url: string) {
  const target = new URL(url, window.location.origin)
  if (target.protocol !== 'https:' && target.protocol !== 'http:') throw new Error('服务端返回了不安全的预览地址')
  window.open(target.toString(), '_blank', 'noopener,noreferrer')
}

async function previewRevision(item: MaterialRevision, material: Material | null | undefined = detail.value?.material) {
  if (!canPreview(item, material)) return
  previewingId.value = item.id
  detailError.value = ''
  try {
    const metadata = await getFileMetadata(item.fileId)
    if (destroyed) return
    if (metadata.scanStatus !== 'CLEAN') {
      throw new Error(metadata.lastError || '文件尚未取得 ClamAV CLEAN，不能预览或下载')
    }
    const download = await getMaterialRevisionDownload(item.materialId, item.id)
    if (destroyed) return
    safeOpen(download.downloadUrl)
  } catch (error) {
    if (!destroyed) detailError.value = errorText(error, '预览地址获取失败，请重试')
  } finally {
    previewingId.value = null
  }
}

async function previewMaterial(item: Material) {
  previewingId.value = item.id
  errorMessage.value = ''
  try {
    const result = await getMaterial(item.id)
    if (destroyed) return
    if (isOffline(result.material)) {
      openDetail(result.material)
      return
    }
    const approved = result.revisions.find((revision) => revision.id === result.material.currentRevisionId && revision.status === 'APPROVED')
    if (!approved) {
      openDetail(item)
      return
    }
    await previewRevision(approved, result.material)
    if (detailError.value) errorMessage.value = detailError.value
  } catch (error) {
    errorMessage.value = errorText(error, '素材预览加载失败，请重试')
  } finally {
    previewingId.value = null
  }
}

async function editMaterial(item: Material) {
  if (!canWrite.value || isOffline(item)) return
  errorMessage.value = ''
  try {
    const result = await getMaterial(item.id)
    const latest = [...result.revisions].sort((a, b) => b.revisionNo - a.revisionNo)[0]
    if (!isOffline(result.material) && latest && ['APPROVED', 'REJECTED'].includes(latest.status)
      && result.material.reviewStatus === latest.status) {
      openRevision(item, latest)
    } else {
      openDetail(item)
    }
  } catch (error) {
    errorMessage.value = errorText(error, '素材版本加载失败，请重试')
  }
}

async function revisionSaved(saved: Material) {
  const created = revisionMaterial.value === null
  revisionVisible.value = false
  ElMessage.success(created ? '素材与首个草稿版本已创建，可核对后送审' : '新版本已追加为草稿，历史版本保持不变')
  await load(created ? 1 : query.page)
  if (destroyed) return
  if (created) {
    openDetail(saved)
  } else if (detailVisible.value && detail.value) {
    await loadDetail(detail.value.material.id)
  }
}

onMounted(() => {
  if (!canRead.value) return
  void dicts.load('materialTypes', 'cities')
  void load(1)
})

onBeforeUnmount(() => {
  destroyed = true
  listSequence++
  detailSequence++
})
</script>

<template>
  <div class="proto-page materials-page">
    <PageHeader title="素材库" description="案例、好评、机构、医生、仪器与产品资料按版本集中管理；只有已审核且文件 CLEAN 的版本才能对客使用">
      <template #actions>
        <el-button v-if="canRead && canWrite" type="primary" data-testid="material-create" @click="openCreate">＋ 新增素材</el-button>
      </template>
    </PageHeader>

    <PageCard v-if="!canRead">
      <div class="proto-empty permission-empty" data-testid="material-permission-empty">
        <strong>没有素材查看权限</strong>
        <span>页面不会请求素材或文件。请联系管理员按职责授权，数据范围仍由后端校验。</span>
      </div>
    </PageCard>

    <PageCard v-else>
      <div class="proto-toolbar material-toolbar">
        <div class="proto-toolbar-left">
          <el-input v-model="query.keyword" clearable placeholder="搜索素材 / 编号 / 标签" class="material-keyword" @keyup.enter="load(1)" />
          <el-button type="primary" :loading="loading" @click="load(1)">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </div>
        <span class="proto-note">只有已审核且文件 CLEAN 的版本可供管家预览</span>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="material-alert">
        <el-button text @click="load()">重试</el-button>
      </el-alert>
      <div v-loading="loading" class="proto-table-wrap">
        <app-table :data="rows" row-key="id">
          <el-table-column label="类型" width="115"><template #default="{ row }"><span class="proto-pill" :class="materialCategoryTone(row.categoryCode)">{{ categoryLabel(row.categoryCode) }}</span></template></el-table-column>
          <el-table-column label="标题" min-width="220"><template #default="{ row }"><strong>{{ row.title }}</strong><div class="proto-subcell">{{ row.materialNo }} · {{ formatDateTime(row.updatedAt) }}</div></template></el-table-column>
          <el-table-column label="城市" width="105"><template #default="{ row }">{{ cityLabel(row.cityCode) }}</template></el-table-column>
          <el-table-column label="关联机构" min-width="150"><template #default="{ row }">{{ row.institutionId ? references.institutionLabel(row.institutionId) : '全局' }}</template></el-table-column>
          <el-table-column label="项目" min-width="150"><template #default="{ row }">{{ row.projectId ? references.projectLabel(row.projectId) : '—' }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }"><span class="proto-pill" :class="statusTone(displayStatus(row))">{{ statusLabel(displayStatus(row)) }}</span></template></el-table-column>
          <el-table-column label="操作" width="285" fixed="right">
            <template #default="{ row }">
              <el-button text :loading="previewingId === row.id" :data-testid="`material-preview-${row.id}`" @click="previewMaterial(row)">预览</el-button>
              <el-button v-if="canWrite && !isOffline(row)" text :data-testid="`material-edit-${row.id}`" @click="editMaterial(row)">修改</el-button>
              <el-button text :data-testid="`material-detail-${row.id}`" @click="openDetail(row)">版本</el-button>
              <el-button v-if="canWrite && !isOffline(row) && row.currentRevisionId" text type="danger" :loading="offliningId === row.id" :data-testid="`material-offline-${row.id}`" @click="offline(row)">下架</el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :description="errorMessage ? '素材加载失败，请重试' : loaded ? '当前条件下没有素材' : '正在读取素材'" /></template>
        </app-table>
      </div>
      <el-pagination
        v-if="total > query.size"
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="load(1)"
      />
    </PageCard>

    <el-drawer v-model="detailVisible" size="min(880px,100vw)" :title="detail ? `${detail.material.materialNo} · 版本历史` : '素材版本历史'">
      <div v-loading="detailLoading" class="material-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" class="detail-alert" />
        <template v-if="detail">
          <div class="detail-heading">
            <div>
              <span class="proto-pill" :class="materialCategoryTone(detail.material.categoryCode)">{{ categoryLabel(detail.material.categoryCode) }}</span>
              <h2>{{ detail.material.title }}</h2>
              <p v-if="isOffline(detail.material)">已于 {{ formatDateTime(detail.material.offlineAt) }} 下架：{{ detail.material.offlineReason }}。当前仅保留历史查看，不提供恢复操作。</p>
              <p v-else>已审核版本不会被覆盖；驳回版本保留审核意见，后续修改必须追加新版本。</p>
            </div>
            <el-button v-if="canWrite && canCreateRevision" type="primary" data-testid="material-new-revision" @click="openRevision(detail.material, revisions[0] ?? null)">＋ 新建版本</el-button>
            <span v-else-if="canWrite && isOffline(detail.material)" class="proto-note">已下架素材已冻结修改、送审与审核</span>
            <span v-else-if="canWrite" class="proto-note">当前有草稿或待审版本，完成该版本流程后才能新建</span>
          </div>
          <div v-if="!revisions.length" class="proto-empty">
            <strong>尚未创建文件版本</strong>
            <span>素材目前只是草稿，不能预览或作为对客资料。</span>
          </div>
          <article v-for="item in revisions" :key="item.id" class="revision-card">
            <div class="revision-head">
              <div><strong>第 {{ item.revisionNo }} 版</strong><span class="revision-id">版本 ID #{{ item.id }}</span></div>
              <span class="proto-pill" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span>
            </div>
            <h3>{{ item.title }}</h3>
            <p class="revision-summary">{{ item.summary || '未填写摘要' }}</p>
            <div class="revision-meta">
              <span>文件 ID #{{ item.fileId }}</span>
              <span>上传人 #{{ item.createdBy }}</span>
              <span>{{ formatDateTime(item.createdAt) }}</span>
              <span>乐观锁版本 {{ item.version }}</span>
            </div>
            <p class="revision-note">变更说明：{{ item.changeNote || '—' }}</p>
            <p v-if="item.reviewNote" class="revision-note">审核意见：{{ item.reviewNote }}</p>
            <p v-if="item.status !== 'APPROVED'" class="revision-warning">未审核版本不可作为对客素材{{ item.status === 'PENDING' && canReview ? '；审核人可在授权后调取审核预览' : '' }}。</p>
            <div class="revision-actions">
              <el-button v-if="canPreview(item)" size="small" :loading="previewingId === item.id" :data-testid="`revision-preview-${item.id}`" @click="previewRevision(item)">
                {{ item.status === 'APPROVED' ? (isOffline(detail.material) ? '历史私有预览' : '私有预览') : item.status === 'PENDING' ? '审核预览' : '版本预览' }}
              </el-button>
              <el-button v-if="canWrite && !isOffline(detail.material) && item.status === 'DRAFT'" size="small" :loading="submittingRevisionId === item.id" :data-testid="`revision-submit-${item.id}`" @click="submitForReview(item)">送审</el-button>
              <template v-if="canReview && !isOffline(detail.material) && item.status === 'PENDING'">
                <el-button size="small" type="primary" :data-testid="`revision-review-approve-${item.id}`" @click="startReview(item, 'APPROVED')">审核通过</el-button>
                <el-button size="small" type="danger" plain :data-testid="`revision-review-reject-${item.id}`" @click="startReview(item, 'REJECTED')">驳回</el-button>
              </template>
            </div>
            <div v-if="!isOffline(detail.material) && reviewTargetId === item.id" class="review-panel">
              <strong>{{ reviewForm.decision === 'APPROVED' ? '审核通过' : '驳回版本' }}</strong>
              <el-input v-model="reviewForm.note" type="textarea" :rows="3" :placeholder="reviewForm.decision === 'REJECTED' ? '必填：说明驳回依据' : '可选：记录审核依据'" data-testid="revision-review-note" />
              <div>
                <el-button size="small" @click="reviewTargetId = null">取消</el-button>
                <el-button size="small" type="primary" :loading="reviewSubmitting" data-testid="revision-review-submit" @click="submitReview">提交审核结果</el-button>
              </div>
            </div>
          </article>
        </template>
      </div>
    </el-drawer>

    <MaterialRevisionDrawer
      v-model="revisionVisible"
      :material="revisionMaterial"
      :latest-revision="revisionBase"
      :categories="dicts.options('materialTypes')"
      :cities="dicts.options('cities')"
      @saved="revisionSaved"
    />
  </div>
</template>

<style scoped>
.material-toolbar { align-items: flex-start; }
.material-keyword { width: 230px; }
.material-alert { margin: 16px 20px 0; width: auto; }
.proto-table-wrap { min-height: 180px; }
.permission-empty { display: grid; gap: 8px; }
.create-note { margin-bottom: 15px; }
.create-note + .proto-form-grid { margin-top: 18px; }
:deep(.el-select), :deep(.el-input-number) { width: 100%; }
.material-detail { min-height: 240px; }
.detail-alert { margin-bottom: 16px; }
.detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 22px; padding-bottom: 20px; border-bottom: 1px solid #e8eef1; }
.detail-heading h2 { margin: 12px 0 7px; overflow-wrap: anywhere; font-size: 20px; line-height: 1.5; }
.detail-heading p { margin: 0; color: #7b919d; font-size: 12px; line-height: 1.8; }
.revision-card { padding: 20px 0; border-bottom: 1px solid #e8eef1; }
.revision-card:last-child { border-bottom: 0; }
.revision-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.revision-head > div { display: flex; align-items: baseline; gap: 9px; }
.revision-id { color: #9aabb5; font-size: 10px; }
.revision-card h3 { margin: 13px 0 7px; overflow-wrap: anywhere; font-size: 14px; }
.revision-summary { margin: 0; overflow-wrap: anywhere; color: #647e8a; font-size: 12px; line-height: 1.8; white-space: pre-wrap; }
.revision-meta { display: flex; gap: 8px 16px; margin: 13px 0; color: #94a6af; font-size: 10px; flex-wrap: wrap; }
.revision-note { margin: 8px 0; overflow-wrap: anywhere; color: #5f7682; font-size: 11px; line-height: 1.7; white-space: pre-wrap; }
.revision-warning { margin: 10px 0 0; color: #9a7440; font-size: 11px; line-height: 1.7; }
.revision-actions { display: flex; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
.revision-actions .el-button + .el-button { margin-left: 0; }
.review-panel { display: grid; gap: 12px; margin-top: 15px; padding: 15px; border-radius: 10px; background: #f4f8f9; }
.review-panel > strong { font-size: 12px; }
.review-panel > div { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 760px) {
  .material-keyword { width: 100%; }
  .material-toolbar .proto-toolbar-left > * { flex: 1 1 140px; }
  .detail-heading { flex-direction: column; }
  .revision-card { padding: 17px 0; }
}
</style>
