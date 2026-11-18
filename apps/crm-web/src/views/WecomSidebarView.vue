<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listEntitlements } from '../api/trading'
import type { Entitlement } from '../api/trading'
import { getWecomSidebarContext } from '../api/wecomSidebar'
import type { WecomSidebarContext } from '../api/wecomSidebar'
import { getCustomerTimeline } from '../api/customerTimeline'
import type { CustomerTimelineItem } from '../api/customerTimeline'
import { getWecomChatContext, isWecomRuntime, redirectToWecomOAuth } from '../services/wecomSdk'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import WecomQuickReplyPanel from '../components/WecomQuickReplyPanel.vue'
import WecomAiReplyPanel from '../components/WecomAiReplyPanel.vue'
import WecomH5CardPanel from '../components/WecomH5CardPanel.vue'

type ViewStatus = 'LOADING' | 'BROWSER' | 'AUTHENTICATING' | 'FORBIDDEN' | 'NOT_CHAT' | 'GROUP_CHAT' | 'READY' | 'ERROR'
type SidebarTab = 'CUSTOMER' | 'PACKAGES' | 'TIMELINE' | 'AI' | 'TOOLS'
type ToolTab = 'QUICK_REPLY' | 'H5_CARD'

const auth = useAuthStore()
const status = ref<ViewStatus>('LOADING')
const activeTab = ref<SidebarTab>('CUSTOMER')
const toolTab = ref<ToolTab>('QUICK_REPLY')
const chatEntry = ref('')
const context = ref<WecomSidebarContext | null>(null)
const entitlements = ref<Entitlement[]>([])
const timeline = ref<CustomerTimelineItem[]>([])
const errorMessage = ref('')
const auxiliaryMessage = ref('')
const customer = computed(() => context.value?.customer ?? null)
const customerName = computed(() => customer.value?.displayName || customer.value?.nickname || customer.value?.customerNo || '当前客户')
const customerInitial = computed(() => customerName.value.slice(0, 1))
const hasCustomer = computed(() => context.value?.contextStatus === 'READY' && customer.value !== null)

async function load(): Promise<void> {
  status.value = 'LOADING'
  errorMessage.value = ''
  auxiliaryMessage.value = ''
  context.value = null
  chatEntry.value = ''
  entitlements.value = []
  timeline.value = []
  try {
    if (!isWecomRuntime()) {
      status.value = 'BROWSER'
      return
    }
    if (!auth.initialized) await auth.fetchMe()
    if (!auth.isLoggedIn) {
      status.value = 'AUTHENTICATING'
      redirectToWecomOAuth()
      return
    }
    if (!auth.hasPermission('wecom:sidebar:view')) {
      status.value = 'FORBIDDEN'
      return
    }
    const chat = await getWecomChatContext()
    if (chat.kind === 'GROUP_CHAT') {
      status.value = 'GROUP_CHAT'
      return
    }
    if (chat.kind !== 'CUSTOMER') {
      status.value = 'NOT_CHAT'
      return
    }
    chatEntry.value = chat.entry
    context.value = await getWecomSidebarContext(chat.externalUserid)
    status.value = 'READY'
    if (context.value.customer) await loadBusinessData(context.value.customer.id)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : error instanceof Error ? error.message : '页面加载失败，请重试'
    status.value = 'ERROR'
  }
}

async function loadBusinessData(customerId: number): Promise<void> {
  const tasks: Promise<void>[] = []
  if (auth.hasPermission('entitlement:read')) {
    tasks.push((async () => {
      try {
        entitlements.value = (await listEntitlements({ customerId, page: 1, size: 50 })).items
      } catch {
        auxiliaryMessage.value = '套餐信息暂时不可用，客户档案仍可正常使用。'
      }
    })())
  }
  if (auth.hasPermission('customer:read')) {
    tasks.push((async () => {
      try {
        timeline.value = (await getCustomerTimeline(customerId, { size: 20 })).items
      } catch {
        auxiliaryMessage.value = '跟进时间线暂时不可用，客户档案仍可正常使用。'
      }
    })())
  }
  await Promise.all(tasks)
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'Asia/Shanghai' }).format(date)
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai', hour12: false }).format(date)
}

