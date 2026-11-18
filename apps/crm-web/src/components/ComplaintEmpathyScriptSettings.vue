<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createComplaintEmpathyScript,
  disableComplaintEmpathyScript,
  listComplaintEmpathyScripts,
  updateComplaintEmpathyScript,
} from '../api/complaint'
import type { ComplaintEmpathyScript } from '../api/complaint'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import PageCard from './prototype/PageCard.vue'

const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('complaint:config'))
const rows = ref<ComplaintEmpathyScript[]>([])
const activeCount = computed(() => rows.value.filter((row) => row.status === 'ACTIVE').length)
const drafts = reactive<Record<number, { title: string; script: string; reason: string }>>({})
const rowBusy = reactive<Record<number, boolean>>({})
const createForm = reactive({ title: '', script: '', reason: '' })
const createOpen = ref(false)
const creating = ref(false)
const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const keys = new Map<string, string>()

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function keyFor(key: string): string {
  const existing = keys.get(key)
  if (existing) return existing
  const created = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `empathy-${Date.now()}-${Math.random()}`
  keys.set(key, created)
  return created
}

async function load(): Promise<void> {
  if (!allowed.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    rows.value = await listComplaintEmpathyScripts()
    rows.value.forEach((row) => {
      drafts[row.id] = { title: row.title, script: row.script, reason: '' }
    })
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  if (creating.value) return
  const title = createForm.title.trim()
  const script = createForm.script.trim()
  const reason = createForm.reason.trim()
  if (!title || !script || !reason) {
    errorMessage.value = '请完整填写话术标题、内容和新增理由'
    return
  }
  creating.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await createComplaintEmpathyScript({ title, script, reason }, keyFor('create'))
    keys.delete('create')
    createForm.title = ''
    createForm.script = ''
    createForm.reason = ''
    createOpen.value = false
    successMessage.value = '新话术已加入话术库，处理客诉时可以选择并人工复制'
    await load()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    creating.value = false
  }
}

async function update(row: ComplaintEmpathyScript): Promise<void> {
  if (rowBusy[row.id]) return
  const draft = drafts[row.id]
  const title = draft.title.trim()
  const script = draft.script.trim()
  const reason = draft.reason.trim()
  if (!title || !script || !reason) {
    errorMessage.value = '请完整填写话术标题、内容和本次修改理由'
    return
  }
  rowBusy[row.id] = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await updateComplaintEmpathyScript(row.id, { title, script, reason, version: row.version }, keyFor(`update:${row.id}`))
    keys.delete(`update:${row.id}`)
    successMessage.value = `“${title}”已保存，其他话术不受影响`
    await load()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    rowBusy[row.id] = false
  }
}

async function disable(row: ComplaintEmpathyScript): Promise<void> {
  if (rowBusy[row.id]) return
  const reason = drafts[row.id].reason.trim()
  if (!reason) {
    errorMessage.value = '请填写停用理由'
    return
  }
  rowBusy[row.id] = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await disableComplaintEmpathyScript(row.id, { reason, version: row.version }, keyFor(`disable:${row.id}`))
    keys.delete(`disable:${row.id}`)
    successMessage.value = `“${row.title}”已停用，历史客诉记录不受影响`
    await load()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    rowBusy[row.id] = false
  }
}

onMounted(() => { if (allowed.value) void load() })
</script>

