<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listNotifications, markNotificationRead } from '../api/notification'
import type { NotificationItem } from '../api/notification'
import PageHeader from '../components/prototype/PageHeader.vue'
import ShellIcon from '../components/shell/ShellIcon.vue'
import { NOTIFICATIONS_CHANGED_EVENT } from '../utils/notificationEvents'

const PAGE_SIZE = 20
const router = useRouter()
const items = ref<NotificationItem[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadError = ref('')
const actionError = ref('')
const pendingReadIds = ref<ReadonlySet<number>>(new Set())
const readIdempotencyKeys = new Map<number, string>()
let loadSequence = 0

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const unreadOnPage = computed(() => items.value.filter((item) => !item.readAt).length)

const typeLabels: Record<string, string> = {
  FOLLOWUP_REMINDER: '跟进提醒',
  FOLLOWUP_DUE: '跟进到期',
  COMPLAINT_ASSIGNED: '客诉待处理',
  COMPLAINT_ESCALATED: '客诉升级',
  TASK_ASSIGNED: '任务提醒',
  APPOINTMENT_UPDATED: '预约动态',
}
const channelLabels: Record<string, string> = { IN_APP: '站内通知', WECOM_APP: '企微应用' }
const deliveryStatusLabels: Record<string, string> = {
  PENDING: '待投递',
  SENDING: '投递中',
  SENT: '已投递',
  DELIVERED: '已送达',
  SUCCESS: '已成功',
  FAILED: '失败',
  DISABLED: '已禁用',
  SKIPPED: '已跳过',
}

function typeLabel(value: string) { return typeLabels[value] ?? value }
function channelLabel(value: string) { return channelLabels[value] ?? value }
function deliveryStatusLabel(value: string) { return deliveryStatusLabels[value] ?? value }
function formatDateTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}
function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const result = await listNotifications({ page: page.value, size: PAGE_SIZE })
    if (sequence !== loadSequence) return
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (error) {
    if (sequence === loadSequence) loadError.value = `通知加载失败：${errorMessage(error, '请稍后重试')}`
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function changePage(target: number) {
  if (loading.value || target < 1 || target > pageCount.value || target === page.value) return
  page.value = target
  void load()
}

async function markRead(item: NotificationItem): Promise<NotificationItem | null> {
  if (item.readAt) return item
  if (pendingReadIds.value.has(item.id)) return null
  const idempotencyKey = readIdempotencyKeys.get(item.id) ?? crypto.randomUUID()
  readIdempotencyKeys.set(item.id, idempotencyKey)
  pendingReadIds.value = new Set(pendingReadIds.value).add(item.id)
  actionError.value = ''
  try {
    const updated = await markNotificationRead(item.id, item.version, idempotencyKey)
    items.value = items.value.map((current) => current.id === updated.id ? updated : current)
    readIdempotencyKeys.delete(item.id)
    window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED_EVENT))
    return updated
  } catch (error) {
    actionError.value = `标记为已读失败：${errorMessage(error, '请稍后重试')}`
    return null
  } finally {
    const pending = new Set(pendingReadIds.value)
    pending.delete(item.id)
    pendingReadIds.value = pending
  }
}

function internalDeepLink(value: string): string | null {
  if (!value.startsWith('/') || value.startsWith('//')) return null
  try {
    const target = new URL(value, window.location.origin)
    if (target.origin !== window.location.origin) return null
    return `${target.pathname}${target.search}${target.hash}`
  } catch {
    return null
  }
}

