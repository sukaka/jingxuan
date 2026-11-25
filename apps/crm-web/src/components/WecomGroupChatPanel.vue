<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getCustomer } from '../api/customer'
import type { CustomerDetail } from '../api/customer'
import { getCustomerTimeline } from '../api/customerTimeline'
import type { CustomerTimelineItem } from '../api/customerTimeline'
import { listEntitlements } from '../api/trading'
import type { Entitlement } from '../api/trading'
import type { WecomSidebarGroupContext, WecomSidebarGroupCustomer } from '../api/wecomSidebar'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import WecomH5CardPanel from './WecomH5CardPanel.vue'
import WecomMaterialPanel from './WecomMaterialPanel.vue'
import WecomQuickActionsPanel from './WecomQuickActionsPanel.vue'
import WecomQuickReplyPanel from './WecomQuickReplyPanel.vue'

type GroupTab = 'MEMBERS' | 'TOOLS'
type MemberSubTab = 'PROFILE' | 'PACKAGES' | 'TIMELINE'
type GroupToolTab = 'QUICK_REPLY' | 'H5_CARD' | 'MATERIAL'

const props = defineProps<{
  groupContext: WecomSidebarGroupContext | null
  chatId: string
  chatEntry: string
  loading?: boolean
  errorMessage?: string
}>()

const emit = defineEmits<{
  reload: []
  'context-mismatch': []
}>()

const auth = useAuthStore()
const activeTab = ref<GroupTab>('MEMBERS')
const memberSubTab = ref<MemberSubTab>('PROFILE')
const toolTab = ref<GroupToolTab>('QUICK_REPLY')
const memberKeyword = ref('')

const selectedMember = ref<WecomSidebarGroupCustomer | null>(null)
const selectedCustomerDetail = ref<CustomerDetail | null>(null)
const entitlements = ref<Entitlement[]>([])
const timeline = ref<CustomerTimelineItem[]>([])
const memberLoading = ref(false)
const memberError = ref('')
const memberAuxNotice = ref('')
let loadSequence = 0

const effectiveChatId = computed(() => props.groupContext?.chatId || props.chatId || '')
const customers = computed(() => props.groupContext?.customers ?? [])
const filteredCustomers = computed(() => {
  const kw = memberKeyword.value.trim().toLowerCase()
  if (!kw) return customers.value
  return customers.value.filter((item) =>
    (item.displayName || '').toLowerCase().includes(kw)
    || (item.nickname || '').toLowerCase().includes(kw)
    || item.customerNo.toLowerCase().includes(kw)
    || (item.mobileMasked || '').toLowerCase().includes(kw),
  )
})

const roomTitle = computed(() => props.groupContext?.room?.displayName || '外部客户群')
const groupKeepAliveKey = computed(() => effectiveChatId.value || 'group')

watch(
  () => [props.chatId, props.groupContext?.chatId],
  () => {
    clearMember()
    memberKeyword.value = ''
    memberSubTab.value = 'PROFILE'
  },
)