<template>
  <PageCard class="script-library-card" title="人工共情话术" subtitle="按服务场景维护多条模板；处理客诉时可选择并人工复制，不会自动发送。">
    <template #actions>
      <span class="script-count"><strong>{{ activeCount }}</strong> 条启用</span>
      <button class="script-add" type="button" data-testid="script-create-open" @click="createOpen = !createOpen">{{ createOpen ? '收起新增' : '＋ 新增话术' }}</button>
    </template>
    <div class="script-library-body">
      <div v-if="errorMessage" role="alert" class="script-alert">{{ errorMessage }}</div>
      <div v-if="successMessage" role="status" class="script-success">{{ successMessage }}</div>

      <form v-if="createOpen" class="script-create-form" data-testid="script-create-form" @submit.prevent="create">
        <div class="create-form-heading"><div><h3>新增场景话术</h3><p>每条话术有独立标题和内容，保存后立即进入启用列表。</p></div><span>仅人工复制</span></div>
        <div class="create-title-field field"><label for="script-create-title">场景标题</label><input id="script-create-title" v-model="createForm.title" data-testid="script-create-title" maxlength="64" placeholder="例如：首次响应、进度跟进、处理完成"></div>
        <div class="field"><label for="script-create-content">话术内容 <span>{{ createForm.script.length }} / 2000</span></label><textarea id="script-create-content" v-model="createForm.script" data-testid="script-create-content" rows="4" maxlength="2000" placeholder="填写共情、核实与跟进说明，不作医疗效果或退款承诺" /></div>
        <div class="create-form-footer">
          <div class="field"><label for="script-create-reason">新增理由</label><input id="script-create-reason" v-model="createForm.reason" data-testid="script-create-reason" maxlength="1000" placeholder="例如：补充进度跟进场景"></div>
          <button class="script-primary" type="submit" :disabled="creating">{{ creating ? '正在保存…' : '保存并启用' }}</button>
        </div>
      </form>

      <div v-if="rows.length" class="script-list">
        <article v-for="row in rows" :key="row.id" class="script-row" :class="{ disabled: row.status !== 'ACTIVE' }" :data-testid="`script-${row.id}`">
          <div class="script-row-heading">
            <div class="script-title-field"><label :for="`script-title-${row.id}`">场景标题</label><input :id="`script-title-${row.id}`" v-model="drafts[row.id].title" :data-testid="`script-title-${row.id}`" maxlength="64" :disabled="row.status !== 'ACTIVE'"></div>
            <div class="script-meta"><span class="script-status" :class="{ disabled: row.status !== 'ACTIVE' }"><i aria-hidden="true" />{{ row.status === 'ACTIVE' ? '启用中' : '已停用' }}</span><small>配置版本 v{{ row.version }}</small></div>
          </div>
          <div class="field"><label :for="`script-content-${row.id}`">话术内容 <span v-if="row.status === 'ACTIVE'">{{ drafts[row.id].script.length }} / 2000</span></label><textarea :id="`script-content-${row.id}`" v-model="drafts[row.id].script" rows="3" maxlength="2000" :readonly="row.status !== 'ACTIVE'" /></div>
          <div v-if="row.status === 'ACTIVE'" class="script-row-footer">
            <div class="field"><label :for="`script-reason-${row.id}`">修改或停用理由</label><input :id="`script-reason-${row.id}`" v-model="drafts[row.id].reason" :data-testid="`script-reason-${row.id}`" maxlength="1000" placeholder="填写本次操作原因"></div>
            <div class="script-actions"><button class="script-secondary" type="button" :data-testid="`script-save-${row.id}`" :disabled="rowBusy[row.id]" @click="update(row)">保存修改</button><button class="script-danger" type="button" :data-testid="`script-disable-${row.id}`" :disabled="rowBusy[row.id]" @click="disable(row)">停用</button></div>
          </div>
          <p v-else class="script-disabled-note">不再出现在客诉处理页的话术选项中，历史配置与客诉记录仍保留。</p>
        </article>
      </div>
      <div v-if="loading" class="script-empty">正在读取话术库…</div>
      <div v-else-if="!rows.length" class="script-empty"><strong>还没有共情话术</strong><span>点击“新增话术”，为不同服务场景添加第一条模板。</span></div>
    </div>
    <p class="script-footnote"><span aria-hidden="true">i</span>话术修改只影响后续人工选择，不会自动发送，也不会修改订单、退款或权益。</p>
  </PageCard>
</template>

