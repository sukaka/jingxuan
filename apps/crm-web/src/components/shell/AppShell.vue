<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { isMenuGroup, visibleMenuItems } from '../../router/menu'
import { ApiError } from '../../types/api'
import { listTasks } from '../../api/task'
import { listAppointments } from '../../api/fulfillment'
import ShellHeader from './ShellHeader.vue'
import ShellSidebar from './ShellSidebar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const header = ref<InstanceType<typeof ShellHeader> | null>(null)
const navigationOpen = ref(false)
const badgeValues = ref<Record<string, number>>({})
const ROLE_LABELS: Record<string, string> = {
  ADMIN: '运营管理员', OWNER: '经营负责人', MANAGER: '管家', MANAGER_LEAD: '管家主管', BUSINESS: '商务运营', FINANCE: '财务',
  INSTITUTION: '合作机构用户', INSTITUTION_OPERATOR: '机构操作员', INSTITUTION_STAFF: '机构操作员', INSTITUTION_FINANCE: '机构财务',
}
const isInstitution = computed(() => auth.user?.userType === 'INSTITUTION')
const isManager = computed(() => auth.hasRole('MANAGER') && !auth.hasRole('ADMIN'))
const isAdmin = computed(() => auth.hasRole('ADMIN'))
const menuItems = computed(() => visibleMenuItems((code) => auth.hasPermission(code), auth.user?.roles ?? [], auth.user?.userType))
const roleLabel = computed(() => (auth.user?.roles ?? []).map((role) => ROLE_LABELS[role] ?? '未识别角色').join(' / ') || '已登录用户')
const displayName = computed(() => auth.user?.displayName || auth.user?.username || '当前用户')
const pageTitle = computed(() => {
  for (const entry of menuItems.value) {
    if (isMenuGroup(entry)) {
      const active = entry.children.find((item) => item.path === route.path)
      if (active) return active.title
    }
  }
  return route.meta.title ?? '工作台'
})
const pageSection = computed(() => isInstitution.value ? '合作机构工作台' : isManager.value ? '管家工作台' : '运营管理')
const scopeLabel = computed(() => isInstitution.value ? '仅本机构授权数据' : isManager.value ? '仅本人负责客户' : isAdmin.value ? '全局管理视图' : '本组及授权团队')

function openNavigation() { navigationOpen.value = true }
async function closeNavigation() {
  if (!navigationOpen.value) return
  navigationOpen.value = false
  await nextTick()
  header.value?.focusMenuButton()
}
function toggleNavigation() {
  if (navigationOpen.value) void closeNavigation()
  else openNavigation()
}
function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && navigationOpen.value) { event.preventDefault(); void closeNavigation() }
}
async function handleLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能继续使用，确定切换登录身份吗？', '切换登录身份', {
      type: 'warning', confirmButtonText: '退出并切换', cancelButtonText: '取消',
    })
  } catch { return }
  try { await auth.logout() } catch (error) {
    ElMessage.warning(error instanceof ApiError && error.isNetworkError ? '后端暂不可达，已在本地退出' : '退出登录时发生错误，已在本地退出')
  }
  navigationOpen.value = false
  await router.push({ name: 'login' })
}
function globalSearch() {
  void router.push({ path: isInstitution.value ? '/appointments' : '/customers', query: { focus: 'search' } })
}
async function loadBadges() {
  const requests: Promise<void>[] = []
  if (auth.hasPermission('task:read')) requests.push(listTasks({ page: 1, size: 1, status: 'OPEN' })
    .then((result) => { badgeValues.value.tasks = result.total }).catch(() => { badgeValues.value.tasks = 0 }))
  if (auth.hasPermission('appointment:read')) requests.push(listAppointments({ page: 1, size: 1, status: 'PENDING_INSTITUTION' })
    .then((result) => { badgeValues.value.appointments = result.total }).catch(() => { badgeValues.value.appointments = 0 }))
  await Promise.all(requests)
}
watch(() => route.fullPath, () => { navigationOpen.value = false; void loadBadges() })
onMounted(() => { document.addEventListener('keydown', handleDocumentKeydown); void loadBadges() })
onBeforeUnmount(() => document.removeEventListener('keydown', handleDocumentKeydown))
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <button v-if="navigationOpen" type="button" class="navigation-scrim" aria-label="关闭主导航" @click="closeNavigation" />
    <ShellSidebar
      :entries="menuItems" :active-path="route.path" :open="navigationOpen" :display-name="displayName"
      :role-label="roleLabel" :scope-label="scopeLabel" :badge-values="badgeValues"
      @close="closeNavigation" @navigate="navigationOpen && closeNavigation()" @switch-user="handleLogout"
    />
    <div class="app-workspace">
      <ShellHeader
        ref="header" :section="pageSection" :title="pageTitle" :display-name="displayName" :role-label="roleLabel"
        :navigation-open="navigationOpen" :is-admin="isAdmin" :can-export="isAdmin && auth.hasPermission('export:read')" @toggle-navigation="toggleNavigation"
        @logout="handleLogout" @search="globalSearch" @refresh="router.go(0)" @export="router.push({ name: 'exports' })"
      />
      <main id="main-content" class="app-content" tabindex="-1"><slot /></main>
    </div>
  </div>
</template>

<style scoped>
.app-shell{display:flex;min-height:100vh;background:#f6f9fb}.app-workspace{min-width:0;min-height:100vh;flex:1;margin-left:238px}.app-content{width:100%;max-width:1620px;min-width:0;margin:0 auto;padding:31px 36px 60px;outline:none}.navigation-scrim{position:fixed;inset:0;z-index:30;display:none;padding:0;border:0;background:rgb(7 27 39 / 42%);backdrop-filter:blur(2px)}.skip-link{position:fixed;top:10px;left:12px;z-index:100;padding:10px 14px;border-radius:8px;color:#fff;background:#0f766e;font-size:13px;text-decoration:none;transform:translateY(-150%)}.skip-link:focus{transform:translateY(0)}
@media(max-width:1020px){.app-workspace{margin-left:212px}.app-content{padding:23px}}
@media(max-width:760px){.app-workspace{margin-left:0}.app-content{padding:21px 15px}.navigation-scrim{display:block}}
</style>
