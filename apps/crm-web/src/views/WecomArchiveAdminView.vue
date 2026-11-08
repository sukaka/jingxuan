<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { WecomContact, WecomContactSyncRun, WecomMessageView, WecomMonitorView } from '../api/wecom'
import {
  getWecomMessageContent,
  getWecomMonitor,
  listWecomContacts,
  listWecomContactSyncRuns,
  listWecomMessages,
  syncWecomArchive,
} from '../api/wecom'
import { useAuthStore } from '../stores/auth'
import { formatDateTime } from './customer/shared'
import StatusPill from './customer/StatusPill.vue'
import { tradingErrorText } from './trading/tradingUi'
import WecomContactPanel from './wecom/WecomContactPanel.vue'
import WecomContentDrawer from './wecom/WecomContentDrawer.vue'
import WecomSyncPolicyPanel from './wecom/WecomSyncPolicyPanel.vue'
import IntegrationRuntimeSettings from '../components/IntegrationRuntimeSettings.vue'
import {
  consentStatusLabel,
  consentStatusTone,
  createWecomKey,
  messageStatusLabel,
  messageStatusTone,
  monitorStatusLabel,
  monitorStatusTone,
  senderTypeLabel,
} from './wecom/wecomUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './wecom/wecom.css'
import { useReferenceNames } from './references'

const { customerLabel, loadCustomerNames } = useReferenceNames()
const auth = useAuthStore()
const isInstitution = computed(() => auth.user?.userType.startsWith('INSTITUTION') ?? false)
const canRead = computed(() => !isInstitution.value && auth.hasPermission('wecom:read'))
const canMonitor = computed(() => canRead.value && auth.hasPermission('wecom:monitor'))
const canManageRuntime = computed(() => canRead.value && auth.hasPermission('integration:runtime:manage'))
const activeTab = ref('messages')
const corpId = ref('MOCK-CORP-001')

const messages = ref<WecomMessageView[]>([])
const messageTotal = ref(0)
const messageLoading = ref(false)
const messageError = ref('')
const messageQuery = reactive({ page: 1, size: 20 })
const customerFilter = ref<number>()

const contacts = ref<WecomContact[]>([])
const contactSyncRuns = ref<WecomContactSyncRun[]>([])
const contactsLoading = ref(false)
const contactsError = ref('')

const monitor = ref<WecomMonitorView | null>(null)
const monitorLoading = ref(false)
const monitorError = ref('')

async function loadMessages() {
  if (!canRead.value) return
  messageLoading.value = true
  messageError.value = ''
  try {
    const result = await listWecomMessages({
      customerId: customerFilter.value,
      page: messageQuery.page,
      size: messageQuery.size,
    })
    messages.value = result.items
    messageTotal.value = result.total
    messageQuery.page = result.page
    messageQuery.size = result.size
    await loadCustomerNames(result.items.flatMap((item) => item.customerId === null ? [] : [item.customerId]))
  } catch (error) {
    messages.value = []
    messageTotal.value = 0
    messageError.value = tradingErrorText(error)
  } finally {
    messageLoading.value = false
  }
}

async function loadContacts() {
  if (!canRead.value) return
  contactsLoading.value = true
  contactsError.value = ''
  try {
    ;[contacts.value, contactSyncRuns.value] = await Promise.all([listWecomContacts(), listWecomContactSyncRuns()])
  } catch (error) {
    contacts.value = []
    contactSyncRuns.value = []
    contactsError.value = tradingErrorText(error)
  } finally {
    contactsLoading.value = false
  }
}

async function loadMonitor() {
  if (!canRead.value) return
  monitorLoading.value = true
  monitorError.value = ''
  try {
    const result = await getWecomMonitor(corpId.value.trim())
    monitor.value = result
    if (!cursorTouched.value) syncCursor.value = result.lastSeq
  } catch (error) {
    monitor.value = null
    monitorError.value = tradingErrorText(error)
  } finally {
    monitorLoading.value = false
  }
}

function loadAll() {
  if (!canRead.value) return
  void Promise.allSettled([loadMessages(), loadContacts(), loadMonitor()])
}

function search() {
  if (!corpId.value.trim()) {
    ElMessage.warning('请输入模拟企业 ID')
    return
  }
  messageQuery.page = 1
  cursorTouched.value = false
  setContentVisible(false)
  loadAll()
}

function searchMessages() {
  messageQuery.page = 1
  setContentVisible(false)
  void loadMessages()
}

