<script setup lang="ts">
import { ref } from 'vue'
import ShellIcon from './ShellIcon.vue'

defineProps<{
  section: string
  title: string
  displayName: string
  roleLabel: string
  navigationOpen: boolean
  isAdmin: boolean
  canExport: boolean
}>()

const emit = defineEmits<{
  toggleNavigation: []
  logout: []
  search: []
  refresh: []
  export: []
}>()
const menuButton = ref<HTMLButtonElement | null>(null)
const showDevelopmentBadge = import.meta.env.DEV
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
      <button type="button" class="role-switch" title="切换登录身份" @click="emit('logout')">
        <span class="header-avatar">{{ displayName.slice(0, 1) || '用' }}</span>
        <span><strong>{{ displayName }}</strong><small>{{ roleLabel }} · 切换身份</small></span>
      </button>
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
.role-switch{gap:9px;padding:7px 10px;border:1px solid #e4ecef;border-radius:10px;color:#597180;background:#fff;cursor:pointer;text-align:left}
.role-switch>span:last-child{display:flex;flex-direction:column}
.role-switch strong{color:#2b4657;font-size:11px}
.role-switch small{margin-top:1px;color:#91a3ae;font-size:9px}
.header-avatar{display:grid;width:26px;height:26px;flex:none;place-items:center;border-radius:50%;color:#11756e;background:linear-gradient(135deg,#d2f6ef,#b6dee7);font-size:10px;font-weight:740}
.circle-button{display:grid;width:35px;height:35px;flex:none;place-items:center;padding:0;border:1px solid #e9eff3;border-radius:10px;color:#6d8796;background:transparent;cursor:pointer}
.circle-button:disabled{cursor:not-allowed;opacity:.4}
.menu-button{display:none}
@media(max-width:1020px){.shell-header{padding:0 23px}.search-top{min-width:190px}.header-actions{gap:9px}}
@media(max-width:760px){.shell-header{height:63px;padding:0 15px}.menu-button{display:grid}.search-top{display:none}.section-icon{display:none}.header-actions{gap:8px}.environment-badge{display:none}}
@media(max-width:480px){.breadcrumb>span,.breadcrumb>.shell-icon{display:none}.role-switch{padding:5px}.role-switch>span:last-child{display:none}}
</style>