<style scoped>
.script-library-card { --script-ink: #203d38; --script-muted: #7b8f88; --script-line: #e3ebe7; --script-primary: #247966; overflow: hidden; border-color: var(--script-line); border-radius: 12px; box-shadow: 0 4px 18px rgb(36 68 57 / 3%); color: var(--script-ink); }
.script-library-card :deep(.proto-card-head) { padding: 21px 24px 18px; border-bottom: 1px solid var(--script-line); }
.script-library-card :deep(.proto-card-head h2) { color: var(--script-ink); font-size: 16px; letter-spacing: -.2px; }
.script-library-card :deep(.proto-card-head p) { margin-top: 6px; color: var(--script-muted); font-size: 12px; line-height: 1.65; }
.script-library-card :deep(.proto-head-actions) { flex-wrap: nowrap; }
.script-count { color: #82938d; font-size: 11px; white-space: nowrap; }
.script-count strong { margin-right: 3px; color: #316f5d; font-size: 18px; font-weight: 750; }
.script-add, .script-primary, .script-secondary, .script-danger { min-height: 38px; padding: 8px 14px; border-radius: 8px; font: inherit; font-size: 12px; font-weight: 650; white-space: nowrap; cursor: pointer; transition: background .18s ease-out, border-color .18s ease-out; }
.script-add, .script-primary { border: 1px solid var(--script-primary); color: #f7fcfa; background: var(--script-primary); }
.script-add { min-height: 34px; padding: 6px 11px; }
.script-add:hover, .script-primary:hover:not(:disabled) { background: #195e50; }
.script-secondary { border: 1px solid #bdd4ca; color: #2c725f; background: #fbfdfc; }
.script-secondary:hover:not(:disabled) { border-color: #74a896; background: #f0f7f4; }
.script-danger { border: 1px solid transparent; color: #a36658; background: transparent; }
.script-danger:hover:not(:disabled) { border-color: #ecd9d3; background: #fff7f4; }
.script-primary:disabled, .script-secondary:disabled, .script-danger:disabled { opacity: .55; cursor: not-allowed; }
.script-library-body { padding: 20px 24px 0; }
.script-alert, .script-success { margin-bottom: 14px; padding: 12px 15px; border: 1px solid transparent; border-radius: 9px; font-size: 13px; }
.script-alert { border-color: #f0d8d1; background: #fff6f3; color: #a34b3a; }
.script-success { border-color: #d5e8dd; background: #f0f8f3; color: #337056; }
.script-create-form { margin-bottom: 22px; padding: 18px; border: 1px solid #dfe9e4; border-radius: 10px; background: #f7faf8; }
.create-form-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.create-form-heading h3 { margin: 0; color: #36574e; font-size: 13px; }
.create-form-heading p { margin: 4px 0 0; color: #8a9a94; font-size: 11px; }
.create-form-heading > span { padding: 4px 7px; border-radius: 5px; color: #4d7668; background: #e7f0eb; font-size: 10px; white-space: nowrap; }
.create-title-field { max-width: 520px; margin-bottom: 14px; }
.field label, .script-title-field label { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; color: #637c73; font-size: 11px; font-weight: 620; }
.field label > span { color: #98a69f; font-size: 10px; font-weight: 500; }
.field input, .field textarea, .script-title-field input { width: 100%; min-width: 0; border: 1px solid #d9e4df; border-radius: 8px; outline: none; color: #304f46; background: #fdfefd; font: inherit; font-size: 13px; transition: border-color .18s ease-out, box-shadow .18s ease-out; }
.field input, .script-title-field input { height: 40px; padding: 0 12px; }
.field textarea { min-height: 86px; padding: 11px 12px; line-height: 1.8; resize: vertical; }
.field input:focus, .field textarea:focus, .script-title-field input:focus { border-color: #68a592; box-shadow: 0 0 0 3px rgb(46 132 108 / 8%); }
.field input::placeholder, .field textarea::placeholder, .script-title-field input::placeholder { color: #a3b0ab; }
.create-form-footer { display: grid; grid-template-columns: minmax(220px, 1fr) auto; gap: 14px; align-items: end; margin-top: 14px; }
.script-list { border-top: 1px solid var(--script-line); }
.script-row { padding: 20px 0; border-bottom: 1px solid #edf2ef; }
.script-row:last-child { border-bottom: 0; }
.script-row-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 13px; }
.script-title-field { width: min(460px, 70%); }
.script-title-field input { height: 38px; border-color: transparent; padding-left: 0; background: transparent; font-size: 14px; font-weight: 680; }
.script-title-field input:focus:not(:disabled) { padding-left: 10px; border-color: #68a592; background: #fdfefd; }
.script-title-field input:disabled, .script-row.disabled textarea { color: #85958f; opacity: 1; -webkit-text-fill-color: #85958f; }
.script-row.disabled textarea { background: #fafcfb; }
.script-meta { display: flex; align-items: center; gap: 10px; padding-bottom: 9px; }
.script-meta small { color: #99a7a2; font-size: 10px; white-space: nowrap; }
.script-status { display: inline-flex; align-items: center; gap: 5px; color: #3c7c64; font-size: 10px; font-weight: 650; white-space: nowrap; }
.script-status i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.script-status.disabled { color: #909b97; }
.script-row-footer { display: grid; grid-template-columns: minmax(240px, 1fr) auto; gap: 18px; align-items: end; margin-top: 13px; }
.script-actions { display: flex; gap: 5px; }
.script-actions button { min-height: 36px; }
.script-disabled-note { margin: 12px 0 0; color: #96a49f; font-size: 11px; }
.script-empty { display: flex; min-height: 120px; align-items: center; justify-content: center; flex-direction: column; gap: 7px; padding: 24px; color: #8b9b95; font-size: 12px; text-align: center; }
.script-empty strong { color: #4d6960; font-size: 13px; }
.script-footnote { display: flex; align-items: center; gap: 7px; margin: 2px 24px 0; padding: 15px 0 18px; border-top: 1px solid var(--script-line); color: #84958e; font-size: 11px; }
.script-footnote span { display: grid; width: 15px; height: 15px; place-items: center; border: 1px solid #a4b4ad; border-radius: 50%; font-family: Georgia, serif; font-size: 10px; }
@media (max-width: 700px) {
  .script-library-card :deep(.proto-card-head) { align-items: flex-start; padding: 18px; }
  .script-library-card :deep(.proto-head-actions) { flex-wrap: wrap; }
  .script-library-body { padding: 16px 18px 0; }
  .create-form-footer, .script-row-footer { grid-template-columns: 1fr; }
  .create-form-footer .script-primary { width: 100%; }
  .script-row-heading { align-items: flex-start; flex-direction: column; gap: 8px; }
  .script-title-field { width: 100%; }
  .script-meta { padding-bottom: 0; }
  .script-actions { justify-content: flex-start; }
  .script-footnote { margin-inline: 18px; }
}
</style>
