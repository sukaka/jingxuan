<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import ShellIcon from './ShellIcon.vue'

defineProps<{
  section: string
  title: string
  displayName: string
  username?: string
  roleLabel: string
  navigationOpen: boolean
  isAdmin: boolean
  canExport: boolean
}>()

const emit = defineEmits<{
  toggleNavigation: []
  changePassword: []
  logout: []
  search: []
  refresh: []
  export: []
}>()

const menuButton = ref<HTMLButtonElement | null>(null)
const accountButton = ref<HTMLButtonElement | null>(null)
const accountMenuRef = ref<HTMLElement | null>(null)
const accountMenuOpen = ref(false)
const showDevelopmentBadge = import.meta.env.DEV

function toggleAccountMenu() {
  accountMenuOpen.value = !accountMenuOpen.value
}

async function closeAccountMenu(restoreFocus = false) {
  if (!accountMenuOpen.value) return
  accountMenuOpen.value = false
  if (restoreFocus) {
    await nextTick()
    accountButton.value?.focus()
  }
}

function selectChangePassword() {
  accountMenuOpen.value = false
  emit('changePassword')
}

function selectLogout() {
  accountMenuOpen.value = false
  emit('logout')
}

function handleDocumentClick(event: MouseEvent) {
  if (!accountMenuOpen.value) return
  const target = event.target as Node | null
  if (target && accountMenuRef.value?.contains(target)) return
  accountMenuOpen.value = false
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && accountMenuOpen.value) {
    event.preventDefault()
    event.stopPropagation()
    void closeAccountMenu(true)
  }
}

onMounted(() => {
  document.addEventListener('click', handleDocumentClick)
  document.addEventListener('keydown', handleDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick)
  document.removeEventListener('keydown', handleDocumentKeydown)
})

defineExpose({ focusMenuButton: () => menuButton.value?.focus() })
</script>