function entitlementStatus(statusCode: string): string {
  return ({ ACTIVE: '生效中', AVAILABLE: '生效中', DEPLETED: '已用完', EXHAUSTED: '已用完', EXPIRED: '已到期', REFUNDED: '已退款', REVOKED: '已收回' } as Record<string, string>)[statusCode] ?? '已结束'
}

function timelineTypeLabel(type: string): string {
  return ({ FOLLOWUP: '跟进', INTENT: '意向', BLOCKER: '阻碍', NEXT_FOLLOWUP: '待跟进', TASK: '待办', OWNERSHIP: '归属', ORDER: '订单', APPOINTMENT: '预约', REDEMPTION: '核销', REFUND: '退款', TIER: '等级', COMPLAINT: '客诉' } as Record<string, string>)[type] ?? '记录'
}

onMounted(() => { void load() })
</script>

<template>
  <main class="wecom-sidebar" data-testid="wecom-sidebar">
    <section v-if="status === 'LOADING'" class="sidebar-state loading-state" aria-live="polite">
      <span class="state-brand">鲸</span><h1>正在连接客户会话</h1><p>校验企业身份并读取当前客户上下文…</p>
      <div class="loading-lines" aria-hidden="true"><i /><i /><i /></div>
    </section>

    <section v-else-if="status === 'BROWSER'" class="sidebar-state" data-testid="browser-state">
      <span class="state-brand">鲸</span><span class="state-eyebrow">企微客户助手</span>
      <h1>请在企业微信中打开</h1>
      <p>当前是普通浏览器，无法读取聊天上下文。请从客户单聊的聊天工具栏进入，系统不会在此伪造当前客户或会话。</p>
      <div class="state-guidance"><strong>正确入口</strong><span>企业微信 → 客户聊天 → 聊天工具栏 → 鲸选客户助手</span></div>
    </section>

    <section v-else-if="status === 'AUTHENTICATING'" class="sidebar-state" aria-live="polite">
      <span class="state-brand">鲸</span><h1>正在进行企业身份认证</h1><p>即将跳转企业微信授权，完成后自动返回当前页面。</p>
    </section>

    <section v-else-if="status === 'FORBIDDEN'" class="sidebar-state" data-testid="forbidden-state">
      <span class="state-brand">鲸</span><h1>当前账号无侧边栏权限</h1><p>请联系管理员为岗位配置 `wecom:sidebar:view`，客户数据仍按原有权限范围隔离。</p>
    </section>

    <section v-else-if="status === 'NOT_CHAT'" class="sidebar-state" data-testid="not-chat-state">
      <span class="state-brand">鲸</span><h1>当前不是客户聊天入口</h1><p>请从客户单聊工具栏或客户资料页进入。当前入口不会读取客户标识，也不会执行会话操作。</p>
    </section>

    <section v-else-if="status === 'GROUP_CHAT'" class="sidebar-state" data-testid="group-chat-state">
      <span class="state-brand">鲸</span><h1>当前是群聊</h1><p>群聊不自动对应某一个 CRM 客户。请进入客户单聊后使用客户助手，避免把客户资料展示在错误的会话中。</p>
    </section>

    <section v-else-if="status === 'ERROR'" class="sidebar-state" data-testid="error-state">
      <span class="state-brand">鲸</span><h1>暂时无法打开客户助手</h1><p role="alert">{{ errorMessage }}</p><button class="state-action" type="button" @click="load">重新加载</button>
    </section>

    <template v-else-if="status === 'READY' && context">
      <header class="customer-hero">
        <div class="assistant-bar"><span class="assistant-brand">鲸</span><span>企微客户助手</span><button type="button" aria-label="刷新当前客户" @click="load">刷新</button></div>
        <template v-if="hasCustomer && customer">
          <div class="customer-identity">
            <span class="customer-avatar">{{ customerInitial }}</span>
            <div><div class="customer-name-row"><h1>{{ customerName }}</h1><span v-if="customer.tierCode" class="customer-tier">{{ customer.tierCode }} 级</span></div><p>{{ customer.mobileMasked || '未登记手机号' }}</p></div>
          </div>
          <div class="customer-owner"><span>客户编号 {{ customer.customerNo }}</span><span>管家 {{ customer.ownerName || '待分配' }}</span></div>
        </template>
        <div v-else class="unlinked-hero"><span class="state-eyebrow">当前企微客户</span><h1>尚未关联 CRM 客户</h1><p>已识别 external_userid，但它不是 CRM 主键。请先由有权限人员在企微管理中完成人工匹配。</p></div>
      </header>

      <template v-if="hasCustomer && customer">
        <section class="summary-strip" aria-label="客户业务摘要">
          <div><strong>{{ context.summary.totalOrders ?? '—' }}</strong><span>累计订单</span></div>
          <div><strong>{{ context.summary.remainingPackages ?? '—' }}</strong><span>可用套餐</span></div>
          <div><strong>{{ context.summary.remainingCount ?? '—' }}</strong><span>剩余次数</span></div>
        </section>
        <nav class="sidebar-tabs" role="tablist" aria-label="客户信息分类">
          <button type="button" role="tab" :aria-selected="activeTab === 'CUSTOMER'" data-testid="sidebar-customer-tab" @click="activeTab = 'CUSTOMER'">客户</button>
          <button type="button" role="tab" :aria-selected="activeTab === 'PACKAGES'" data-testid="sidebar-packages-tab" @click="activeTab = 'PACKAGES'">套餐</button>
          <button type="button" role="tab" :aria-selected="activeTab === 'TIMELINE'" data-testid="sidebar-timeline-tab" @click="activeTab = 'TIMELINE'">跟进</button>
          <button type="button" role="tab" :aria-selected="activeTab === 'AI'" data-testid="sidebar-ai-tab" @click="activeTab = 'AI'">AI 助手</button>
          <button type="button" role="tab" :aria-selected="activeTab === 'TOOLS'" data-testid="sidebar-tools-tab" @click="activeTab = 'TOOLS'">工具</button>
        </nav>
        <div v-if="auxiliaryMessage" class="auxiliary-notice" role="status">{{ auxiliaryMessage }}</div>
        <div class="sidebar-content">
          <section v-if="activeTab === 'CUSTOMER'" class="content-section" data-testid="sidebar-customer-panel">
            <div class="section-heading"><span>客户档案</span><small>实时 CRM 数据</small></div>
            <dl class="profile-list">
              <div><dt>客户等级</dt><dd>{{ customer.tierCode || '未分级' }}</dd></div>
              <div><dt>意向程度</dt><dd>{{ customer.intentLevel || '未评估' }}</dd></div>
              <div><dt>归属管家</dt><dd>{{ customer.ownerName || '待分配' }}</dd></div>
              <div><dt>首次下单</dt><dd>{{ formatDate(customer.firstOrderAt) }}</dd></div>
              <div><dt>最近跟进</dt><dd>{{ formatDate(customer.lastFollowupAt) }}</dd></div>
              <div><dt>客户生日</dt><dd>{{ formatDate(customer.birthday) }}</dd></div>
            </dl>
            <div class="profile-tags"><span>客户标签</span><div v-if="customer.tagCodes.length"><span v-for="tag in customer.tagCodes" :key="tag">{{ tag }}</span></div><p v-else>暂未设置标签</p></div>
            <p class="privacy-note">手机号等敏感信息默认脱敏，明文查看仍受原权限与审计约束。</p>
          </section>

          <section v-else-if="activeTab === 'PACKAGES'" class="content-section" data-testid="sidebar-packages-panel">
            <div class="section-heading"><span>已购套餐与项目</span><small>{{ entitlements.length }} 条权益</small></div>
            <div v-if="entitlements.length" class="entitlement-list">
              <article v-for="item in entitlements" :key="item.id" class="entitlement-row">
                <div class="entitlement-heading"><strong>{{ item.projectName || '服务项目' }}</strong><span :class="{ inactive: !['ACTIVE', 'AVAILABLE'].includes(item.status) }">{{ entitlementStatus(item.status) }}</span></div>
                <div class="entitlement-count"><strong>{{ item.availableCount }}</strong><span>次剩余</span><small>共 {{ item.grantedCount }} 次</small></div>
                <p>有效期 {{ formatDate(item.validFrom) }} 至 {{ formatDate(item.validTo) }}</p>
              </article>
            </div>
            <div v-else class="inline-empty"><strong>当前没有套餐权益</strong><p>可能尚未购买、已用完或已退款；请以订单与权益明细为准。</p></div>
          </section>

          <section v-else-if="activeTab === 'AI'" class="content-section" data-testid="sidebar-ai-panel">
            <WecomAiReplyPanel :customer-id="customer.id" :external-userid="context.externalUserid || ''" :chat-entry="chatEntry" />
          </section>

          <section v-else-if="activeTab === 'TOOLS'" class="content-section" data-testid="sidebar-tools-panel">
            <div class="tool-switch" role="tablist" aria-label="员工工具">
              <button type="button" role="tab" :aria-selected="toolTab === 'QUICK_REPLY'" data-testid="quick-reply-tool-tab" @click="toolTab = 'QUICK_REPLY'">快捷话术</button>
              <button type="button" role="tab" :aria-selected="toolTab === 'H5_CARD'" data-testid="h5-card-tool-tab" @click="toolTab = 'H5_CARD'">H5 卡片</button>
            </div>
            <WecomQuickReplyPanel v-if="toolTab === 'QUICK_REPLY'" :customer-id="customer.id" :external-userid="context.externalUserid || ''" :chat-entry="chatEntry" />
            <WecomH5CardPanel v-else :customer-id="customer.id" :external-userid="context.externalUserid || ''" :chat-entry="chatEntry" />
          </section>

          <section v-else class="content-section" data-testid="sidebar-timeline-panel">
            <div class="section-heading"><span>客户跟进时间线</span><small>最近 20 条</small></div>
            <ol v-if="timeline.length" class="timeline-list">
              <li v-for="item in timeline" :key="item.eventKey"><span class="timeline-dot" aria-hidden="true" /><div><div class="timeline-heading"><strong>{{ item.title }}</strong><span>{{ timelineTypeLabel(item.type) }}</span></div><p v-if="item.summary">{{ item.summary }}</p><time>{{ formatDateTime(item.occurredAt) }}</time></div></li>
            </ol>
            <div v-else class="inline-empty"><strong>暂无跟进记录</strong><p>新的跟进、预约、订单和客诉记录会按时间汇总在这里。</p></div>
          </section>
        </div>
        <footer class="sidebar-footer">数据来自 CRM 实时查询 · 仅展示当前授权范围</footer>
      </template>
    </template>
  </main>
