<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  listQuickReplyCategories,
  listQuickReplies,
  listQuickReplyVariables,
  previewQuickReply,
  recordQuickReplyUsage,
  setQuickReplyFavorite,
} from '../api/quickReply'
import type { QuickReplyCategory, QuickReplyPreview, QuickReplyTemplate, QuickReplyVariable } from '../api/quickReply'
import { sendTextToCurrentChat } from '../services/wecomSdk'
import type { WecomSendTarget } from '../services/wecomSdk'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import { copyTextWithFallback } from '../utils/clipboard'

type QuickReplyFilterMode = 'ALL' | 'FAVORITE' | 'RECENT'

const PLACEHOLDER_RE = /\{([a-z_]+)\}/g

const props = defineProps<{
  customerId?: number | null
  externalUserid?: string
  chatId?: string
  chatEntry: string
}>()

const emit = defineEmits<{
  'context-mismatch': []
}>()

const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('quickreply:view'))
const categories = ref<QuickReplyCategory[]>([])
const templates = ref<QuickReplyTemplate[]>([])
const variables = ref<QuickReplyVariable[]>([])
const filterMode = ref<QuickReplyFilterMode>('ALL')
const searchKeyword = ref('')
const categoryId = ref(0)
const templateId = ref<number | null>(null)
const preview = ref<QuickReplyPreview | null>(null)
const draft = ref('')
const loading = ref(false)
const previewBusy = ref(false)
const actionBusy = ref(false)
const errorMessage = ref('')
const feedback = ref('')
let usageKey = ''

const hasCustomerId = computed(() => typeof props.customerId === 'number' && props.customerId > 0)
const hasCustomerContext = computed(() => hasCustomerId.value && Boolean(props.externalUserid?.trim()))
const isGroupChat = computed(() => props.chatEntry === 'group_chat_tools')
const isSendableEntry = computed(() => props.chatEntry === 'single_chat_tools' || props.chatEntry === 'group_chat_tools')
const normalizedKeyword = computed(() => searchKeyword.value.trim().toLowerCase())

function extractPlaceholders(content: string): string[] {
  const found = new Set<string>()
  for (const match of content.matchAll(PLACEHOLDER_RE)) {
    if (match[1]) found.add(match[1])
  }
  return Array.from(found)
}

function isStaticTemplate(template: QuickReplyTemplate): boolean {
  return extractPlaceholders(template.content).length === 0
}

function usedAtMs(value: string | null): number {
  if (!value) return -1
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? -1 : parsed
}

function matchesKeyword(template: QuickReplyTemplate, keyword: string): boolean {
  if (!keyword) return true
  return template.name.toLowerCase().includes(keyword) || template.content.toLowerCase().includes(keyword)
}

const visibleCategories = computed(() =>
  categories.value.filter((category) => templates.value.some((template) => template.categoryId === category.id)),
)

const showCategoryTabs = computed(() =>
  filterMode.value === 'ALL' && !normalizedKeyword.value && visibleCategories.value.length > 0,
)

const visibleTemplates = computed(() => {
  const keyword = normalizedKeyword.value
  const matched = templates.value.filter((template) => matchesKeyword(template, keyword))
  if (filterMode.value === 'FAVORITE') {
    return matched.filter((template) => template.favorite)
  }
  if (filterMode.value === 'RECENT') {
    return [...matched]
      .filter((template) => Boolean(template.lastUsedAt) || template.usageCount > 0)
      .sort((a, b) => {
        const timeDiff = usedAtMs(b.lastUsedAt) - usedAtMs(a.lastUsedAt)
        if (timeDiff !== 0) return timeDiff
        const countDiff = b.usageCount - a.usageCount
        if (countDiff !== 0) return countDiff
        return a.sortNo - b.sortNo || a.id - b.id
      })
  }
  if (!keyword && categoryId.value > 0) {
    return matched.filter((template) => template.categoryId === categoryId.value)
  }
  return matched
})

const emptyHeading = computed(() => {
  if (normalizedKeyword.value) return '未找到匹配的话术'
  if (filterMode.value === 'FAVORITE') return '暂无个人收藏话术'
  if (filterMode.value === 'RECENT') return '暂无最近常用话术'
  return '暂无可选用话术'
})

const emptyHint = computed(() => {
  if (normalizedKeyword.value) return '请尝试调整关键词，或清空搜索查看全部话术。'
  if (filterMode.value === 'FAVORITE') return '点击话术卡片右下角的“收藏”后，会固定显示在这里。'
  if (filterMode.value === 'RECENT') return '复制或发送话术后，会按最近使用时间与频次排在前面。'
  return '请先由管理员在快捷话术库中配置当前场景模板。'
})