async function openNotification(item: NotificationItem) {
  if (!item.deepLink) return
  const destination = internalDeepLink(item.deepLink)
  if (!destination) {
    actionError.value = '通知链接无效：仅支持站内路径'
    return
  }
  const current = await markRead(item)
  if (!current?.readAt) return
  try {
    await router.push(destination)
  } catch (error) {
    actionError.value = `打开通知链接失败：${errorMessage(error, '请稍后重试')}`
  }
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page notifications-page">
    <PageHeader title="通知中心" description="仅展示当前账号的通知；标记已读不会修改来源业务记录">
      <template #actions>
        <button type="button" class="notification-refresh" :disabled="loading" @click="load">
          <ShellIcon name="refresh" :size="15" />{{ loading ? '正在刷新' : '刷新通知' }}
        </button>
      </template>
    </PageHeader>

    <section class="notification-panel" aria-label="本人通知列表" :aria-busy="loading">
      <div class="notification-toolbar">
        <div><strong>全部通知</strong><span>共 {{ total }} 条 · 本页 {{ unreadOnPage }} 条未读</span></div>
        <span class="notification-scope">仅当前账号</span>
      </div>

      <div v-if="loadError" class="notification-message is-error" role="alert">
        <p>{{ loadError }}</p>
        <button type="button" data-testid="retry-load" @click="load">重新加载</button>
      </div>
      <div v-if="actionError" class="notification-message is-error" role="alert">
        <p>{{ actionError }}</p>
      </div>
      <div v-if="loading" class="notification-loading" role="status">正在加载通知…</div>
      <div v-else-if="!loadError && !items.length" class="empty-state" role="status">
        <ShellIcon name="bell" :size="28" />
        <strong>暂时没有通知</strong>
        <p>新的业务提醒会显示在这里。</p>
      </div>

      <ol v-if="items.length" class="notification-list">
        <li
          v-for="item in items"
          :key="item.id"
          class="notification-item"
          :class="{ 'is-unread': !item.readAt }"
          :data-testid="`notification-${item.id}`"
        >
          <span class="notification-read-marker" :class="{ visible: !item.readAt }" aria-hidden="true" />
          <div class="notification-body">
            <div class="notification-meta">
              <span class="notification-type">{{ typeLabel(item.notificationType) }}</span>
              <time :datetime="item.createdAt">{{ formatDateTime(item.createdAt) }}</time>
              <span class="notification-read-state">{{ item.readAt ? '已读' : '未读' }}</span>
            </div>
            <h2>{{ item.title }}</h2>
            <p class="notification-content">{{ item.contentMasked || '暂无通知摘要' }}</p>
            <p class="notification-source">来源 {{ item.sourceType }} #{{ item.sourceId }}</p>

            <details v-if="item.deliveries.length" class="delivery-details">
              <summary>投递记录 · {{ item.deliveries.length }}</summary>
              <ul>
                <li v-for="(delivery, index) in item.deliveries" :key="`${delivery.channel}-${index}`">
                  <div class="delivery-heading">
                    <strong>{{ channelLabel(delivery.channel) }}</strong>
                    <span :class="{ 'is-failed': delivery.status === 'FAILED' }">{{ deliveryStatusLabel(delivery.status) }}</span>
                  </div>
                  <p v-if="delivery.providerMessageId">渠道消息 ID：{{ delivery.providerMessageId }}</p>
                  <p v-if="delivery.lastErrorMasked" class="delivery-error">{{ delivery.lastErrorMasked }}</p>
                </li>
              </ul>
            </details>
          </div>

          <div class="notification-actions">
            <button
              v-if="!item.readAt"
              type="button"
              class="notification-read-button"
              :disabled="pendingReadIds.has(item.id)"
              :data-testid="`mark-read-${item.id}`"
              @click="markRead(item)"
            >
              {{ pendingReadIds.has(item.id) ? '标记中…' : '标为已读' }}
            </button>
            <button
              v-if="item.deepLink"
              type="button"
              class="notification-open-button"
              :disabled="pendingReadIds.has(item.id)"
              :data-testid="`open-notification-${item.id}`"
              @click="openNotification(item)"
            >
              查看详情<ShellIcon name="arrow-right" :size="14" />
            </button>
          </div>
        </li>
      </ol>

      <nav v-if="total > PAGE_SIZE" class="notification-pagination" aria-label="通知分页">
        <button
          type="button"
          :disabled="page <= 1 || loading"
          data-testid="previous-page"
          @click="changePage(page - 1)"
        >
          上一页
        </button>
        <span>第 {{ page }} / {{ pageCount }} 页</span>
        <button
          type="button"
          :disabled="page >= pageCount || loading"
          data-testid="next-page"
          @click="changePage(page + 1)"
        >
          下一页
        </button>
      </nav>
    </section>
  </div>
</template>

<style scoped>
.notification-panel{overflow:hidden;border:1px solid #e9eff3;border-radius:14px;background:#fff;box-shadow:0 6px 28px rgb(30 62 84 / 4.5%)}.notification-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 22px;border-bottom:1px solid #e8eef2}.notification-toolbar>div{display:flex;align-items:baseline;gap:12px}.notification-toolbar strong{color:#233d50;font-size:14px}.notification-toolbar span{color:#8a9eaa;font-size:11px}.notification-scope{padding:4px 8px;border-radius:6px;color:#55756f!important;background:#eef7f5;white-space:nowrap}.notification-message{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:16px 22px 0;padding:12px 14px;border-radius:9px;font-size:12px}.notification-message.is-error{border:1px solid #f0ccd1;color:#9b3f4d;background:#fff5f6}.notification-message p{margin:0;line-height:1.7}.notification-message button{flex:none;padding:5px 9px;border:1px solid #dbaab1;border-radius:6px;color:#913d49;background:transparent;cursor:pointer}.notification-loading{padding:48px 22px;color:#78909e;text-align:center}.notification-list{margin:0;padding:0;list-style:none}.notification-item{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:22px;padding:23px 24px 23px 29px;border-bottom:1px solid #edf2f4}.notification-item:last-child{border-bottom:0}.notification-item.is-unread{background:#f6fbfa}.notification-read-marker{position:absolute;top:30px;left:15px;width:6px;height:6px;border-radius:50%;background:transparent}.notification-read-marker.visible{background:#0f948b;box-shadow:0 0 0 3px #dff2ee}.notification-body{min-width:0}.notification-meta{display:flex;align-items:center;gap:10px;flex-wrap:wrap;color:#8ca0ac;font-size:10px}.notification-type{padding:3px 7px;border-radius:5px;color:#32756c;background:#e7f4f1;font-size:10px;font-weight:700}.notification-read-state{margin-left:auto}.is-unread .notification-read-state{color:#08766f;font-weight:700}.notification-body h2{margin:10px 0 7px;color:#233d50;font-size:15px;line-height:1.5;overflow-wrap:anywhere}.is-unread .notification-body h2{font-weight:760}.notification-content{margin:0;color:#526d7c;font-size:12px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.notification-source{margin:9px 0 0;color:#93a5b0;font-size:10px;overflow-wrap:anywhere}.notification-actions{display:flex;align-items:flex-end;justify-content:center;flex-direction:column;gap:9px}.notification-actions button,.notification-refresh,.notification-pagination button{display:inline-flex;min-height:34px;align-items:center;justify-content:center;gap:6px;padding:7px 11px;border-radius:8px;cursor:pointer;font-size:11px;font-weight:650;white-space:nowrap}.notification-actions button:disabled,.notification-refresh:disabled,.notification-pagination button:disabled{cursor:not-allowed;opacity:.45}.notification-read-button{border:1px solid #bfded8;color:#08766f;background:#fff}.notification-read-button:hover:not(:disabled){background:#eaf7f4}.notification-open-button{border:0;color:#0f8981;background:transparent}.notification-open-button:hover:not(:disabled){color:#075f59;background:#eaf7f4}.notification-refresh{border:1px solid #dce8e8;color:#39766f;background:#fff}.notification-refresh:hover:not(:disabled){border-color:#a9d2ca;background:#f1faf8}.delivery-details{margin-top:13px}.delivery-details summary{display:inline-flex;padding:4px 0;color:#718a97;cursor:pointer;font-size:10px}.delivery-details ul{display:grid;gap:8px;margin:8px 0 0;padding:0;list-style:none}.delivery-details li{padding:10px 12px;border-radius:8px;background:#f3f7f9}.is-unread .delivery-details li{background:#edf5f4}.delivery-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#597381;font-size:10px}.delivery-heading strong{color:#3f5f6e}.delivery-heading .is-failed{color:#ad4c5a}.delivery-details p{margin:5px 0 0;color:#718895;font-size:10px;line-height:1.6;overflow-wrap:anywhere}.delivery-details .delivery-error{color:#9d515c}.notification-pagination{display:flex;align-items:center;justify-content:flex-end;gap:14px;padding:17px 22px;border-top:1px solid #e8eef2}.notification-pagination button{border:1px solid #dfe9ec;color:#4e6d7b;background:#fff}.notification-pagination button:hover:not(:disabled){border-color:#a9d2ca;color:#08766f}.notification-pagination span{color:#8095a2;font-size:11px;font-variant-numeric:tabular-nums}
@media(max-width:700px){.notification-toolbar{align-items:flex-start;padding:16px}.notification-toolbar>div{align-items:flex-start;flex-direction:column;gap:4px}.notification-message{align-items:flex-start;margin:12px 14px 0;flex-direction:column}.notification-item{grid-template-columns:minmax(0,1fr);gap:15px;padding:20px 18px 19px 25px}.notification-read-marker{top:27px;left:11px}.notification-read-state{margin-left:0}.notification-actions{align-items:center;justify-content:flex-start;flex-direction:row;flex-wrap:wrap}.notification-actions button{min-height:40px}.notification-pagination{justify-content:space-between;padding:15px}.notification-pagination button{min-height:40px}.notification-refresh{min-height:40px}.delivery-heading{align-items:flex-start}}
</style>