<template>
  <header class="shell-header">
    <div class="header-left">
      <button
        ref="menuButton" type="button" class="circle-button menu-button"
        :aria-label="navigationOpen ? '关闭主导航' : '打开主导航'" aria-controls="shell-sidebar"
        :aria-expanded="navigationOpen" @click="emit('toggleNavigation')"
      >
        <ShellIcon name="menu" :size="19" />
      </button>
      <ShellIcon class="section-icon" name="grid" :size="17" />
      <nav class="breadcrumb" aria-label="面包屑">
        <span>{{ section }}</span><ShellIcon name="arrow-right" :size="12" /><strong>{{ title }}</strong>
      </nav>
    </div>
    <div class="header-actions">
      <button type="button" class="search-top" @click="emit('search')">
        <ShellIcon name="search" :size="15" /><span>搜索客户 / 手机号 / 订单号</span>
      </button>
      <span v-if="showDevelopmentBadge" class="environment-badge"><i />开发环境</span>
      <slot name="actions" />
      <div ref="accountMenuRef" class="account-menu">
        <button
          ref="accountButton"
          type="button"
          class="role-switch"
          :class="{ 'is-open': accountMenuOpen }"
          title="个人账号菜单"
          aria-haspopup="menu"
          aria-controls="header-account-menu"
          :aria-expanded="accountMenuOpen"
          @click="toggleAccountMenu"
        >
          <span class="header-avatar">{{ displayName.slice(0, 1) || '用' }}</span>
          <span class="role-switch-meta">
            <strong>{{ displayName }}</strong>
            <small>{{ roleLabel }}</small>
          </span>
          <ShellIcon class="role-switch-chevron" :class="{ open: accountMenuOpen }" name="chevron" :size="13" />
        </button>
        <div
          v-if="accountMenuOpen"
          id="header-account-menu"
          class="account-dropdown"
          role="menu"
          aria-label="个人账号操作"
        >
          <div class="account-dropdown-head">
            <strong>{{ displayName }}</strong>
            <small>{{ username ? `@${username} · ` : '' }}{{ roleLabel }}</small>
          </div>
          <div class="account-dropdown-list">
            <button
              type="button"
              class="account-dropdown-item"
              role="menuitem"
              data-testid="account-change-password"
              @click="selectChangePassword"
            >
              <ShellIcon name="lock" :size="14" />
              <span>修改密码</span>
            </button>
            <button
              type="button"
              class="account-dropdown-item is-danger"
              role="menuitem"
              data-testid="account-switch-identity"
              @click="selectLogout"
            >
              <ShellIcon name="logout" :size="14" />
              <span>切换身份</span>
            </button>
          </div>
        </div>
      </div>
      <button v-if="isAdmin" type="button" class="circle-button" title="刷新当前页面" @click="emit('refresh')">
        <ShellIcon name="refresh" :size="16" />
      </button>
      <button v-if="canExport" type="button" class="circle-button" title="数据导出中心" @click="emit('export')">
        <ShellIcon name="download" :size="16" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.shell-header{position:sticky;top:0;z-index:20;display:flex;height:70px;align-items:center;justify-content:space-between;gap:20px;padding:0 36px;border-bottom:1px solid #eaf0f3;background:rgb(255 255 255 / 95%);backdrop-filter:blur(13px)}
.header-left,.header-actions,.breadcrumb,.search-top,.role-switch{display:flex;align-items:center}
.header-left{min-width:0;gap:10px;color:#8095a5}
.section-icon{flex:none}
.breadcrumb{min-width:0;gap:10px;font-size:12px}
.breadcrumb span,.breadcrumb strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.breadcrumb strong{color:#233d50;font-weight:720}
.breadcrumb .shell-icon{opacity:.6}
.header-actions{flex:none;gap:14px}
.search-top{gap:7px;min-width:220px;padding:10px 13px;border:1px solid #e8eef2;border-radius:9px;color:#92a4b1;background:#f8fafb;cursor:pointer;font-size:11px;text-align:left}
.search-top:hover{border-color:#b6d4cf;color:#0f948b}
.environment-badge{display:inline-flex;align-items:center;gap:6px;padding:7px 11px;border:1px solid #f6e4c8;border-radius:100px;color:#99652d;background:#fff5e6;font-size:11px;font-weight:650;white-space:nowrap}
.environment-badge i{width:6px;height:6px;border-radius:50%;background:#c4812b}
.account-menu{position:relative}
.role-switch{gap:9px;padding:7px 10px;border:1px solid #e4ecef;border-radius:10px;color:#597180;background:#fff;cursor:pointer;text-align:left;transition:border-color .16s,background-color .16s}
.role-switch:hover,.role-switch.is-open{border-color:#b6d4cf;background:#f8fcfb}
.role-switch-meta{display:flex;flex-direction:column}
.role-switch strong{color:#2b4657;font-size:11px}
.role-switch small{margin-top:1px;color:#91a3ae;font-size:9px}
.role-switch-chevron{margin-left:2px;color:#8ca1ad;transition:transform .16s}
.role-switch-chevron.open{transform:rotate(180deg)}
.header-avatar{display:grid;width:26px;height:26px;flex:none;place-items:center;border-radius:50%;color:#11756e;background:linear-gradient(135deg,#d2f6ef,#b6dee7);font-size:10px;font-weight:740}
.account-dropdown{position:absolute;top:calc(100% + 8px);right:0;z-index:45;min-width:206px;overflow:hidden;border:1px solid #e3ecef;border-radius:12px;background:#fff;box-shadow:0 14px 36px rgb(16 42 61 / 12%)}
.account-dropdown-head{display:flex;flex-direction:column;gap:3px;padding:12px 14px;border-bottom:1px solid #edf2f5;background:#f9fcfc}
.account-dropdown-head strong{color:#1d3648;font-size:12px;font-weight:720}
.account-dropdown-head small{color:#8399a7;font-size:10px;word-break:break-all}
.account-dropdown-list{display:flex;flex-direction:column;padding:6px}
.account-dropdown-item{display:flex;width:100%;align-items:center;gap:9px;padding:9px 10px;border:0;border-radius:8px;color:#304a5c;background:transparent;font-size:12px;font-weight:600;text-align:left;cursor:pointer;transition:background-color .14s,color .14s}
.account-dropdown-item:hover{color:#0b7c74;background:#edf8f6}
.account-dropdown-item.is-danger:hover{color:#c04553;background:#fff1f3}
.circle-button{display:grid;width:35px;height:35px;flex:none;place-items:center;padding:0;border:1px solid #e9eff3;border-radius:10px;color:#6d8796;background:transparent;cursor:pointer}
.circle-button:disabled{cursor:not-allowed;opacity:.4}
.menu-button{display:none}
@media(max-width:1020px){.shell-header{padding:0 23px}.search-top{min-width:190px}.header-actions{gap:9px}}
@media(max-width:760px){.shell-header{height:63px;padding:0 15px}.menu-button{display:grid}.search-top{display:none}.section-icon{display:none}.header-actions{gap:8px}.environment-badge{display:none}}
@media(max-width:480px){.breadcrumb>span,.breadcrumb>.shell-icon{display:none}.role-switch{gap:4px;padding:5px 7px}.role-switch-meta{display:none}}
</style>