const canUse = computed(() => preview.value?.canSend === true && draft.value.trim().length > 0 && !previewBusy.value && !actionBusy.value)
const canSendCurrentChat = computed(() => canUse.value && isSendableEntry.value)

function errorText(error: unknown): string {
  return error instanceof ApiError || error instanceof Error ? error.message : '操作失败，请重试'
}

function newUsageKey(): string {
  if (!usageKey) usageKey = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `quick-reply-${Date.now()}-${Math.random()}`
  return usageKey
}

function buildSendTarget(): WecomSendTarget | undefined {
  if (props.chatEntry === 'single_chat_tools' && props.externalUserid?.trim()) {
    return { expectedExternalUserid: props.externalUserid.trim() }
  }
  if (props.chatEntry === 'group_chat_tools' && props.chatId?.trim()) {
    return { expectedChatId: props.chatId.trim() }
  }
  return undefined
}

function resetSelection(): void {
  templateId.value = null
  preview.value = null
  draft.value = ''
  feedback.value = ''
  errorMessage.value = ''
  usageKey = ''
}

async function load(): Promise<void> {
  if (!allowed.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [storedCategories, storedTemplates, storedVariables] = await Promise.all([
      listQuickReplyCategories(), listQuickReplies(), listQuickReplyVariables(),
    ])
    categories.value = storedCategories
    templates.value = storedTemplates
    variables.value = storedVariables
    categoryId.value = visibleCategories.value[0]?.id ?? 0
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function selectFilterMode(mode: QuickReplyFilterMode): void {
  filterMode.value = mode
  feedback.value = ''
}

function selectCategory(id: number): void {
  categoryId.value = id
  resetSelection()
}

async function selectTemplate(template: QuickReplyTemplate): Promise<void> {
  if (previewBusy.value || actionBusy.value) return
  templateId.value = template.id
  preview.value = null
  errorMessage.value = ''
  feedback.value = ''
  usageKey = ''

  if (!hasCustomerId.value) {
    const placeholders = extractPlaceholders(template.content)
    preview.value = {
      templateId: template.id,
      content: template.content,
      variables: {},
      missingVariables: placeholders,
      canSend: placeholders.length === 0,
      allowEdit: template.allowEdit,
    }
    draft.value = template.content
    return
  }

  previewBusy.value = true
  try {
    preview.value = await previewQuickReply(template.id, props.customerId!, template.scene)
    draft.value = preview.value.content
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    previewBusy.value = false
  }
}

async function copy(): Promise<void> {
  if (!canUse.value || !preview.value || actionBusy.value) return
  actionBusy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    await copyTextWithFallback(draft.value)
    if (hasCustomerContext.value) {
      try {
        await recordUsage('COPIED', 'clipboard:ok')
        feedback.value = '话术已复制并记录，粘贴前请再次确认内容'
      } catch (error) {
        errorMessage.value = `话术已复制，但使用日志记录失败：${errorText(error)}`
      }
    } else {
      feedback.value = '话术已复制，粘贴到当前会话前请再次确认内容'
    }
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    actionBusy.value = false
  }
}

async function send(): Promise<void> {
  if (!canSendCurrentChat.value || !preview.value || actionBusy.value) return
  actionBusy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    const result = await sendTextToCurrentChat(draft.value, buildSendTarget())
    if (result.mismatch) {
      errorMessage.value = '当前企微会话已切换，已阻止误发，正在刷新上下文'
      emit('context-mismatch')
      return
    }
    if (hasCustomerContext.value) {
      try {
        await recordUsage(result.status, result.clientResult)
      } catch (error) {
        errorMessage.value = `企微已返回 ${result.status}，但使用日志记录失败：${errorText(error)}；请先核实会话，不要直接重复发送`
        return
      }
    }
    if (result.status === 'SDK_SUCCESS') {
      feedback.value = hasCustomerContext.value
        ? '企微已确认发送，使用结果已记录；这不代表客户已读'
        : '企微已确认发送到当前群聊；这不代表群成员已读'
    } else if (result.status === 'SDK_FAILED') {
      feedback.value = hasCustomerContext.value
        ? '企微返回发送失败，失败结果已记录'
        : '企微返回发送失败，请稍后核实群聊状态'
    } else {
      feedback.value = '发送结果未知，请先到会话核实，不要直接重复发送'
    }
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    actionBusy.value = false
  }
}

