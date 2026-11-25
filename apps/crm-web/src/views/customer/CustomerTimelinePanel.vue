<script setup lang="ts">
import { ref, watch } from 'vue'
import type { CustomerTimelineItem, CustomerTimelineType } from '../../api/customerTimeline'
import { getCustomerTimeline } from '../../api/customerTimeline'
import { fenToYuan, formatDateTime, useDictionaries } from './shared'

const props = defineProps<{ customerId: number }>()
const dicts = useDictionaries()
void dicts.load('communicationChannels')
const items = ref<CustomerTimelineItem[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let loadSequence = 0

const typeLabels: Record<CustomerTimelineType, string> = {
  FOLLOWUP: '跟进', INTENT: '意向', BLOCKER: '卡点', NEXT_FOLLOWUP: '下一步', TASK: '任务',
  OWNERSHIP: '归属', ORDER: '订单', APPOINTMENT: '预约', REDEMPTION: '核销', REFUND: '退款',
  TIER: '等级', COMPLAINT: '客诉',
}

const CHANNEL_FALLBACKS: Record<string, string> = {
  WECOM: '企业微信',
  PHONE: '电话',
  VISIT: '到院面谈',
  WECHAT: '微信',
  SMS: '短信',
}

const STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  PROCESSING: '处理中',
  ASSIGNED: '处理中',
  DONE: '已完成',
  RESOLVED: '已处理',
  CLOSED: '已关闭',
  DISMISSED: '已驳回',
  CANCELED: '已取消',
  CANCELLED: '已取消',
  DRAFT: '草稿',
  PENDING: '待处理',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  PARTIAL_PAID: '部分支付',
  REFUNDED: '已退款',
  PARTIAL_REFUNDED: '部分退款',
  CONFIRMED: '已确认',
  CHECKED_IN: '已到院',
  SERVICE_DONE: '已完成服务',
  COMPLETED: '已完成',
  NO_SHOW: '未到院',
  PENDING_INSTITUTION: '待机构确认',
  POSTED: '机构已确认',
  REJECTED: '已退回',
  REVERSED: '已冲正',
  APPROVED: '已批准',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
}

const TIER_CHANGE_LABELS: Record<string, string> = {
  AUTO_UPGRADE: '自动升级',
  DOWNGRADE_APPROVED: '降级复核通过',
  MANUAL_ADJUST: '人工调整',
}

function formatDetailEntry(key: string, value: unknown, details: Record<string, unknown>): string | null {
  if (value === null || value === undefined || value === '') return null
  if (key === 'oldOwnerId' && details.oldOwnerName) return null
  if (key === 'newOwnerId' && details.newOwnerName) return null
  switch (key) {
    case 'channelCode': {
      const code = String(value)
      const hit = dicts.options('communicationChannels').find((item) => item.code === code)
      return `沟通方式：${hit?.label ?? CHANNEL_FALLBACKS[code] ?? '其他方式'}`
    }
    case 'estimatedAmountFen':
      return `预计金额：¥${fenToYuan(Number(value))}`
    case 'followupId':
      return `跟进记录 #${String(value)}`
    case 'nextFollowupAt':
      return `计划时间：${formatDateTime(String(value))}`
    case 'status': {
      const code = String(value)
      return `状态：${STATUS_LABELS[code] ?? '未知状态'}`
    }
    case 'assigneeUserId':
      return `处理人：用户 #${String(value)}`
    case 'dueAt':
      return `截止时间：${formatDateTime(String(value))}`
    case 'oldOwnerName':
      return `原管家：${String(value)}`
    case 'oldOwnerId':
      return `原管家：#${String(value)}`
    case 'newOwnerName':
      return `新管家：${String(value)}`
    case 'newOwnerId':
      return `新管家：#${String(value)}`
    case 'payableFen':
      return `应付金额：¥${fenToYuan(Number(value))}`
    case 'paidFen':
      return `实付金额：¥${fenToYuan(Number(value))}`
    case 'refundedFen':
      return `已退金额：¥${fenToYuan(Number(value))}`
    case 'requestedFen':
      return `申请退款：¥${fenToYuan(Number(value))}`
    case 'approvedFen':
      return `批准退款：¥${fenToYuan(Number(value))}`
    case 'succeededFen':
      return `实际退款：¥${fenToYuan(Number(value))}`
    case 'scheduledAt':
      return `预约时间：${formatDateTime(String(value))}`
    case 'serviceAt':
      return `服务时间：${formatDateTime(String(value))}`
    case 'appointmentId':
      return `预约单 #${String(value)}`
    case 'institutionId':
      return `机构 #${String(value)}`
    case 'orderId':
      return `订单 #${String(value)}`
    case 'fromTierCode':
      return `原等级：${String(value)} 级`
    case 'toTierCode':
      return `新等级：${String(value)} 级`
    case 'changeType':
      return `变更方式：${TIER_CHANGE_LABELS[String(value)] ?? '等级变更'}`
    case 'complaintCaseNo':
      return `客诉单号：${String(value)}`
    default:
      return /[\u4e00-\u9fa5]/.test(key) ? `${key}：${String(value)}` : null
  }
}