</template>

<style scoped>
.wecom-sidebar { --ws-ink: #203c35; --ws-muted: #7d9089; --ws-line: #e4ebe7; --ws-green: #276c59; width: min(100%, 520px); min-height: 100vh; min-height: 100dvh; margin: 0 auto; overflow: hidden; border-inline: 1px solid #e8eeeb; color: var(--ws-ink); background: #f7f9f8; font-size: 13px; }
.sidebar-state { display: flex; min-height: 100vh; min-height: 100dvh; align-items: center; justify-content: center; flex-direction: column; padding: 42px 28px; text-align: center; background: #f7faf8; }
.state-brand { display: grid; width: 52px; height: 52px; place-items: center; margin-bottom: 24px; border-radius: 16px; color: #edf7f2; background: #245d4e; font-size: 23px; font-weight: 760; box-shadow: 0 8px 24px rgb(35 82 66 / 12%); }
.state-eyebrow { margin-bottom: 12px; color: #6f8b80; font-size: 11px; font-weight: 650; letter-spacing: 2px; }
.sidebar-state h1 { margin: 0 0 14px; color: #23473b; font-size: 24px; letter-spacing: -.6px; line-height: 1.35; }
.sidebar-state > p { max-width: 340px; margin: 0; color: #71877e; font-size: 13px; line-height: 1.9; }
.state-guidance { display: grid; gap: 9px; margin-top: 28px; padding: 17px 20px; border: 1px solid #dfe9e4; border-radius: 12px; text-align: left; background: #eef4f0; }
.state-guidance strong { color: #3c6556; font-size: 12px; }
.state-guidance span { color: #71877e; font-size: 12px; line-height: 1.7; }
.state-action { margin-top: 24px; padding: 11px 22px; border: 0; border-radius: 9px; color: #f6faf8; background: #276c59; font: inherit; font-weight: 650; cursor: pointer; }
.loading-lines { display: grid; gap: 10px; width: 100%; max-width: 300px; margin-top: 34px; }
.loading-lines i { display: block; height: 12px; border-radius: 6px; background: #e5ede9; animation: ws-pulse 1.3s ease-in-out infinite alternate; }
.loading-lines i:nth-child(2) { width: 82%; animation-delay: .15s; }
.loading-lines i:nth-child(3) { width: 60%; animation-delay: .3s; }
.customer-hero { padding: 18px 22px 20px; color: #edf5f1; background: #1e453a; }
.assistant-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 26px; color: #c7dbd1; font-size: 11px; font-weight: 650; letter-spacing: .5px; }
.assistant-brand { display: grid; width: 25px; height: 25px; place-items: center; border: 1px solid #6f9686; border-radius: 8px; color: #e4f0e9; font-size: 12px; }
.assistant-bar button { margin-left: auto; padding: 4px 0 4px 12px; border: 0; color: #d6e6de; background: transparent; font: inherit; font-size: 11px; cursor: pointer; }
.customer-identity { display: flex; align-items: center; gap: 15px; }
.customer-avatar { display: grid; width: 52px; height: 52px; flex: 0 0 52px; place-items: center; border-radius: 50%; color: #2c5948; background: #dcebe2; font-size: 22px; font-weight: 740; }
.customer-name-row { display: flex; align-items: center; gap: 9px; }
.customer-name-row h1 { margin: 0; color: #f3f8f5; font-size: 25px; letter-spacing: -.5px; }
.customer-tier { padding: 3px 7px; border: 1px solid #8b9f82; border-radius: 5px; color: #e2e8cd; font-size: 10px; }
.customer-identity p { margin: 6px 0 0; color: #b7cdc1; font-size: 12px; letter-spacing: .5px; }
.customer-owner { display: flex; justify-content: space-between; gap: 12px; margin-top: 20px; padding-top: 14px; border-top: 1px solid rgb(223 239 231 / 13%); color: #b7cdc1; font-size: 10px; }
.unlinked-hero .state-eyebrow { color: #aac8ba; }
.unlinked-hero h1 { margin: 0 0 10px; color: #f0f6f2; font-size: 23px; }
.unlinked-hero p { margin: 0; color: #b7cdc1; font-size: 12px; line-height: 1.8; }
.summary-strip { display: grid; grid-template-columns: repeat(3, 1fr); margin: 16px 18px 0; padding: 17px 0; border: 1px solid var(--ws-line); border-radius: 12px; background: #fdfefd; }
.summary-strip > div { display: flex; align-items: center; flex-direction: column; gap: 5px; border-right: 1px solid var(--ws-line); }
.summary-strip > div:last-child { border-right: 0; }
.summary-strip strong { color: #285f4e; font-size: 25px; font-weight: 740; font-variant-numeric: tabular-nums; }
.summary-strip span { color: #82958d; font-size: 10px; }
.sidebar-tabs { position: sticky; top: 0; z-index: 2; display: flex; gap: 24px; margin-top: 18px; padding: 0 22px; border-bottom: 1px solid var(--ws-line); background: #f7f9f8; }
.sidebar-tabs button { position: relative; min-width: 46px; padding: 13px 4px; border: 0; color: #80948b; background: transparent; font: inherit; font-weight: 650; cursor: pointer; }
.sidebar-tabs button[aria-selected='true'] { color: #276c59; }
.sidebar-tabs button[aria-selected='true']::after { position: absolute; right: 4px; bottom: -1px; left: 4px; height: 2px; background: #347762; content: ''; }
.tool-switch { display: flex; gap: 6px; margin-bottom: 20px; padding: 4px; border-radius: 9px; background: #eaf0ec; }
.tool-switch button { flex: 1; padding: 8px 10px; border: 0; border-radius: 6px; color: #758e81; background: transparent; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.tool-switch button[aria-selected='true'] { color: #315f4b; background: #fcfefc; box-shadow: 0 1px 4px rgb(38 79 59 / 7%); }
.auxiliary-notice { margin: 14px 18px 0; padding: 10px 12px; border-radius: 8px; color: #8b6d43; background: #f7f1e6; font-size: 11px; line-height: 1.7; }
.sidebar-content { padding: 0 18px 22px; }
.content-section { padding-top: 20px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-heading > span { color: #2d4e42; font-size: 14px; font-weight: 700; }
.section-heading small { color: #94a49d; font-size: 10px; }
.profile-list { margin: 0; padding: 4px 16px; border: 1px solid var(--ws-line); border-radius: 12px; background: #fdfefd; }
.profile-list > div { display: flex; justify-content: space-between; gap: 20px; padding: 13px 0; border-bottom: 1px solid #edf1ef; }
.profile-list > div:last-child { border-bottom: 0; }
.profile-list dt { color: #84978e; font-size: 11px; }
.profile-list dd { margin: 0; color: #38574b; font-size: 12px; font-weight: 600; text-align: right; }
.profile-tags { margin-top: 18px; }
.profile-tags > span { display: block; margin-bottom: 10px; color: #84978e; font-size: 11px; }
.profile-tags > div { display: flex; gap: 7px; flex-wrap: wrap; }
.profile-tags > div span { padding: 5px 8px; border: 1px solid #dfe9e4; border-radius: 6px; color: #527165; background: #f0f5f2; font-size: 10px; }
.profile-tags p { margin: 0; color: #9aa9a2; font-size: 11px; }
.privacy-note { margin: 22px 0 0; color: #97a69f; font-size: 10px; line-height: 1.8; }
.entitlement-list { border-top: 1px solid var(--ws-line); }
.entitlement-row { padding: 18px 0; border-bottom: 1px solid var(--ws-line); }
.entitlement-heading { display: flex; justify-content: space-between; gap: 12px; }
.entitlement-heading strong { color: #2d4e42; font-size: 14px; }
.entitlement-heading span { color: #487864; font-size: 10px; }
.entitlement-heading span.inactive { color: #98a49f; }
.entitlement-count { display: flex; align-items: baseline; gap: 5px; margin-top: 12px; }
.entitlement-count strong { color: #2c6b56; font-size: 27px; font-weight: 730; }
.entitlement-count > span { color: #6e877c; font-size: 11px; }
.entitlement-count small { margin-left: auto; color: #94a49d; font-size: 10px; }
.entitlement-row > p { margin: 9px 0 0; color: #8a9b94; font-size: 10px; }
.timeline-list { margin: 0; padding: 0; list-style: none; }
.timeline-list li { position: relative; display: flex; gap: 14px; padding-bottom: 22px; }
.timeline-list li::before { position: absolute; top: 11px; bottom: -2px; left: 5px; width: 1px; background: #dbe6e0; content: ''; }
.timeline-list li:last-child::before { display: none; }
.timeline-dot { position: relative; z-index: 1; width: 11px; height: 11px; flex: 0 0 11px; margin-top: 3px; border: 3px solid #d8e7df; border-radius: 50%; background: #497b66; }
.timeline-list li > div { min-width: 0; flex: 1; }
.timeline-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.timeline-heading strong { color: #38574b; font-size: 12px; line-height: 1.6; }
.timeline-heading > span { flex: 0 0 auto; padding: 2px 5px; border-radius: 4px; color: #738d81; background: #eaf1ed; font-size: 9px; }
.timeline-list p { margin: 5px 0; color: #768d82; font-size: 11px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.timeline-list time { color: #a0aca7; font-size: 10px; }
.inline-empty { padding: 38px 20px; border: 1px dashed #d8e3de; border-radius: 12px; text-align: center; }
.inline-empty strong { color: #547166; font-size: 13px; }
.inline-empty p { margin: 8px 0 0; color: #91a29a; font-size: 11px; line-height: 1.8; }
.sidebar-footer { padding: 10px 20px 26px; color: #a1aea9; font-size: 9px; text-align: center; }
@keyframes ws-pulse { from { opacity: .55; } to { opacity: 1; } }
@media (min-width: 521px) { .wecom-sidebar { box-shadow: 0 0 36px rgb(35 66 54 / 7%); } }
@media (max-width: 350px) { .customer-hero { padding-inline: 18px; } .summary-strip { margin-inline: 12px; } .sidebar-content { padding-inline: 14px; } .sidebar-tabs { padding-inline: 18px; } }
@media (prefers-reduced-motion: reduce) { .loading-lines i { animation: none; } }
</style>
