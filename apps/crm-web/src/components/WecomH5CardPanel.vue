<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listProjects } from '../api/catalog'
import type { Project } from '../api/catalog'
import { createH5Card, disableH5Card, listH5Cards, recordH5CardUsage } from '../api/h5Card'
import type { H5Card } from '../api/h5Card'
import { sendH5CardToCurrentChat } from '../services/wecomSdk'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

const props = defineProps<{ customerId: number; externalUserid: string; chatEntry: string }>()
const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('wecom:sidebar:view') && auth.hasPermission('product:read'))
const projects = ref<Project[]>([])
const history = ref<H5Card[]>([])
const projectId = ref<number | null>(null)
const title = ref('')
const summary = ref('')
const content = ref('')
const current = ref<H5Card | null>(null)
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const feedback = ref('')
const disabling = ref(false)
const disableReason = ref('')
let generationKey = ''
let disableKey = ''
let usageKey = ''

const canShare = computed(() => current.value?.status === 'ACTIVE' && !busy.value)
const projectName = computed(() => projects.value.find((item) => item.id === projectId.value)?.projectName ?? current.value?.projectName ?? '服务项目')

function errorText(error: unknown): string {
  return error instanceof ApiError || error instanceof Error ? error.message : '操作失败，请重试'
}

function requestKey(prefix: string): string {
  return `${prefix}-${typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`}`
}

function clearGenerated(): void {
  current.value = null
  generationKey = ''
  disableKey = ''
  usageKey = ''
  disabling.value = false
  disableReason.value = ''
  feedback.value = ''
}

function applyProject(): void {
  clearGenerated()
  const project = projects.value.find((item) => item.id === projectId.value)
  if (!project) return
  title.value = `${project.projectName}｜项目介绍`
  summary.value = '了解服务流程、机构安排与预约方式，具体以人工沟通为准。'
  content.value = `可了解${project.projectName}的服务流程、注意事项与预约方式。\n具体项目是否适合您，请与专属管家进一步沟通。`
}

function selectCard(card: H5Card): void {
  current.value = card
  projectId.value = card.projectId
  title.value = card.title
  summary.value = card.summary
  content.value = card.content
  generationKey = ''
  disableKey = ''
  usageKey = ''
  disabling.value = false
  disableReason.value = ''
  errorMessage.value = ''
  feedback.value = ''
}

async function load(): Promise<void> {
  if (!allowed.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [projectPage, cards] = await Promise.all([
      listProjects({ page: 1, size: 100, status: 'ACTIVE' }), listH5Cards(props.customerId),
    ])
    projects.value = projectPage.items
    history.value = cards
    projectId.value = projects.value[0]?.id ?? null
    applyProject()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function generate(): Promise<void> {
  if (!allowed.value || !projectId.value || busy.value) return
  if (!title.value.trim() || !summary.value.trim() || !content.value.trim()) {
    errorMessage.value = '请填写卡片标题、摘要和公开介绍'
    return
  }
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  if (!generationKey) generationKey = requestKey('h5-card-generate')
  try {
    const card = await createH5Card({
      customerId: props.customerId, projectId: projectId.value, externalUserid: props.externalUserid,
      title: title.value.trim(), summary: summary.value.trim(), content: content.value.trim(),
    }, generationKey)
    current.value = card
    history.value = [card, ...history.value.filter((item) => item.id !== card.id)]
    generationKey = ''
    usageKey = ''
    feedback.value = '分享链接已生成，请先打开公开页检查，再由你手动复制或发送'
  } catch (error) {
    errorMessage.value = `${errorText(error)}；页面重试会复用本次请求编号`
  } finally {
    busy.value = false
  }
}

async function recordUsage(status: 'COPIED' | 'SDK_SUCCESS' | 'SDK_FAILED' | 'SDK_UNKNOWN', result: string): Promise<void> {
  if (!current.value) return
  if (!usageKey) usageKey = requestKey('h5-card-usage')
  await recordH5CardUsage(current.value.id, {
    customerId: props.customerId, sendStatus: status, clientResult: result,
  }, usageKey)
  usageKey = ''
}

async function copyLink(): Promise<void> {
  if (!canShare.value || !current.value) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    await navigator.clipboard.writeText(current.value.link)
    try {
      await recordUsage('COPIED', 'navigator.clipboard:ok')
      feedback.value = '卡片链接已复制并记录，粘贴前请再次确认当前客户'
    } catch (error) {
      errorMessage.value = `链接已复制，但日志记录失败：${errorText(error)}`
    }
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function send(): Promise<void> {
  if (!canShare.value || !current.value || props.chatEntry !== 'single_chat_tools') return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    const result = await sendH5CardToCurrentChat(current.value)
    try {
      await recordUsage(result.status, result.clientResult)
    } catch (error) {
      errorMessage.value = `企微已返回 ${result.status}，但日志记录失败：${errorText(error)}；请先核实会话，不要直接重复发送`
      return
    }
    if (result.status === 'SDK_SUCCESS') feedback.value = '企微已确认 H5 卡片发送；这不代表客户已读'
    else if (result.status === 'SDK_FAILED') feedback.value = '企微返回卡片发送失败，失败结果已记录'
    else feedback.value = '发送结果未知，请先到会话核实，不要直接重复发送'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function disable(): Promise<void> {
  if (!current.value || current.value.status !== 'ACTIVE' || !disableReason.value.trim() || busy.value) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  if (!disableKey) disableKey = requestKey('h5-card-disable')
  try {
    const saved = await disableH5Card(current.value.id, {
      version: current.value.version, reason: disableReason.value.trim(),
    }, disableKey)
    selectCard(saved)
    history.value = history.value.map((item) => item.id === saved.id ? saved : item)
    feedback.value = '公开链接已停用，历史复制与发送记录仍保留'
    disableKey = ''
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="h5-card-panel" data-testid="h5-card-panel">
    <header class="h5-heading"><div><span>H5 · 项目介绍</span><h2>把项目说明分享给客户</h2></div><small>员工手动分享</small></header>
    <p class="h5-boundary">公开页只包含项目介绍，不包含客户档案、订单、剩余次数、员工 ID 或内部备注。</p>
    <div v-if="!allowed" class="h5-empty">当前账号没有项目读取或企微侧边栏权限</div>
    <div v-else-if="loading" class="h5-empty">正在读取可分享项目…</div>
    <template v-else>
      <div v-if="errorMessage" class="h5-alert" role="alert">{{ errorMessage }}</div>
      <div class="h5-editor">
        <label for="h5-project">介绍项目</label>
        <select id="h5-project" v-model.number="projectId" data-testid="h5-project" :disabled="busy" @change="applyProject"><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.projectName }}</option></select>
        <div v-if="!projects.length" class="h5-empty">暂无已启用项目，请先在产品与套餐中维护。</div>
        <label for="h5-title">卡片标题</label>
        <input id="h5-title" v-model="title" data-testid="h5-title" maxlength="160" :disabled="busy" @input="clearGenerated">
        <label for="h5-summary">卡片摘要</label>
        <textarea id="h5-summary" v-model="summary" data-testid="h5-summary" rows="2" maxlength="500" :disabled="busy" @input="clearGenerated" />
        <label for="h5-content">公开介绍</label>
        <textarea id="h5-content" v-model="content" data-testid="h5-content" rows="5" maxlength="4000" :disabled="busy" @input="clearGenerated" />
        <p class="content-note">请勿填写客户隐私、疗效保证或绝对化承诺。修改内容后需生成新的分享快照。</p>
        <button class="generate-card" type="button" data-testid="h5-generate" :disabled="busy || !projectId" @click="generate">{{ busy && !current ? '正在生成…' : current ? '重新生成分享快照' : '生成分享卡片' }}</button>
      </div>

      <section class="card-preview" aria-label="H5 卡片预览">
        <img src="/h5-card-cover.svg" alt="服务项目介绍封面">
        <div><strong>{{ title || '项目介绍' }}</strong><p>{{ summary || '填写摘要后在此预览' }}</p><span>{{ projectName }}</span></div>
      </section>
      <a v-if="current?.status === 'ACTIVE'" class="public-preview" data-testid="h5-public-preview" :href="current.link" target="_blank" rel="noopener noreferrer">打开公开页预览 <span aria-hidden="true">↗</span></a>
      <div v-if="current" class="snapshot-meta"><span :class="{ disabled: current.status === 'DISABLED' }">{{ current.status === 'ACTIVE' ? '分享快照已生成' : '公开链接已停用' }}</span><small>v{{ current.version }}</small></div>
      <div v-if="current?.status === 'ACTIVE'" class="card-actions">
        <button class="copy-link" type="button" data-testid="h5-copy" :disabled="!canShare" @click="copyLink">复制链接</button>
        <button v-if="chatEntry === 'single_chat_tools'" class="send-card" type="button" data-testid="h5-send" :disabled="!canShare" @click="send">发送到当前会话</button>
      </div>
      <p v-if="current?.status === 'ACTIVE' && chatEntry !== 'single_chat_tools'" class="content-note">当前是客户资料页，可复制链接；请进入客户单聊后发送卡片。</p>
      <div v-if="current?.status === 'ACTIVE'" class="disable-area">
        <button v-if="!disabling" class="disable-link" type="button" data-testid="h5-show-disable" @click="disabling = true">停用此公开链接</button>
        <div v-else class="disable-form"><label for="h5-disable-reason">停用理由</label><input id="h5-disable-reason" v-model="disableReason" data-testid="h5-disable-reason" maxlength="1000" placeholder="停用后公开页将无法访问"><button type="button" data-testid="h5-disable" :disabled="busy || !disableReason.trim()" @click="disable">确认停用</button></div>
      </div>
      <div v-if="feedback" class="h5-feedback" role="status">{{ feedback }}</div>

      <section v-if="history.length" class="card-history"><h3>本人最近分享</h3><button v-for="item in history" :key="item.id" type="button" :disabled="busy" :data-testid="`h5-history-${item.id}`" @click="selectCard(item)"><span>{{ item.title }}</span><small>{{ item.status === 'ACTIVE' ? '可分享' : '已停用' }}</small></button></section>
    </template>
  </section>
</template>

<style scoped>
.h5-card-panel { color: #2b4b3d; }
.h5-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h5-heading > div > span { color: #799082; font-size: 10px; font-weight: 650; letter-spacing: 1.5px; }
.h5-heading h2 { margin: 6px 0 0; color: #294c3c; font-size: 20px; letter-spacing: -.4px; line-height: 1.35; }
.h5-heading > small { margin-top: 3px; padding: 4px 6px; border: 1px solid #c6d6cb; border-radius: 5px; color: #547463; font-size: 9px; white-space: nowrap; }
.h5-boundary { margin: 12px 0 20px; color: #789084; font-size: 11px; line-height: 1.8; }
.h5-editor { display: grid; gap: 8px; }
.h5-editor label { margin-top: 5px; color: #567363; font-size: 11px; font-weight: 650; }
.h5-editor input, .h5-editor select, .h5-editor textarea, .disable-form input { box-sizing: border-box; width: 100%; min-height: 39px; padding: 9px 11px; border: 1px solid #d4e1d8; border-radius: 8px; outline: none; color: #345744; background: #fcfefc; font: inherit; font-size: 12px; line-height: 1.75; }
.h5-editor textarea { resize: vertical; }
.h5-editor input:focus, .h5-editor select:focus, .h5-editor textarea:focus { border-color: #7ba58e; box-shadow: 0 0 0 3px rgb(50 111 83 / 8%); }
.content-note { margin: 2px 0 5px; color: #8a9e92; font-size: 10px; line-height: 1.7; }
.generate-card, .card-actions button { min-height: 40px; padding: 9px 13px; border-radius: 8px; font: inherit; font-size: 12px; font-weight: 650; cursor: pointer; }
.generate-card { border: 1px solid #2b6a52; color: #f3f9f5; background: #2b6a52; }
.card-preview { display: grid; grid-template-columns: 108px 1fr; gap: 14px; margin-top: 23px; padding: 14px; border: 1px solid #dce6df; border-radius: 10px; background: #fdfefd; }
.card-preview img { width: 108px; height: 82px; object-fit: cover; border-radius: 6px; }
.card-preview > div { min-width: 0; }
.card-preview strong { display: block; overflow: hidden; color: #365c48; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.card-preview p { display: -webkit-box; margin: 7px 0; overflow: hidden; color: #809387; font-size: 10px; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.card-preview span { color: #97a69d; font-size: 9px; }
.public-preview { display: flex; justify-content: space-between; margin-top: 11px; padding: 8px 2px; color: #347258; font-size: 11px; font-weight: 650; text-decoration: none; }
.snapshot-meta { display: flex; justify-content: space-between; margin-top: 12px; color: #4d775f; font-size: 10px; }
.snapshot-meta .disabled { color: #9a6b57; }
.snapshot-meta small { color: #95a69b; }
.card-actions { display: flex; gap: 9px; margin-top: 13px; }
.copy-link { border: 1px solid #b9cebf; color: #3a7056; background: #f4f8f5; }
.send-card { flex: 1; border: 1px solid #2b6a52; color: #f3f9f5; background: #2b6a52; }
button:disabled { opacity: .45; cursor: not-allowed; }
.disable-area { margin-top: 13px; }
.disable-link { padding: 4px 0; border: 0; color: #987461; background: transparent; font: inherit; font-size: 10px; cursor: pointer; }
.disable-form { display: grid; gap: 7px; padding: 11px; border-radius: 7px; background: #f8f3ef; }
.disable-form label { color: #896c5e; font-size: 10px; }
.disable-form button { min-height: 34px; border: 1px solid #d2b4a4; border-radius: 6px; color: #935e46; background: transparent; font: inherit; font-size: 11px; cursor: pointer; }
.h5-alert, .h5-feedback { margin-bottom: 13px; padding: 10px 12px; border-radius: 7px; font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.h5-alert { color: #9b5841; background: #fff0e9; }
.h5-feedback { margin-top: 13px; margin-bottom: 0; color: #3b7054; background: #ecf5ef; }
.h5-empty { padding: 28px 10px; color: #84988c; font-size: 11px; line-height: 1.8; text-align: center; }
.card-history { margin-top: 22px; padding-top: 13px; border-top: 1px solid #e0e8e2; }
.card-history h3 { margin: 0 0 6px; color: #567160; font-size: 11px; }
.card-history button { display: flex; width: 100%; justify-content: space-between; gap: 12px; padding: 10px 2px; border: 0; border-bottom: 1px solid #edf2ee; color: #597765; background: transparent; font: inherit; font-size: 11px; text-align: left; cursor: pointer; }
.card-history small { color: #93a399; font-size: 10px; }
@media (max-width: 340px) { .card-preview { grid-template-columns: 84px 1fr; gap: 10px; padding: 11px; } .card-preview img { width: 84px; height: 74px; } }
</style>