async function toggleFavorite(template: QuickReplyTemplate): Promise<void> {
  if (actionBusy.value) return
  actionBusy.value = true
  errorMessage.value = ''
  try {
    await setQuickReplyFavorite(template.id, !template.favorite, `quick-reply-favorite-${Date.now()}-${Math.random()}`)
    templates.value = await listQuickReplies()
    if (categoryId.value > 0 && !visibleCategories.value.some((item) => item.id === categoryId.value)) {
      categoryId.value = visibleCategories.value[0]?.id ?? 0
    }
    feedback.value = template.favorite ? '已取消收藏，使用记录仍保留' : '已加入个人收藏'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    actionBusy.value = false
  }
}

async function recordUsage(sendStatus: 'COPIED' | 'SDK_SUCCESS' | 'SDK_FAILED' | 'SDK_UNKNOWN', clientResult: string): Promise<void> {
  const current = preview.value
  if (!current || !hasCustomerContext.value) return
  await recordQuickReplyUsage(current.templateId, {
    customerId: props.customerId!,
    externalUserid: props.externalUserid!.trim(),
    scene: templates.value.find((template) => template.id === current.templateId)?.scene ?? '',
    originalContent: current.content,
    finalContent: draft.value,
    edited: current.content !== draft.value,
    sendStatus,
    clientResult,
  }, newUsageKey())
  usageKey = ''
}

function missingLabel(code: string): string {
  return variables.value.find((item) => item.variableCode === code)?.variableName ?? code
}

watch(
  () => [props.customerId, props.externalUserid, props.chatId, props.chatEntry],
  () => { resetSelection() },
)

onMounted(() => { void load() })
</script>

<template>
  <section class="quick-reply-panel" data-testid="quick-reply-panel">
    <div class="quick-reply-heading"><div><strong>快捷话术</strong><p>选择场景模板，预览后由员工人工复制或发送。</p></div><span>仅人工操作</span></div>
    <div v-if="!allowed" class="quick-reply-empty">当前账号没有快捷话术使用权限</div>
    <div v-else-if="loading" class="quick-reply-empty">正在读取话术库…</div>
    <template v-else>
      <div v-if="errorMessage" role="alert" class="quick-reply-alert">{{ errorMessage }}</div>
      <div v-if="isGroupChat && !hasCustomerId" class="quick-reply-info" data-testid="quick-reply-group-hint">
        当前处于群聊模式：可直接选用并发送不含客户变量的通用话术；含客户变量的话术需先在群成员中选择已关联客户。
      </div>

      <div class="quick-reply-toolbar">
        <div class="quick-reply-search-wrap">
          <input
            v-model="searchKeyword"
            type="search"
            class="quick-reply-search"
            data-testid="quick-reply-search"
            aria-label="搜索快捷话术"
            placeholder="搜索话术名称或内容…"
          >
          <button
            v-if="searchKeyword"
            type="button"
            class="search-clear"
            aria-label="清空搜索"
            @click="searchKeyword = ''"
          >
            清空
          </button>
        </div>
        <div class="quick-reply-filters" role="group" aria-label="快捷筛选">
          <button
            type="button"
            :class="{ selected: filterMode === 'ALL' }"
            :aria-pressed="filterMode === 'ALL'"
            data-testid="quick-reply-filter-all"
            @click="selectFilterMode('ALL')"
          >
            全部
          </button>
          <button
            type="button"
            :class="{ selected: filterMode === 'FAVORITE' }"
            :aria-pressed="filterMode === 'FAVORITE'"
            data-testid="quick-reply-filter-favorite"
            @click="selectFilterMode('FAVORITE')"
          >
            我的收藏
          </button>
          <button
            type="button"
            :class="{ selected: filterMode === 'RECENT' }"
            :aria-pressed="filterMode === 'RECENT'"
            data-testid="quick-reply-filter-recent"
            @click="selectFilterMode('RECENT')"
          >
            最近常用
          </button>
        </div>
      </div>

      <div v-if="showCategoryTabs" class="quick-reply-categories" aria-label="话术分类">
        <button v-for="category in visibleCategories" :key="category.id" type="button" :class="{ selected: categoryId === category.id }" :data-testid="`quick-reply-category-${category.id}`" @click="selectCategory(category.id)">{{ category.name }}</button>
      </div>
      <div v-if="visibleTemplates.length" class="quick-reply-templates">
        <article v-for="template in visibleTemplates" :key="template.id" class="template-entry" :class="{ selected: templateId === template.id }">
          <button class="template-select" type="button" :data-testid="`quick-reply-template-${template.id}`" @click="selectTemplate(template)"><strong>{{ template.name }}</strong><span class="template-content">{{ template.content }}</span></button>
          <div class="template-meta">
            <span v-if="!hasCustomerId && isStaticTemplate(template)" class="static-badge">群聊可用</span>
            <span v-else-if="!hasCustomerId" class="need-customer-badge">需选客户</span>
            <span v-if="template.featured">公司推荐</span>
            <span v-if="template.aiEnabled">AI 候选</span>
            <span v-if="template.usageCount > 0">使用 {{ template.usageCount }} 次</span>
            <span v-if="template.lastUsedAt">最近使用</span>
            <button class="favorite-toggle" type="button" :disabled="actionBusy" :data-testid="`quick-reply-favorite-${template.id}`" :aria-label="template.favorite ? `取消收藏 ${template.name}` : `收藏 ${template.name}`" @click="toggleFavorite(template)">{{ template.favorite ? '已收藏' : '收藏' }}</button>
          </div>
        </article>
      </div>
      <div v-else-if="!loading" class="quick-reply-empty"><strong>{{ emptyHeading }}</strong><p>{{ emptyHint }}</p></div>

      <div v-if="previewBusy" class="quick-reply-empty">正在按当前客户真实数据生成预览…</div>
      <div v-if="preview" class="quick-reply-preview">
        <div class="preview-heading"><span>发送前预览</span><small>{{ preview.allowEdit ? '可编辑' : '内容固定' }}</small></div>
        <textarea v-model="draft" data-testid="quick-reply-draft" :readonly="!preview.allowEdit || actionBusy" rows="6" maxlength="4000" aria-label="话术预览与编辑" />
        <div v-if="preview.missingVariables.length" class="missing-variables" data-testid="missing-variables">
          <strong>{{ hasCustomerId ? '缺少必要信息，不能发送' : '含客户变量，需先选择关联客户后才可发送' }}</strong>
          <span v-for="code in preview.missingVariables" :key="code">{{ missingLabel(code) }}</span>
        </div>
        <p class="preview-note">
          {{ hasCustomerId ? '订单、次数、日期和机构信息来自 CRM 服务端，不由页面输入决定。' : '当前为通用静态话术预览，未注入客户私有信息；发送前请人工确认群聊对象。' }}
        </p>
        <div class="quick-reply-actions">
          <button class="copy-button" type="button" data-testid="quick-reply-copy" :disabled="!canUse" @click="copy">{{ actionBusy ? '处理中…' : '复制话术' }}</button>
          <button v-if="isSendableEntry" class="send-button" type="button" data-testid="quick-reply-send" :disabled="!canSendCurrentChat" @click="send">{{ isGroupChat ? '发送到当前群聊' : '发送到当前会话' }}</button>
        </div>
        <p v-if="!isSendableEntry" class="preview-note">当前是客户资料页，可复制话术；请进入客户单聊或群聊后再发送。</p>
      </div>
      <div v-if="feedback" role="status" class="quick-reply-feedback">{{ feedback }}</div>
    </template>
  </section>
