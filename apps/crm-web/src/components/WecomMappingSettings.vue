<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createWecomMapping,
  listWecomMappings,
  updateWecomMapping,
} from '../api/wecomMapping'
import type { WecomMapping } from '../api/wecomMapping'
import PageCard from './prototype/PageCard.vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const canRead = computed(() => auth.hasPermission('notification:delivery:read'))
const canWrite = computed(() => auth.hasPermission('notification:delivery:retry'))
const rows = ref<WecomMapping[]>([])
const enabledMappingCount = computed(() => rows.value.filter((row) => row.messageEnabled).length)
const hasMockMappings = computed(() => rows.value.some(isMockMapping))
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const drafts = reactive<Record<number, { wecomUserid: string; messageEnabled: boolean; reason: string }>>({})
const createForm = reactive({ corpId: 'MOCK-CORP-001', wecomUserid: '', staffUserId: null as number | null, messageEnabled: true, reason: '配置企微通知接收人' })
const keys = new Map<string, string>()

function isMockMapping(row: WecomMapping): boolean {
  return row.corpId.toUpperCase().includes('MOCK') || /^(staff-\d+|mock-)/i.test(row.wecomUserid)
}

function keyFor(key: string): string {
  const existing = keys.get(key)
  if (existing) return existing
  const created = crypto.randomUUID()
  keys.set(key, created)
  return created
}