async function load(append = false) {
  const targetCustomerId = props.customerId
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  const cursor = append ? nextCursor.value : null
  try {
    const result = await getCustomerTimeline(targetCustomerId, {
      size: 20, ...(cursor ? { cursor } : {}),
    })
    if (sequence !== loadSequence || props.customerId !== targetCustomerId) return
    const existing = new Set(append ? items.value.map((item) => item.eventKey) : [])
    const additions = result.items.filter((item) => !existing.has(item.eventKey))
    items.value = append ? [...items.value, ...additions] : result.items
    nextCursor.value = result.nextCursor
  } catch (error) {
    if (sequence !== loadSequence || props.customerId !== targetCustomerId) return
    if (!append) items.value = []
    errorMessage.value = error instanceof Error ? error.message : '时间线加载失败'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function detailText(item: CustomerTimelineItem): string {
  return Object.entries(item.details)
    .map(([key, value]) => formatDetailEntry(key, value, item.details))
    .filter((entry): entry is string => Boolean(entry))
    .join(' · ')
}

watch(() => props.customerId, () => {
  loadSequence += 1
  items.value = []
  nextCursor.value = null
  errorMessage.value = ''
  void load()
}, { immediate: true })
</script>

<template>
  <section class="timeline-panel" aria-label="客户统一时间线">
    <el-alert
      title="仅汇总业务摘要，不展示企微聊天全文；时间按统一发生顺序倒序排列"
      type="info" :closable="false" class="timeline-note"
    />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
    <div v-loading="loading" class="timeline-list">
      <article v-for="item in items" :key="item.eventKey" class="timeline-item" data-testid="timeline-item">
        <div class="timeline-marker" />
        <div class="timeline-content">
          <div class="timeline-heading">
            <el-tag size="small" effect="plain">{{ typeLabels[item.type] ?? '业务动态' }}</el-tag>
            <strong>{{ item.title }}</strong>
            <time>{{ formatDateTime(item.occurredAt) }}</time>
          </div>
          <p v-if="item.summary" class="timeline-summary">{{ item.summary }}</p>
          <p v-if="detailText(item)" class="timeline-details">{{ detailText(item) }}</p>
        </div>
      </article>
      <el-empty
        v-if="!items.length && !loading && !errorMessage"
        description="暂无业务时间线记录" data-testid="timeline-empty"
      />
    </div>
    <el-button v-if="nextCursor" :loading="loading" data-testid="timeline-more" @click="load(true)">
      加载更多
    </el-button>
  </section>
</template>

<style scoped>
.timeline-panel { display: grid; gap: 16px; }
.timeline-note { margin-bottom: 2px; }
.timeline-list { min-height: 120px; }
.timeline-item { display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 10px; position: relative; padding-bottom: 18px; }
.timeline-item:not(:last-child)::before { content: ''; position: absolute; left: 5px; top: 14px; bottom: 0; width: 1px; background: #dce7e9; }
.timeline-marker { width: 11px; height: 11px; margin-top: 5px; border-radius: 50%; background: #0f948b; z-index: 1; }
.timeline-content { padding: 0 0 14px; border-bottom: 1px solid #edf2f4; min-width: 0; }
.timeline-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.timeline-heading time { margin-left: auto; color: #79909f; font-size: 12px; }
.timeline-summary { margin: 9px 0 0; color: #304b5c; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.timeline-details { margin: 6px 0 0; color: #79909f; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 640px) { .timeline-heading time { margin-left: 0; width: 100%; } }
</style>
