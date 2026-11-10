<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getWecomMessageContent, listWecomContacts, listWecomMessages } from '../api/wecom'
import type { WecomContact, WecomMessageView } from '../api/wecom'
import { confirmAiJob, createAiJob, getAiJob, listAiJobs } from '../api/ai'
import type { AiJob } from '../api/ai'
import { getCustomer } from '../api/customer'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import CustomerAvatar from './customer/CustomerAvatar.vue'
import { formatDateTime } from './customer/shared'
import { useReferenceNames } from './references'

interface Conversation { id: string; customerId: number | null; externalUserid: string; staffUserId: number; messages: WecomMessageView[] }
const route = useRoute()
const auth = useAuthStore()
const { projectCodeLabel } = useReferenceNames()
const canRead = computed(() => auth.user?.userType !== 'INSTITUTION' && auth.hasPermission('wecom:read'))
const canAnalyze = computed(() => auth.hasPermission('ai:write'))
const canConfirm = computed(() => auth.hasPermission('ai:confirm') && auth.hasPermission('task:write'))
const conversations = ref<Conversation[]>([]), contacts = ref<WecomContact[]>([])
const selectedId = ref(''), messages = ref<{ id: number; senderType: string; sentAt: string; content: string }[]>([])
const customerNames = ref<Record<number, string>>({})
const loading = ref(false), contentLoading = ref(false), errorMessage = ref(''), contentError = ref('')
const activeJob = ref<AiJob | null>(null), aiLoading = ref(false), aiError = ref('')
const confirmVisible = ref(false), confirming = ref(false)
const confirmForm = reactive({ title: '', description: '', dueAt: '', reason: '' })
let pollTimer: ReturnType<typeof setTimeout> | undefined
const selected = computed(() => conversations.value.find((item) => item.id === selectedId.value) || null)
const hasInsight = computed(() => activeJob.value?.status === 'PROPOSED' && activeJob.value.insight)
function conversationName(item: Conversation) { return item.customerId ? customerNames.value[item.customerId] || `客户 #${item.customerId}` : `待匹配 · ${item.externalUserid}` }
function time(value: string) { return value.slice(11, 16) }
function errorText(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
function statusLabel(status: string) { return ({ QUEUED: '排队等待', RUNNING: '分析中', RETRY: '等待重试', PROPOSED: '待人工确认', FAILED: '分析失败', CONFIRMED: '已生成待办', REJECTED: '已驳回' } as Record<string, string>)[status] || '未知任务状态' }
function intentLabel(type: string) { return ({ APPOINTMENT_INTENT: '预约意向', PRICE_INQUIRY: '价格咨询', REFUND_INTENT: '退款意向', COMPLAINT: '投诉/不满', REPURCHASE_INTENT: '复购意向', SERVICE_EXPERIENCE: '服务后体验', SERVICE_COMPLETION_CLAIM: '已完成服务陈述（待核实）', GENERAL: '未明确意图' } as Record<string, string>)[type] || '未知意图' }
function riskLabel(value: string) { return ({ COMPLAINT: '投诉风险', REFUND_REQUEST: '退款诉求', SERVICE_CLAIM: '服务完成陈述', URGENT: '需优先复核' } as Record<string, string>)[value] || '未知风险' }
function actionLabel(value: string) { return ({ VERIFY_BALANCE: '核实剩余权益', ASK_PREFERRED_INSTITUTION: '确认意向机构', CREATE_FOLLOWUP_DRAFT: '生成回访草稿', VERIFY_SERVICE_RECORD: '核实服务记录', OFFER_HUMAN_SUPPORT: '转人工支持', VERIFY_PAYMENT_RECORD: '核实支付记录' } as Record<string, string>)[value] || '未知操作' }
function stopPolling() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
async function loadLatest(conversationId: string) {
  if (!auth.hasPermission('ai:read')) return
  try {
    const result = await listAiJobs({ conversationId, page: 1, size: 1 })
    activeJob.value = result.items[0] || null
    aiError.value = ''
  } catch (error) { activeJob.value = null; aiError.value = errorText(error) }
}
async function selectConversation(id: string) {
  stopPolling()
  selectedId.value = id
  activeJob.value = null
  aiError.value = ''
  messages.value = []
  contentError.value = ''
  const conversation = conversations.value.find((item) => item.id === id)
  if (!conversation) return
  contentLoading.value = true
  try {
    const results = await Promise.allSettled(conversation.messages.map((message) => getWecomMessageContent(message.id)))
    messages.value = results.flatMap((result, index) => result.status === 'fulfilled' ? [{ id: conversation.messages[index].id, senderType: conversation.messages[index].senderType, sentAt: conversation.messages[index].sentAt, content: result.value.content }] : [])
    if (results.some((result) => result.status === 'rejected')) contentError.value = '部分内容未获授权或暂不可读取，未展示旧全文'
    await loadLatest(id)
  } finally { contentLoading.value = false }
}
async function analyze() {
  const conversation = selected.value
  if (!conversation?.customerId) { aiError.value = '客户身份尚未人工匹配，不能生成 AI 分析'; return }
  const ids = conversation.messages.filter((item) => item.messageType === 'TEXT').map((item) => item.id)
  if (!ids.length) { aiError.value = '当前会话没有可分析的授权文本'; return }
  stopPolling()
  aiLoading.value = true
  aiError.value = ''
  try {
    activeJob.value = await createAiJob(ids, `ai-job:${crypto.randomUUID()}`)
    poll(activeJob.value.id)
  } catch (error) { aiError.value = errorText(error) } finally { aiLoading.value = false }
}
function poll(id: number) {
  stopPolling()
  pollTimer = setTimeout(async () => {
    try {
      const job = await getAiJob(id)
      activeJob.value = job
      if (['QUEUED', 'RUNNING', 'RETRY'].includes(job.status)) poll(id)
    } catch (error) { aiError.value = errorText(error) }
  }, 1000)
}
function openConfirm() {
  const draft = activeJob.value?.insight?.followupDraft
  confirmForm.title = draft?.title || '人工核实客户沟通意向'
  confirmForm.description = draft?.description || activeJob.value?.insight?.reasoningSummary || ''
  confirmForm.dueAt = draft?.due_at || ''
  confirmForm.reason = '已核对原始会话证据'
  confirmVisible.value = true
}
async function submitConfirm() {
  if (!activeJob.value || !confirmForm.title.trim()) { ElMessage.warning('请填写待办标题'); return }
  confirming.value = true
  try {
    activeJob.value = await confirmAiJob(activeJob.value.id, { version: activeJob.value.version, decision: 'CONFIRM', title: confirmForm.title.trim(), description: confirmForm.description.trim(), dueAt: confirmForm.dueAt || undefined, reason: confirmForm.reason.trim() }, `ai-confirm:${crypto.randomUUID()}`)
    confirmVisible.value = false
    ElMessage.success(`已生成普通回访待办 #${activeJob.value.taskId}`)
  } catch (error) { ElMessage.error(errorText(error)) } finally { confirming.value = false }
}
async function reject() {
  if (!activeJob.value) return
  try {
    const result = await ElMessageBox.prompt('请填写驳回原因，原始 AI 建议不会修改业务事实。', '驳回 AI 建议', { inputType: 'textarea', inputValidator: (value) => !!value.trim() || '请填写驳回原因' })
    activeJob.value = await confirmAiJob(activeJob.value.id, { version: activeJob.value.version, decision: 'REJECT', reason: result.value.trim() }, `ai-reject:${crypto.randomUUID()}`)
    ElMessage.success('AI 建议已驳回')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorText(error)) }
}
async function copyDraft() {
  const text = activeJob.value?.insight?.replyDraft
  if (!text) return
  try { await navigator.clipboard.writeText(text); ElMessage.success('话术草稿已复制，不会自动发送') } catch { ElMessage.error('复制失败，请手动选择文本') }
}
onBeforeUnmount(stopPolling)
onMounted(async () => {
  if (!canRead.value) return
  loading.value = true
  try {
    const [messagePage, contactRows] = await Promise.all([listWecomMessages({ page: 1, size: 100 }), listWecomContacts()])
    contacts.value = contactRows
    const groups = new Map<string, Conversation>()
    messagePage.items.forEach((message) => {
      const key = message.conversationId || message.externalUserid
      const group = groups.get(key) || { id: key, customerId: message.customerId, externalUserid: message.externalUserid, staffUserId: message.staffUserId, messages: [] }
      group.customerId = message.customerId
      group.messages.push(message)
      groups.set(key, group)
    })
    conversations.value = [...groups.values()].map((group) => ({ ...group, messages: group.messages.sort((a, b) => a.archiveSeq - b.archiveSeq) }))
      .sort((a, b) => (b.messages.at(-1)?.archiveSeq || 0) - (a.messages.at(-1)?.archiveSeq || 0))
    const ids = [...new Set(messagePage.items.flatMap((item) => item.customerId ? [item.customerId] : []))]
    const names = await Promise.allSettled(ids.map((id) => getCustomer(id)))
    names.forEach((result, index) => { if (result.status === 'fulfilled') customerNames.value[ids[index]] = result.value.displayName || result.value.nickname || `客户 #${ids[index]}` })
    if (conversations.value.length) {
      const requested = typeof route?.query?.conversation === 'string' ? route.query.conversation : ''
      await selectConversation(conversations.value.some((item) => item.id === requested) ? requested : conversations.value[0].id)
    }
  } catch (error) { errorMessage.value = errorText(error) }
  finally { loading.value = false }
})
</script>

