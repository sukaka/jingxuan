<script setup lang="ts">
import { ref, watch } from 'vue'
import type { CustomerTimelineItem, CustomerTimelineType } from '../../api/customerTimeline'
import { getCustomerTimeline } from '../../api/customerTimeline'
import { formatDateTime } from './shared'

const props = defineProps<{ customerId: number }>()
const items = ref<CustomerTimelineItem[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const typeLabels: Record<CustomerTimelineType, string> = {
  FOLLOWUP: '跟进', INTENT: '意向', BLOCKER: '卡点', NEXT_FOLLOWUP: '下一步', TASK: '任务',
  OWNERSHIP: '归属', ORDER: '订单', APPOINTMENT: '预约', REDEMPTION: '核销', REFUND: '退款',
  TIER: '等级', COMPLAINT: '客诉',
}

async function load(append = false) {
  loading.value = true
  errorMessage.value = ''
  const cursor = append ? nextCursor.value : null
  try {
    const result = await getCustomerTimeline(props.customerId, {
      size: 20, ...(cursor ? { cursor } : {}),
    })
    const existing = new Set(append ? items.value.map((item) => item.eventKey) : [])
    const additions = result.items.filter((item) => !existing.has(item.eventKey))
    items.value = append ? [...items.value, ...additions] : result.items
    nextCursor.value = result.nextCursor
  } catch (error) {
    if (!append) items.value = []
    errorMessage.value = error instanceof Error ? error.message : '时间线加载失败'
  } finally {
    loading.value = false
  }
}

function detailText(item: CustomerTimelineItem): string {
  const entries = Object.entries(item.details).filter(([, value]) => value !== null && value !== undefined)
  return entries.map(([key, value]) => `${key}: ${String(value)}`).join(' · ')
}

watch(() => props.customerId, () => {
  items.value = []
  nextCursor.value = null
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
            <el-tag size="small" effect="plain">{{ typeLabels[item.type] ?? item.type }}</el-tag>
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
