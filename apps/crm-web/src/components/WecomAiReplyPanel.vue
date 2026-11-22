<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { decideAiReply, generateAiReply, listAiReplyHistory } from '../api/aiReply'
import type { AiReplySuggestion } from '../api/aiReply'
import { listQuickReplies, recordQuickReplyUsage } from '../api/quickReply'
import type { QuickReplyTemplate, QuickReplyUsageInput } from '../api/quickReply'
import { sendTextToCurrentChat } from '../services/wecomSdk'
import type { WecomSendTarget } from '../services/wecomSdk'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import { copyTextWithFallback } from '../utils/clipboard'

const props = defineProps<{ customerId: number; externalUserid: string; chatEntry: string }>()
const emit = defineEmits<{
  'context-mismatch': []
}>()

const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('quickreply:view') && (auth.hasPermission('ai:write') || auth.hasPermission('ai:read')))
const canGenerate = computed(() => auth.hasPermission('quickreply:view') && auth.hasPermission('ai:write'))
const templates = ref<QuickReplyTemplate[]>([])
const history = ref<AiReplySuggestion[]>([])
const selectedTemplateId = ref<number | null>(null)
const current = ref<AiReplySuggestion | null>(null)
const draft = ref('')
const instruction = ref('')
const rejectionReason = ref('')
const rejecting = ref(false)
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const feedback = ref('')
let generationKey = ''
let decisionKey = ''
let usageKey = ''

const selectedTemplate = computed(() => templates.value.find((template) => template.id === selectedTemplateId.value) ?? null)
const proposed = computed(() => current.value?.status === 'PROPOSED')
const accepted = computed(() => current.value?.status === 'ACCEPTED' && current.value.finalContent !== null)
const canUse = computed(() => accepted.value && !busy.value && (current.value?.finalContent?.trim().length ?? 0) > 0)
const statusLabel = computed(() => ({ PROPOSED: '待人工处理', ACCEPTED: '已人工采纳', REJECTED: '已驳回' } as Record<string, string>)[current.value?.status ?? ''] ?? '')

function hasChatContext(suggestion: AiReplySuggestion): boolean {
  return suggestion.chatContextIncluded === true && (suggestion.chatContextCount ?? 0) > 0
}

function chatContextBadgeText(suggestion: AiReplySuggestion): string {
  return hasChatContext(suggestion)
    ? `已参考最近 ${suggestion.chatContextCount} 条已授权存档聊天`
    : '仅基于 CRM 档案事实'
}

function historyContextLabel(suggestion: AiReplySuggestion): string {
  return hasChatContext(suggestion)
    ? `含 ${suggestion.chatContextCount} 条存档聊天`
    : '仅 CRM 事实'
}

function errorText(error: unknown): string {
  return error instanceof ApiError || error instanceof Error ? error.message : '操作失败，请重试'
}

