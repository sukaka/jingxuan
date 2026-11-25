<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getAiDelegationCustomerControl,
  getAiDelegationPolicy,
  getAiDelegationRuns,
  getAiDelegationStatus,
  setAiDelegationCustomerControl,
  setAiDelegationEmergencyStop,
  updateAiDelegationPolicy,
} from '../api/aiChatDelegation'
import type {
  AiDelegationCustomerControl,
  DelegationCustomerMode,
  AiDelegationPolicy,
  AiDelegationRun,
  DelegationScopeType,
  AiDelegationStatus,
} from '../api/aiChatDelegation'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import { aiIntentLabel } from '../utils/aiIntent'

const auth = useAuthStore()
const canConfig = computed(() => auth.hasPermission('ai:config'))
const canWrite = computed(() => auth.hasPermission('ai:write'))
const policy = ref<AiDelegationPolicy | null>(null)
const status = ref<AiDelegationStatus | null>(null)
const runs = ref<AiDelegationRun[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const policyReason = ref('')
const blockedTermsText = ref('')
const newTeamId = ref<number | null>(null)
const newStaffId = ref<number | null>(null)
const runCustomerId = ref<number | null>(null)
const runStatus = ref('')
const controlCustomerId = ref<number | null>(null)
const controlVersion = ref(0)
const controlReason = ref('')
const controlMode = ref('ACTIVE')
let policyKey = ''
let policyPayload = ''
let emergencyKey = ''
let emergencyPayload = ''
let controlKey = ''
let controlPayload = ''

const senderDescription = computed(() => {
  if (!status.value) return '状态读取中'
  if (status.value.senderMode === 'MOCK') return '模拟演示模式：不会发送给客户'
  if (status.value.senderMode === 'OFFICIAL') {
    return status.value.realOfficialEnabled
      ? '官方外部联系人通道已启用；接口接受不代表客户已读'
      : '已选择官方通道，但真实发送开关尚未完整启用'
  }
  return '外部联系人发送已禁用，不会发送给客户'
})

function newKey(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `ai-delegation-${Date.now()}-${Math.random()}`
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function applyPolicy(value: AiDelegationPolicy): void {
  policy.value = { ...value, scopes: value.scopes.map((scope) => ({ ...scope })), blockedTerms: [...value.blockedTerms] }
  blockedTermsText.value = value.blockedTerms.join('\n')
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [storedPolicy, storedStatus, storedRuns] = await Promise.all([
      getAiDelegationPolicy(), getAiDelegationStatus(), getAiDelegationRuns({ page: page.value, size }),
    ])
    applyPolicy(storedPolicy)
    status.value = storedStatus
    runs.value = storedRuns.items
    total.value = storedRuns.total
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function refreshRuns(): Promise<void> {
  errorMessage.value = ''
  try {
    const result = await getAiDelegationRuns({
      ...(runCustomerId.value ? { customerId: runCustomerId.value } : {}),
      ...(runStatus.value ? { status: runStatus.value } : {}),
      page: page.value,
      size,
    })
    runs.value = result.items
    total.value = result.total
  } catch (error) {
    errorMessage.value = errorText(error)
  }
}

function addScope(scopeType: DelegationScopeType): void {
  const current = policy.value
  const scopeId = scopeType === 'TEAM' ? newTeamId.value : newStaffId.value
  if (!current || !scopeId || scopeId <= 0) {
    errorMessage.value = '请输入有效的团队或管家用户 ID'
    return
  }
  if (current.scopes.some((scope) => scope.scopeType === scopeType && scope.scopeId === scopeId)) {
    errorMessage.value = '该团队或管家已在开关列表中'
    return
  }
  current.scopes.push({ scopeType, scopeId, enabled: true })
  if (scopeType === 'TEAM') newTeamId.value = null
  else newStaffId.value = null
  errorMessage.value = ''
}

async function savePolicy(): Promise<void> {
  const current = policy.value
  if (!current || saving.value || !canConfig.value) return
  const reason = policyReason.value.trim()
  if (!reason) {
    errorMessage.value = '请填写配置修改理由'
    return
  }
  const blockedTerms = blockedTermsText.value.split(/[\n,，]/).map((term) => term.trim()).filter(Boolean)
  const body = {
    enabled: current.enabled,
    minIntervalSeconds: current.minIntervalSeconds,
    dailyLimit: current.dailyLimit,
    allowedStart: current.allowedStart,
    allowedEnd: current.allowedEnd,
    zoneId: current.zoneId.trim(),
    blockedTerms,
    scopes: current.scopes.map((scope) => ({ ...scope })),
    version: current.version,
    reason,
  }
  const payload = JSON.stringify(body)
  if (policyPayload !== payload) {
    policyKey = newKey()
    policyPayload = payload
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    applyPolicy(await updateAiDelegationPolicy(body, policyKey))
    policyKey = ''
    policyPayload = ''
    policyReason.value = ''
    successMessage.value = '托管策略已保存；全局、团队和管家开关必须同时允许'
    await refreshStatus()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function toggleEmergency(): Promise<void> {
  const current = policy.value
  if (!current || saving.value || !canConfig.value) return
  const reason = policyReason.value.trim()
  if (!reason) {
    errorMessage.value = '紧急停用或恢复必须填写理由'
    return
  }
  const body = { stopped: !current.emergencyStopped, version: current.version, reason }
  const payload = JSON.stringify(body)
  if (emergencyPayload !== payload) {
    emergencyKey = newKey()
    emergencyPayload = payload
  }
  saving.value = true
  errorMessage.value = ''
  try {
    applyPolicy(await setAiDelegationEmergencyStop(body, emergencyKey))
    emergencyKey = ''
    emergencyPayload = ''
    policyReason.value = ''
    successMessage.value = body.stopped ? '已紧急停用；后续自动发送已阻断' : '已解除紧急停用；其他开关仍需逐项允许'
    await refreshStatus()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function loadControl(): Promise<void> {
  if (!controlCustomerId.value || controlCustomerId.value <= 0) {
    errorMessage.value = '请输入有效的客户 ID'
    return
  }
  try {
    const control = await getAiDelegationCustomerControl(controlCustomerId.value)
    applyControl(control)
    errorMessage.value = ''
  } catch (error) {
    errorMessage.value = errorText(error)
  }
}

function applyControl(control: AiDelegationCustomerControl): void {
  controlCustomerId.value = control.customerId
  controlVersion.value = control.version
  controlMode.value = control.mode
}

async function setControl(mode: DelegationCustomerMode): Promise<void> {
  if (!canWrite.value || saving.value) return
  const customerId = controlCustomerId.value
  const reason = controlReason.value.trim()
  if (!customerId || customerId <= 0 || controlVersion.value < 0 || !reason) {
    errorMessage.value = '请填写客户 ID、当前版本和操作理由'
    return
  }
  const body = { mode, version: controlVersion.value, reason }
  const payload = JSON.stringify({ customerId, ...body })
  if (controlPayload !== payload) {
    controlKey = newKey()
    controlPayload = payload
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    applyControl(await setAiDelegationCustomerControl(customerId, body, controlKey))
    controlKey = ''
    controlPayload = ''
    controlReason.value = ''
    successMessage.value = mode === 'HUMAN_TAKEOVER'
      ? '已切换人工接管；后续自动回复已停止'
      : mode === 'PAUSED' ? '已暂停该客户的自动回复' : '已恢复该客户；仍需满足其他全部门禁'
    await Promise.all([refreshRuns(), refreshStatus()])
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function refreshStatus(): Promise<void> {
  try {
    status.value = await getAiDelegationStatus()
  } catch (error) {
    errorMessage.value = errorText(error)
  }
}

function controlModeLabel(value: string): string {
  return ({
    ACTIVE: '正常托管',
    PAUSED: '已暂停',
    HUMAN_TAKEOVER: '人工接管',
  } as Record<string, string>)[value] ?? '未知模式'
}

function sendProviderLabel(value: string | null | undefined): string {
  if (!value) return '—'
  return ({
    MOCK: '模拟演示',
    OFFICIAL: '企微官方通道',
    DISABLED: '已禁用',
  } as Record<string, string>)[value] ?? '其他通道'
}

function maskedReasonLabel(value: string | null | undefined): string {
  if (!value) return '—'
  const known: Record<string, string> = {
    WECOM_RESPONSE_UNKNOWN: '企微发送结果未明，需人工核实',
    WECOM_SEND_FAILED: '企微发送失败',
    EMERGENCY_STOPPED: '已触发紧急停用',
    CUSTOMER_PAUSED: '该客户已暂停托管',
    HUMAN_TAKEOVER: '已切换人工接管',
    OUT_OF_TIME_WINDOW: '不在允许发送时段',
    RATE_LIMITED: '已触发发送频控限制',
    DAILY_LIMIT_REACHED: '已达到每日回复上限',
    BLOCKED_TERM: '命中禁发词限制',
    SCOPE_DISABLED: '团队或管家开关未开启',
    DISABLED: '托管发送通道已禁用',
  }
  return known[value] ?? value
}

function runStatusLabel(value: string): string {
  return ({
    QUEUED: '等待生成', DEFERRED: '等待允许时段', GENERATING: 'AI 生成中', READY_TO_SEND: '等待发送',
    SENDING: '发送中', SENT: '官方接口已接受（不代表已读）', MOCKED: '模拟演示完成，未发送给客户',
    DISABLED: '发送通道禁用，未发送', SKIPPED: '已阻断', FAILED: '失败',
    RETRY_GENERATE: '等待重试生成', RETRY_SEND: '等待明确失败重试', UNKNOWN: '结果未知，禁止自动重发',
  } as Record<string, string>)[value] ?? '未知状态'
}

function intentLabel(value: string | null): string {
  return aiIntentLabel(value)
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page delegation-page">
    <PageHeader title="AI 聊天托管" description="合法企微客户文本经授权、AI 意图与禁发门禁后，才进入独立外部联系人发送通道" />
    <div v-if="errorMessage" class="delegation-alert error" role="alert">{{ errorMessage }}</div>
    <div v-if="successMessage" class="delegation-alert success" role="status">{{ successMessage }}</div>

    <PageCard title="运行状态" subtitle="状态以系统实际运行为准；模拟演示或连接配置均不代表客户已经收到或已读">
      <div v-if="status" class="status-grid">
        <div><span>发送通道</span><strong data-testid="sender-mode">{{ senderDescription }}</strong></div>
        <div><span>托管总开关</span><strong>{{ status.enabled && !status.emergencyStopped ? '已允许（仍受下级门禁约束）' : '已停止' }}</strong></div>
        <div><span>归档事件消费</span><strong>{{ status.eventConsumerEnabled ? '已启用' : '未启用' }}</strong></div>
        <div><span>托管执行器</span><strong>{{ status.workerEnabled ? '已启用' : '未启用' }}</strong></div>
      </div>
      <div v-else class="delegation-empty">{{ loading ? '正在读取运行状态…' : '运行状态不可用' }}</div>
    </PageCard>

    <PageCard title="托管配置" subtitle="无团队或管家配置视为关闭；客户暂停、人工接管和紧急停用优先">
      <form v-if="policy" class="delegation-form" @submit.prevent="savePolicy">
        <fieldset :disabled="!canConfig || saving">
          <label class="switch-line"><input v-model="policy.enabled" type="checkbox" data-testid="global-enabled">启用当前租户 AI 自动回复</label>
          <div v-if="policy.emergencyStopped" class="delegation-alert error">当前处于紧急停用状态，所有新发送均被阻断。</div>
          <div class="form-grid">
            <label>同客户最小间隔（秒）<input v-model.number="policy.minIntervalSeconds" type="number" min="0" max="3600" required></label>
            <label>同客户每日最多回复消息数<input v-model.number="policy.dailyLimit" type="number" min="1" max="100" required></label>
            <label>允许开始时间<input v-model="policy.allowedStart" type="time" step="60" required></label>
            <label>允许结束时间<input v-model="policy.allowedEnd" type="time" step="60" required></label>
            <label>频控时区<input v-model="policy.zoneId" maxlength="64" required></label>
          </div>
          <label>额外禁发词（每行一个，普通包含匹配）<textarea v-model="blockedTermsText" rows="3" maxlength="6400" /></label>
          <div class="scope-editor">
            <strong>团队与管家开关</strong>
            <div v-for="(scope, index) in policy.scopes" :key="`${scope.scopeType}-${scope.scopeId}`" class="scope-row">
              <span>{{ scope.scopeType === 'TEAM' ? '团队' : '管家' }} #{{ scope.scopeId }}</span>
              <label><input v-model="scope.enabled" type="checkbox">允许</label>
              <button type="button" @click="policy?.scopes.splice(index, 1)">移除</button>
            </div>
            <div v-if="!policy.scopes.length" class="delegation-empty">尚未配置团队或管家，当前不会自动发送。</div>
            <div class="scope-add">
              <input v-model.number="newTeamId" type="number" min="1" placeholder="团队 ID" aria-label="团队 ID">
              <button type="button" @click="addScope('TEAM')">添加团队</button>
              <input v-model.number="newStaffId" type="number" min="1" placeholder="管家用户 ID" aria-label="管家用户 ID">
              <button type="button" @click="addScope('STAFF')">添加管家</button>
            </div>
          </div>
        </fieldset>
        <label>本次配置或紧急操作理由<input v-model="policyReason" data-testid="policy-reason" maxlength="1000" :disabled="!canConfig || saving"></label>
        <div v-if="canConfig" class="action-row">
          <button type="button" data-testid="save-policy" :disabled="saving" @click="savePolicy">{{ saving ? '提交中…' : '保存托管配置' }}</button>
          <button type="button" class="danger" data-testid="emergency-stop" :disabled="saving" @click="toggleEmergency">{{ policy.emergencyStopped ? '解除紧急停用' : '紧急停用全部托管' }}</button>
          <span>配置版本 {{ policy.version }}</span>
        </div>
        <p v-else class="delegation-note">当前账号可查看运行状态，但暂无 AI 聊天托管配置权限。</p>
      </form>
    </PageCard>

    <PageCard title="客户暂停与人工接管" subtitle="操作按客户数据范围校验；不会改写历史发送结果">
      <div class="control-form">
        <label>客户 ID<input v-model.number="controlCustomerId" data-testid="control-customer-id" type="number" min="1" :disabled="!canWrite"></label>
        <label>当前版本<input v-model.number="controlVersion" data-testid="control-version" type="number" min="0" :disabled="!canWrite"></label>
        <button type="button" :disabled="!canWrite || saving" @click="loadControl">读取当前状态</button>
        <span>当前模式：{{ controlModeLabel(controlMode) }}</span>
        <label class="control-reason">操作理由<input v-model="controlReason" data-testid="control-reason" maxlength="1000" :disabled="!canWrite"></label>
        <div class="action-row">
          <button type="button" data-testid="pause-customer" :disabled="!canWrite || saving" @click="setControl('PAUSED')">暂停客户</button>
          <button type="button" class="danger" data-testid="human-takeover" :disabled="!canWrite || saving" @click="setControl('HUMAN_TAKEOVER')">人工接管</button>
          <button type="button" data-testid="resume-customer" :disabled="!canWrite || saving" @click="setControl('ACTIVE')">恢复托管</button>
        </div>
      </div>
    </PageCard>

    <PageCard title="发送日志" subtitle="仅展示状态、脱敏原因和提供方引用，不展示聊天或 AI 回复正文">
      <div class="log-filter">
        <input v-model.number="runCustomerId" type="number" min="1" placeholder="客户 ID" aria-label="日志客户 ID">
        <select v-model="runStatus" aria-label="日志状态">
          <option value="">全部状态</option><option value="SENT">官方已接受</option><option value="MOCKED">模拟演示</option>
          <option value="UNKNOWN">未知</option><option value="SKIPPED">已阻断</option><option value="FAILED">失败</option>
        </select>
        <button type="button" @click="page = 1; refreshRuns()">查询</button>
      </div>
      <div class="log-table-wrap">
        <table>
          <thead><tr><th>流水</th><th>客户 / 管家</th><th>意图</th><th>状态</th><th>脱敏原因</th><th>发送方</th><th>尝试</th><th>更新时间</th></tr></thead>
          <tbody>
            <tr v-for="run in runs" :key="run.id" :data-testid="`run-${run.id}`">
              <td>#{{ run.id }}<small>消息 #{{ run.sourceMessageId }}</small></td>
              <td>{{ run.customerId ?? '未匹配' }} / {{ run.staffUserId }}</td>
              <td>{{ intentLabel(run.intent) }}</td>
              <td><span class="run-status" :class="run.status.toLowerCase()">{{ runStatusLabel(run.status) }}</span></td>
              <td>{{ maskedReasonLabel(run.reasonMasked) }}</td>
              <td>{{ sendProviderLabel(run.sendProvider) }}<small v-if="run.providerMessageId">引用 {{ run.providerMessageId }}</small></td>
              <td>生成 {{ run.generationAttempts }} / 发送 {{ run.sendAttempts }}</td>
              <td>{{ formatTime(run.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!runs.length && !loading" class="delegation-empty">暂无符合当前数据范围的托管日志</div>
      </div>
      <div class="pagination"><button type="button" :disabled="page <= 1" @click="page--; refreshRuns()">上一页</button><span>第 {{ page }} 页，共 {{ total }} 条</span><button type="button" :disabled="page * size >= total" @click="page++; refreshRuns()">下一页</button></div>
    </PageCard>
  </div>
</template>

<style scoped>
.delegation-page{min-width:0;color:#29483e}.delegation-page>.proto-card{margin-top:16px}.delegation-alert{margin:0 0 14px;padding:12px 14px;border-radius:8px}.delegation-alert.error{background:#fff0ed;color:#a33c2e}.delegation-alert.success{background:#edf7f0;color:#356b50}.status-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;padding:18px}.status-grid>div{display:grid;gap:6px;padding:12px;background:#f5f8f6;border-radius:8px}.status-grid span,.delegation-note,.delegation-empty{color:#6c8177;font-size:13px}.delegation-form,.control-form{display:grid;gap:14px;padding:18px}.delegation-form fieldset{display:grid;gap:14px;border:0;padding:0;margin:0;min-width:0}.delegation-form label,.control-form label{display:grid;gap:6px;font-size:13px}.delegation-form input:not([type='checkbox']),.delegation-form textarea,.control-form input,.log-filter input,.log-filter select{width:100%;box-sizing:border-box;border:1px solid #d5e1db;border-radius:8px;padding:9px 11px;font:inherit;background:#fff}.switch-line{display:flex!important;align-items:center;gap:8px;font-weight:600}.form-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.scope-editor{display:grid;gap:10px;padding:12px;border:1px solid #e1eae5;border-radius:8px}.scope-row,.scope-add,.action-row,.log-filter,.pagination{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.scope-row{justify-content:space-between}.scope-add input{width:160px!important}.action-row button,.scope-editor button,.control-form button,.log-filter button,.pagination button{border:1px solid #bfd3c8;border-radius:7px;background:#f5faf7;color:#29483e;padding:8px 12px;cursor:pointer}.action-row button:first-child{background:#2f6b51;color:#fff}.action-row button.danger,.control-form button.danger{border-color:#d9a399;background:#fff4f1;color:#a33c2e}.action-row button:disabled,button:disabled{cursor:not-allowed;opacity:.55}.control-form{grid-template-columns:180px 160px auto 1fr;align-items:end}.control-reason{grid-column:1/-1}.control-form .action-row{grid-column:1/-1}.log-filter{padding:16px 18px 0}.log-filter input{width:180px}.log-table-wrap{overflow:auto;padding:12px 18px}table{width:100%;border-collapse:collapse;min-width:1040px}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #e8eeeb;padding:10px 8px;font-size:13px}th{color:#6c8177;white-space:nowrap}td small{display:block;color:#7d8e86;margin-top:4px}.run-status{font-weight:600}.run-status.unknown,.run-status.failed{color:#a33c2e}.run-status.mocked,.run-status.disabled{color:#8a6424}.run-status.sent{color:#28734d}.delegation-empty{padding:16px 0}.pagination{justify-content:flex-end;padding:0 18px 18px}@media(max-width:900px){.form-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.control-form{grid-template-columns:1fr 1fr}}@media(max-width:600px){.status-grid,.form-grid,.control-form{grid-template-columns:1fr}.control-reason,.control-form .action-row{grid-column:auto}}
</style>
