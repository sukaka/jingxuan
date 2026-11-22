<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getWecomMonitor, listWecomContactSyncRuns, listWecomContacts, listWecomMessages } from '../api/wecom'
import type { WecomMessageView, WecomMonitorView } from '../api/wecom'
import { getAiQualitySummary, listAiJobs } from '../api/ai'
import type { AiJob, AiQualitySummary } from '../api/ai'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { formatDateTime } from './customer/shared'
import { useReferenceNames } from './references'
import { aiIntentLabel } from '../utils/aiIntent'
import { fetchAllPages } from '../utils/asyncBatch'

const route = useRoute()
const auth = useAuthStore()
const canRead = computed(() => auth.user?.userType !== 'INSTITUTION' && auth.hasPermission('wecom:read'))
const { customerLabel, loadCustomerNames } = useReferenceNames()
const messages = ref<WecomMessageView[]>([]), monitor = ref<WecomMonitorView | null>(null), keyword = ref(''), overdueOnly = ref(false)
const quality = ref<AiQualitySummary | null>(null), aiJobs = ref<AiJob[]>([])
const loading = ref(false), errorMessage = ref(''), monitorError = ref('')
let disposed = false

function waitHours(message: WecomMessageView) {
  return Math.max(0, Math.round((Date.now() - new Date(message.sentAt).getTime()) / 360000) / 10)
}

const allLatest = computed(() => {
  const groups = new Map<string, WecomMessageView>()
  messages.value.forEach((message) => {
    const key = message.conversationId || message.externalUserid || `message-${message.id}`
    if (!groups.has(key) || message.archiveSeq > groups.get(key)!.archiveSeq) groups.set(key, message)
  })
  return [...groups.values()]
})

const latest = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return allLatest.value
    .filter((message) => !overdueOnly.value || (message.senderType === 'CUSTOMER' && waitHours(message) >= 24))
    .filter((message) => {
      if (!kw) return true
      const searchable = [message.customerId, message.staffUserId, message.externalUserid, message.conversationId]
        .filter((value) => value !== null && value !== undefined && value !== '')
        .join(' ')
        .toLowerCase()
      return searchable.includes(kw)
    })
})

const customerLast = computed(() => latest.value.filter((message) => message.senderType === 'CUSTOMER').length)
const overdue = computed(() => latest.value.filter((message) => message.senderType === 'CUSTOMER' && waitHours(message) >= 24).length)
const risks = computed(() => Object.fromEntries(aiJobs.value.filter((job) => job.status === 'PROPOSED' && job.insight?.riskFlags.length).map((job) => [job.conversationId, job.insight!.riskFlags])))
function intentLabel(type: string) { return aiIntentLabel(type) }
function riskLabel(value: string) { return ({ COMPLAINT: '投诉风险', REFUND_REQUEST: '退款诉求', SERVICE_CLAIM: '服务完成陈述', URGENT: '优先复核' } as Record<string, string>)[value] || '未知风险' }
function statusLabel(status: string) { return ({ QUEUED: '排队等待', RUNNING: '分析中', RETRY: '等待重试', PROPOSED: '待人工确认', FAILED: '分析失败', CONFIRMED: '已生成待办', REJECTED: '已驳回' } as Record<string, string>)[status] || '未知任务状态' }
function evidenceLabel(ids: number[]) { return ids.map((id) => `消息 #${id}`).join('、') }

async function resolveCorpId(items: WecomMessageView[]): Promise<string> {
  const routeCorp = typeof route?.query?.corpId === 'string' ? route.query.corpId.trim() : ''
  if (routeCorp) return routeCorp
  const messageCorp = items.find((item) => item.corpId?.trim())?.corpId?.trim()
  if (messageCorp) return messageCorp
  const [runsResult, contactsResult] = await Promise.allSettled([
    listWecomContactSyncRuns(),
    listWecomContacts(),
  ])
  if (runsResult.status === 'fulfilled') {
    const runCorp = runsResult.value.find((run) => run.corpId?.trim())?.corpId?.trim()
    if (runCorp) return runCorp
  }
  if (contactsResult.status === 'fulfilled') {
    const contactCorp = contactsResult.value.find((contact) => contact.corpId?.trim())?.corpId?.trim()
    if (contactCorp) return contactCorp
  }
  return ''
}