<template>
  <div class="proto-page wecom-page">
    <PageHeader title="企业微信 · AI 管家" description="官方客户联系与会话归档；AI 只提供证据建议，不能自动执行交易或发送消息">
      <template #actions><span class="proto-pill warning">模拟 · 未连接真实企业微信</span><RouterLink to="/integrations/wecom/archive" class="el-button">接入状态 / 归档管理</RouterLink></template>
    </PageHeader>
    <div v-if="canRead" class="proto-callout">Q10 未确认，官方 SDK 保持禁用，不接个人微信。AI 识别不等于自动执行，全文仅在成员、同意与客户数据范围均满足时读取。</div>
    <el-alert v-if="!canRead" type="error" :title="auth.user?.userType === 'INSTITUTION' ? '机构用户无企业微信归档访问入口' : '缺少 wecom:read 权限'" :closable="false" />
    <el-alert v-else-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
    <PageCard v-if="canRead">
      <div v-loading="loading" class="chat-layout">
        <aside class="chat-contacts"><div v-for="item in conversations" :key="item.id" class="chat-contact" :class="{active:selectedId===item.id}" @click="selectConversation(item.id)"><CustomerAvatar :name="conversationName(item)" :size="36" /><div><strong>{{ conversationName(item) }}</strong><p>管家 #{{ item.staffUserId }} · {{ item.messages.length }} 条归档</p><small>选择后按权限读取消息</small></div></div><div v-if="!conversations.length && !loading" class="proto-empty">暂无授权会话</div></aside>
        <section class="chat-panel"><header class="chat-head"><strong>{{ selected ? conversationName(selected) : '选择会话' }}</strong><span v-if="selected" class="proto-muted">专属管家 #{{ selected.staffUserId }}</span></header><div v-loading="contentLoading" class="chat-messages"><el-alert v-if="contentError" type="warning" :title="contentError" :closable="false" /><div v-for="message in messages" :key="message.id" class="bubble-row" :class="{right:message.senderType==='EMPLOYEE'}"><div class="bubble">{{ message.content }}<small>{{ message.senderType === 'EMPLOYEE' ? '管家' : '客户' }} · {{ time(message.sentAt) }}</small></div></div><div v-if="!messages.length && !contentLoading" class="proto-empty">选择会话查看授权内容</div></div><div class="chat-input"><el-input disabled placeholder="正式系统不直接发送企微消息" /><el-button disabled>发送</el-button></div></section>
        <aside class="ai-panel">
          <h3>AI 智能洞察</h3>
          <p class="proto-note">只依据授权证据生成待核实建议；置信度不是校准概率，人工确认前不改变业务事实。</p>
          <el-alert v-if="aiError" type="warning" :title="aiError" :closable="false" />
          <div v-if="activeJob" class="ai-suggestion"><strong>任务状态</strong><span class="proto-pill warning">{{ statusLabel(activeJob.status) }}</span><div v-if="activeJob.provider" class="proto-subcell">{{ activeJob.provider }} · {{ activeJob.modelName }}</div><div v-if="activeJob.errorMasked">{{ activeJob.errorMasked }}</div></div>
          <template v-if="hasInsight && activeJob?.insight">
            <div class="ai-suggestion"><strong>客户意图</strong>{{ intentLabel(activeJob.insight.insightType) }}<div class="proto-subcell">模型自报置信度 {{ Math.round(activeJob.insight.confidence * 100) }}%</div></div>
            <div class="ai-suggestion"><strong>涉及项目 / 机构</strong>{{ activeJob.insight.projectCodes.map(projectCodeLabel).join('、') || '未明确' }}<div class="proto-subcell">{{ activeJob.insight.mentionedInstitution || '机构待确认' }} · {{ activeJob.insight.mentionedTime || '时间待确认' }}</div></div>
            <div class="ai-suggestion"><strong>证据消息</strong><span v-for="id in activeJob.insight.evidenceMessageIds" :key="id" class="proto-pill info">消息 #{{ id }}</span><div v-if="activeJob.insight.riskFlags.length" class="proto-subcell">AI 风险建议：{{ activeJob.insight.riskFlags.map(riskLabel).join('、') }}（未核实）</div></div>
            <div class="ai-suggestion"><strong>沟通摘要 / 建议下一步</strong>{{ activeJob.insight.reasoningSummary }}<div class="proto-subcell">{{ activeJob.insight.proposedActions.map(actionLabel).join(' · ') }}</div></div>
            <div class="ai-suggestion"><strong>建议回复草稿</strong>{{ activeJob.insight.replyDraft || '暂无话术草稿' }}</div>
          </template>
          <div v-else-if="!activeJob" class="ai-suggestion"><strong>证据状态</strong>{{ selected?.customerId ? '尚未生成本次会话的 AI 分析' : '客户身份待人工匹配' }}</div>
          <div v-if="activeJob?.taskId" class="ai-suggestion"><strong>人工处理结果</strong>已生成普通回访待办 #{{ activeJob.taskId }}<RouterLink to="/tasks" class="el-button is-text">查看待办 →</RouterLink></div>
          <el-button v-if="canAnalyze" type="primary" :loading="aiLoading" :disabled="!selected?.customerId" data-testid="ai-analyze" @click="analyze">{{ activeJob ? '重新生成 AI 分析' : '生成 AI 分析' }}</el-button>
          <template v-if="hasInsight">
            <el-button v-if="canConfirm" type="primary" data-testid="ai-confirm-open" @click="openConfirm">确认并生成待办</el-button>
            <el-button v-if="canConfirm" @click="reject">驳回建议</el-button>
            <el-button :disabled="!activeJob?.insight?.replyDraft" @click="copyDraft">复制话术</el-button>
          </template>
          <el-dialog v-model="confirmVisible" title="人工确认并生成普通回访待办" width="min(580px,94vw)">
            <el-alert type="warning" :closable="false" title="这里只创建回访待办，不会确认支付、退款、预约或核销。" />
            <el-form label-position="top" style="margin-top:16px"><el-form-item label="待办标题" required><el-input v-model="confirmForm.title" maxlength="255" data-testid="ai-confirm-title" /></el-form-item><el-form-item label="待办说明"><el-input v-model="confirmForm.description" type="textarea" :rows="4" maxlength="2000" /></el-form-item><el-form-item label="截止时间"><el-date-picker v-model="confirmForm.dueAt" type="datetime" value-format="YYYY-MM-DD[T]HH:mm:ssZ" style="width:100%" /></el-form-item><el-form-item label="人工核实说明"><el-input v-model="confirmForm.reason" type="textarea" :rows="2" maxlength="1000" /></el-form-item></el-form>
            <template #footer><el-button @click="confirmVisible=false">取消</el-button><el-button type="primary" :loading="confirming" data-testid="ai-confirm-submit" @click="submitConfirm">确认生成待办</el-button></template>
          </el-dialog>
        </aside>
      </div>
    </PageCard>
    <footer class="proto-footer">消息只读 · 不模拟客户回复 · 不采集个人微信 · {{ formatDateTime(new Date().toISOString()) }}</footer>
  </div>