async function load(): Promise<void> {
  if (!canRead.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    rows.value = await listWecomMappings()
    rows.value.forEach((row) => {
      drafts[row.id] = { wecomUserid: row.wecomUserid, messageEnabled: row.messageEnabled, reason: '' }
    })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '映射读取失败'
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  if (!canWrite.value || busy.value) return
  const staffUserId = Number(createForm.staffUserId)
  if (!createForm.corpId.trim() || !createForm.wecomUserid.trim() || !Number.isInteger(staffUserId) || staffUserId < 1) {
    errorMessage.value = '请完整填写企业 ID、企微成员 ID 和有效 CRM 员工 ID'
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    await createWecomMapping({ corpId: createForm.corpId.trim(), wecomUserid: createForm.wecomUserid.trim(), staffUserId, messageEnabled: createForm.messageEnabled, reason: createForm.reason.trim() }, keyFor('create'))
    keys.delete('create')
    createForm.wecomUserid = ''
    createForm.staffUserId = null
    successMessage.value = '企微通知映射已保存；归档采集范围未改变'
    await load()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '映射保存失败'
  } finally {
    busy.value = false
  }
}

async function update(row: WecomMapping): Promise<void> {
  if (!canWrite.value || busy.value) return
  const draft = drafts[row.id]
  if (!draft?.wecomUserid.trim() || !draft.reason.trim()) {
    errorMessage.value = '请填写企微成员 ID 和本次修改理由'
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    await updateWecomMapping(row.id, { corpId: row.corpId, wecomUserid: draft.wecomUserid.trim(), staffUserId: row.staffUserId, messageEnabled: draft.messageEnabled, reason: draft.reason.trim(), version: row.version }, keyFor(`update:${row.id}`))
    keys.delete(`update:${row.id}`)
    successMessage.value = '映射与通知状态已更新；历史投递记录不变'
    await load()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '映射修改失败'
  } finally {
    busy.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <PageCard class="mapping-card" title="企微通知接收人" subtitle="关联 CRM 员工与企微成员 ID；通知开关与归档采集互不影响，历史投递不会被覆盖。">
    <template #actions><span class="mapping-count"><strong>{{ enabledMappingCount }}</strong> 人允许通知</span></template>
    <div class="mapping-body">
      <div v-if="hasMockMappings" class="mock-notice">
        <span class="mock-label">模拟演示数据</span>
        <p>带有“模拟”标识的成员账号为模拟演示账号，仅用于功能演示，<strong>不是真实员工姓名</strong>。</p>
      </div>
      <div v-if="errorMessage" role="alert" class="mapping-alert">{{ errorMessage }}</div>
      <div v-if="successMessage" role="status" class="mapping-success">{{ successMessage }}</div>

      <form v-if="canWrite" class="mapping-create" data-testid="mapping-create-form" @submit.prevent="create">
        <div class="create-heading">
          <div><h3>添加接收人</h3><p>填写企业微信中的成员标识，建立员工通知映射。</p></div>
          <label class="create-toggle">
            <input v-model="createForm.messageEnabled" type="checkbox">
            <span class="switch-track" aria-hidden="true"><i /></span>
            <span>允许接收通知</span>
          </label>
        </div>
        <div class="create-fields">
          <div class="mapping-field"><label for="mapping-corp-id">企业 ID</label><input id="mapping-corp-id" v-model="createForm.corpId" aria-label="企业 ID" maxlength="96" placeholder="例如：wwxxxxxxxxxxxx" autocomplete="off"></div>
          <div class="mapping-field"><label for="mapping-member-id">企微成员 ID</label><input id="mapping-member-id" v-model="createForm.wecomUserid" aria-label="企微成员 ID" maxlength="96" placeholder="输入企微成员账号" autocomplete="off"></div>
          <div class="mapping-field"><label for="mapping-staff-id">CRM 员工 ID</label><input id="mapping-staff-id" v-model.number="createForm.staffUserId" type="number" min="1" aria-label="CRM 员工 ID" placeholder="输入内部员工 ID"></div>
        </div>
        <div class="create-footer">
          <div class="mapping-field"><label for="mapping-create-reason">操作理由</label><input id="mapping-create-reason" v-model="createForm.reason" aria-label="新增映射理由" maxlength="1000" placeholder="填写本次配置原因"></div>
          <button class="mapping-primary" type="submit" :disabled="busy"><span aria-hidden="true">＋</span>{{ busy ? '正在保存…' : '新增映射' }}</button>
        </div>
      </form>

      <div v-if="rows.length" class="mapping-table-wrap">
        <table class="mapping-table">
          <thead><tr><th>企微成员</th><th>CRM 员工</th><th>归档采集</th><th>接收通知</th><th>修改理由</th><th><span class="sr-only">操作</span></th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" :data-testid="`mapping-${row.id}`">
              <td>
                <div class="member-editor">
                  <input v-if="canWrite" v-model="drafts[row.id].wecomUserid" :aria-label="`映射 ${row.id} 企微成员`" maxlength="96">
                  <span v-else class="member-id">{{ row.wecomUserid }}</span>
                  <small>{{ row.corpId }}<span v-if="isMockMapping(row)" class="mock-tag">模拟</span></small>
                </div>
              </td>
              <td><span class="staff-id">员工 #{{ row.staffUserId }}</span><small>内部员工 ID</small></td>
              <td><span class="mapping-status" :class="{ enabled: row.archiveEnabled }"><i aria-hidden="true" />{{ row.archiveEnabled ? '已启用' : '未启用' }}</span></td>
              <td>
                <label v-if="canWrite" class="table-switch">
                  <input v-model="drafts[row.id].messageEnabled" type="checkbox" :aria-label="`映射 ${row.id} 通知开关`">
                  <span class="switch-track" aria-hidden="true"><i /></span>
                  <span>{{ drafts[row.id].messageEnabled ? '允许通知' : '已停用' }}</span>
                </label>
                <span v-else>{{ row.messageEnabled ? '允许通知' : '已停用' }}</span>
                <small class="row-version">版本 {{ row.version }}</small>
              </td>
              <td><input v-if="canWrite" v-model="drafts[row.id].reason" :aria-label="`映射 ${row.id} 修改理由`" maxlength="1000" placeholder="填写本次修改原因"><span v-else class="readonly-copy">只读</span></td>
              <td class="mapping-action"><button v-if="canWrite" class="mapping-save" type="button" :disabled="busy" @click="update(row)">保存修改</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="loading" class="mapping-empty">正在读取接收人配置…</div>
      <div v-else-if="!canRead" class="mapping-empty">没有企微通知映射查看权限</div>
      <div v-else-if="!rows.length" class="mapping-empty"><strong>尚未配置通知接收人</strong><span>添加员工与企微成员的映射后，才能投递外部通知。</span></div>
    </div>
  </PageCard>
</template>

<style scoped>
.mapping-card {
  --mapping-ink: #203d38;
  --mapping-muted: #7c8f88;
  --mapping-line: #e4ece8;
  --mapping-primary: #247966;
  overflow: hidden;
  border-color: var(--mapping-line);
  border-radius: 12px;
  box-shadow: 0 4px 18px rgb(36 68 57 / 3%);
  color: var(--mapping-ink);
  animation: mapping-enter .45s .18s ease-out both;
}
.mapping-card :deep(.proto-card-head) { padding: 21px 24px 18px; border-bottom: 1px solid var(--mapping-line); }
.mapping-card :deep(.proto-card-head h2) { color: var(--mapping-ink); font-size: 16px; letter-spacing: -.2px; }
.mapping-card :deep(.proto-card-head p) { margin-top: 6px; color: var(--mapping-muted); font-size: 12px; line-height: 1.65; }
.mapping-count { color: #82938d; font-size: 11px; white-space: nowrap; }
.mapping-count strong { margin-right: 3px; color: #316f5d; font-size: 18px; font-weight: 750; font-variant-numeric: tabular-nums; }
.mapping-body { padding: 20px 24px 8px; }
.mock-notice { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; padding: 11px 13px; border: 1px solid #e8e2d4; border-radius: 8px; background: #fbf9f3; }
.mock-label { flex: 0 0 auto; padding: 3px 7px; border-radius: 5px; color: #846c3f; background: #f1ead9; font-size: 10px; font-weight: 700; }
.mock-notice p { margin: 0; color: #7f7766; font-size: 11px; line-height: 1.7; }
.mock-notice strong { color: #746243; font-weight: 650; }
.mock-notice code { padding: 1px 4px; border-radius: 3px; color: #675d49; background: #f2eee4; font-size: 11px; }
.mapping-alert, .mapping-success { margin-bottom: 14px; padding: 12px 15px; border: 1px solid transparent; border-radius: 9px; font-size: 13px; }
.mapping-alert { border-color: #f0d8d1; background: #fff6f3; color: #a34b3a; }
.mapping-success { border-color: #d5e8dd; background: #f0f8f3; color: #337056; }
.mapping-create { margin-bottom: 22px; padding: 18px; border: 1px solid #e3ebe7; border-radius: 10px; background: #f8faf9; }
.create-heading { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 17px; }
.create-heading h3 { margin: 0; color: #36574e; font-size: 13px; font-weight: 680; }
.create-heading p { margin: 4px 0 0; color: #8a9a94; font-size: 11px; }
.create-toggle, .table-switch { display: inline-flex; align-items: center; gap: 8px; color: #5e786f; font-size: 11px; white-space: nowrap; cursor: pointer; }
.create-toggle input, .table-switch input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.switch-track { position: relative; width: 34px; height: 20px; flex: 0 0 34px; border-radius: 20px; background: #cbd5d1; transition: background .2s ease-out; }
.switch-track i { position: absolute; top: 3px; left: 3px; width: 14px; height: 14px; border-radius: 50%; background: #fcfefd; box-shadow: 0 1px 3px rgb(22 52 43 / 20%); transition: transform .2s ease-out; }
.create-toggle input:checked + .switch-track, .table-switch input:checked + .switch-track { background: var(--mapping-primary); }
.create-toggle input:checked + .switch-track i, .table-switch input:checked + .switch-track i { transform: translateX(14px); }
.create-toggle input:focus-visible + .switch-track, .table-switch input:focus-visible + .switch-track { outline: 3px solid rgb(36 121 102 / 20%); outline-offset: 3px; }
.create-fields { display: grid; grid-template-columns: 1fr 1fr .8fr; gap: 12px; }
.mapping-field label { display: block; margin-bottom: 7px; color: #637c73; font-size: 11px; font-weight: 620; }
.mapping-field input, .member-editor input, .mapping-table td:nth-child(5) input { width: 100%; min-width: 0; height: 39px; padding: 0 11px; border: 1px solid #d9e4df; border-radius: 7px; outline: none; color: #304f46; background: #fdfefd; font: inherit; font-size: 12px; transition: border-color .18s ease-out, box-shadow .18s ease-out; }
.mapping-field input::placeholder, .mapping-table input::placeholder { color: #a3b0ab; }
.mapping-field input:focus, .member-editor input:focus, .mapping-table td:nth-child(5) input:focus { border-color: #68a592; box-shadow: 0 0 0 3px rgb(46 132 108 / 8%); }
.create-footer { display: grid; grid-template-columns: minmax(220px, 1fr) auto; gap: 14px; align-items: end; margin-top: 14px; }
.mapping-primary, .mapping-save { min-height: 39px; padding: 8px 15px; border-radius: 7px; font: inherit; font-size: 12px; font-weight: 650; white-space: nowrap; cursor: pointer; transition: background .18s ease-out, border-color .18s ease-out, transform .18s ease-out; }
.mapping-primary { display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--mapping-primary); color: #f7fcfa; background: var(--mapping-primary); }
.mapping-primary span { font-size: 15px; font-weight: 400; }
.mapping-primary:hover:not(:disabled) { background: #195e50; transform: translateY(-1px); }
.mapping-primary:disabled, .mapping-save:disabled { opacity: .55; cursor: not-allowed; }
.mapping-table-wrap { margin: 0 -24px; overflow-x: auto; }
.mapping-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.mapping-table th { padding: 12px 14px; border-top: 1px solid var(--mapping-line); border-bottom: 1px solid var(--mapping-line); color: #8a9b95; background: #fafcfb; font-size: 10px; font-weight: 650; text-align: left; white-space: nowrap; }
.mapping-table th:first-child, .mapping-table td:first-child { padding-left: 24px; }
.mapping-table th:last-child, .mapping-table td:last-child { padding-right: 24px; }
.mapping-table td { min-width: 110px; padding: 16px 14px; border-bottom: 1px solid #edf2ef; color: #526e64; text-align: left; vertical-align: middle; }
.mapping-table tbody tr { transition: background .18s ease-out; }
.mapping-table tbody tr:hover { background: #fbfdfc; }
.mapping-table td:first-child { min-width: 190px; }
.mapping-table td:nth-child(5) { min-width: 210px; }
.mapping-table td small { display: block; margin-top: 6px; color: #96a59f; font-size: 10px; }
.member-editor small { color: #93a29c; }
.member-id, .staff-id { color: #36564d; font-weight: 650; }
.mock-tag { display: inline-block; margin-left: 6px; padding: 1px 4px; border-radius: 3px; color: #8c774e; background: #f3eee2; font-size: 9px; }
.mapping-status { display: inline-flex; align-items: center; gap: 6px; color: #929e99; font-size: 11px; white-space: nowrap; }
.mapping-status i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.mapping-status.enabled { color: #477b65; }
.row-version { white-space: nowrap; }
.mapping-action { min-width: 90px !important; text-align: right !important; }
.mapping-save { min-height: 34px; padding: 6px 11px; border: 1px solid #bdd4ca; color: #2c725f; background: #fbfdfc; }
.mapping-save:hover:not(:disabled) { border-color: #74a896; background: #f0f7f4; }
.readonly-copy { color: #97a49f; }
.mapping-empty { display: flex; min-height: 120px; align-items: center; justify-content: center; flex-direction: column; gap: 6px; padding: 24px; color: #8b9b95; font-size: 12px; text-align: center; }
.mapping-empty strong { color: #4d6960; font-size: 13px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@keyframes mapping-enter { from { transform: translateY(6px); } to { transform: translateY(0); } }
@media (max-width: 1050px) {
  .create-fields { grid-template-columns: 1fr 1fr; }
  .create-fields .mapping-field:last-child { grid-column: 1 / -1; }
}
@media (max-width: 700px) {
  .mapping-card :deep(.proto-card-head) { align-items: flex-start; padding: 18px; }
  .mapping-body { padding: 16px 18px 4px; }
  .mock-notice { align-items: flex-start; flex-direction: column; gap: 7px; }
  .mapping-create { padding: 15px; }
  .create-heading { align-items: flex-start; flex-direction: column; }
  .create-fields, .create-footer { grid-template-columns: 1fr; }
  .create-fields .mapping-field:last-child { grid-column: auto; }
  .mapping-primary { justify-content: center; }
  .mapping-table-wrap { margin-inline: -18px; }
  .mapping-table th:first-child, .mapping-table td:first-child { padding-left: 18px; }
  .mapping-table th:last-child, .mapping-table td:last-child { padding-right: 18px; }
}
</style>