onBeforeUnmount(() => {
  disposed = true
})

onMounted(async () => {
  if (!canRead.value) return
  loading.value = true
  errorMessage.value = ''
  monitorError.value = ''
  try {
    const page = await fetchAllPages(
      (pageNo, size) => listWecomMessages({ page: pageNo, size }),
      { size: 100, maxPages: 5 },
    )
    if (disposed) return
    messages.value = page.items

    const corpId = await resolveCorpId(page.items)
    if (disposed) return
    if (corpId) {
      try {
        const status = await getWecomMonitor(corpId)
        if (!disposed) monitor.value = status
      } catch (error) {
        if (!disposed) monitorError.value = error instanceof Error ? error.message : '企业微信采集状态获取失败'
      }
    }

    if (auth.hasPermission('wecom:monitor') && auth.hasPermission('ai:read')) {
      const [summaryResult, jobsResult] = await Promise.allSettled([getAiQualitySummary(), listAiJobs({ page: 1, size: 20 })])
      if (disposed) return
      if (summaryResult.status === 'fulfilled') quality.value = summaryResult.value
      if (jobsResult.status === 'fulfilled') aiJobs.value = jobsResult.value.items
    }
    await loadCustomerNames([...page.items.map((item) => item.customerId), ...aiJobs.value.map((item) => item.customerId)].flatMap((id) => id ? [id] : []))
  } catch (error) {
    if (!disposed) errorMessage.value = error instanceof Error ? error.message : '企业微信会话监管加载失败'
  } finally {
    if (!disposed) loading.value = false
  }
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="企业微信会话监管" description="按真实合规归档查看最后沟通与回复时长；AI 风险标签仅作辅助，不作为处罚依据"><template #actions><RouterLink to="/customers/ownership-logs" class="el-button">客户归属日志</RouterLink></template></PageHeader>
    <el-alert v-if="!canRead" type="error" :title="auth.user?.userType === 'INSTITUTION' ? '机构用户无企业微信监管访问权限' : '缺少 wecom:read 权限'" :closable="false" data-testid="wecom-monitor-permission-error" />
    <template v-else>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" style="margin-bottom:14px" data-testid="wecom-monitor-error" />
      <el-alert v-if="monitorError" type="warning" :title="`采集状态暂不可用：${monitorError}`" :closable="false" style="margin-bottom:14px" data-testid="wecom-monitor-status-error" />
      <el-alert v-if="monitor?.dataGap" type="warning" :title="`采集覆盖缺口：${monitor.gapReason || '无法判断完整沟通情况'}`" :closable="false" style="margin-bottom:14px" />
      <div class="proto-stat-grid">
        <StatCard label="客户最后发言" :value="`${customerLast} 位`" sub="按每个会话最后一条消息" icon="chat" />
        <StatCard label="超过24小时" :value="`${overdue} 位`" sub="仅统计客户最后发言" icon="calendar" />
        <StatCard label="AI 风险建议" :value="quality ? `${quality.riskSuggested} 位` : '待接入'" sub="待人工核实，非处罚事实" icon="shield" />
        <StatCard label="有未读" value="待接入" sub="未读回执尚未接入" icon="users" />
      </div>
      <PageCard>
        <div class="proto-toolbar"><div class="proto-toolbar-left"><el-input v-model="keyword" placeholder="客户 / 管家 / 外部身份" clearable style="width:270px" data-testid="wecom-monitor-keyword" /><el-select v-model="overdueOnly" style="width:190px" data-testid="wecom-monitor-overdue"><el-option label="全部会话" :value="false" /><el-option label="超过24小时未回复" :value="true" /></el-select></div><span class="proto-note">未读回执尚未接入；AI 风险仅显示真实任务中的待核实建议</span></div>
        <div v-loading="loading" class="proto-table-wrap">
          <app-table :data="latest">
            <el-table-column label="客户 / 群聊" min-width="160"><template #default="{row}">{{ row.conversationType === 'ROOM' ? `群聊 · ${row.conversationId}` : row.customerId ? customerLabel(row.customerId) : `待匹配 · ${row.externalUserid}` }}</template></el-table-column>
            <el-table-column label="所属管家" width="110"><template #default="{row}">{{ row.conversationType === 'ROOM' ? '群内多成员' : `#${row.staffUserId}` }}</template></el-table-column>
            <el-table-column label="最后沟通" min-width="160"><template #default="{row}">{{ formatDateTime(row.sentAt) }}</template></el-table-column>
            <el-table-column label="最后发言方" width="105"><template #default="{row}">{{ row.senderType === 'CUSTOMER' ? '客户' : '管家' }}</template></el-table-column>
            <el-table-column label="等待回复" width="105"><template #default="{row}">{{ row.senderType === 'CUSTOMER' ? `${waitHours(row)} 小时` : '—' }}</template></el-table-column>
            <el-table-column label="AI风险标签" min-width="130"><template #default="{row}">{{ risks[row.conversationId || row.externalUserid]?.map(riskLabel).join('、') || '无待审建议' }}</template></el-table-column>
            <el-table-column label="级别" width="90"><template #default="{row}">{{ risks[row.conversationId || row.externalUserid] ? '建议级' : '待评估' }}</template></el-table-column>
            <el-table-column label="未读" width="75">—</el-table-column>
            <el-table-column label="操作" width="140" fixed="right"><template #default="{row}"><RouterLink :to="`/integrations/wecom?conversation=${row.conversationId || row.externalUserid}`" class="el-button is-text">查看对话</RouterLink><RouterLink v-if="row.customerId" :to="`/customers?customerId=${row.customerId}`" class="el-button is-text">客户档案</RouterLink></template></el-table-column>
            <template #empty>没有符合筛选的已归档会话；这不等于无沟通</template>
          </app-table>
        </div>
      </PageCard>
      <PageCard title="AI 会话质量建议" :subtitle="quality?.riskNotice || '当前账号无主管质量统计权限'" style="margin-top:18px">
        <div v-if="quality" class="proto-stat-grid"><StatCard label="待人工确认" :value="`${quality.pendingReview} 项`" icon="clipboard" /><StatCard label="风险建议" :value="`${quality.riskSuggested} 项`" icon="shield" /><StatCard label="分析失败" :value="`${quality.failed} 项`" icon="warning" /><StatCard label="已确认待办" :value="`${quality.confirmed} 项`" icon="check" /></div>
        <div class="proto-table-wrap"><app-table :data="aiJobs"><el-table-column label="客户" min-width="120"><template #default="{row}">{{ customerLabel(row.customerId) }}</template></el-table-column><el-table-column label="意图" width="125"><template #default="{row}">{{ row.insight ? intentLabel(row.insight.insightType) : '等待分析' }}</template></el-table-column><el-table-column label="AI 风险建议" min-width="150"><template #default="{row}">{{ row.insight?.riskFlags.map(riskLabel).join('、') || '—' }}</template></el-table-column><el-table-column label="证据" min-width="130"><template #default="{row}">{{ row.insight ? evidenceLabel(row.insight.evidenceMessageIds) : '—' }}</template></el-table-column><el-table-column label="置信度" width="90"><template #default="{row}">{{ row.insight ? `${Math.round(row.insight.confidence * 100)}%` : '—' }}</template></el-table-column><el-table-column label="状态" width="120"><template #default="{row}"><span class="proto-pill info">{{ statusLabel(row.status) }}</span></template></el-table-column><el-table-column label="操作" width="100"><template #default="{row}"><RouterLink :to="`/integrations/wecom?conversation=${row.conversationId}`" class="el-button is-text">查看证据</RouterLink></template></el-table-column><template #empty>暂无 AI 分析任务</template></app-table></div>
      </PageCard>
    </template>
  </div>
</template>
