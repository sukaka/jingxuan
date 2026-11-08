<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import type { KnowledgeDocumentItem, KnowledgeDocumentStatus, KnowledgeSyncStatus } from '../api/knowledge'
import {
  disableKnowledgeDocument,
  listKnowledgeDocuments,
  publishKnowledgeDocument,
  retryKnowledgeSyncJob,
} from '../api/knowledge'
import type { Material, MaterialDetail, MaterialRevision } from '../api/material'
import { getMaterial, listMaterials } from '../api/material'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import ShellIcon from '../components/shell/ShellIcon.vue'
import { knowledgeDocumentTone, knowledgeSyncTone } from '../utils/tagTone'

const auth = useAuthStore()
const materials = ref<Material[]>([])
const documents = ref<KnowledgeDocumentItem[]>([])
const selectedMaterialId = ref<number>()
const selectedRevisionId = ref<number>()
const selectedMaterial = ref<MaterialDetail | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const errorMessage = ref('')
const publishing = ref(false)
const pendingActionId = ref<number | null>(null)
const total = ref(0)
const successMessages = ref<string[]>([])
const query = reactive({ page: 1, size: 20, status: '' as KnowledgeDocumentStatus | '' })

const canRead = computed(() => auth.hasPermission('ai:knowledge:read') || auth.hasPermission('ai:knowledge:publish'))
const canPublishKnowledge = computed(() => auth.hasPermission('ai:knowledge:publish'))
const canReadMaterials = computed(() => ['material:read', 'material:write', 'material:review'].some((permission) => auth.hasPermission(permission)))
const canSelectMaterials = computed(() => canPublishKnowledge.value && canReadMaterials.value)
const revisionOptions = computed(() => (selectedMaterial.value?.revisions ?? [])
  .filter((revision) => revision.status === 'APPROVED'))
const selectedRevision = computed(() => revisionOptions.value.find((revision) => revision.id === selectedRevisionId.value))
const canPublish = computed(() => Boolean(canSelectMaterials.value && selectedRevision.value && !publishing.value))

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '知识资料操作失败，请重试'
}

async function loadMaterials() {
  if (!canReadMaterials.value) return
  const result = await listMaterials({ page: 1, size: 100, status: 'APPROVED' })
  materials.value = result.items
}

async function loadDocuments(page = query.page) {
  const result = await listKnowledgeDocuments({ page, size: query.size, status: query.status || undefined })
  documents.value = result.items
  total.value = result.total
  query.page = result.page
  query.size = result.size
}

