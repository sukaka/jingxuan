<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createComplaintKeyword,
  disableComplaintKeyword,
  getComplaintPolicy,
  listComplaintKeywords,
  updateComplaintKeyword,
  updateComplaintPolicy,
} from '../api/complaint'
import type { ComplaintKeyword, ComplaintPolicy } from '../api/complaint'
import ComplaintEmpathyScriptSettings from '../components/ComplaintEmpathyScriptSettings.vue'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import WecomMappingSettings from '../components/WecomMappingSettings.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('complaint:config'))
const policy = ref<(ComplaintPolicy & { reason?: string }) | null>(null)
const keywords = ref<ComplaintKeyword[]>([])
const activeKeywordCount = computed(() => keywords.value.filter((item) => item.status === 'ACTIVE').length)
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const newKeyword = ref('')
const keywordReasons = reactive<Record<number, string>>({})
const keywordDrafts = reactive<Record<number, string>>({})
const keywordBusy = reactive<Record<number, boolean>>({})
let policyKey = ''
let policyPayload = ''

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function newKey(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `complaint-config-${Date.now()}-${Math.random()}`
}

async function load(): Promise<void> {
  if (!allowed.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [storedPolicy, storedKeywords] = await Promise.all([getComplaintPolicy(), listComplaintKeywords()])
    policy.value = storedPolicy
    keywords.value = storedKeywords
    storedKeywords.forEach((item) => {
      keywordDrafts[item.id] = item.keyword
      if (keywordReasons[item.id] === undefined) keywordReasons[item.id] = ''
    })
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function savePolicy(): Promise<void> {
  const current = policy.value
  if (!current || saving.value) return
  const reason = current.reason?.trim() ?? ''
  if (!reason) {
    errorMessage.value = '请填写策略修改理由'
    return
  }
  const body = {
    enabled: current.enabled,
    fallbackRecipientUserId: current.fallbackRecipientUserId || null,
    empathyScript: current.empathyScript.trim(),
    version: current.version,
    reason,
  }
  if (!body.empathyScript) {
    errorMessage.value = '共情话术不能为空；它仅供人工复制'
    return
  }
  const serialized = JSON.stringify(body)
  if (serialized !== policyPayload) {
    policyKey = newKey()
    policyPayload = serialized
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    policy.value = { ...await updateComplaintPolicy(body, policyKey), reason }
    successMessage.value = '策略已保存；规则检测不依赖 AI 开关'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function addKeyword(): Promise<void> {
  const keyword = newKeyword.value.trim()
  if (!keyword) {
    errorMessage.value = '请输入要新增的关键词'
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    await createComplaintKeyword({ keyword }, newKey())
    newKeyword.value = ''
    successMessage.value = '关键词已新增，仅用于后续合法来源检测'
    await load()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function saveKeyword(item: ComplaintKeyword): Promise<void> {
  const reason = keywordReasons[item.id]?.trim() ?? ''
  const keyword = keywordDrafts[item.id]?.trim() ?? ''
  if (!reason) {
    errorMessage.value = '请填写修改理由'
    return
  }
  if (!keyword) {
    errorMessage.value = '关键词不能为空'
    return
  }
  keywordBusy[item.id] = true
  errorMessage.value = ''
  try {
    await updateComplaintKeyword(item.id, { keyword, version: item.version, reason }, newKey())
    keywordReasons[item.id] = ''
    successMessage.value = '关键词已修改；历史快照保持原值'
    await load()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    keywordBusy[item.id] = false
  }
}

async function disableKeyword(item: ComplaintKeyword): Promise<void> {
  const reason = keywordReasons[item.id]?.trim() ?? ''
  if (!reason) {
    errorMessage.value = '请填写停用理由'
    return
  }
  if (keywordBusy[item.id]) return
  keywordBusy[item.id] = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await disableComplaintKeyword(item.id, { version: item.version, reason }, newKey())
    keywordReasons[item.id] = ''
    successMessage.value = '关键词已停用；历史命中与正式单不受影响'
    await load()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    keywordBusy[item.id] = false
  }
}

onMounted(() => { if (allowed.value) void load() })
</script>

<template>
  <div class="proto-page complaint-settings-page">
    <PageHeader title="客诉关键词" description="配置检测规则、人工话术与通知接收人；关键词命中后仍需人工核查。">
      <template #actions>
        <span v-if="policy" class="page-status" :class="{ stopped: !policy.enabled }">
          <i aria-hidden="true" />{{ policy.enabled ? '规则检测运行中' : '规则检测已停用' }}
        </span>
      </template>
    </PageHeader>

    <PageCard v-if="!allowed" class="settings-card">
      <div class="settings-empty" data-testid="complaint-config-denied"><strong>没有客诉配置权限</strong><span>请联系管理员按职责授权。</span></div>
    </PageCard>

    <template v-else>
      <div class="settings-notice">
        <span class="notice-symbol" aria-hidden="true">i</span>
        <div><strong>AI 停用不影响关键词检测</strong><span>共情话术仅供人工复制，系统不会自动发送，也不会修改订单、退款或权益。</span></div>
      </div>
      <div v-if="errorMessage" role="alert" class="settings-alert">{{ errorMessage }}</div>
      <div v-if="successMessage" role="status" class="settings-success">{{ successMessage }}</div>

      <PageCard
        class="settings-card policy-card"
        title="检测策略"
        subtitle="通知依次查找客户负责人、本组唯一在职主管和兜底接收人；无有效接收人时只记录异常。"
      >
        <template #actions><span v-if="policy" class="version-chip">配置版本 v{{ policy.version }}</span></template>
        <form v-if="policy" class="policy-form" @submit.prevent="savePolicy">
          <div class="policy-layout">
            <label class="policy-enabled" for="policy-enabled">
              <input id="policy-enabled" v-model="policy.enabled" type="checkbox">
              <span class="switch-track" aria-hidden="true"><i /></span>
              <span class="switch-copy"><strong>启用关键词规则检测</strong><small>关闭后不再产生新的关键词命中</small></span>
            </label>
            <div class="field fallback-field">
              <label for="fallback-recipient">兜底接收人 <span>选填</span></label>
              <div class="input-with-suffix">
                <input id="fallback-recipient" v-model.number="policy.fallbackRecipientUserId" type="number" min="1" placeholder="输入 CRM 员工 ID">
                <span>ID</span>
              </div>
              <p class="field-help">仅在找不到客户负责人和主管时使用，留空则不配置兜底。</p>
            </div>
          </div>
          <div class="policy-audit">
            <div class="field">
              <label for="policy-reason">本次修改理由 <em>必填</em></label>
              <input id="policy-reason" v-model="policy.reason" data-testid="policy-reason" maxlength="1000" placeholder="例如：调整检测开关或兜底接收人">
              <p class="field-help">保存后写入不可覆盖的审计记录。</p>
            </div>
            <div class="policy-actions">
              <span>修改仅影响后续检测</span>
              <button class="button-primary" type="button" data-testid="policy-save" :disabled="saving" @click="savePolicy">{{ saving ? '正在保存…' : '保存检测策略' }}</button>
            </div>
          </div>
        </form>
        <div v-else class="settings-empty">{{ loading ? '正在读取策略…' : '策略不可用' }}</div>
      </PageCard>

      <ComplaintEmpathyScriptSettings />

      <PageCard
        class="settings-card keyword-card"
        title="关键词配置"
        subtitle="新增、修改与停用均需填写理由；历史关键词快照保持原值。"
      >
        <template #actions><span class="section-count"><strong>{{ activeKeywordCount }}</strong> 个启用</span></template>
        <div class="keyword-body">
          <div class="keyword-create">
            <label for="new-keyword">新增检测词</label>
            <div class="keyword-create-controls">
              <input id="new-keyword" v-model="newKeyword" maxlength="128" placeholder="输入关键词，例如：投诉" @keyup.enter="addKeyword">
              <button class="button-primary" type="button" :disabled="saving" @click="addKeyword"><span aria-hidden="true">＋</span>新增关键词</button>
            </div>
            <p class="field-help">命中后只生成待核查记录，不会自动认定为正式客诉。</p>
          </div>

          <div v-if="keywords.length" class="keyword-list">
            <div class="keyword-list-head" aria-hidden="true"><span>关键词</span><span>操作理由</span><span>操作</span></div>
            <div v-for="(item, index) in keywords" :key="item.id" class="keyword-row" :data-testid="`keyword-${item.id}`">
              <div class="keyword-main">
                <span class="keyword-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <div class="keyword-name">
                  <label class="keyword-field-label" :for="`keyword-input-${item.id}`">关键词</label>
                  <input :id="`keyword-input-${item.id}`" v-model="keywordDrafts[item.id]" :disabled="item.status !== 'ACTIVE'" maxlength="128" :aria-label="`关键词 ${item.id}`">
                  <div class="keyword-meta">
                    <span class="status-pill" :class="item.status === 'ACTIVE' ? 'is-active' : 'is-disabled'"><i aria-hidden="true" />{{ item.status === 'ACTIVE' ? '启用中' : '已停用' }}</span>
                    <small>配置版本 v{{ item.version }}</small>
                  </div>
                </div>
              </div>
              <template v-if="item.status === 'ACTIVE'">
                <div class="field keyword-reason">
                  <label :for="`keyword-reason-${item.id}`">修改或停用理由</label>
                  <input :id="`keyword-reason-${item.id}`" v-model="keywordReasons[item.id]" :data-testid="`keyword-reason-${item.id}`" maxlength="1000" placeholder="填写本次操作原因" :aria-label="`关键词 ${item.id} 操作理由`">
                </div>
                <div class="keyword-actions"><button class="button-secondary" type="button" :disabled="keywordBusy[item.id]" @click="saveKeyword(item)">保存修改</button><button class="button-danger" type="button" :data-testid="`disable-keyword-${item.id}`" :disabled="keywordBusy[item.id]" @click="disableKeyword(item)">停用</button></div>
              </template>
              <p v-else class="disabled-note">不再参与后续检测，历史命中与正式单不受影响。</p>
            </div>
          </div>
          <div v-if="!loading && keywords.length === 0" class="settings-empty"><strong>还没有检测关键词</strong><span>添加第一个关键词后，系统才会生成待核查记录。</span></div>
        </div>
        <p class="settings-footnote"><span aria-hidden="true">i</span>停用只影响后续检测，不提供删除或自动确认操作。</p>
      </PageCard>
      <WecomMappingSettings />
    </template>
  </div>
</template>

<style scoped>
.complaint-settings-page {
  --settings-ink: #203d38;
  --settings-muted: #748883;
  --settings-line: #e3ebe7;
  --settings-primary: #247966;
  --settings-primary-dark: #195e50;
  --settings-soft: #f1f7f4;
  min-width: 0;
  color: var(--settings-ink);
}
.complaint-settings-page :deep(.proto-page-head) { margin-bottom: 20px; }
.complaint-settings-page :deep(.proto-page-heading h1) { color: var(--settings-ink); letter-spacing: -.8px; }
.complaint-settings-page :deep(.proto-page-heading p) { color: var(--settings-muted); font-size: 13px; }
.page-status { display: inline-flex; align-items: center; gap: 8px; padding: 8px 12px; border: 1px solid #d8e9e1; border-radius: 8px; color: #2c6f58; background: #f2f8f5; font-size: 12px; font-weight: 650; white-space: nowrap; }
.page-status i, .status-pill i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.page-status.stopped { border-color: #e5e7e6; color: #7b8582; background: #f5f6f6; }
.settings-notice { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 18px; padding: 14px 18px; border: 1px solid #dfe9e4; border-radius: 10px; background: #f7faf8; animation: settings-enter .45s ease-out both; }
.notice-symbol { display: grid; width: 20px; height: 20px; flex: 0 0 20px; place-items: center; margin-top: 1px; border: 1px solid #8eaaa0; border-radius: 50%; color: #628579; font-family: Georgia, serif; font-size: 13px; font-weight: 700; }
.settings-notice strong { display: block; margin-bottom: 3px; color: #375e52; font-size: 13px; }
.settings-notice div > span { color: #71867f; font-size: 12px; line-height: 1.7; }
.settings-alert, .settings-success { margin-bottom: 14px; padding: 12px 15px; border: 1px solid transparent; border-radius: 9px; font-size: 13px; }
.settings-alert { border-color: #f0d8d1; background: #fff6f3; color: #a34b3a; }
.settings-success { border-color: #d5e8dd; background: #f0f8f3; color: #337056; }
.settings-card { overflow: hidden; border-color: var(--settings-line); border-radius: 12px; box-shadow: 0 4px 18px rgb(36 68 57 / 3%); animation: settings-enter .45s .06s ease-out both; }
.settings-card :deep(.proto-card-head) { padding: 21px 24px 18px; border-bottom: 1px solid var(--settings-line); }
.settings-card :deep(.proto-card-head h2) { color: var(--settings-ink); font-size: 16px; letter-spacing: -.2px; }
.settings-card :deep(.proto-card-head p) { max-width: 780px; margin-top: 6px; color: var(--settings-muted); font-size: 12px; line-height: 1.65; }
.version-chip { padding: 5px 9px; border: 1px solid #e2e9e5; border-radius: 6px; color: #84948e; background: #fafcfb; font-size: 11px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.policy-form { padding: 24px; }
.policy-layout { display: grid; grid-template-columns: minmax(260px, .85fr) minmax(360px, 1.35fr); gap: clamp(28px, 4vw, 60px); }
.policy-enabled { display: flex; align-items: center; gap: 12px; cursor: pointer; }
.policy-enabled input, .table-switch input, .create-toggle input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.switch-track { position: relative; width: 40px; height: 23px; flex: 0 0 40px; border-radius: 20px; background: #cbd5d1; transition: background .2s ease-out; }
.switch-track i { position: absolute; top: 3px; left: 3px; width: 17px; height: 17px; border-radius: 50%; background: #fcfefd; box-shadow: 0 1px 3px rgb(22 52 43 / 20%); transition: transform .2s ease-out; }
.policy-enabled input:checked + .switch-track { background: var(--settings-primary); }
.policy-enabled input:checked + .switch-track i { transform: translateX(17px); }
.policy-enabled input:focus-visible + .switch-track, .table-switch input:focus-visible + .switch-track, .create-toggle input:focus-visible + .switch-track { outline: 3px solid rgb(36 121 102 / 20%); outline-offset: 3px; }
.switch-copy strong { display: block; color: #2c4c44; font-size: 13px; font-weight: 680; }
.switch-copy small { display: block; margin-top: 4px; color: var(--settings-muted); font-size: 11px; }
.field label, .keyword-create > label { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: #435f57; font-size: 12px; font-weight: 650; }
.field label > span { color: #93a19c; font-size: 10px; font-weight: 500; }
.field label em { color: #a26554; font-size: 10px; font-style: normal; font-weight: 500; }
.field input, .field textarea, .keyword-create input, .keyword-name input { width: 100%; min-width: 0; border: 1px solid #dbe5e0; border-radius: 8px; outline: none; color: #29473f; background: #fdfefd; font: inherit; font-size: 13px; transition: border-color .18s ease-out, box-shadow .18s ease-out, background .18s ease-out; }
.field input, .keyword-create input, .keyword-name input { height: 42px; padding: 0 12px; }
.field textarea { min-height: 142px; padding: 12px 14px; line-height: 1.85; resize: vertical; }
.field input:hover, .field textarea:hover, .keyword-create input:hover, .keyword-name input:hover { border-color: #b8ccc3; }
.field input:focus, .field textarea:focus, .keyword-create input:focus, .keyword-name input:focus { border-color: #65a391; background: #fff; box-shadow: 0 0 0 3px rgb(46 132 108 / 9%); }
.field input::placeholder, .field textarea::placeholder, .keyword-create input::placeholder { color: #a4b1ac; }
.input-with-suffix { position: relative; }
.input-with-suffix input { padding-right: 42px; }
.input-with-suffix > span { position: absolute; top: 50%; right: 12px; color: #95a69f; font-size: 10px; transform: translateY(-50%); pointer-events: none; }
.field-help { margin: 7px 0 0; color: #8a9a94; font-size: 11px; line-height: 1.65; }
.policy-audit { display: grid; grid-template-columns: minmax(260px, 1fr) auto; gap: 24px; align-items: end; margin-top: 24px; padding-top: 22px; border-top: 1px solid var(--settings-line); }
.policy-actions { display: flex; align-items: center; gap: 16px; padding-bottom: 1px; }
.policy-actions > span { color: #93a19c; font-size: 11px; white-space: nowrap; }
.button-primary, .button-secondary, .button-danger { min-height: 40px; padding: 9px 16px; border-radius: 8px; font: inherit; font-size: 12px; font-weight: 650; white-space: nowrap; cursor: pointer; transition: background .18s ease-out, border-color .18s ease-out, color .18s ease-out, transform .18s ease-out; }
.button-primary { border: 1px solid var(--settings-primary); color: #f7fcfa; background: var(--settings-primary); box-shadow: 0 3px 8px rgb(30 101 82 / 12%); }
.button-primary:hover:not(:disabled) { border-color: var(--settings-primary-dark); background: var(--settings-primary-dark); transform: translateY(-1px); }
.button-secondary { border: 1px solid #bdd4ca; color: #2c725f; background: #fbfdfc; }
.button-secondary:hover:not(:disabled) { border-color: #74a896; background: #f0f7f4; }
.button-danger { border: 1px solid transparent; color: #a36658; background: transparent; }
.button-danger:hover:not(:disabled) { border-color: #ecd9d3; background: #fff7f4; }
.button-primary:disabled, .button-secondary:disabled, .button-danger:disabled { opacity: .55; cursor: not-allowed; }
.section-count { color: #82938d; font-size: 11px; }
.section-count strong { margin-right: 3px; color: #316f5d; font-size: 18px; font-weight: 750; font-variant-numeric: tabular-nums; }
.keyword-card { animation-delay: .12s; }
.keyword-body { padding: 22px 24px 0; }
.keyword-create { max-width: 760px; padding-bottom: 22px; }
.keyword-create-controls { display: grid; grid-template-columns: minmax(180px, 1fr) auto; gap: 10px; }
.keyword-create-controls .button-primary { display: inline-flex; align-items: center; gap: 6px; }
.keyword-create-controls .button-primary span { font-size: 16px; font-weight: 400; line-height: 1; }
.keyword-list { border-top: 1px solid var(--settings-line); }
.keyword-list-head, .keyword-row { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(260px, 1fr) 190px; gap: 22px; align-items: start; }
.keyword-list-head { padding: 14px 0 10px; color: #94a29d; font-size: 10px; font-weight: 650; }
.keyword-list-head span:last-child { text-align: right; }
.keyword-row { padding: 18px 0; border-top: 1px solid #edf2ef; }
.keyword-list-head + .keyword-row { border-top: 0; }
.keyword-main { display: flex; align-items: flex-start; gap: 14px; min-width: 0; }
.keyword-index { padding-top: 31px; color: #a7b4af; font-family: Georgia, serif; font-size: 12px; font-variant-numeric: tabular-nums; }
.keyword-name { flex: 1; min-width: 0; }
.keyword-field-label, .keyword-reason label { display: block; height: 15px; margin-bottom: 6px; color: #84958e; font-size: 10px; font-weight: 500; line-height: 15px; }
.keyword-name input { height: 38px; border-color: transparent; padding-left: 0; background: transparent; color: #29483f; font-size: 14px; font-weight: 650; }
.keyword-name input:hover:not(:disabled), .keyword-name input:focus { padding-left: 10px; border-color: #cfe0d8; background: #fbfdfc; }
.keyword-name input:disabled { color: #82918c; opacity: 1; -webkit-text-fill-color: #82918c; }
.keyword-meta { display: flex; align-items: center; gap: 10px; margin-top: 7px; }
.keyword-meta small { color: #99a7a2; font-size: 10px; }
.status-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 10px; font-weight: 650; }
.status-pill.is-active { color: #3c7c64; }
.status-pill.is-disabled { color: #909b97; }
.keyword-reason input { height: 38px; font-size: 12px; }
.keyword-actions { display: flex; justify-content: flex-end; gap: 5px; margin-top: 21px; }
.keyword-actions .button-secondary, .keyword-actions .button-danger { min-height: 38px; padding: 7px 12px; }
.disabled-note { grid-column: 2 / -1; margin: 21px 0 0; color: #96a49f; font-size: 11px; }
.settings-footnote { display: flex; align-items: center; gap: 7px; margin: 2px 24px 0; padding: 15px 0 18px; border-top: 1px solid var(--settings-line); color: #84958e; font-size: 11px; }
.settings-footnote span { display: grid; width: 15px; height: 15px; place-items: center; border: 1px solid #a4b4ad; border-radius: 50%; font-family: Georgia, serif; font-size: 10px; }
.settings-empty { display: flex; min-height: 130px; align-items: center; justify-content: center; flex-direction: column; gap: 7px; padding: 26px; color: #8b9b95; text-align: center; }
.settings-empty strong { color: #4d6960; font-size: 13px; }
.settings-empty span { font-size: 12px; }
@keyframes settings-enter { from { transform: translateY(6px); } to { transform: translateY(0); } }
@media (max-width: 1100px) {
  .policy-layout { grid-template-columns: 1fr; gap: 24px; }
  .keyword-list-head { display: none; }
  .keyword-row { grid-template-columns: minmax(220px, 1fr) minmax(240px, 1fr); }
  .keyword-actions { grid-column: 2; margin-top: 0; }
  .disabled-note { grid-column: 2; margin-top: 0; }
}
@media (max-width: 760px) {
  .page-status { padding: 6px 9px; }
  .settings-notice { padding: 13px 14px; }
  .settings-card :deep(.proto-card-head) { align-items: flex-start; padding: 18px; }
  .policy-form, .keyword-body { padding: 18px; }
  .policy-audit { grid-template-columns: 1fr; gap: 18px; }
  .policy-actions { justify-content: space-between; }
  .keyword-create { max-width: none; }
  .keyword-row { grid-template-columns: 1fr; gap: 14px; padding: 18px 0; }
  .keyword-actions, .disabled-note { grid-column: 1; }
  .keyword-actions { justify-content: flex-start; }
  .keyword-body { padding-bottom: 0; }
  .settings-footnote { margin-inline: 18px; }
}
@media (max-width: 480px) {
  .keyword-create-controls { grid-template-columns: 1fr; }
  .keyword-create-controls .button-primary { justify-content: center; }
  .policy-actions { align-items: stretch; flex-direction: column; }
  .policy-actions .button-primary { width: 100%; }
}
</style>