function handleLinked() {
  void Promise.allSettled([loadContacts(), loadMessages(), loadMonitor()])
}

const messageEmptyText = computed(() => monitor.value?.dataGap || monitorError.value
  ? '采集覆盖缺口，无法判断沟通情况；当前没有可展示的归档元数据'
  : '当前筛选没有归档元数据；不能据此判断沟通情况')

const contentVisible = ref(false)
const contentTitle = ref('授权私有内容')
const contentLoading = ref(false)
const contentText = ref<string | null>(null)
const contentError = ref('')
const contentReadingId = ref<number | null>(null)
let contentSequence = 0

function clearContent() {
  contentSequence += 1
  contentLoading.value = false
  contentText.value = null
  contentError.value = ''
  contentReadingId.value = null
}

function setContentVisible(visible: boolean) {
  contentVisible.value = visible
  if (!visible) clearContent()
}

function canReadContent(message: WecomMessageView): boolean {
  return canRead.value && message.consentSnapshot === 'GRANTED' && message.currentConsentStatus === 'GRANTED'
}

async function openContent(message: WecomMessageView) {
  if (!canReadContent(message)) return
  const sequence = ++contentSequence
  contentTitle.value = `授权私有内容 · ${message.msgId}`
  contentText.value = null
  contentError.value = ''
  contentLoading.value = true
  contentReadingId.value = message.id
  contentVisible.value = true
  try {
    const result = await getWecomMessageContent(message.id)
    if (sequence !== contentSequence) return
    contentText.value = result.content
  } catch (error) {
    if (sequence !== contentSequence) return
    contentText.value = null
    contentError.value = tradingErrorText(error)
  } finally {
    if (sequence === contentSequence) {
      contentLoading.value = false
      contentReadingId.value = null
    }
  }
}

const syncing = ref(false)
const syncCursor = ref(0)
const syncLimit = ref(50)
const cursorTouched = ref(false)
let syncAttempt: { signature: string; key: string } | null = null

async function runSync() {
  if (!canMonitor.value || syncing.value) return
  const syncCorpId = corpId.value.trim()
  if (!syncCorpId) {
    ElMessage.warning('请输入模拟企业 ID')
    return
  }
  if (!Number.isSafeInteger(syncCursor.value) || syncCursor.value < 0) {
    ElMessage.warning('归档游标必须是非负整数')
    return
  }
  if (!Number.isSafeInteger(syncLimit.value) || syncLimit.value < 1 || syncLimit.value > 100) {
    ElMessage.warning('每页同步条数必须在 1 到 100 之间')
    return
  }
  const signature = `${syncCorpId}:${syncCursor.value}:${syncLimit.value}`
  if (syncAttempt?.signature !== signature) {
    syncAttempt = { signature, key: createWecomKey('wecom.archive.sync') }
  }
  cursorTouched.value = true
  syncing.value = true
  try {
    const result = await syncWecomArchive({
      corpId: syncCorpId,
      cursor: syncCursor.value,
      limit: syncLimit.value,
    }, syncAttempt.key)
    syncAttempt = null
    cursorTouched.value = false
    syncCursor.value = result.lastSeq
    await Promise.allSettled([loadMessages(), loadContacts(), loadMonitor()])
    const summary = `接收 ${result.received} 条，存储 ${result.stored} 条，重复 ${result.duplicates} 条，跳过 ${result.skipped} 条，成功游标 ${result.lastSeq}`
    if (result.dataGap) {
      ElMessage.warning(`模拟同步返回采集覆盖缺口，无法判断沟通情况；${summary}`)
    } else {
      ElMessage.success(`模拟归档同步完成，不代表已采集真实客户聊天；${summary}${result.hasMore ? '；仍有后续游标' : ''}`)
    }
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
    await Promise.allSettled([loadMonitor()])
  } finally {
    syncing.value = false
  }
}

onMounted(() => {
  loadAll()
})
</script>