</template>

<style scoped>
.quick-reply-panel { color: #29483d; }
.quick-reply-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.quick-reply-heading strong { font-size: 14px; }
.quick-reply-heading p { margin: 5px 0 0; color: #84978d; font-size: 11px; line-height: 1.7; }
.quick-reply-heading > span { padding: 4px 6px; border-radius: 5px; color: #517364; background: #e8f0eb; font-size: 9px; white-space: nowrap; }
.quick-reply-info { margin-bottom: 12px; padding: 9px 11px; border: 1px solid #d5e5dc; border-radius: 8px; color: #3d6b57; background: #eff6f2; font-size: 11px; line-height: 1.7; }
.quick-reply-toolbar { display: grid; gap: 9px; margin-bottom: 12px; }
.quick-reply-search-wrap { position: relative; display: flex; align-items: center; }
.quick-reply-search { box-sizing: border-box; width: 100%; min-height: 36px; padding: 7px 46px 7px 11px; border: 1px solid #d6e3dc; border-radius: 8px; outline: none; color: #29483d; background: #fcfefc; font: inherit; font-size: 12px; }
.quick-reply-search:focus { border-color: #76a58f; box-shadow: 0 0 0 3px rgb(51 111 84 / 8%); }
.quick-reply-search::placeholder { color: #97a89f; }
.search-clear { position: absolute; right: 8px; padding: 3px 6px; border: 0; border-radius: 4px; color: #6c887b; background: #eef4f0; font: inherit; font-size: 10px; cursor: pointer; }
.quick-reply-filters { display: flex; gap: 7px; }
.quick-reply-filters button { flex: 1; padding: 6px 8px; border: 1px solid #dce6e0; border-radius: 7px; color: #6c8579; background: #f9fbfa; font: inherit; font-size: 11px; cursor: pointer; }
.quick-reply-filters button.selected { border-color: #6ea089; color: #24604a; background: #e6f2ec; font-weight: 650; }
.quick-reply-categories { display: flex; gap: 7px; padding-bottom: 12px; overflow-x: auto; }
.quick-reply-categories button { flex: 0 0 auto; padding: 6px 10px; border: 1px solid #dce6e0; border-radius: 7px; color: #718a7e; background: #f9fbfa; font: inherit; font-size: 11px; cursor: pointer; }
.quick-reply-categories button.selected { border-color: #8eb5a2; color: #2e6d55; background: #eaf3ee; font-weight: 650; }
.quick-reply-templates { display: grid; gap: 9px; }
.template-entry { overflow: hidden; border: 1px solid #e1e9e5; border-radius: 10px; color: #36584a; background: #fdfefd; }
.template-entry.selected { border-color: #8fb7a4; background: #f0f6f2; }
.template-select { display: grid; width: 100%; gap: 6px; padding: 13px 14px 8px; border: 0; color: #36584a; background: transparent; font: inherit; text-align: left; cursor: pointer; }
.template-select strong { font-size: 12px; }
.template-content { display: -webkit-box; overflow: hidden; color: #899c92; font-size: 10px; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.template-meta { display: flex; align-items: center; gap: 5px; padding: 0 12px 9px; flex-wrap: wrap; }
.template-meta > span { color: #84998e; font-size: 9px; }
.static-badge { padding: 1px 5px; border-radius: 4px; color: #2d6c53 !important; background: #e5f2eb; }
.need-customer-badge { padding: 1px 5px; border-radius: 4px; color: #8f6743 !important; background: #f7efe5; }
.favorite-toggle { margin-left: auto; padding: 3px 5px; border: 0; color: #39745a; background: transparent; font: inherit; font-size: 10px; font-weight: 650; cursor: pointer; }
.favorite-toggle:disabled { opacity: .5; cursor: not-allowed; }
.quick-reply-preview { margin-top: 16px; padding-top: 15px; border-top: 1px solid #e3ebe6; }
.preview-heading { display: flex; justify-content: space-between; margin-bottom: 8px; color: #486b5b; font-size: 11px; font-weight: 650; }
.preview-heading small { color: #8a9e93; font-weight: 400; }
.quick-reply-preview textarea { box-sizing: border-box; width: 100%; min-height: 132px; padding: 12px; border: 1px solid #d8e4dd; border-radius: 9px; outline: none; color: #2c4e40; background: #fdfefd; font: inherit; font-size: 12px; line-height: 1.85; resize: vertical; }
.quick-reply-preview textarea:focus { border-color: #76a58f; box-shadow: 0 0 0 3px rgb(51 111 84 / 8%); }
.missing-variables { display: flex; gap: 6px; margin-top: 10px; padding: 10px; border-radius: 8px; color: #9a624e; background: #fff3ed; flex-wrap: wrap; }
.missing-variables strong { width: 100%; font-size: 11px; }
.missing-variables span { padding: 2px 5px; border: 1px solid #edcfc2; border-radius: 4px; font-size: 10px; }
.preview-note { margin: 9px 0 0; color: #93a49b; font-size: 10px; line-height: 1.7; }
.quick-reply-actions { display: flex; gap: 9px; margin-top: 13px; }
.quick-reply-actions button { min-height: 39px; padding: 9px 13px; border-radius: 8px; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.copy-button { border: 1px solid #a9c4b5; color: #356c54; background: #f4f8f5; }
.send-button { flex: 1; border: 1px solid #276c59; color: #f5faf7; background: #276c59; }
.quick-reply-actions button:disabled { opacity: .45; cursor: not-allowed; }
.quick-reply-alert, .quick-reply-feedback { margin-bottom: 12px; padding: 10px 12px; border-radius: 8px; font-size: 11px; line-height: 1.7; }
.quick-reply-alert { color: #a04f3f; background: #fff0eb; }
.quick-reply-feedback { margin-top: 13px; margin-bottom: 0; color: #3d7259; background: #edf6f0; }
.quick-reply-empty { padding: 28px 15px; color: #8b9e94; text-align: center; font-size: 11px; }
.quick-reply-empty strong { color: #4b6b5c; font-size: 12px; }
.quick-reply-empty p { margin: 7px 0 0; line-height: 1.7; }
</style>