function requestKey(prefix: string): string {
  return `${prefix}-${typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`}`
}

function selectSuggestion(value: AiReplySuggestion): void {
  current.value = value
  selectedTemplateId.value = value.templateId
  draft.value = value.finalContent ?? value.reply
  rejecting.value = false
  rejectionReason.value = ''
  errorMessage.value = ''
  feedback.value = ''
  decisionKey = ''
  usageKey = ''
}

function resetCandidate(): void {
  current.value = null
  draft.value = ''
  rejecting.value = false
  rejectionReason.value = ''
  feedback.value = ''
  generationKey = ''
  decisionKey = ''
  usageKey = ''
}

async function load(): Promise<void> {
  if (!allowed.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const requests: [Promise<QuickReplyTemplate[]>, Promise<AiReplySuggestion[]> | Promise<null>] = [
      listQuickReplies(),
      auth.hasPermission('ai:read') ? listAiReplyHistory(props.customerId) : Promise.resolve(null),
    ]
    const [storedTemplates, storedHistory] = await Promise.all(requests)
    templates.value = storedTemplates.filter((template) => template.aiEnabled)
    history.value = storedHistory ?? []
    selectedTemplateId.value = selectedTemplate.value?.id ?? templates.value[0]?.id ?? null
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function generate(): Promise<void> {
  if (!canGenerate.value || !selectedTemplateId.value || busy.value) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  if (!generationKey) resetCandidate()
  if (!generationKey) generationKey = requestKey('ai-reply-generate')
  try {
    const generated = await generateAiReply({
      customerId: props.customerId,
      templateId: selectedTemplateId.value,
      instruction: instruction.value.trim() || undefined,
    }, generationKey)
    selectSuggestion(generated)
    history.value = [generated, ...history.value.filter((item) => item.id !== generated.id)]
    generationKey = ''
  } catch (error) {
    errorMessage.value = `${errorText(error)}；可使用页面重试，系统会复用本次请求编号`
  } finally {
    busy.value = false
  }
}

async function accept(): Promise<void> {
  if (!current.value || !proposed.value || !draft.value.trim() || busy.value) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  if (!decisionKey) decisionKey = requestKey('ai-reply-accept')
  try {
    const saved = await decideAiReply(current.value.id, {
      decision: 'ACCEPT', version: current.value.version, finalContent: draft.value,
      reason: '员工已人工核对客户、场景和回复内容',
    }, decisionKey)
    selectSuggestion(saved)
    history.value = history.value.map((item) => item.id === saved.id ? saved : item)
    feedback.value = '已采纳并锁定本次回复，现在可由你手动复制或发送'
    decisionKey = ''
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function reject(): Promise<void> {
  if (!current.value || !proposed.value || !rejectionReason.value.trim() || busy.value) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  if (!decisionKey) decisionKey = requestKey('ai-reply-reject')
  try {
    const saved = await decideAiReply(current.value.id, {
      decision: 'REJECT', version: current.value.version, reason: rejectionReason.value.trim(),
    }, decisionKey)
    selectSuggestion(saved)
    history.value = history.value.map((item) => item.id === saved.id ? saved : item)
    feedback.value = '候选已驳回，不会复制、发送或记为已使用'
    decisionKey = ''
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function recordUsage(status: QuickReplyUsageInput['sendStatus'], clientResult: string): Promise<void> {
  if (!current.value?.accepted || current.value.finalContent === null) return
  if (!usageKey) usageKey = requestKey('ai-reply-usage')
  await recordQuickReplyUsage(current.value.templateId, {
    customerId: props.customerId,
    externalUserid: props.externalUserid,
    scene: current.value.scene,
    originalContent: current.value.reply,
    finalContent: current.value.finalContent,
    edited: current.value.edited,
    sendStatus: status,
    clientResult,
    aiSuggestionId: current.value.id,
  }, usageKey)
  usageKey = ''
}

async function copy(): Promise<void> {
  if (!canUse.value || !current.value?.finalContent) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    await copyTextWithFallback(current.value.finalContent)
    try {
      await recordUsage('COPIED', 'clipboard:ok')
      feedback.value = '已复制并记录；粘贴到会话前请再次确认'
    } catch (error) {
      errorMessage.value = `内容已复制，但使用日志记录失败：${errorText(error)}`
    }
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function send(): Promise<void> {
  if (!canUse.value || props.chatEntry !== 'single_chat_tools' || !current.value?.finalContent) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    const target: WecomSendTarget | undefined = props.externalUserid.trim()
      ? { expectedExternalUserid: props.externalUserid.trim() }
      : undefined
    const result = await sendTextToCurrentChat(current.value.finalContent, target)
    if (result.mismatch) {
      errorMessage.value = '当前企微会话已切换，已阻止误发，正在刷新上下文'
      emit('context-mismatch')
      return
    }
    try {
      await recordUsage(result.status, result.clientResult)
    } catch (error) {
      errorMessage.value = `企微已返回 ${result.status}，但日志记录失败：${errorText(error)}；请先核实会话，不要直接重复发送`
      return
    }
    if (result.status === 'SDK_SUCCESS') feedback.value = '企微已确认发送；这不代表客户已读'
    else if (result.status === 'SDK_FAILED') feedback.value = '企微返回发送失败，失败结果已记录'
    else feedback.value = '发送结果未知，请先到会话核实，不要直接重复发送'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

watch(
  () => props.customerId,
  () => {
    resetCandidate()
    history.value = []
    void load()
  },
)

onMounted(() => { void load() })
</script>

<template>
  <section class="ai-reply-panel" data-testid="ai-reply-panel">
    <header class="ai-reply-heading">
      <div><span class="ai-kicker">AI · 回复草拟</span><h2>先审阅，再发给客户</h2></div>
      <span class="human-badge">人工把关</span>
    </header>
    <p class="ai-boundary">只读 CRM 已授权事实，生成建议但不执行订单、预约、核销或退款，也不会自动发消息。</p>
    <div v-if="!allowed" class="ai-empty">当前账号没有 AI 回复使用权限</div>
    <div v-else-if="loading" class="ai-empty">正在读取可用场景…</div>
    <template v-else>
      <div v-if="errorMessage" class="ai-alert" role="alert">{{ errorMessage }}</div>
      <div class="reply-controls">
        <label for="ai-scene">回复场景</label>
        <select id="ai-scene" v-model="selectedTemplateId" data-testid="ai-template-select" :disabled="busy || proposed" @change="resetCandidate">
          <option v-for="template in templates" :key="template.id" :value="template.id">{{ template.name }}</option>
        </select>
        <label for="ai-instruction">措辞要求 <small>选填</small></label>
        <textarea id="ai-instruction" v-model="instruction" data-testid="ai-instruction" rows="2" maxlength="500" :disabled="busy || proposed" placeholder="例如：语气简洁、先表达理解；不要在这里填写业务事实" />
        <button v-if="canGenerate" class="generate-action" type="button" data-testid="ai-generate" :disabled="busy || !selectedTemplateId || proposed" @click="generate">{{ busy && !current ? '正在生成…' : current ? '重新生成候选' : '生成回复候选' }}</button>
        <div v-if="!templates.length" class="ai-empty">暂无允许 AI 使用的话术场景，请由管理员在话术库中开启。</div>
      </div>

      <article v-if="current" class="suggestion-sheet" :data-status="current.status">
        <div class="suggestion-meta"><span>{{ statusLabel }}</span><span v-if="current.provider === 'mock'" class="mock-label">Mock 演示候选</span><span v-else>{{ current.model }}</span></div>
        <div
          class="chat-context-badge"
          :class="{ included: hasChatContext(current) }"
          data-testid="ai-chat-context-badge"
        >
          {{ chatContextBadgeText(current) }}
        </div>
        <p class="suggestion-reason">{{ current.reason }}</p>
        <label for="ai-reply-draft">回复正文</label>
        <textarea id="ai-reply-draft" v-model="draft" data-testid="ai-reply-draft" rows="6" maxlength="4000" :readonly="!proposed || busy" placeholder="候选正文为空" />
        <p v-if="proposed" class="edit-note">可直接修改；采纳后文本将锁定并关联本次人工决策。</p>
        <p v-else-if="accepted" class="edit-note">已由你人工采纳{{ current.edited ? '并编辑' : '' }}，原始候选仍保留。</p>
        <div v-if="proposed" class="decision-actions">
          <button class="accept-action" type="button" data-testid="ai-accept" :disabled="busy || !draft.trim()" @click="accept">采纳此回复</button>
          <button class="reject-action" type="button" data-testid="ai-show-reject" :disabled="busy" @click="rejecting = !rejecting">驳回</button>
        </div>
        <div v-if="proposed && rejecting" class="reject-form">
          <label for="ai-reject-reason">驳回原因</label>
          <textarea id="ai-reject-reason" v-model="rejectionReason" data-testid="ai-reject-reason" rows="2" maxlength="500" placeholder="请说明不合适的原因" />
          <button type="button" data-testid="ai-confirm-reject" :disabled="busy || !rejectionReason.trim()" @click="reject">确认驳回</button>
        </div>
        <div v-if="accepted" class="use-actions">
          <button class="copy-action" type="button" data-testid="ai-copy" :disabled="!canUse" @click="copy">复制回复</button>
          <button v-if="chatEntry === 'single_chat_tools'" class="send-action" type="button" data-testid="ai-send" :disabled="!canUse" @click="send">发送到当前会话</button>
        </div>
        <p v-if="accepted && chatEntry !== 'single_chat_tools'" class="edit-note">当前为客户资料页，仅可复制；请从客户单聊进入后发送。</p>
      </article>

      <div v-if="feedback" class="ai-feedback" role="status">{{ feedback }}</div>
      <section v-if="history.length" class="reply-history" aria-label="最近生成记录">
        <div class="history-heading"><h3>最近候选</h3><span>仅本人可见</span></div>
        <button v-for="item in history" :key="item.id" type="button" :disabled="busy" :class="{ active: current?.id === item.id }" :data-testid="`ai-history-${item.id}`" @click="selectSuggestion(item)">
          <span class="history-title">
            <span>{{ templates.find((template) => template.id === item.templateId)?.name ?? item.scene }}</span>
            <em class="history-context" :class="{ included: hasChatContext(item) }">{{ historyContextLabel(item) }}</em>
          </span>
          <small>{{ ({ PROPOSED: '待处理', ACCEPTED: '已采纳', REJECTED: '已驳回' } as Record<string, string>)[item.status] }}</small>
        </button>
      </section>
    </template>
  </section>
</template>

<style scoped>
.ai-reply-panel { color: #29483d; container-type: inline-size; }
.ai-reply-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.ai-kicker { display: block; margin-bottom: 6px; color: #748d80; font-size: 10px; font-weight: 650; letter-spacing: 1.5px; }
.ai-reply-heading h2 { margin: 0; color: #284c3e; font-size: 20px; font-weight: 680; letter-spacing: -.4px; line-height: 1.35; }
.human-badge { flex: 0 0 auto; margin-top: 4px; padding: 4px 7px; border: 1px solid #bfd1c6; border-radius: 5px; color: #46705e; font-size: 10px; }
.ai-boundary { margin: 12px 0 20px; color: #758d81; font-size: 11px; line-height: 1.8; }
.reply-controls { display: grid; grid-template-columns: 1fr; gap: 8px; }
.reply-controls label, .suggestion-sheet > label, .reject-form label { margin-top: 5px; color: #506f60; font-size: 11px; font-weight: 650; }
.reply-controls label small { margin-left: 5px; color: #8da095; font-weight: 400; }
.reply-controls select, .reply-controls textarea, .suggestion-sheet textarea { box-sizing: border-box; width: 100%; border: 1px solid #d5e2da; border-radius: 8px; outline: none; color: #2d5141; background: #fcfefc; font: inherit; font-size: 12px; line-height: 1.75; }
.reply-controls select { min-height: 40px; padding: 8px 10px; }
.reply-controls textarea { min-height: 62px; padding: 9px 11px; resize: vertical; }
.reply-controls select:focus, .reply-controls textarea:focus, .suggestion-sheet textarea:focus { border-color: #78a18d; box-shadow: 0 0 0 3px rgb(47 108 83 / 8%); }
.reply-controls textarea::placeholder { color: #9baaa1; }
.generate-action, .decision-actions button, .reject-form button, .use-actions button { min-height: 40px; padding: 9px 14px; border-radius: 8px; font: inherit; font-size: 12px; font-weight: 650; cursor: pointer; }
.generate-action { margin-top: 5px; border: 1px solid #276c59; color: #f1f8f4; background: #276c59; }
button:disabled { opacity: .45; cursor: not-allowed; }
.suggestion-sheet { margin-top: 24px; padding: 17px 0 0; border-top: 1px solid #dce7df; }
.suggestion-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #6d897b; font-size: 10px; }
.suggestion-meta > span:first-child { color: #34634f; font-weight: 700; }
.mock-label { padding: 3px 6px; border-radius: 4px; color: #866c43; background: #f5efe3; }
.chat-context-badge { display: inline-flex; align-items: center; margin-top: 9px; padding: 4px 8px; border: 1px solid #d8e2dc; border-radius: 6px; color: #5b7266; background: #f1f5f3; font-size: 10px; font-weight: 600; }
.chat-context-badge.included { border-color: #a9d0bd; color: #24644b; background: #e7f5ee; }
.suggestion-reason { margin: 10px 0 16px; color: #7b9185; font-size: 10px; line-height: 1.7; }
.suggestion-sheet textarea { min-height: 148px; margin-top: 7px; padding: 12px; resize: vertical; }
.suggestion-sheet textarea[readonly] { color: #526c5f; background: #f3f7f4; }
.edit-note { margin: 8px 0 0; color: #869b90; font-size: 10px; line-height: 1.7; }
.decision-actions, .use-actions { display: flex; gap: 9px; margin-top: 15px; }
.accept-action, .send-action { flex: 1; border: 1px solid #276c59; color: #f1f8f4; background: #276c59; }
.reject-action { border: 1px solid #d7dfda; color: #7b635b; background: transparent; }
.reject-form { display: grid; gap: 8px; margin-top: 14px; padding: 13px; border-radius: 8px; background: #f8f3ef; }
.reject-form textarea { min-height: 58px; background: #fdfbf9; }
.reject-form button { border: 1px solid #c9aa9c; color: #8c5542; background: transparent; }
.copy-action { border: 1px solid #b6ccbf; color: #396b53; background: #f5f9f6; }
.ai-alert, .ai-feedback { margin-bottom: 13px; padding: 10px 12px; border-radius: 7px; font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.ai-alert { color: #97523f; background: #fff0e9; }
.ai-feedback { margin-top: 14px; margin-bottom: 0; color: #3b7056; background: #edf5ef; }
.ai-empty { padding: 26px 10px; color: #82998d; font-size: 11px; line-height: 1.8; text-align: center; }
.reply-history { margin-top: 25px; padding-top: 14px; border-top: 1px solid #e0e8e3; }
.history-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 7px; }
.history-heading h3 { margin: 0; color: #506c5d; font-size: 11px; }
.history-heading span { color: #92a398; font-size: 9px; }
.reply-history > button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 3px; border: 0; border-bottom: 1px solid #edf2ee; color: #577566; background: transparent; font: inherit; font-size: 11px; text-align: left; cursor: pointer; }
.reply-history > button.active { color: #24634c; font-weight: 700; }
.history-title { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; min-width: 0; }
.history-context { padding: 1px 5px; border-radius: 4px; color: #6c8076; background: #eef3f0; font-style: normal; font-size: 9px; font-weight: 500; }
.history-context.included { color: #28674f; background: #e6f4ec; }
.reply-history small { flex: 0 0 auto; color: #8c9e94; font-size: 10px; }
@container (min-width: 430px) { .reply-controls { grid-template-columns: 110px 1fr; align-items: start; } .reply-controls label { padding-top: 10px; } .reply-controls label small { display: block; margin: 2px 0 0; } .generate-action, .reply-controls > .ai-empty { grid-column: 2; } }
</style>