<template>
  <div class="crm-ui crm-page-bg">
    <div class="trading-page-head">
      <div>
        <h2>企业微信会话归档中心</h2>
        <p>合规同意与成员覆盖 → 模拟增量归档 → 私有内容按需授权读取 → 人工客户匹配与缺口监控。</p>
      </div>
    </div>

    <div class="wecom-mock-banner" data-testid="wecom-mock-banner">
      <span class="wecom-mock-badge">模拟</span>
      <strong>Q10 未确认 · 官方 SDK 禁用 · 不接个人微信</strong>
      <p>当前仅验证企业微信官方归档模型的本地模拟流程。没有真实企业授权、成员覆盖与客户同意，不宣称已采集真实客户聊天；不使用个人微信、非官方钩子或模拟登录。</p>
    </div>

    <el-alert v-if="isInstitution" type="error" :closable="false" title="机构用户无企业微信归档访问入口" data-testid="wecom-forbidden" />
    <el-alert v-else-if="!canRead" type="error" :closable="false" title="缺少 wecom:read 权限，无法查看企微归档数据" data-testid="wecom-forbidden" />
    <template v-else>
      <IntegrationRuntimeSettings v-if="canManageRuntime" provider="wecom" />
      <el-card shadow="never" class="wecom-control-card">
        <div class="crm-toolbar">
          <el-input v-model="corpId" class="wecom-corp-input" placeholder="模拟企业 ID" data-testid="wecom-corp-id" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询归档状态</el-button>
          <span class="wecom-readonly-note">默认仅识别后端模拟企业；查询不到不代表真实企业没有数据。</span>
        </div>
      </el-card>

      <section v-loading="monitorLoading" class="wecom-monitor-panel" data-testid="wecom-monitor">
        <div>
          <div class="wecom-monitor-heading">
            <div><h3>游标与采集覆盖监控</h3><p>成功游标只代表连续成功区间末尾；缺口历史不会因后续成功被抹除。</p></div>
            <StatusPill v-if="monitor" :text="monitorStatusLabel(monitor.status)" :tone="monitorStatusTone(monitor.status)" />
          </div>
          <el-alert v-if="monitorError" type="error" :closable="false" :title="`采集监控加载失败：${monitorError}；无法判断沟通情况`" class="wecom-monitor-alert" data-testid="wecom-monitor-error" />
          <template v-else-if="monitor">
            <el-alert
              v-if="monitor.dataGap"
              type="error"
              :closable="false"
              title="采集覆盖缺口：无法判断沟通情况"
              :description="monitor.gapReason || '存在未覆盖成员、同意或同步区间缺口'"
              class="wecom-monitor-alert"
              data-testid="wecom-data-gap"
              show-icon
            />
            <el-alert v-else type="info" :closable="false" title="当前模拟游标未报告覆盖缺口；不代表真实企业微信已接入或沟通完整" class="wecom-monitor-alert" />
            <dl class="wecom-monitor-facts">
              <div><dt>归档范围</dt><dd>{{ ({ MOCK_ARCHIVE: '模拟归档' } as Record<string, string>)[monitor.archiveScope] || '未知归档范围' }}</dd></div>
              <div><dt>最后完整成功的归档序号</dt><dd>{{ monitor.lastSeq }}</dd></div>
              <div><dt>最近成功 / 最近尝试</dt><dd>{{ formatDateTime(monitor.lastSuccessAt) }} / {{ formatDateTime(monitor.lastAttemptAt) }}</dd></div>
              <div><dt>可见消息元数据</dt><dd>{{ monitor.messageCount }} 条（不等于沟通总量）</dd></div>
              <div><dt>未关联联系人</dt><dd>{{ monitor.unmatchedCount }}</dd></div>
              <div><dt>当前缺口 / 历史已恢复缺口</dt><dd>{{ monitor.activeGaps }} / {{ monitor.historicalGaps }}</dd></div>
            </dl>
          </template>
          <el-empty v-else-if="!monitorLoading" description="监控尚未返回，无法判断沟通情况" />
        </div>
        <div v-if="canMonitor" class="wecom-sync-panel">
          <h3>模拟归档同步</h3>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="起始游标（不得超过已确认成功位置）">
              <el-input-number v-model="syncCursor" :min="0" :precision="0" :controls="false" data-testid="wecom-sync-cursor" @change="cursorTouched = true" />
            </el-form-item>
            <el-form-item label="每页拉取条数">
              <el-input-number v-model="syncLimit" :min="1" :max="100" :precision="0" data-testid="wecom-sync-limit" />
            </el-form-item>
            <el-button type="primary" :loading="syncing" :disabled="!corpId.trim()" data-testid="wecom-sync" @click="runSync">执行模拟同步</el-button>
          </el-form>
          <p>失败重试沿用同一幂等键；失败后先刷新缺口，不跳过未成功区间。</p>
        </div>
        <div v-else class="wecom-sync-note">当前为只读视图。模拟同步与人工客户匹配还需要 wecom:monitor；后端仍会独立校验权限与数据范围。</div>
      </section>

      <el-card shadow="never">
        <el-tabs v-model="activeTab" class="wecom-tabs">
          <el-tab-pane label="消息元数据" name="messages" />
          <el-tab-pane label="联系人与人工匹配" name="contacts" />
          <el-tab-pane v-if="canMonitor" label="同步设置" name="policy" />
        </el-tabs>
        <template v-if="activeTab === 'messages'">
          <div class="wecom-message-filter">
            <el-input-number v-model="customerFilter" :min="1" :precision="0" :controls="false" placeholder="CRM 客户 ID（可选）" data-testid="wecom-message-customer" />
            <el-button @click="searchMessages">筛选消息</el-button>
            <span class="wecom-readonly-note">列表仅含元数据，不返回聊天全文；后端按客户与成员范围过滤。</span>
          </div>
          <el-alert v-if="messageError" type="error" :closable="false" :title="`消息元数据加载失败：${messageError}`" class="wecom-table-alert" />
          <app-table v-loading="messageLoading" :data="messages" row-key="id">
            <el-table-column prop="msgId" label="消息 ID" min-width="175" />
            <el-table-column prop="archiveSeq" label="归档序号" width="80" />
            <el-table-column label="会话 / 发送方" min-width="180">
              <template #default="{ row }">{{ row.conversationId || '-' }}<div class="wecom-cell-sub">{{ senderTypeLabel(row.senderType) }} · 员工 #{{ row.staffUserId }}</div></template>
            </el-table-column>
            <el-table-column prop="externalUserid" label="外部身份" min-width="180" />
            <el-table-column label="CRM 客户" width="130"><template #default="{ row }">{{ row.customerId === null ? '未关联' : customerLabel(row.customerId) }}</template></el-table-column>
            <el-table-column label="类型" width="80"><template #default="{ row }">{{ ({ TEXT: '文本', IMAGE: '图片', FILE: '文件', LINK: '链接', VIDEO: '视频', VOICE: '语音' } as Record<string, string>)[row.messageType] || '其他类型' }}</template></el-table-column>
            <el-table-column label="发送时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.sentAt) }}</template></el-table-column>
            <el-table-column label="采集同意快照" width="140"><template #default="{ row }"><StatusPill :text="consentStatusLabel(row.consentSnapshot)" :tone="consentStatusTone(row.consentSnapshot)" /></template></el-table-column>
            <el-table-column label="当前同意状态" width="140"><template #default="{ row }"><StatusPill :text="consentStatusLabel(row.currentConsentStatus)" :tone="consentStatusTone(row.currentConsentStatus)" /></template></el-table-column>
            <el-table-column label="归档状态" width="130"><template #default="{ row }"><StatusPill :text="messageStatusLabel(row.status)" :tone="messageStatusTone(row.status)" /></template></el-table-column>
            <el-table-column label="私有内容" width="120" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canReadContent(row)" text type="primary" size="small" :loading="contentReadingId === row.id" data-testid="wecom-content-read" @click="openContent(row)">按需读取</el-button>
                <span v-else class="wecom-readonly-note">同意无效不可读</span>
              </template>
            </el-table-column>
            <template #empty><el-empty :description="messageEmptyText" /></template>
          </app-table>
          <el-pagination v-model:current-page="messageQuery.page" v-model:page-size="messageQuery.size" :total="messageTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="wecom-pager" @current-change="loadMessages" @size-change="messageQuery.page = 1; loadMessages()" />
        </template>
        <template v-else-if="activeTab === 'contacts'">
          <el-alert v-if="contactsError" type="error" :closable="false" :title="`联系人加载失败：${contactsError}`" class="wecom-table-alert" />
          <WecomContactPanel :contacts="contacts" :sync-runs="contactSyncRuns" :loading="contactsLoading" :can-monitor="canMonitor" @saved="handleLinked" />
        </template>
        <template v-else>
          <WecomSyncPolicyPanel :corp-id="corpId.trim()" :can-write="canMonitor" />
        </template>
      </el-card>
    </template>

    <WecomContentDrawer
      :model-value="contentVisible"
      :title="contentTitle"
      :loading="contentLoading"
      :content="contentText"
      :error="contentError"
      @update:model-value="setContentVisible"
    />
  </div>
</template>