function memberName(member: WecomSidebarGroupCustomer): string {
  return member.displayName || member.nickname || member.customerNo
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

const INTENT_LABELS: Record<string, string> = {
  HIGH: '高意向',
  MEDIUM: '中意向',
  LOW: '低意向',
}

const TAG_LABELS: Record<string, string> = {
  VIP: '贵宾客户',
  HIGH_INTENT: '高意向',
  NEW_7D: '近 7 天新增',
  REFERRAL_GOOD: '优质转介绍',
  RISK: '风险预警',
}

function intentLabel(value: string | null | undefined): string {
  if (!value) return '未评估'
  return INTENT_LABELS[value] ?? '其他意向'
}

function tagLabel(code: string): string {
  return TAG_LABELS[code] ?? code
}

function entitlementStatus(statusCode: string): string {
  return ({ ACTIVE: '生效中', AVAILABLE: '生效中', DEPLETED: '已用完', EXHAUSTED: '已用完', EXPIRED: '已到期', REFUNDED: '已退款', REVOKED: '已收回' } as Record<string, string>)[statusCode] ?? '已结束'
}

function timelineTypeLabel(type: string): string {
  return ({ FOLLOWUP: '跟进', INTENT: '意向', BLOCKER: '阻碍', NEXT_FOLLOWUP: '待跟进', TASK: '待办', OWNERSHIP: '归属', ORDER: '订单', APPOINTMENT: '预约', REDEMPTION: '核销', REFUND: '退款', TIER: '等级', COMPLAINT: '客诉' } as Record<string, string>)[type] ?? '记录'
}

async function loadSelectedCustomer(member: WecomSidebarGroupCustomer): Promise<void> {
  const seq = ++loadSequence
  memberLoading.value = true
  memberError.value = ''
  memberAuxNotice.value = ''
  selectedCustomerDetail.value = null
  entitlements.value = []
  timeline.value = []

  try {
    if (auth.hasPermission('customer:read')) {
      const detail = await getCustomer(member.customerId)
      if (seq !== loadSequence) return
      selectedCustomerDetail.value = detail
    }
    const tasks: Promise<void>[] = []
    if (auth.hasPermission('entitlement:read')) {
      tasks.push((async () => {
        try {
          const page = await listEntitlements({ customerId: member.customerId, page: 1, size: 50 })
          if (seq === loadSequence) entitlements.value = page.items
        } catch {
          if (seq === loadSequence) memberAuxNotice.value = '套餐权益暂时不可用，基础档案仍可查看。'
        }
      })())
    }
    if (auth.hasPermission('customer:read')) {
      tasks.push((async () => {
        try {
          const res = await getCustomerTimeline(member.customerId, { size: 20 })
          if (seq === loadSequence) timeline.value = res.items
        } catch {
          if (seq === loadSequence) memberAuxNotice.value = '跟进时间线暂时不可用，基础档案仍可查看。'
        }
      })())
    }
    await Promise.all(tasks)
  } catch (error) {
    if (seq === loadSequence) {
      memberError.value = error instanceof ApiError || error instanceof Error ? error.message : '加载客户档案失败'
    }
  } finally {
    if (seq === loadSequence) memberLoading.value = false
  }
}

function selectMember(member: WecomSidebarGroupCustomer): void {
  selectedMember.value = member
  memberSubTab.value = 'PROFILE'
  void loadSelectedCustomer(member)
}

function clearMember(): void {
  loadSequence++
  memberLoading.value = false
  selectedMember.value = null
  selectedCustomerDetail.value = null
  entitlements.value = []
  timeline.value = []
  memberError.value = ''
  memberAuxNotice.value = ''
}

function handleToolMemberChange(event: Event): void {
  const raw = (event.target as HTMLSelectElement).value
  if (!raw) {
    clearMember()
    return
  }
  const id = Number(raw)
  const hit = customers.value.find((item) => item.customerId === id) ?? null
  if (!hit) {
    clearMember()
    return
  }
  if (selectedMember.value?.customerId === hit.customerId && selectedCustomerDetail.value) {
    return
  }
  selectMember(hit)
}

function refreshSelectedCustomer(): void {
  if (selectedMember.value) {
    void loadSelectedCustomer(selectedMember.value)
  }
}
</script>

<template>
  <div class="group-chat-panel" data-testid="group-chat-state">
    <header class="group-hero">
      <div class="assistant-bar">
        <span class="assistant-brand">鲸</span>
        <span>企微群聊助手</span>
        <button type="button" aria-label="刷新当前群聊" data-testid="group-reload-btn" @click="emit('reload')">刷新</button>
      </div>
      <div class="group-title-row">
        <h1>{{ roomTitle }}</h1>
        <span v-if="groupContext?.room?.status" class="group-badge">客户群</span>
      </div>
      <p v-if="groupContext?.room" class="group-stats">
        <span>员工 {{ groupContext.room.staffCount }}</span>
        <span>外部联系人 {{ groupContext.room.externalCount }}</span>
        <span>已关联 CRM {{ groupContext.room.linkedCustomerCount }}</span>
      </p>
      <p v-else-if="groupContext?.contextStatus === 'ROOM_UNREGISTERED'" class="group-notice-hero">
        当前外部群尚未在 CRM 企微群管理中登记，仍可使用下方群发工具。
      </p>
      <p v-else class="group-notice-hero">
        群聊不自动对应单一客户；请选择群内已关联客户查看档案，或直接使用群发工具。
      </p>
    </header>

    <nav class="group-tabs" role="tablist" aria-label="群聊功能切换">
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'MEMBERS'"
        data-testid="group-members-tab"
        @click="activeTab = 'MEMBERS'"
      >
        群内客户 ({{ customers.length }})
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'TOOLS'"
        data-testid="group-tools-tab"
        @click="activeTab = 'TOOLS'"
      >
        群发工具
      </button>
    </nav>

    <div class="group-content">
      <div v-if="errorMessage" class="group-alert" role="alert">
        {{ errorMessage }}
        <button type="button" @click="emit('reload')">重试</button>
      </div>
      <div v-if="loading" class="group-empty">正在读取群聊与群内客户信息…</div>

      <!-- Tab 1: Group Linked CRM Customers -->
      <section v-else-if="activeTab === 'MEMBERS'" data-testid="group-members-panel">
        <template v-if="selectedMember">
          <div class="member-back-bar">
            <button type="button" data-testid="group-member-back" @click="clearMember">← 返回群内客户</button>
            <button type="button" class="to-tools-link" @click="activeTab = 'TOOLS'">用此客户发工具 →</button>
          </div>

          <div class="member-detail-head">
            <span class="member-avatar">{{ memberName(selectedMember).slice(0, 1) }}</span>
            <div>
              <div class="member-name-row">
                <strong>{{ memberName(selectedMember) }}</strong>
                <span v-if="selectedMember.tierCode" class="member-tier">{{ selectedMember.tierCode }} 级</span>
              </div>
              <small>{{ selectedMember.customerNo }} · {{ selectedMember.mobileMasked || '未登记手机号' }} · 管家 {{ selectedMember.ownerName || '待分配' }}</small>
            </div>
          </div>

          <div class="member-subtabs" role="tablist" aria-label="客户详情标签">
            <button type="button" role="tab" :aria-selected="memberSubTab === 'PROFILE'" data-testid="group-member-profile-tab" @click="memberSubTab = 'PROFILE'">档案</button>
            <button type="button" role="tab" :aria-selected="memberSubTab === 'PACKAGES'" data-testid="group-member-packages-tab" @click="memberSubTab = 'PACKAGES'">套餐 ({{ entitlements.length }})</button>
            <button type="button" role="tab" :aria-selected="memberSubTab === 'TIMELINE'" data-testid="group-member-timeline-tab" @click="memberSubTab = 'TIMELINE'">跟进</button>
          </div>

          <div v-if="memberLoading" class="group-empty">正在读取客户详情…</div>
          <div v-else-if="memberError" class="group-alert" role="alert">{{ memberError }}</div>
          <template v-else>
            <div v-if="memberAuxNotice" class="aux-notice" role="status">{{ memberAuxNotice }}</div>

            <div v-if="memberSubTab === 'PROFILE'" data-testid="group-member-profile-panel">
              <WecomQuickActionsPanel
                v-if="selectedCustomerDetail"
                :customer="selectedCustomerDetail"
                :external-userid="selectedMember.externalUserid"
                @updated="refreshSelectedCustomer"
              />
              <dl v-if="selectedCustomerDetail" class="profile-list">
                <div><dt>客户等级</dt><dd>{{ selectedCustomerDetail.tierCode || '未分级' }}</dd></div>
                <div><dt>意向程度</dt><dd>{{ intentLabel(selectedCustomerDetail.intentLevel) }}</dd></div>
                <div><dt>归属管家</dt><dd>{{ selectedCustomerDetail.ownerName || '待分配' }}</dd></div>
                <div><dt>首次下单</dt><dd>{{ formatDate(selectedCustomerDetail.firstOrderAt) }}</dd></div>
                <div><dt>最近跟进</dt><dd>{{ formatDate(selectedCustomerDetail.lastFollowupAt) }}</dd></div>
              </dl>
              <div v-if="selectedCustomerDetail" class="profile-tags">
                <span>客户标签</span>
                <div v-if="selectedCustomerDetail.tagCodes.length">
                  <span v-for="tag in selectedCustomerDetail.tagCodes" :key="tag">{{ tagLabel(tag) }}</span>
                </div>
                <p v-else>暂未设置标签</p>
              </div>
            </div>

            <div v-else-if="memberSubTab === 'PACKAGES'" data-testid="group-member-packages-panel">
              <div v-if="entitlements.length" class="entitlement-list">
                <article v-for="item in entitlements" :key="item.id" class="entitlement-row">
                  <div class="entitlement-heading">
                    <strong>{{ item.projectName || '服务项目' }}</strong>
                    <span :class="{ inactive: !['ACTIVE', 'AVAILABLE'].includes(item.status) }">{{ entitlementStatus(item.status) }}</span>
                  </div>
                  <div class="entitlement-count">
                    <strong>{{ item.availableCount }}</strong><span>次剩余</span><small>共 {{ item.grantedCount }} 次</small>
                  </div>
                  <p>有效期 {{ formatDate(item.validFrom) }} 至 {{ formatDate(item.validTo) }}</p>
                </article>
              </div>
              <div v-else class="group-empty">当前客户暂无生效套餐权益。</div>
            </div>

            <div v-else data-testid="group-member-timeline-panel">
              <ol v-if="timeline.length" class="timeline-list">
                <li v-for="item in timeline" :key="item.eventKey">
                  <span class="timeline-dot" aria-hidden="true" />
                  <div>
                    <div class="timeline-heading"><strong>{{ item.title }}</strong><span>{{ timelineTypeLabel(item.type) }}</span></div>
                    <p v-if="item.summary">{{ item.summary }}</p>
                    <time>{{ formatDateTime(item.occurredAt) }}</time>
                  </div>
                </li>
              </ol>
              <div v-else class="group-empty">暂无跟进记录。</div>
            </div>
          </template>
        </template>

        <template v-else>
          <input
            v-if="customers.length > 3"
            v-model="memberKeyword"
            class="member-search"
            data-testid="group-member-search"
            placeholder="搜索群内已关联客户姓名 / 编号"
          >
          <div v-if="filteredCustomers.length" class="member-list">
            <button
              v-for="member in filteredCustomers"
              :key="member.customerId"
              type="button"
              class="member-card"
              :data-testid="`group-member-${member.customerId}`"
              @click="selectMember(member)"
            >
              <span class="member-avatar">{{ memberName(member).slice(0, 1) }}</span>
              <div class="member-meta">
                <div class="member-name-row">
                  <strong>{{ memberName(member) }}</strong>
                  <span v-if="member.tierCode" class="member-tier">{{ member.tierCode }} 级</span>
                </div>
                <small>{{ member.customerNo }} · {{ member.mobileMasked || '未登记手机' }} · {{ member.ownerName || '待分配' }}</small>
              </div>
              <span class="member-arrow" aria-hidden="true">›</span>
            </button>
          </div>
          <div v-else class="group-empty">
            <strong>暂无可展示的群内 CRM 客户</strong>
            <p>仅展示已在 CRM 关联且位于当前账号数据权限范围内的客户，可切换至「群发工具」直接发送通用话术或素材。</p>
          </div>
        </template>
      </section>

      <!-- Tab 2: Group Chat Sending Tools (KeepAlive keyed by chatId survives MEMBERS <-> TOOLS switch) -->
      <section v-show="!loading && activeTab === 'TOOLS'" data-testid="group-tools-panel">
        <div v-if="customers.length" class="tool-context-picker">
          <label for="group-tool-customer">参考客户（H5 卡片 / 变量话术可选）</label>
          <select
            id="group-tool-customer"
            :value="selectedMember?.customerId ?? ''"
            data-testid="group-tool-customer-select"
            @change="handleToolMemberChange"
          >
            <option value="">不指定客户（仅发送通用内容）</option>
            <option v-for="c in customers" :key="c.customerId" :value="c.customerId">
              {{ memberName(c) }}（{{ c.customerNo }}）
            </option>
          </select>
        </div>

        <div class="tool-switch" role="tablist" aria-label="群聊员工工具">
          <button type="button" role="tab" :aria-selected="toolTab === 'QUICK_REPLY'" data-testid="group-quick-reply-tab" @click="toolTab = 'QUICK_REPLY'">快捷话术</button>
          <button type="button" role="tab" :aria-selected="toolTab === 'H5_CARD'" data-testid="group-h5-card-tab" @click="toolTab = 'H5_CARD'">H5 卡片</button>
          <button type="button" role="tab" :aria-selected="toolTab === 'MATERIAL'" data-testid="group-material-tab" @click="toolTab = 'MATERIAL'">素材库</button>
        </div>

        <KeepAlive :key="groupKeepAliveKey">
          <WecomQuickReplyPanel
            v-if="activeTab === 'TOOLS' && toolTab === 'QUICK_REPLY'"
            :key="`gqr-${groupKeepAliveKey}`"
            :customer-id="selectedMember?.customerId ?? null"
            :external-userid="selectedMember?.externalUserid ?? ''"
            :chat-id="effectiveChatId"
            :chat-entry="chatEntry"
            @context-mismatch="emit('context-mismatch')"
          />
          <WecomH5CardPanel
            v-else-if="activeTab === 'TOOLS' && toolTab === 'H5_CARD'"
            :key="`gh5-${groupKeepAliveKey}`"
            :customer-id="selectedMember?.customerId ?? null"
            :external-userid="selectedMember?.externalUserid ?? ''"
            :chat-id="effectiveChatId"
            :chat-entry="chatEntry"
            @context-mismatch="emit('context-mismatch')"
          />
          <WecomMaterialPanel
            v-else-if="activeTab === 'TOOLS' && toolTab === 'MATERIAL'"
            :key="`gmat-${groupKeepAliveKey}`"
            :customer-id="selectedMember?.customerId ?? null"
            :external-userid="selectedMember?.externalUserid ?? ''"
            :chat-id="effectiveChatId"
            :chat-entry="chatEntry"
            @context-mismatch="emit('context-mismatch')"
          />
        </KeepAlive>
      </section>
    </div>

    <footer class="group-footer">群聊数据仅展示当前授权范围 · 群发消息由员工手动确认</footer>
  </div>
</template>

<style scoped>
.group-chat-panel { color: #203c35; }
.group-hero { padding: 18px 22px 20px; color: #edf5f1; background: #1e453a; }
.assistant-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 20px; color: #c7dbd1; font-size: 11px; font-weight: 650; letter-spacing: .5px; }
.assistant-brand { display: grid; width: 25px; height: 25px; place-items: center; border: 1px solid #6f9686; border-radius: 8px; color: #e4f0e9; font-size: 12px; }
.assistant-bar button { margin-left: auto; padding: 4px 0 4px 12px; border: 0; color: #d6e6de; background: transparent; font: inherit; font-size: 11px; cursor: pointer; }
.group-title-row { display: flex; align-items: center; gap: 10px; }
.group-title-row h1 { margin: 0; color: #f3f8f5; font-size: 22px; letter-spacing: -.4px; }
.group-badge { padding: 3px 7px; border: 1px solid #86a797; border-radius: 5px; color: #d8ece1; font-size: 10px; white-space: nowrap; }
.group-stats { display: flex; gap: 14px; flex-wrap: wrap; margin: 14px 0 0; padding-top: 12px; border-top: 1px solid rgb(223 239 231 / 14%); color: #b7cdc1; font-size: 11px; }
.group-notice-hero { margin: 10px 0 0; color: #b7cdc1; font-size: 11px; line-height: 1.75; }
.group-tabs { position: sticky; top: 0; z-index: 2; display: flex; gap: 24px; padding: 0 22px; border-bottom: 1px solid #e4ebe7; background: #f7f9f8; }
.group-tabs button { position: relative; padding: 13px 4px; border: 0; color: #80948b; background: transparent; font: inherit; font-weight: 650; cursor: pointer; }
.group-tabs button[aria-selected='true'] { color: #276c59; }
.group-tabs button[aria-selected='true']::after { position: absolute; right: 2px; bottom: -1px; left: 2px; height: 2px; background: #347762; content: ''; }
.group-content { padding: 18px; }
.group-alert { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 14px; padding: 10px 12px; border-radius: 8px; color: #9c5240; background: #fff0eb; font-size: 11px; }
.group-alert button { padding: 3px 8px; border: 1px solid #d5a99c; border-radius: 5px; color: #9c5240; background: transparent; font: inherit; font-size: 10px; cursor: pointer; }
.aux-notice { margin-bottom: 12px; padding: 9px 11px; border-radius: 8px; color: #8b6d43; background: #f7f1e6; font-size: 11px; }
.member-search { box-sizing: border-box; width: 100%; min-height: 36px; margin-bottom: 12px; padding: 7px 11px; border: 1px solid #d5e2da; border-radius: 8px; color: #2a4c3e; background: #fdfefd; font: inherit; font-size: 12px; }
.member-list { display: grid; gap: 9px; }
.member-card { display: flex; align-items: center; gap: 12px; width: 100%; padding: 12px; border: 1px solid #e0e8e3; border-radius: 10px; color: #26483b; background: #fdfefd; font: inherit; text-align: left; cursor: pointer; }
.member-card:hover { border-color: #93b8a6; background: #f3f8f5; }
.member-avatar { display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 50%; color: #2a5948; background: #dcebe2; font-size: 16px; font-weight: 700; }
.member-meta { min-width: 0; flex: 1; }
.member-name-row { display: flex; align-items: center; gap: 7px; }
.member-name-row strong { font-size: 13px; color: #224438; }
.member-tier { padding: 2px 6px; border-radius: 4px; color: #396d57; background: #e8f3ed; font-size: 10px; }
.member-meta small, .member-detail-head small { display: block; margin-top: 3px; color: #81958b; font-size: 10px; overflow-wrap: anywhere; }
.member-arrow { color: #8ba096; font-size: 18px; }
.member-back-bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.member-back-bar button { padding: 4px 0; border: 0; color: #2c6b56; background: transparent; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.to-tools-link { color: #5b7c6d !important; font-weight: 500 !important; }
.member-detail-head { display: flex; align-items: center; gap: 12px; padding: 12px; border: 1px solid #e0e8e3; border-radius: 10px; background: #fdfefd; margin-bottom: 12px; }
.member-subtabs { display: flex; gap: 6px; margin-bottom: 14px; padding: 4px; border-radius: 8px; background: #eaf0ec; }
.member-subtabs button { flex: 1; padding: 7px 8px; border: 0; border-radius: 6px; color: #758e81; background: transparent; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.member-subtabs button[aria-selected='true'] { color: #2e5d4a; background: #fcfefc; }
.profile-list { margin: 0; padding: 4px 14px; border: 1px solid #e4ebe7; border-radius: 10px; background: #fdfefd; }
.profile-list > div { display: flex; justify-content: space-between; gap: 16px; padding: 11px 0; border-bottom: 1px solid #edf1ef; }
.profile-list > div:last-child { border-bottom: 0; }
.profile-list dt { color: #84978e; font-size: 11px; }
.profile-list dd { margin: 0; color: #38574b; font-size: 12px; font-weight: 600; text-align: right; }
.profile-tags { margin-top: 14px; }
.profile-tags > span { display: block; margin-bottom: 8px; color: #84978e; font-size: 11px; }
.profile-tags > div { display: flex; gap: 6px; flex-wrap: wrap; }
.profile-tags > div span { padding: 4px 8px; border: 1px solid #dfe9e4; border-radius: 6px; color: #527165; background: #f0f5f2; font-size: 10px; }
.profile-tags p { margin: 0; color: #9aa9a2; font-size: 11px; }
.entitlement-list { border-top: 1px solid #e4ebe7; }
.entitlement-row { padding: 14px 0; border-bottom: 1px solid #e4ebe7; }
.entitlement-heading { display: flex; justify-content: space-between; gap: 12px; }
.entitlement-heading strong { color: #2d4e42; font-size: 13px; }
.entitlement-heading span { color: #487864; font-size: 10px; }
.entitlement-heading span.inactive { color: #98a49f; }
.entitlement-count { display: flex; align-items: baseline; gap: 5px; margin-top: 8px; }
.entitlement-count strong { color: #2c6b56; font-size: 24px; font-weight: 730; }
.entitlement-count > span { color: #6e877c; font-size: 11px; }
.entitlement-count small { margin-left: auto; color: #94a49d; font-size: 10px; }
.entitlement-row > p { margin: 7px 0 0; color: #8a9b94; font-size: 10px; }
.timeline-list { margin: 0; padding: 0; list-style: none; }
.timeline-list li { position: relative; display: flex; gap: 12px; padding-bottom: 18px; }
.timeline-list li::before { position: absolute; top: 11px; bottom: -2px; left: 5px; width: 1px; background: #dbe6e0; content: ''; }
.timeline-list li:last-child::before { display: none; }
.timeline-dot { position: relative; z-index: 1; width: 11px; height: 11px; flex: 0 0 11px; margin-top: 3px; border: 3px solid #d8e7df; border-radius: 50%; background: #497b66; }
.timeline-list li > div { min-width: 0; flex: 1; }
.timeline-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.timeline-heading strong { color: #38574b; font-size: 12px; line-height: 1.5; }
.timeline-heading > span { flex: 0 0 auto; padding: 2px 5px; border-radius: 4px; color: #738d81; background: #eaf1ed; font-size: 9px; }
.timeline-list p { margin: 4px 0; color: #768d82; font-size: 11px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.timeline-list time { color: #a0aca7; font-size: 10px; }
.tool-context-picker { display: grid; gap: 6px; margin-bottom: 14px; }
.tool-context-picker label { color: #547163; font-size: 11px; font-weight: 600; }
.tool-context-picker select { box-sizing: border-box; width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid #d5e2da; border-radius: 8px; color: #2b4d3f; background: #fdfefd; font: inherit; font-size: 12px; }
.tool-switch { display: flex; gap: 6px; margin-bottom: 18px; padding: 4px; border-radius: 9px; background: #eaf0ec; }
.tool-switch button { flex: 1; padding: 8px 6px; border: 0; border-radius: 6px; color: #758e81; background: transparent; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.tool-switch button[aria-selected='true'] { color: #315f4b; background: #fcfefc; box-shadow: 0 1px 4px rgb(38 79 59 / 7%); }
.group-empty { padding: 30px 16px; border: 1px dashed #d8e3de; border-radius: 10px; color: #84988e; font-size: 11px; text-align: center; line-height: 1.75; }
.group-empty strong { display: block; margin-bottom: 6px; color: #4d6b5e; font-size: 12px; }
.group-empty p { margin: 0; }
.group-footer { padding: 10px 20px 24px; color: #a1aea9; font-size: 9px; text-align: center; }
</style>
