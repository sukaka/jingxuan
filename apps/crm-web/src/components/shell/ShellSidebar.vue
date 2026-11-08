<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { isMenuGroup } from '../../router/menu'
import type { MenuEntry, MenuGroup } from '../../router/menu'
import ShellIcon from './ShellIcon.vue'

const props = defineProps<{
  entries: MenuEntry[]
  activePath: string
  open: boolean
  displayName: string
  roleLabel: string
  scopeLabel: string
  badgeValues: Record<string, number>
}>()
const emit = defineEmits<{ close: []; navigate: []; switchUser: [] }>()
const closeButton = ref<HTMLButtonElement | null>(null)
const expandedGroups = ref<Record<string, boolean>>({})
const showDevelopmentBadge = import.meta.env.DEV

function containsActive(group: MenuGroup) { return group.children.some((item) => item.path === props.activePath) }
function isExpanded(group: MenuGroup, index: number) { return expandedGroups.value[group.key] ?? (index === 0 || containsActive(group)) }
function toggleGroup(group: MenuGroup, index: number) { expandedGroups.value[group.key] = !isExpanded(group, index) }
function badge(group: MenuGroup) {
  const count = group.children.reduce((sum, item) => sum + (item.badgeKey ? props.badgeValues[item.badgeKey] || 0 : 0), 0)
  return count > 0 ? count : null
}
function trapFocus(event: KeyboardEvent) {
  if (!props.open || event.key !== 'Tab') return
  const sidebar = event.currentTarget as HTMLElement
  const items = Array.from(sidebar.querySelectorAll<HTMLElement>('a[href],button:not([disabled])'))
  const first = items[0], last = items.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
watch(() => props.activePath, () => props.entries.forEach((entry) => {
  if (isMenuGroup(entry) && containsActive(entry)) expandedGroups.value[entry.key] = true
}))
watch(() => props.open, async (open) => { if (open) { await nextTick(); closeButton.value?.focus() } })
</script>

<template>
  <aside id="shell-sidebar" class="shell-sidebar" :class="{ 'is-open': open }" aria-label="主导航" @keydown="trapFocus">
    <div class="brand-row">
      <RouterLink class="brand-link" to="/" aria-label="鲸选美学 CRM 工作台" @click="emit('navigate')">
        <span class="brand-mark">鲸</span><span class="brand-copy"><strong>鲸选美学</strong><small>会员体验管理 CRM</small></span>
      </RouterLink>
      <button ref="closeButton" type="button" class="sidebar-close" aria-label="关闭主导航" @click="emit('close')"><ShellIcon name="close" :size="19" /></button>
    </div>
    <nav class="nav-area" aria-label="工作空间">
      <div v-for="(entry,index) in entries" :key="isMenuGroup(entry) ? entry.key : entry.path" class="nav-group">
        <template v-if="isMenuGroup(entry)">
          <button
            type="button" class="nav-parent" :class="{ active: containsActive(entry), open: isExpanded(entry,index) }"
            :aria-expanded="isExpanded(entry,index)" @click="toggleGroup(entry,index)"
          >
            <ShellIcon class="nav-parent-icon" :name="entry.icon" :size="18" /><span class="nav-parent-title">{{ entry.title }}</span>
            <span v-if="badge(entry)" class="nav-group-badge">{{ badge(entry) }}</span><ShellIcon class="nav-chevron" name="chevron" :size="14" />
          </button>
          <div v-show="isExpanded(entry,index)" class="nav-children">
            <template v-for="child in entry.children" :key="child.title">
              <button v-if="child.disabled" type="button" class="nav-btn future" disabled title="后续阶段开放">
                <ShellIcon :name="child.icon" :size="15" /><span>{{ child.title }}</span><small>后续</small>
              </button>
              <RouterLink
                v-else :to="child.path" class="nav-btn" :class="{ active: activePath === child.path }"
                :aria-current="activePath === child.path ? 'page' : undefined" @click="emit('navigate')"
              >
                <ShellIcon :name="child.icon" :size="15" /><span>{{ child.title }}</span>
                <span v-if="child.badgeKey && badgeValues[child.badgeKey]" class="nav-group-badge">{{ badgeValues[child.badgeKey] }}</span>
              </RouterLink>
            </template>
          </div>
        </template>
      </div>
    </nav>
    <div class="nav-bottom">
      <div class="nav-user"><span class="user-avatar">{{ displayName.slice(0,1) || '用' }}</span><div><strong>{{ displayName }}</strong><small>{{ roleLabel }}</small></div></div>
      <div class="sidebar-footer">{{ scopeLabel }}<template v-if="showDevelopmentBadge"><br>开发环境</template></div>
      <button type="button" class="switch-login" @click="emit('switchUser')"><ShellIcon name="logout" :size="13" />切换登录身份</button>
    </div>
  </aside>
</template>

<style scoped>
.shell-sidebar{position:fixed;inset:0 auto 0 0;z-index:40;display:flex;width:238px;height:100vh;height:100dvh;flex-direction:column;color:#e5f2f7;background:linear-gradient(160deg,#0c2133,#102d40);box-shadow:3px 0 20px rgb(12 33 51 / 4%)}
.brand-row{display:flex;height:88px;flex:none;align-items:center;gap:8px;padding:21px 23px;border-bottom:1px solid rgb(255 255 255 / 8%)}
.brand-link{display:flex;align-items:center;gap:11px;text-decoration:none}
.brand-mark{display:grid;width:40px;height:40px;place-items:center;border-radius:13px;color:#fff;background:linear-gradient(150deg,#54d3ba,#0d9c94);box-shadow:0 4px 20px rgb(32 190 168 / 25%);font-size:24px;font-weight:700}
.brand-copy strong{display:block;color:#fff;font-size:16px;font-weight:750;letter-spacing:.2px}.brand-copy small{display:block;margin-top:3px;color:#7eacbb;font-size:8px;letter-spacing:1.1px;white-space:nowrap}
.sidebar-close{display:none;margin-left:auto;padding:0;border:0;color:#d0e1e8;background:transparent}
.nav-area{flex:1;min-height:0;padding:16px 12px;overflow:auto;overscroll-behavior:contain}.nav-group{margin:5px 0}
.nav-parent,.nav-btn{position:relative;display:flex;width:100%;align-items:center;gap:11px;border:0;border-radius:10px;text-align:left;text-decoration:none;transition:.16s}
.nav-parent{padding:11px 13px;color:#d0e1e8;background:transparent;font-weight:720;cursor:pointer}.nav-parent.active{color:#e9fff9;background:rgb(49 184 166 / 10%)}.nav-parent-icon{display:grid;width:22px;place-items:center;color:#81b7b3}.nav-parent-title{flex:1;min-width:0}.nav-chevron{opacity:.6;transition:transform .18s}.nav-parent.open .nav-chevron{transform:rotate(180deg)}
.nav-children{margin:3px 0 8px 25px;padding:2px 0 3px 10px;border-left:1px solid rgb(145 190 197 / 18%)}
.nav-btn{padding:9px 11px;margin:2px 0;color:#9fb8c5;font-size:12px;font-weight:520}.nav-btn>span:first-of-type{flex:1}.nav-btn:hover{color:#fff;background:rgb(255 255 255 / 6%)}.nav-btn.active{color:#e0fff6;background:#174a54;font-weight:700}.nav-btn.active:before{position:absolute;left:-11px;width:3px;height:16px;border-radius:3px;background:#43d4b2;content:''}.nav-btn.future{cursor:not-allowed;opacity:.48}.nav-btn.future small{font-size:9px}
.nav-group-badge{display:inline-flex;min-width:19px;height:19px;align-items:center;justify-content:center;margin-left:4px;padding:0 5px;border-radius:10px;color:#142735;background:#e7a24e;font-size:10px;font-weight:800}
.nav-bottom{padding:18px 16px;border-top:1px solid rgb(255 255 255 / 9%)}.nav-user{display:flex;align-items:center;gap:10px}.user-avatar{display:grid;width:34px;height:34px;flex:none;place-items:center;border-radius:50%;color:#11756e;background:linear-gradient(135deg,#d2f6ef,#b6dee7);font-weight:740}.nav-user strong{display:block;font-size:12px}.nav-user small{display:block;margin-top:3px;color:#86aabb;font-size:10px}.sidebar-footer{margin-top:12px;color:#7197aa;font-size:10px;line-height:1.8}.switch-login{display:flex;align-items:center;gap:6px;margin-top:10px;padding:0;border:0;color:#9fc2ca;background:transparent;font-size:10px;cursor:pointer}
@media(max-width:1020px){.shell-sidebar{width:212px}.brand-row{padding:21px 17px}.brand-copy small{font-size:7px}}
@media(max-width:760px){.shell-sidebar{width:238px;transform:translateX(-100%);transition:transform .2s}.shell-sidebar.is-open{transform:translateX(0)}.sidebar-close{display:block}}
</style>