</template>

<style scoped>
.chat-layout{display:grid;grid-template-columns:250px minmax(0,1fr) 320px;min-height:620px}.chat-contacts{border-right:1px solid #e8eff2}.chat-contact{display:flex;gap:10px;align-items:center;padding:16px 15px;border-bottom:1px solid #f1f4f5;cursor:pointer}.chat-contact.active,.chat-contact:hover{background:#eff9f7}.chat-contact strong{display:block;font-size:12px}.chat-contact p,.chat-contact small{margin:4px 0 0;color:#93a5af;font-size:10px}.chat-panel{display:flex;min-width:0;flex-direction:column}.chat-head{display:flex;justify-content:space-between;padding:20px;border-bottom:1px solid #edf2f4}.chat-messages{flex:1;min-height:450px;padding:22px;background:#fbfcfd}.bubble-row{display:flex;margin:14px 0}.bubble-row.right{justify-content:flex-end}.bubble{max-width:86%;padding:13px 15px;border:1px solid #e6eef1;border-radius:12px 12px 12px 3px;color:#42606e;background:#fff;font-size:12px;line-height:1.85;box-shadow:0 4px 16px rgb(26 73 83 / 4%)}.bubble-row.right .bubble{border-color:#c5ead9;border-radius:12px 12px 3px 12px;background:#dff5eb}.bubble small{display:block;margin-top:5px;color:#93a5af;font-size:10px}.chat-input{display:flex;gap:10px;padding:16px 20px;border-top:1px solid #edf2f4}.ai-panel{padding:22px 19px;border-left:1px solid #e6eff0;background:#f8fcfb}.ai-panel h3{margin:2px 0 7px;font-size:14px}.ai-suggestion{margin-top:14px;padding:13px;border:1px solid #e4f1ef;border-radius:11px;color:#567887;background:#fff;font-size:11px;line-height:1.7}.ai-suggestion strong{display:block;margin-bottom:6px;color:#237f76}.ai-panel .el-button{width:100%;margin-top:10px}.ai-panel .el-button + .el-button{margin-left:0}
@media(max-width:1300px){.chat-layout{grid-template-columns:200px minmax(0,1fr)}.ai-panel{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,1fr);gap:12px;border-top:1px solid #e6eff0;border-left:0}.ai-panel h3,.ai-panel>p{grid-column:1/-1}.ai-suggestion{margin-top:0}}@media(max-width:760px){.chat-layout{grid-template-columns:1fr}.chat-contacts{display:flex;overflow-x:auto}.chat-contact{min-width:170px}.ai-panel{grid-template-columns:1fr}}
</style>
