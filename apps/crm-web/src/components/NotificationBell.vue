<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getNotificationUnreadCount } from '../api/notification'
import { NOTIFICATIONS_CHANGED_EVENT } from '../utils/notificationEvents'
import ShellIcon from './shell/ShellIcon.vue'

const POLL_INTERVAL_MS = 30_000
const router = useRouter()
const unreadCount = ref(0)
const hasUnreadValue = ref(false)
const updateFailed = ref(false)
let pollTimer: number | undefined
let refreshing = false

const displayCount = computed(() => unreadCount.value > 99 ? '99+' : String(unreadCount.value))
const accessibilityLabel = computed(() => {
  if (updateFailed.value && !hasUnreadValue.value) return '通知中心，未读数暂不可用'
  const countLabel = unreadCount.value > 0 ? `${unreadCount.value} 条未读通知` : '暂无未读通知'
  return `通知中心，${countLabel}${updateFailed.value ? '，未读数更新失败' : ''}`
})

async function refreshUnreadCount() {
  if (refreshing || document.hidden) return
  refreshing = true
  try {
    const result = await getNotificationUnreadCount()
    unreadCount.value = Math.max(0, result.unreadCount)
    hasUnreadValue.value = true
    updateFailed.value = false
  } catch {
    updateFailed.value = true
  } finally {
    refreshing = false
  }
}

function requestRefresh() {
  void refreshUnreadCount()
}

function handleVisibilityChange() {
  if (!document.hidden) requestRefresh()
}

function openNotifications() {
  void router.push({ name: 'notifications' })
}

onMounted(() => {
  requestRefresh()
  pollTimer = window.setInterval(requestRefresh, POLL_INTERVAL_MS)
  window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, requestRefresh)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) window.clearInterval(pollTimer)
  window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, requestRefresh)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <button
    type="button"
    class="notification-bell"
    :class="{ 'has-unread': hasUnreadValue && unreadCount > 0, 'has-error': updateFailed }"
    :aria-label="accessibilityLabel"
    :title="accessibilityLabel"
    @click="openNotifications"
  >
    <ShellIcon name="bell" :size="18" />
    <span
      v-if="hasUnreadValue && unreadCount > 0"
      class="notification-unread-count"
      data-testid="notification-unread-count"
      aria-hidden="true"
    >{{ displayCount }}</span>
    <span v-if="updateFailed" class="notification-update-warning" aria-hidden="true" />
  </button>
</template>

<style scoped>
.notification-bell{position:relative;display:grid;width:35px;height:35px;flex:none;place-items:center;padding:0;border:1px solid #e9eff3;border-radius:10px;color:#6d8796;background:transparent;cursor:pointer;transition:color .16s,border-color .16s,background-color .16s}.notification-bell:hover{color:#0f948b;border-color:#b6d4cf;background:#f3faf8}.notification-bell.has-unread{color:#08766f}.notification-unread-count{position:absolute;top:-7px;right:-8px;display:flex;min-width:19px;height:19px;align-items:center;justify-content:center;padding:0 5px;border:2px solid #fff;border-radius:10px;color:#fff;background:#ce5b68;font-size:9px;font-weight:800;font-variant-numeric:tabular-nums;line-height:1}.notification-update-warning{position:absolute;right:-2px;bottom:-2px;width:8px;height:8px;border:2px solid #fff;border-radius:50%;background:#b7792b}.notification-unread-count~.notification-update-warning{right:auto;left:-3px}
@media(max-width:480px){.notification-bell{width:34px;height:34px}.notification-unread-count{right:-6px}}
</style>
