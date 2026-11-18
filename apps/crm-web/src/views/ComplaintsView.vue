<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  assignComplaint,
  closeComplaint,
  confirmComplaintHit,
  dismissComplaint,
  dismissComplaintHit,
  getComplaint,
  getComplaintHit,
  listComplaintHits,
  listComplaints,
  resolveComplaint,
} from '../api/complaint'
import type {
  ComplaintCase,
  ComplaintCaseDetail,
  ComplaintHit,
  ComplaintHitDetail,
} from '../api/complaint'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

type Tab = 'HITS' | 'CASES'
type Action = 'CONFIRM_HIT' | 'DISMISS_HIT' | 'ASSIGN' | 'RESOLVE' | 'CLOSE' | 'DISMISS'

const auth = useAuthStore()
const canRead = computed(() => auth.hasPermission('complaint:read'))
const canWrite = computed(() => auth.hasPermission('complaint:write'))
const tab = ref<Tab>('HITS')
const hits = ref<ComplaintHit[]>([])
const cases = ref<ComplaintCase[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({ status: '', keyword: '', page: 1, size: 20 })
const hitDetail = ref<ComplaintHitDetail | null>(null)
const caseDetail = ref<ComplaintCaseDetail | null>(null)
const selectedEmpathyScriptId = ref<number | null>(null)
const empathyScripts = computed(() => {
  const current = caseDetail.value
  if (!current) return []
  if (current.empathyScripts) return current.empathyScripts
  if (!current.empathyScript) return []
  return [{
    id: 0,
    title: '默认共情话术',
    script: current.empathyScript,
    status: 'ACTIVE' as const,
    createdBy: null,
    createdAt: '',
    updatedAt: '',
    version: 0,
  }]
})
const selectedEmpathyScript = computed(() => empathyScripts.value.find((item) => item.id === selectedEmpathyScriptId.value) ?? empathyScripts.value[0] ?? null)
const detailLoading = ref(false)
const action = ref<Action | null>(null)
const actionTargetId = ref<number | null>(null)
const actionVersion = ref(0)
const actionReason = ref('')
const actionSubmitting = ref(false)
const actionError = ref('')
const copied = ref(false)
let actionKey = ''
let actionPayload = ''

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function newKey(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `complaint-${Date.now()}-${Math.random()}`
}

async function load(): Promise<void> {
  if (!canRead.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = tab.value === 'HITS' ? await listComplaintHits(query) : await listComplaints(query)
    if (tab.value === 'HITS') hits.value = result.items as ComplaintHit[]
    else cases.value = result.items as ComplaintCase[]
    total.value = result.total
    query.page = result.page
  } catch (error) {
    errorMessage.value = errorText(error)
    if (tab.value === 'HITS') hits.value = []
    else cases.value = []
  } finally {
    loading.value = false
  }
}

function switchTab(next: Tab): void {
  tab.value = next
  query.status = ''
  query.page = 1
  action.value = null
  void load()
}

async function showHitDetail(item: ComplaintHit): Promise<void> {
  detailLoading.value = true
  errorMessage.value = ''
  try {
    hitDetail.value = await getComplaintHit(item.id)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    detailLoading.value = false
  }
}

async function showCaseDetail(item: ComplaintCase): Promise<void> {
  detailLoading.value = true
  errorMessage.value = ''
  copied.value = false
  selectedEmpathyScriptId.value = null
  try {
    caseDetail.value = await getComplaint(item.id)
    selectedEmpathyScriptId.value = empathyScripts.value[0]?.id ?? null
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    detailLoading.value = false
  }
}

function openHitAction(item: ComplaintHit, next: 'CONFIRM_HIT' | 'DISMISS_HIT'): void {
  action.value = next
  actionTargetId.value = item.id
  actionVersion.value = item.version
  actionReason.value = ''
  actionError.value = ''
  actionKey = newKey()
  actionPayload = ''
}

function openCaseAction(item: ComplaintCase, next: Exclude<Action, 'CONFIRM_HIT' | 'DISMISS_HIT'>): void {
  action.value = next
  actionTargetId.value = item.id
  actionVersion.value = item.version
  actionReason.value = ''
  actionError.value = ''
  actionKey = newKey()
  actionPayload = ''
}

const actionTitle = computed(() => ({
  CONFIRM_HIT: '人工确认为正式客诉', DISMISS_HIT: '驳回关键词命中', ASSIGN: '认领正式客诉',
  RESOLVE: '填写处理结论', CLOSE: '关闭正式客诉', DISMISS: '驳回正式客诉',
} as Record<Action, string>)[action.value ?? 'CONFIRM_HIT'])

async function submitAction(): Promise<void> {
  const id = actionTargetId.value
  const current = action.value
  const reason = actionReason.value.trim()
  if (!id || !current || actionSubmitting.value) return
  if (!reason) {
    actionError.value = '请填写人工核查或处理理由'
    return
  }
  const payload = `${id}:${actionVersion.value}:${reason}`
  if (payload !== actionPayload) {
    actionKey = newKey()
    actionPayload = payload
  }
  actionSubmitting.value = true
  actionError.value = ''
  try {
    if (current === 'CONFIRM_HIT') await confirmComplaintHit(id, { version: actionVersion.value, reason }, actionKey)
    else if (current === 'DISMISS_HIT') await dismissComplaintHit(id, { version: actionVersion.value, reason }, actionKey)
    else if (current === 'ASSIGN') await assignComplaint(id, { version: actionVersion.value, reason }, actionKey)
    else if (current === 'RESOLVE') await resolveComplaint(id, { version: actionVersion.value, resolution: reason }, actionKey)
    else if (current === 'CLOSE') await closeComplaint(id, { version: actionVersion.value, reason }, actionKey)
    else await dismissComplaint(id, { version: actionVersion.value, reason }, actionKey)
    action.value = null
    actionTargetId.value = null
    await load()
  } catch (error) {
    actionError.value = errorText(error)
  } finally {
    actionSubmitting.value = false
  }
}

async function copyEmpathy(): Promise<void> {
  const script = selectedEmpathyScript.value?.script.trim()
  if (!script) return
  try {
    await navigator.clipboard.writeText(script)
    copied.value = true
  } catch {
    errorMessage.value = '复制失败，请手动选择话术文本复制；系统不会自动发送'
  }
}

function statusLabel(status: string): string {
  return ({
    OPEN: '待核查 / 待认领', CONVERTED: '已转正式单', ASSIGNED: '处理中', RESOLVED: '已处理',
    CLOSED: '已关闭', DISMISSED: '已驳回',
  } as Record<string, string>)[status] ?? status
}

function routingErrorLabel(code: string | null): string {
  return code === 'NO_RECIPIENT' ? '无接收人，未发送通知' : code === 'AMBIGUOUS_TEAM_MANAGER_LEAD' ? '本组主管不唯一，未广播' : (code ?? '路由异常')
}

onMounted(() => { if (canRead.value) void load() })
</script>

<template>
  <div class="proto-page complaints-page">
    <PageHeader title="客诉关键词与闭环" description="关键词命中只生成待核查记录；正式确认、认领、处理、关闭与驳回均由人工执行并追加历史" />

    <PageCard v-if="!canRead">
      <div class="complaint-empty" data-testid="complaint-permission-empty">
        <strong>没有客诉查看权限</strong><span>页面不会请求命中证据或正式单历史。</span>
      </div>
    </PageCard>

    <template v-else>
      <div class="complaint-tabs" role="tablist" aria-label="客诉记录类型">
        <button type="button" role="tab" :aria-selected="tab === 'HITS'" data-testid="hits-tab" @click="switchTab('HITS')">关键词命中</button>
        <button type="button" role="tab" :aria-selected="tab === 'CASES'" data-testid="cases-tab" @click="switchTab('CASES')">正式客诉单</button>
      </div>
      <PageCard>
        <div class="complaint-notice">
          <strong>{{ tab === 'HITS' ? '命中不是正式客诉' : '正式单状态不可逆' }}</strong>
          <span>{{ tab === 'HITS' ? '先人工核查证据，再决定确认或驳回；无接收人只进入异常。' : 'OPEN → ASSIGNED → RESOLVED → CLOSED；非终态可由有权限人员驳回。' }}</span>
        </div>
        <div class="complaint-filters">
          <input v-model="query.keyword" aria-label="搜索客诉" placeholder="客户编号 / 关键词 / 脱敏证据" @keyup.enter="query.page = 1; load()">
          <select v-model="query.status" aria-label="状态筛选">
            <option value="">全部状态</option>
            <template v-if="tab === 'HITS'"><option value="OPEN">待核查</option><option value="CONVERTED">已转正式单</option><option value="DISMISSED">已驳回</option></template>
            <template v-else><option value="OPEN">待认领</option><option value="ASSIGNED">处理中</option><option value="RESOLVED">已处理</option><option value="CLOSED">已关闭</option><option value="DISMISSED">已驳回</option></template>
          </select>
          <button type="button" :disabled="loading" @click="query.page = 1; load()">查询</button>
        </div>
        <div v-if="errorMessage" role="alert" class="complaint-alert">{{ errorMessage }}<button type="button" @click="load">重试</button></div>

        <div class="complaint-table-wrap">
          <table v-if="tab === 'HITS'">
            <thead><tr><th>来源 / 关键词</th><th>脱敏证据</th><th>主管快照</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="item in hits" :key="item.id">
                <td><strong>{{ item.sourceType }} #{{ item.sourceId }}</strong><small>{{ item.keywordSnapshot }} · 客户 #{{ item.customerId }}</small></td>
                <td class="complaint-evidence">{{ item.evidenceMasked }}</td>
                <td>
                  <span v-if="item.recipientUserIdSnapshot">用户 #{{ item.recipientUserIdSnapshot }}<small>{{ item.recipientSourceSnapshot }}</small></span>
                  <span v-else class="complaint-exception" :data-testid="`routing-exception-${item.id}`">{{ routingErrorLabel(item.routingError) }}</span>
                </td>
                <td><span class="complaint-status">{{ statusLabel(item.status) }}</span></td>
                <td class="complaint-actions">
                  <button type="button" :data-testid="`hit-detail-${item.id}`" @click="showHitDetail(item)">历史</button>
                  <template v-if="canWrite && item.status === 'OPEN'">
                    <button type="button" :data-testid="`confirm-hit-${item.id}`" @click="openHitAction(item, 'CONFIRM_HIT')">确认正式客诉</button>
                    <button type="button" :data-testid="`dismiss-hit-${item.id}`" @click="openHitAction(item, 'DISMISS_HIT')">驳回</button>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
          <table v-else>
            <thead><tr><th>正式单 / 客户</th><th>处理结论</th><th>认领人</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="item in cases" :key="item.id">
                <td><strong>{{ item.complaintCaseNo }}</strong><small>客户 #{{ item.customerId }} · 命中 #{{ item.complaintHitId }}</small></td>
                <td class="complaint-evidence">{{ item.resolutionMasked || item.dismissReasonMasked || '尚未填写处理结论' }}</td>
                <td>{{ item.assigneeUserId ? `用户 #${item.assigneeUserId}` : '未认领' }}</td>
                <td><span class="complaint-status">{{ statusLabel(item.status) }}</span></td>
                <td class="complaint-actions">
                  <button type="button" :data-testid="`case-detail-${item.id}`" @click="showCaseDetail(item)">详情 / 历史</button>
                  <button v-if="canWrite && item.status === 'OPEN'" type="button" @click="openCaseAction(item, 'ASSIGN')">认领</button>
                  <button v-if="canWrite && item.status === 'ASSIGNED'" type="button" @click="openCaseAction(item, 'RESOLVE')">处理完成</button>
                  <button v-if="canWrite && item.status === 'RESOLVED'" type="button" @click="openCaseAction(item, 'CLOSE')">关闭</button>
                  <button v-if="canWrite && !['CLOSED', 'DISMISSED'].includes(item.status)" type="button" @click="openCaseAction(item, 'DISMISS')">驳回</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!loading && (tab === 'HITS' ? hits.length === 0 : cases.length === 0)" class="complaint-empty">当前条件下没有记录</div>
        </div>
        <div class="complaint-pagination"><span>共 {{ total }} 条</span><button type="button" :disabled="query.page <= 1" @click="query.page--; load()">上一页</button><button type="button" :disabled="query.page * query.size >= total" @click="query.page++; load()">下一页</button></div>
      </PageCard>

      <PageCard v-if="action" :title="actionTitle" subtitle="写操作由后端校验数据范围、CSRF、幂等键与 version；失败重试沿用同一逻辑操作">
        <form class="complaint-action-form" data-testid="action-submit" @submit.prevent="submitAction">
          <label for="complaint-action-reason">{{ action === 'RESOLVE' ? '处理结论' : '人工理由' }}</label>
          <textarea id="complaint-action-reason" v-model="actionReason" data-testid="action-reason" rows="4" maxlength="2000" placeholder="填写实际核查依据，不填写医疗诊断或效果承诺" />
          <div v-if="actionError" role="alert" class="complaint-alert">{{ actionError }}</div>
          <div><button type="submit" :disabled="actionSubmitting">{{ actionSubmitting ? '提交中…' : '提交人工处理' }}</button><button type="button" @click="action = null">取消</button></div>
        </form>
      </PageCard>

      <PageCard v-if="hitDetail" title="命中追加历史" subtitle="原始证据与主管快照不可覆盖">
        <button type="button" @click="hitDetail = null">收起</button>
        <ol class="complaint-history"><li v-for="entry in hitDetail.history" :key="entry.id"><strong>{{ entry.action }}</strong><span>{{ entry.fromStatus || '—' }} → {{ entry.toStatus }}</span><p>{{ entry.reasonMasked }}</p></li></ol>
      </PageCard>

      <PageCard v-if="caseDetail" :title="`正式单 ${caseDetail.complaintCase.complaintCaseNo}`" subtitle="共情话术仅供人工复制，不会自动发送，也不会修改订单、退款或权益">
        <button type="button" @click="caseDetail = null">收起</button>
        <div v-if="empathyScripts.length && selectedEmpathyScript" class="complaint-script">
          <div class="script-picker">
            <label for="empathy-script-select">选择共情话术</label>
            <span>共 {{ empathyScripts.length }} 条</span>
            <select id="empathy-script-select" v-model.number="selectedEmpathyScriptId" data-testid="empathy-script-select">
              <option v-for="item in empathyScripts" :key="item.id" :value="item.id">{{ item.title }}</option>
            </select>
          </div>
          <label for="empathy-script">话术内容 <small>{{ selectedEmpathyScript.title }}</small></label>
          <textarea id="empathy-script" :value="selectedEmpathyScript.script" readonly rows="4" />
          <div class="script-copy-row"><button type="button" data-testid="copy-empathy" @click="copyEmpathy">复制所选话术</button><span v-if="copied" role="status">话术已复制，需人工确认后自行发送</span></div>
        </div>
        <div v-else class="complaint-no-script">当前没有启用中的共情话术，可由管理员在配置页添加。</div>
        <ol class="complaint-history"><li v-for="entry in caseDetail.history" :key="entry.id"><strong>{{ entry.action }}</strong><span>{{ entry.fromStatus || '—' }} → {{ entry.toStatus }}</span><p>{{ entry.reasonMasked }}</p></li></ol>
      </PageCard>
      <span v-if="detailLoading" role="status">正在读取详情与历史…</span>
    </template>
  </div>
</template>

<style scoped>
.complaints-page { min-width: 0; color: #29483e; }
.complaint-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.complaint-tabs button, .complaint-actions button, .complaint-pagination button { border: 1px solid #d8e5df; border-radius: 8px; background: #fff; color: #35584b; padding: 8px 12px; cursor: pointer; }
.complaint-tabs button[aria-selected='true'] { border-color: #8eb4a3; background: #eaf4ef; font-weight: 700; }
.complaint-notice { display: flex; gap: 12px; align-items: center; padding: 14px 18px; border-radius: 10px; background: #f0f7f3; color: #577268; flex-wrap: wrap; }
.complaint-notice strong { color: #2f5949; }
.complaint-filters { display: flex; gap: 10px; margin: 16px 0; flex-wrap: wrap; }
.complaint-filters input { flex: 1; min-width: 220px; }
.complaint-filters input, .complaint-filters select, .complaint-action-form textarea, .complaint-script textarea, .complaint-script select { border: 1px solid #d5e1db; border-radius: 8px; padding: 10px 12px; font: inherit; }
.complaint-filters button, .complaint-action-form button, .complaint-script button { border: 0; border-radius: 8px; background: #356b57; color: #fff; padding: 9px 14px; cursor: pointer; }
.complaint-alert { margin: 12px 0; padding: 12px; border-radius: 8px; background: #fff0ed; color: #a33c2e; }
.complaint-alert button { margin-left: 12px; }
.complaint-table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 900px; }
th { padding: 10px 12px; border-bottom: 1px solid #dfe8e3; color: #72887f; text-align: left; font-size: 12px; }
td { padding: 14px 12px; border-bottom: 1px solid #edf2ef; vertical-align: top; }
td small { display: block; margin-top: 5px; color: #81948c; }
.complaint-evidence { max-width: 360px; white-space: pre-wrap; overflow-wrap: anywhere; }
.complaint-exception { color: #a84d32; font-weight: 650; }
.complaint-status { display: inline-block; border-radius: 999px; background: #edf4f0; padding: 4px 9px; white-space: nowrap; }
.complaint-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.complaint-actions button { padding: 6px 8px; }
.complaint-pagination { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.complaint-empty { display: flex; flex-direction: column; gap: 8px; padding: 28px; color: #7c9187; text-align: center; }
.complaint-action-form { display: grid; gap: 10px; max-width: 720px; }
.complaint-action-form > div { display: flex; gap: 8px; }
.complaint-action-form button + button { margin-left: 8px; }
.complaint-script { display: grid; gap: 10px; margin: 14px 0; max-width: 760px; }
.script-picker { display: grid; grid-template-columns: auto auto minmax(220px, 1fr); gap: 10px; align-items: center; }
.script-picker > span { color: #7b9086; font-size: 11px; }
.complaint-script > label small { margin-left: 8px; color: #8a9b93; font-weight: 400; }
.script-copy-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.script-copy-row span { color: #4d7765; }
.complaint-no-script { margin: 14px 0; padding: 14px; border-radius: 8px; color: #7c9187; background: #f5f8f6; }
.complaint-history { padding-left: 22px; }
.complaint-history li { margin: 12px 0; padding-left: 6px; }
.complaint-history span { margin-left: 12px; color: #758a81; }
.complaint-history p { margin-bottom: 0; white-space: pre-wrap; }
</style>