async function refreshDocuments(page = query.page) {
  if (refreshing.value) return
  refreshing.value = true
  errorMessage.value = ''
  try {
    await loadDocuments(page)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    refreshing.value = false
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    await Promise.all([loadMaterials(), loadDocuments()])
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function selectMaterial(id: number) {
  if (!canSelectMaterials.value) return
  selectedRevisionId.value = undefined
  selectedMaterial.value = null
  try {
    const result = await getMaterial(id)
    selectedMaterial.value = result
    selectedRevisionId.value = result.material.currentRevisionId
      ?? result.revisions.find((revision) => revision.status === 'APPROVED')?.id
  } catch (error) {
    errorMessage.value = errorText(error)
  }
}

async function publish() {
  const revision = selectedRevision.value
  if (!revision || !canPublish.value) return
  publishing.value = true
  errorMessage.value = ''
  try {
    await publishKnowledgeDocument({
      materialId: revision.materialId,
      revisionId: revision.id,
      version: revision.version,
    }, crypto.randomUUID())
    successMessages.value.push('发布任务已创建')
    await loadDocuments()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    publishing.value = false
  }
}

async function retry(item: KnowledgeDocumentItem) {
  if (!canPublishKnowledge.value || item.latestJobId === null || pendingActionId.value !== null) return
  pendingActionId.value = item.latestJobId
  errorMessage.value = ''
  try {
    await retryKnowledgeSyncJob(item.latestJobId, crypto.randomUUID())
    successMessages.value.push('重试任务已恢复')
    await loadDocuments()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingActionId.value = null
  }
}

async function disable(item: KnowledgeDocumentItem) {
  if (!canPublishKnowledge.value || pendingActionId.value !== null) return
  pendingActionId.value = item.id
  errorMessage.value = ''
  try {
    await disableKnowledgeDocument(item.id, crypto.randomUUID())
    successMessages.value.push('资料已停用')
    await loadDocuments()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    pendingActionId.value = null
  }
}

function materialName(item: KnowledgeDocumentItem): string {
  return materials.value.find((material) => material.id === item.materialId)?.title ?? `素材 #${item.materialId}`
}

function statusText(status: KnowledgeDocumentStatus): string {
  return ({ WAITING: '待同步', SYNCING: '同步中', ACTIVE: '已生效', FAILED: '失败', DISABLED: '已停用' } as const)[status]
}

function jobStatusText(status: KnowledgeSyncStatus): string {
  return ({
    WAITING: '待同步',
    PENDING: '待执行',
    RUNNING: '同步中',
    INDEXING: '索引中',
    RETRY: '等待重试',
    SUCCEEDED: '已完成',
    FAILED: '失败',
  } as const)[status]
}

function revisionText(revision: MaterialRevision): string {
  return `第 ${revision.revisionNo} 版 · 文件 #${revision.fileId}`
}

function timeText(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

onMounted(() => {
  if (!canRead.value) {
    loading.value = false
    return
  }
  void load()
})
watch(selectedMaterialId, (id) => { if (id !== undefined) void selectMaterial(id) })
</script>

<template>
  <div class="proto-page knowledge-page">
    <PageHeader title="知识资料" description="将审核通过的素材发布到知识库，并查看同步结果" />

    <PageCard v-if="canPublishKnowledge" class="publish-card">
      <div class="publish-panel">
        <div class="publish-heading">
          <h2>发布素材</h2>
          <p>仅可发布审核通过的内容</p>
        </div>
        <el-alert v-if="!canSelectMaterials" type="info" title="当前账号缺少素材查看权限，不能选择发布内容" :closable="false" />
        <div v-else>
          <div class="publish-form">
            <label class="publish-field">
              <span>素材</span>
              <el-select v-model="selectedMaterialId" :placeholder="materials.length ? '请选择素材' : '暂无可发布素材'" :disabled="!materials.length" filterable data-testid="knowledge-material">
                <el-option v-for="item in materials" :key="item.id" :label="item.title" :value="item.id" />
              </el-select>
            </label>
            <label class="publish-field">
              <span>素材版本</span>
              <el-select v-model="selectedRevisionId" placeholder="请选择已审核版本" data-testid="knowledge-revision" :disabled="!selectedMaterial || publishing">
                <el-option v-for="revision in revisionOptions" :key="revision.id" :label="revisionText(revision)" :value="revision.id" />
              </el-select>
            </label>
            <el-button type="primary" class="publish-button" :disabled="!canPublish" :loading="publishing" data-testid="knowledge-publish" @click="publish">
              <ShellIcon v-if="!publishing" name="plus" :size="14" />发布到知识库
            </el-button>
          </div>
          <p v-if="!materials.length && !loading" class="publish-empty">暂无已审核素材，请先在素材库完成审核。</p>
        </div>
      </div>
    </PageCard>

    <div class="page-alerts">
      <el-alert v-for="(message, index) in successMessages" :key="`${index}-${message}`" type="success" :title="message" :closable="false" show-icon />
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" show-icon />
    </div>
    <el-alert v-if="!canRead" type="warning" title="当前账号没有知识资料查看或发布权限" :closable="false" />
    <el-skeleton v-if="loading" :rows="4" animated />

    <PageCard v-else-if="canRead" class="documents-card">
      <div class="documents-toolbar">
        <div class="documents-heading">
          <h2>资料列表</h2>
          <span class="record-count">共 {{ total }} 条</span>
        </div>
        <div class="documents-actions">
          <el-select v-model="query.status" placeholder="全部状态" aria-label="资料状态" data-testid="knowledge-status-filter" @change="refreshDocuments(1)">
            <el-option label="全部状态" value="" />
            <el-option v-for="status in ['WAITING', 'SYNCING', 'ACTIVE', 'FAILED', 'DISABLED']" :key="status" :label="statusText(status)" :value="status" />
          </el-select>
          <el-button :loading="refreshing" data-testid="knowledge-refresh" @click="refreshDocuments()">
            <ShellIcon v-if="!refreshing" name="refresh" :size="14" />刷新
          </el-button>
        </div>
      </div>
      <div class="proto-table-wrap">
        <app-table v-loading="refreshing" :data="documents" row-key="id" class="documents-table">
          <el-table-column label="素材" min-width="160" show-overflow-tooltip>
            <template #default="{ row }"><div class="document-name">{{ materialName(row) }}</div><div class="proto-subcell">素材 #{{ row.materialId }}</div></template>
          </el-table-column>
          <el-table-column label="生效修订" width="100">
            <template #default="{ row }">{{ row.activeRevisionId ? `修订 #${row.activeRevisionId}` : '尚未生效' }}</template>
          </el-table-column>
          <el-table-column prop="datasetId" label="知识库" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.datasetId || '未配置' }}</template>
          </el-table-column>
          <el-table-column label="资料状态" width="96">
            <template #default="{ row }"><span class="proto-pill" :class="knowledgeDocumentTone(row.status)" :data-testid="`knowledge-document-status-${row.id}`">{{ statusText(row.status) }}</span></template>
          </el-table-column>
          <el-table-column label="最近同步" min-width="190">
            <template #default="{ row }">
              <template v-if="row.latestJobId">
                <span class="proto-pill" :class="knowledgeSyncTone(row.latestJobStatus)" :data-testid="`knowledge-job-status-${row.latestJobId}`">{{ jobStatusText(row.latestJobStatus!) }}</span>
                <div class="proto-subcell">任务 #{{ row.latestJobId }} · 已尝试 {{ row.attemptCount ?? 0 }} 次</div>
                <div v-if="row.lastErrorMasked" class="sync-error" :title="row.lastErrorMasked">{{ row.lastErrorMasked }}</div>
              </template>
              <span v-else class="proto-muted">尚未发布</span>
            </template>
          </el-table-column>
          <el-table-column label="索引成功时间" width="142">
            <template #default="{ row }">{{ timeText(row.publishedAt) }}</template>
          </el-table-column>
          <el-table-column v-if="canPublishKnowledge" label="操作" width="118" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.latestJobStatus === 'FAILED' && row.status !== 'DISABLED'" text type="primary" :disabled="pendingActionId !== null" :data-testid="`knowledge-retry-${row.latestJobId}`" @click="retry(row)">重试</el-button>
              <el-button v-if="row.status !== 'DISABLED'" text type="danger" :disabled="pendingActionId !== null" :data-testid="`knowledge-disable-${row.id}`" @click="disable(row)">停用</el-button>
              <span v-if="row.status === 'DISABLED'" class="proto-muted">—</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="documents-empty">
              <ShellIcon name="file" :size="22" />
              <div>
                <strong>{{ query.status ? '没有符合当前筛选条件的资料' : '暂无已发布的知识资料' }}</strong>
                <p>{{ query.status ? '切换为“全部状态”查看其他资料' : canSelectMaterials ? '从上方选择素材，发布后在这里查看同步进度' : '已发布素材的同步进度会在这里显示' }}</p>
              </div>
            </div>
          </template>
        </app-table>
      </div>
      <el-pagination
        v-if="total > query.size"
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="refreshDocuments"
        @size-change="refreshDocuments(1)"
      />
    </PageCard>
  </div>
</template>

<style scoped>
.publish-card { overflow: hidden; }
.publish-panel { display: grid; grid-template-columns: minmax(150px, .7fr) minmax(0, 3fr); gap: 24px; align-items: end; padding: 20px 22px; background: #f5faf9; }
.publish-heading { align-self: center; }
.publish-heading h2, .documents-heading h2 { margin: 0; color: #203b4e; font-size: 15px; font-weight: 750; }
.publish-heading p { margin: 6px 0 0; color: #78918f; font-size: 11px; line-height: 1.6; }
.publish-form { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(170px, .8fr) auto; gap: 12px; align-items: end; }
.publish-field { display: grid; gap: 7px; min-width: 0; color: #69817f; font-size: 11px; }
.publish-field .el-select { width: 100%; }
.publish-button { min-width: 138px; }
.publish-empty { margin: 10px 0 0; color: #849a97; font-size: 11px; }
.page-alerts { display: grid; gap: 10px; }
.page-alerts:not(:empty) { margin-top: 16px; }
.page-alerts + .documents-card { margin-top: 16px; }
.documents-toolbar { display: flex; min-height: 66px; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 20px; border-bottom: 1px solid var(--line); }
.documents-heading, .documents-actions { display: flex; align-items: center; gap: 12px; }
.record-count { color: #8b9ca8; font-size: 11px; }
.documents-actions .el-select { width: 138px; }
.documents-actions .el-button { margin-left: 0; }
.document-name { overflow: hidden; color: #294456; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.sync-error { overflow: hidden; margin-top: 4px; color: #ba5362; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.documents-empty { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 34px 20px; color: #9aadb6; line-height: 1.6; text-align: left; }
.documents-empty strong { color: #526b7c; font-size: 13px; font-weight: 650; }
.documents-empty p { margin: 6px 0 0; color: #93a5af; font-size: 11px; }
.documents-card :deep(.app-table__toolbar) { min-height: 30px; padding: 5px 20px 0; }
@media (max-width: 1100px) {
  .publish-panel { grid-template-columns: 1fr; gap: 16px; }
}
@media (max-width: 760px) {
  .publish-panel { padding: 18px; }
  .publish-form { grid-template-columns: 1fr; }
  .publish-button { width: 100%; }
  .documents-toolbar { align-items: flex-start; flex-direction: column; }
  .documents-actions { width: 100%; }
  .documents-actions .el-select { flex: 1; }
  .documents-empty { display: block; padding: 26px 14px; text-align: center; }
  .documents-empty .shell-icon { display: none; }
  .documents-empty strong { display: block; }
  .documents-empty p { max-width: 240px; margin: 6px auto 0; }
}
</style>
