<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { listInstitutionProfiles } from '../api/institution'
import type { InstitutionProfile } from '../api/institution'
import {
  createQuickReply,
  createQuickReplyCategory,
  disableQuickReply,
  disableQuickReplyCategory,
  getQuickReplyStatistics,
  listQuickReplyCategories,
  listQuickReplies,
  listQuickReplyVariables,
  previewQuickReply,
  updateQuickReply,
  updateQuickReplyCategory,
} from '../api/quickReply'
import type {
  QuickReplyCategory,
  QuickReplyCategoryInput,
  QuickReplyPreview,
  QuickReplyTemplate,
  QuickReplyTemplateInput,
  QuickReplyUsageStat,
  QuickReplyVariable,
} from '../api/quickReply'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

type Tab = 'TEMPLATES' | 'CATEGORIES' | 'STATISTICS'
type ScopeType = QuickReplyCategory['scopeType']
interface TemplateForm {
  id: number | null
  categoryId: number | null
  name: string
  code: string
  content: string
  scene: string
  allowEdit: boolean
  requirePreview: boolean
  aiEnabled: boolean
  featured: boolean
  sortNo: number
  version: number
  reason: string
}
interface CategoryForm {
  id: number | null
  name: string
  code: string
  parentId: number | null
  scopeType: ScopeType
  scopeId: number | null
  sortNo: number
  version: number
  reason: string
}

const auth = useAuthStore()
const allowed = computed(() => auth.hasPermission('quickreply:manage'))
const activeTab = ref<Tab>('TEMPLATES')
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const templates = ref<QuickReplyTemplate[]>([])
const categories = ref<QuickReplyCategory[]>([])
const variables = ref<QuickReplyVariable[]>([])
const institutions = ref<InstitutionProfile[]>([])
const keyword = ref('')
const categoryFilter = ref(0)
const templateForm = ref<TemplateForm | null>(null)
const contentInput = ref<HTMLTextAreaElement | null>(null)
const previewCustomerId = ref<number | null>(null)
const preview = ref<QuickReplyPreview | null>(null)
const previewBusy = ref(false)
const categoryEditing = ref(false)
const categoryForm = ref<CategoryForm>(blankCategory())
const statistics = ref<QuickReplyUsageStat[]>([])
const statisticsLoading = ref(false)
const statisticsFrom = ref('')
const statisticsTo = ref('')
let templateKey = ''
let templatePayload = ''
let categoryKey = ''
let categoryPayload = ''

const activeTemplates = computed(() => templates.value.filter((item) => item.status === 'ACTIVE'))
const filteredTemplates = computed(() => activeTemplates.value.filter((item) => {
  const search = keyword.value.trim().toLowerCase()
  return (!categoryFilter.value || item.categoryId === categoryFilter.value)
    && (!search || `${item.name} ${item.code} ${item.content}`.toLowerCase().includes(search))
}))
const selectedTemplateCategory = computed(() => categories.value.find((item) => item.id === templateForm.value?.categoryId) ?? null)
const parentOptions = computed(() => categories.value.filter((item) => item.parentId === null && item.id !== categoryForm.value.id && item.status === 'ACTIVE'))
const activeCategoryCount = computed(() => categories.value.filter((item) => item.status === 'ACTIVE').length)

function blankCategory(): CategoryForm {
  return { id: null, name: '', code: '', parentId: null, scopeType: 'GLOBAL', scopeId: null, sortNo: 0, version: 0, reason: '' }
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function newKey(prefix: string): string {
  return `${prefix}-${typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`}`
}

const SCENE_LABELS: Record<string, string> = {
  FOLLOWUP: '日常跟进',
  PACKAGE_REMAINING: '套餐余次提醒',
  POSTOPERATIVE: '术后关怀',
  APPOINTMENT: '预约提醒',
  APPOINTMENT_REMINDER: '预约提醒',
  GREETING: '客户问候',
  WELCOME: '欢迎语',
  AFTER_SALES: '售后安抚',
  COMPLAINT: '客诉安抚',
  PROMOTION: '活动邀约',
  GENERAL: '通用场景',
}

function scopeLabel(type: ScopeType): string {
  return ({ GLOBAL: '全公司', DEPARTMENT: '当前部门', PERSONAL: '本人专用', INSTITUTION: '指定机构' } as Record<ScopeType, string>)[type]
}

function sceneLabel(code: string): string {
  if (!code) return '通用场景'
  return SCENE_LABELS[code] ?? '自定义场景'
}

function categoryName(id: number): string {
  return categories.value.find((item) => item.id === id)?.name ?? `分类 #${id}`
}

function variableLabel(code: string): string {
  return variables.value.find((item) => item.variableCode === code)?.variableName ?? code
}

async function loadBase(): Promise<void> {
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
    institutions.value = []
    if (auth.hasPermission('institution:read')) {
      try {
        institutions.value = (await listInstitutionProfiles({ page: 1, size: 100, status: 'ACTIVE' })).items
      } catch {
        institutions.value = []
      }
    }
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function switchTab(tab: Tab): void {
  activeTab.value = tab
  errorMessage.value = ''
  successMessage.value = ''
}

function openNewTemplate(): void {
  const first = categories.value.find((item) => item.status === 'ACTIVE')
  templateForm.value = {
    id: null, categoryId: first?.id ?? null, name: '', code: '', content: '', scene: 'FOLLOWUP',
    allowEdit: true, requirePreview: true, aiEnabled: false, featured: false, sortNo: 0, version: 0, reason: '',
  }
  preview.value = null
  previewCustomerId.value = null
  templateKey = ''
  templatePayload = ''
  errorMessage.value = ''
}

function openTemplate(item: QuickReplyTemplate): void {
  templateForm.value = {
    id: item.id, categoryId: item.categoryId, name: item.name, code: item.code, content: item.content,
    scene: item.scene, allowEdit: item.allowEdit, requirePreview: item.requirePreview, aiEnabled: item.aiEnabled,
    featured: item.featured, sortNo: item.sortNo, version: item.version, reason: '',
  }
  preview.value = null
  previewCustomerId.value = null
  templateKey = ''
  templatePayload = ''
  errorMessage.value = ''
  successMessage.value = ''
}

function templateBody(form: TemplateForm): QuickReplyTemplateInput | null {
  const category = selectedTemplateCategory.value
  if (!category || !form.categoryId) return null
  return {
    categoryId: form.categoryId, name: form.name.trim(), code: form.code.trim().toUpperCase(), messageType: 'TEXT',
    content: form.content.trim(), scene: form.scene.trim().toUpperCase(), scopeType: category.scopeType,
    scopeId: category.scopeId, allowEdit: form.allowEdit, requirePreview: form.requirePreview,
    aiEnabled: form.aiEnabled, featured: form.featured, sortNo: Number(form.sortNo),
    ...(form.id === null ? {} : { version: form.version }), reason: form.reason.trim(),
  }
}

async function saveTemplate(): Promise<void> {
  const form = templateForm.value
  if (!form || saving.value) return
  const body = templateBody(form)
  if (!body || !body.name || !body.code || !body.content || !body.scene || !body.reason) {
    errorMessage.value = '请完整填写分类、名称、编码、场景、正文和本次操作理由'
    return
  }
  if (!/^[A-Z][A-Z0-9_]{1,63}$/.test(body.code) || !/^[A-Z][A-Z0-9_]{1,63}$/.test(body.scene)) {
    errorMessage.value = '编码和场景仅使用大写字母、数字与下划线，并以字母开头'
    return
  }
  const serialized = JSON.stringify(body)
  if (serialized !== templatePayload) {
    templateKey = newKey('quick-reply-template-save')
    templatePayload = serialized
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    if (form.id === null) await createQuickReply(body, templateKey)
    else await updateQuickReply(form.id, body, templateKey)
    await loadBase()
    templateForm.value = null
    successMessage.value = '话术已保存，员工端将按作用域和排序读取最新启用配置'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function removeTemplate(): Promise<void> {
  const form = templateForm.value
  if (!form?.id || saving.value || !form.reason.trim()) {
    errorMessage.value = '停用前请在编辑区填写操作理由'
    return
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await disableQuickReply(form.id, { version: form.version, reason: form.reason.trim() }, newKey('quick-reply-template-disable'))
    await loadBase()
    templateForm.value = null
    successMessage.value = '话术已停用；历史使用日志与 AI 候选保留，不支持直接删除'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function insertVariable(code: string): Promise<void> {
  if (!templateForm.value) return
  const token = `{${code}}`
  const input = contentInput.value
  if (!input) {
    templateForm.value.content += token
    return
  }
  const start = input.selectionStart
  const end = input.selectionEnd
  templateForm.value.content = `${input.value.slice(0, start)}${token}${input.value.slice(end)}`
  await nextTick()
  input.focus()
  input.setSelectionRange(start + token.length, start + token.length)
  preview.value = null
}

async function renderPreview(): Promise<void> {
  const form = templateForm.value
  if (!form?.id || !previewCustomerId.value || previewBusy.value) return
  previewBusy.value = true
  errorMessage.value = ''
  preview.value = null
  try {
    preview.value = await previewQuickReply(form.id, previewCustomerId.value, form.scene)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    previewBusy.value = false
  }
}

function openNewCategory(): void {
  categoryForm.value = blankCategory()
  categoryEditing.value = true
  categoryKey = ''
  categoryPayload = ''
  errorMessage.value = ''
  successMessage.value = ''
}

function openCategory(item: QuickReplyCategory): void {
  categoryForm.value = {
    id: item.id, name: item.name, code: item.code, parentId: item.parentId, scopeType: item.scopeType,
    scopeId: item.scopeId, sortNo: item.sortNo, version: item.version, reason: '',
  }
  categoryEditing.value = true
  categoryKey = ''
  categoryPayload = ''
  errorMessage.value = ''
  successMessage.value = ''
}

function changeCategoryScope(): void {
  const form = categoryForm.value
  form.scopeId = form.scopeType === 'INSTITUTION' ? institutions.value[0]?.id ?? null : null
  form.parentId = null
}

function categoryBody(form: CategoryForm): QuickReplyCategoryInput {
  return {
    name: form.name.trim(), code: form.code.trim().toUpperCase(), parentId: form.parentId,
    scopeType: form.scopeType, scopeId: form.scopeType === 'INSTITUTION' ? form.scopeId : null,
    sortNo: Number(form.sortNo), ...(form.id === null ? {} : { version: form.version }), reason: form.reason.trim(),
  }
}

async function saveCategory(): Promise<void> {
  const form = categoryForm.value
  if (!categoryEditing.value || saving.value) return
  const body = categoryBody(form)
  if (!body.name || !body.code || !body.reason || (body.scopeType === 'INSTITUTION' && !body.scopeId)) {
    errorMessage.value = '请完整填写分类名称、编码、作用域和操作理由'
    return
  }
  if (!/^[A-Z][A-Z0-9_]{1,47}$/.test(body.code)) {
    errorMessage.value = '分类编码仅使用大写字母、数字与下划线，并以字母开头'
    return
  }
  const serialized = JSON.stringify(body)
  if (serialized !== categoryPayload) {
    categoryKey = newKey('quick-reply-category-save')
    categoryPayload = serialized
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    if (form.id === null) await createQuickReplyCategory(body, categoryKey)
    else await updateQuickReplyCategory(form.id, body, categoryKey)
    await loadBase()
    categoryEditing.value = false
    successMessage.value = '分类已保存；分类与话术的作用域必须一致'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function removeCategory(): Promise<void> {
  const form = categoryForm.value
  if (!form.id || saving.value || !form.reason.trim()) {
    errorMessage.value = '停用前请在编辑区填写操作理由'
    return
  }
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await disableQuickReplyCategory(form.id, { version: form.version, reason: form.reason.trim() }, newKey('quick-reply-category-disable'))
    await loadBase()
    categoryEditing.value = false
    successMessage.value = '分类已停用；若分类下仍有启用话术，系统将阻止停用'
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    saving.value = false
  }
}

async function loadStatistics(): Promise<void> {
  if (statisticsFrom.value && statisticsTo.value && statisticsFrom.value > statisticsTo.value) {
    errorMessage.value = '统计开始日期不能晚于结束日期'
    return
  }
  statisticsLoading.value = true
  errorMessage.value = ''
  try {
    statistics.value = await getQuickReplyStatistics({
      from: statisticsFrom.value || undefined, to: statisticsTo.value || undefined,
    })
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    statisticsLoading.value = false
  }
}

function formatLastUsed(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Shanghai',
  }).format(date)
}

watch(activeTab, (tab) => { if (tab === 'STATISTICS') void loadStatistics() })
onMounted(() => { void loadBase() })
</script>

<template>
  <div class="proto-page quick-reply-admin">
    <PageHeader title="快捷话术库" description="把高频服务表达沉淀为受控模板；业务变量由系统自动解析填充，员工始终人工确认后使用。">
      <template #actions>
        <button v-if="allowed" class="button-primary" type="button" data-testid="new-template" @click="switchTab('TEMPLATES'); openNewTemplate()">新建话术</button>
      </template>
    </PageHeader>

    <PageCard v-if="!allowed" class="admin-card">
      <div class="admin-empty" data-testid="quick-reply-admin-denied"><strong>没有话术管理权限</strong><span>请联系管理员为当前岗位配置快捷话术管理权限；客户数据权限不会因本页面扩大。</span></div>
    </PageCard>
    <template v-else>
      <div class="admin-notice"><strong>配置变更可审计</strong><span>编码创建后不可修改；停用不删除历史话术、AI 候选或使用日志。AI 候选开关不等于自动发送。</span></div>
      <nav class="admin-tabs" aria-label="话术管理分类">
        <button type="button" :class="{ active: activeTab === 'TEMPLATES' }" data-testid="templates-tab" @click="switchTab('TEMPLATES')">话术配置 <span>{{ activeTemplates.length }}</span></button>
        <button type="button" :class="{ active: activeTab === 'CATEGORIES' }" data-testid="categories-tab" @click="switchTab('CATEGORIES')">分类与作用域 <span>{{ activeCategoryCount }}</span></button>
        <button type="button" :class="{ active: activeTab === 'STATISTICS' }" data-testid="statistics-tab" @click="switchTab('STATISTICS')">使用统计</button>
      </nav>
      <div v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</div>
      <div v-if="successMessage" class="admin-success" role="status">{{ successMessage }}</div>

      <div v-if="activeTab === 'TEMPLATES'" class="workspace" :class="{ editing: templateForm }">
        <PageCard class="admin-card library-card" title="启用中的话术" subtitle="员工端按收藏、最近使用、AI 候选与公司推荐排序">
          <template #actions><button class="button-secondary" type="button" @click="loadBase">刷新</button></template>
          <div class="list-filters">
            <input v-model="keyword" type="search" placeholder="搜索名称、编码或正文" aria-label="搜索话术">
            <select v-model.number="categoryFilter" aria-label="按分类筛选"><option :value="0">全部分类</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name }}</option></select>
          </div>
          <div v-if="loading" class="admin-empty">正在读取话术配置…</div>
          <div v-else-if="!filteredTemplates.length" class="admin-empty"><strong>当前筛选下没有启用话术</strong><span>可新建话术，或先建立一个启用的分类。</span></div>
          <div v-else class="template-table-wrap">
            <table class="template-table">
              <thead><tr><th>话术</th><th>分类 / 场景</th><th>使用策略</th><th>版本</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in filteredTemplates" :key="item.id" :class="{ selected: templateForm?.id === item.id }">
                  <td><button class="template-name" type="button" @click="openTemplate(item)">{{ item.name }}</button><p>{{ item.content }}</p></td>
                  <td><span>{{ categoryName(item.categoryId) }}</span><small class="scene-sub">{{ sceneLabel(item.scene) }}</small></td>
                  <td><div class="policy-tags"><span>{{ scopeLabel(item.scopeType) }}</span><span v-if="item.allowEdit">可编辑</span><span v-if="item.requirePreview">需预览</span><span v-if="item.aiEnabled" class="ai-tag">AI 候选</span><span v-if="item.featured">公司推荐</span></div></td>
                  <td>版本 {{ item.version }}</td>
                  <td><button class="table-action" type="button" :data-testid="`edit-template-${item.id}`" @click="openTemplate(item)">编辑</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </PageCard>

        <PageCard v-if="templateForm" class="admin-card editor-card" :title="templateForm.id ? '编辑话术' : '新建话术'" subtitle="作用域继承所选分类，避免跨机构或跨部门错配">
          <template #actions><button class="icon-close" type="button" aria-label="关闭话术编辑" @click="templateForm = null">×</button></template>
          <form class="editor-form" data-testid="template-form" @submit.prevent="saveTemplate">
            <label>所属分类<select v-model.number="templateForm.categoryId" data-testid="template-category" required><option v-for="item in categories" :key="item.id" :value="item.id" :disabled="item.status !== 'ACTIVE'">{{ item.name }} · {{ scopeLabel(item.scopeType) }}</option></select></label>
            <div class="form-grid">
              <label>话术名称<input v-model="templateForm.name" data-testid="template-name" maxlength="96" required placeholder="例如：术后第 7 天回访"></label>
              <label>模板编码<input v-model="templateForm.code" data-testid="template-code" maxlength="64" required :readonly="templateForm.id !== null" placeholder="大写字母、数字或下划线" @blur="templateForm.code = templateForm.code.toUpperCase()"></label>
              <label>业务场景编码<input v-model="templateForm.scene" data-testid="template-scene" maxlength="64" required placeholder="大写字母、数字或下划线" @blur="templateForm.scene = templateForm.scene.toUpperCase()"></label>
              <label>分类内排序<input v-model.number="templateForm.sortNo" type="number" min="0" required></label>
            </div>
            <label>话术正文<textarea ref="contentInput" v-model="templateForm.content" data-testid="template-content" rows="6" maxlength="2000" required placeholder="输入固定文本，或从下方插入变量" @input="preview = null" /></label>
            <div class="variable-picker"><span>插入真实变量</span><div><button v-for="item in variables" :key="item.variableCode" type="button" :title="item.description" @click="insertVariable(item.variableCode)">{{ item.variableName }}</button></div><small>缺失必填变量时，员工端会阻止使用；不支持自定义脚本表达式。</small></div>
            <fieldset class="policy-options"><legend>使用策略</legend><label><input v-model="templateForm.allowEdit" type="checkbox">允许员工编辑</label><label><input v-model="templateForm.requirePreview" type="checkbox">使用前必须预览</label><label><input v-model="templateForm.aiEnabled" data-testid="template-ai" type="checkbox">允许 AI 草拟候选</label><label><input v-model="templateForm.featured" data-testid="template-featured" type="checkbox">设为公司推荐</label></fieldset>
            <label>本次操作理由<textarea v-model="templateForm.reason" data-testid="template-reason" rows="2" maxlength="1000" required placeholder="写入不可覆盖审计，例如：新增术后回访标准表达" /></label>
            <div class="editor-actions"><button class="button-primary" type="submit" data-testid="save-template" :disabled="saving">{{ saving ? '正在保存…' : templateForm.id ? '保存修改' : '创建话术' }}</button><button v-if="templateForm.id" class="danger-action" type="button" data-testid="disable-template" :disabled="saving" @click="removeTemplate">停用当前话术</button></div>
          </form>
          <section v-if="templateForm.id" class="preview-tool">
            <div><strong>按真实客户检查</strong><span>只读预览，不复制、不发送</span></div>
            <div class="preview-controls"><input v-model.number="previewCustomerId" data-testid="preview-customer" type="number" min="1" placeholder="CRM 客户 ID" aria-label="预览客户 ID"><button class="button-secondary" type="button" data-testid="preview-template" :disabled="previewBusy || !previewCustomerId" @click="renderPreview">{{ previewBusy ? '正在读取…' : '生成预览' }}</button></div>
            <div v-if="preview" class="preview-result" data-testid="template-preview"><p>{{ preview.content }}</p><span :class="{ blocked: !preview.canSend }">{{ preview.canSend ? '必填变量完整' : '缺少必填变量，员工端不可使用' }}</span><div v-if="preview.missingVariables.length"><code v-for="code in preview.missingVariables" :key="code">{{ variableLabel(code) }}</code></div></div>
          </section>
        </PageCard>
      </div>

      <div v-else-if="activeTab === 'CATEGORIES'" class="workspace" :class="{ editing: categoryEditing }">
        <PageCard class="admin-card" title="分类与可见范围" subtitle="分类最多两层；已有话术后不能直接改变分类作用域">
          <template #actions><button class="button-primary" type="button" data-testid="new-category" @click="openNewCategory">新建分类</button></template>
          <div v-if="!categories.length" class="admin-empty">暂无启用分类，请先新建。</div>
          <div v-else class="category-list"><article v-for="item in categories" :key="item.id"><div><strong>{{ item.name }}</strong></div><span>{{ scopeLabel(item.scopeType) }}</span><small>排序 {{ item.sortNo }} · 版本 {{ item.version }}</small><button class="table-action" type="button" :data-testid="`edit-category-${item.id}`" @click="openCategory(item)">编辑</button></article></div>
        </PageCard>
        <PageCard v-if="categoryEditing" class="admin-card editor-card" :title="categoryForm.id ? '编辑分类' : '新建分类'" subtitle="机构、部门和个人范围由系统再次校验">
          <form class="editor-form" data-testid="category-form" @submit.prevent="saveCategory">
            <div class="form-grid"><label>分类名称<input v-model="categoryForm.name" data-testid="category-name" maxlength="64" required></label><label>分类编码<input v-model="categoryForm.code" data-testid="category-code" maxlength="48" required :readonly="categoryForm.id !== null" @blur="categoryForm.code = categoryForm.code.toUpperCase()"></label><label>父分类<select v-model.number="categoryForm.parentId" data-testid="category-parent"><option :value="null">无（一级分类）</option><option v-for="item in parentOptions" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label>排序<input v-model.number="categoryForm.sortNo" type="number" min="0" required></label></div>
            <label>作用域<select v-model="categoryForm.scopeType" data-testid="category-scope" @change="changeCategoryScope"><option value="GLOBAL">全公司</option><option value="DEPARTMENT">当前部门</option><option value="PERSONAL">本人专用</option><option value="INSTITUTION">指定机构</option></select></label>
            <label v-if="categoryForm.scopeType === 'INSTITUTION'">机构<select v-model.number="categoryForm.scopeId" data-testid="category-institution" required><option :value="null" disabled>请选择机构</option><option v-for="item in institutions" :key="item.id" :value="item.id">{{ item.shortName || item.fullName }}</option></select><small v-if="!institutions.length" class="field-warning">没有可读取的启用机构，请先在机构中心配置。</small></label>
            <label>本次操作理由<textarea v-model="categoryForm.reason" data-testid="category-reason" rows="2" maxlength="1000" required /></label>
            <div class="editor-actions"><button class="button-primary" type="submit" data-testid="save-category" :disabled="saving">保存分类</button><button v-if="categoryForm.id" class="danger-action" type="button" data-testid="disable-category" :disabled="saving" @click="removeCategory">停用当前分类</button><button class="button-secondary" type="button" @click="categoryEditing = false">取消</button></div>
          </form>
        </PageCard>
      </div>

      <PageCard v-else class="admin-card" title="使用统计" subtitle="按历史使用记录聚合；发送成功率仅统计企微发送回执，不把复制或客户已读混入分母">
        <form class="statistics-filter" @submit.prevent="loadStatistics"><label>开始日期<input v-model="statisticsFrom" data-testid="statistics-from" type="date"></label><label>结束日期<input v-model="statisticsTo" data-testid="statistics-to" type="date"></label><button class="button-primary" type="submit" data-testid="load-statistics" :disabled="statisticsLoading">{{ statisticsLoading ? '正在统计…' : '查询统计' }}</button></form>
        <div v-if="statisticsLoading" class="admin-empty">正在统计真实使用记录…</div>
        <div v-else-if="!statistics.length" class="admin-empty">当前时间范围内暂无使用记录。</div>
        <div v-else class="template-table-wrap"><table class="template-table statistics-table"><thead><tr><th>话术</th><th>总使用</th><th>复制</th><th>企微发送成功</th><th>失败 / 未知</th><th>编辑数</th><th>企微发送成功率</th><th>最近使用</th></tr></thead><tbody><tr v-for="item in statistics" :key="item.templateId"><td><strong>{{ item.templateName }}</strong></td><td>{{ item.totalCount }}</td><td>{{ item.copiedCount }}</td><td>{{ item.successCount }}</td><td>{{ item.failedCount }} / {{ item.unknownCount }}</td><td>{{ item.editedCount }}</td><td>{{ item.successRate === null ? '—' : `${item.successRate}%` }}</td><td>{{ formatLastUsed(item.lastUsedAt) }}</td></tr></tbody></table></div>
      </PageCard>
    </template>
  </div>
</template>

<style scoped>
.quick-reply-admin { --qr-ink: #28483c; --qr-muted: #80958a; --qr-line: #e2eae5; }
.admin-card { min-width: 0; }
.button-primary, .button-secondary { min-height: 38px; padding: 8px 14px; border-radius: 7px; font: inherit; font-size: 12px; font-weight: 650; white-space: nowrap; cursor: pointer; transition: background-color .16s ease-out, border-color .16s ease-out; }
.button-primary { border: 1px solid #2e6e55; color: #f3f9f5; background: #2e6e55; }
.button-secondary { border: 1px solid #bdd2c5; color: #397158; background: #f8fbf9; }
.button-primary:hover:not(:disabled) { border-color: #245a45; background: #245a45; }
.button-secondary:hover:not(:disabled) { border-color: #79a58e; background: #edf5f0; }
.button-primary:disabled, .button-secondary:disabled, .danger-action:disabled { opacity: .5; cursor: not-allowed; }
.admin-empty { display: grid; gap: 8px; padding: 42px 20px; color: var(--qr-muted); font-size: 12px; text-align: center; }
.admin-empty strong { color: var(--qr-ink); font-size: 14px; }
.admin-notice { display: flex; gap: 16px; align-items: baseline; margin-bottom: 18px; padding: 13px 16px; border: 1px solid #dce8df; border-radius: 9px; color: #527161; background: #f0f6f2; font-size: 11px; line-height: 1.7; }
.admin-notice strong { flex: 0 0 auto; color: #315c47; font-size: 12px; }
.admin-tabs { display: flex; gap: 27px; margin-bottom: 18px; border-bottom: 1px solid var(--qr-line); }
.admin-tabs button { position: relative; padding: 10px 0 13px; border: 0; color: #7b9185; background: transparent; font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; }
.admin-tabs button.active { color: #2f6b52; }
.admin-tabs button.active::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; background: #397a5d; content: ''; }
.admin-tabs button span { margin-left: 4px; color: #92a398; font-size: 10px; }
.admin-alert, .admin-success { margin-bottom: 14px; padding: 11px 14px; border-radius: 8px; font-size: 12px; line-height: 1.7; }
.admin-alert { color: #9b503d; background: #fff0e9; }
.admin-success { color: #3d6e56; background: #ebf4ee; }
.workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; align-items: start; }
.workspace.editing { grid-template-columns: minmax(0, 1.45fr) minmax(330px, .85fr); }
.list-filters { display: grid; grid-template-columns: minmax(180px, 1fr) 190px; gap: 10px; margin-bottom: 16px; }
.list-filters input, .list-filters select, .editor-form input, .editor-form select, .editor-form textarea, .preview-controls input, .statistics-filter input { box-sizing: border-box; width: 100%; min-height: 38px; padding: 8px 10px; border: 1px solid #d6e2da; border-radius: 7px; outline: none; color: #365647; background: #fcfefc; font: inherit; font-size: 12px; }
.editor-form textarea { line-height: 1.75; resize: vertical; }
.list-filters input:focus, .editor-form input:focus, .editor-form select:focus, .editor-form textarea:focus, .preview-controls input:focus { border-color: #7ca58f; box-shadow: 0 0 0 3px rgb(49 111 81 / 7%); }
.template-table-wrap { overflow-x: auto; }
.template-table { width: 100%; border-collapse: collapse; color: #466454; font-size: 12px; }
.template-table th { padding: 10px 12px; border-bottom: 1px solid var(--qr-line); color: #82968b; font-size: 10px; font-weight: 600; text-align: left; white-space: nowrap; }
.template-table td { padding: 14px 12px; border-bottom: 1px solid #edf2ef; vertical-align: top; }
.template-table tr.selected td { background: #f2f7f4; }
.template-table td code, .template-table td .scene-sub, .category-list code { display: block; margin-top: 4px; color: #8a9d92; font-size: 10px; overflow-wrap: anywhere; }
.template-table td p { display: -webkit-box; max-width: 330px; margin: 8px 0 0; overflow: hidden; color: #7c9186; font-size: 11px; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.template-name { padding: 0; border: 0; color: #315e48; background: transparent; font: inherit; font-weight: 700; cursor: pointer; }
.policy-tags { display: flex; max-width: 190px; gap: 5px; flex-wrap: wrap; }
.policy-tags span { padding: 3px 5px; border-radius: 4px; color: #6e8779; background: #eef3ef; font-size: 9px; white-space: nowrap; }
.policy-tags .ai-tag { color: #416f5c; background: #e5f0e9; }
.table-action { padding: 5px 9px; border: 1px solid #c8d8ce; border-radius: 6px; color: #3f7258; background: transparent; font: inherit; font-size: 11px; cursor: pointer; white-space: nowrap; }
.editor-form { display: grid; gap: 14px; }
.editor-form > label, .form-grid label, .statistics-filter label { display: grid; gap: 6px; color: #5a7566; font-size: 11px; font-weight: 650; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.editor-form input[readonly] { color: #81958a; background: #f0f4f1; }
.icon-close { padding: 3px 7px; border: 0; color: #7f9589; background: transparent; font-size: 20px; cursor: pointer; }
.variable-picker { padding: 11px 0; border-block: 1px solid #e6ede8; }
.variable-picker > span { color: #5a7566; font-size: 11px; font-weight: 650; }
.variable-picker > div { display: flex; gap: 5px; margin-top: 8px; flex-wrap: wrap; }
.variable-picker button { padding: 4px 6px; border: 1px solid #d7e4db; border-radius: 5px; color: #507561; background: #f4f8f5; font: inherit; font-size: 10px; cursor: pointer; }
.variable-picker small { display: block; margin-top: 8px; color: #8c9e94; font-size: 10px; line-height: 1.7; }
.policy-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; padding: 12px; border: 1px solid #e0e9e3; border-radius: 8px; }
.policy-options legend { padding: 0 5px; color: #5a7566; font-size: 11px; font-weight: 650; }
.policy-options label { display: flex; align-items: center; gap: 7px; color: #577362; font-size: 11px; }
.policy-options input { width: 14px; min-height: 14px; accent-color: #34765a; }
.editor-actions { display: flex; align-items: center; gap: 9px; padding-top: 3px; }
.editor-actions .button-primary, .editor-actions .danger-action, .editor-actions .button-secondary { min-height: 38px; padding: 8px 13px; font-size: 12px; }
.danger-action { border: 1px solid #ddc4ba; border-radius: 7px; color: #9c5f4b; background: transparent; font: inherit; cursor: pointer; }
.preview-tool { margin-top: 20px; padding-top: 16px; border-top: 1px solid #e2eae5; }
.preview-tool > div:first-child { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.preview-tool strong { color: #4d6e5b; font-size: 11px; }
.preview-tool span { color: #8a9d92; font-size: 10px; }
.preview-controls { display: grid; grid-template-columns: 1fr auto; gap: 8px; margin-top: 10px; }
.preview-result { margin-top: 12px; padding: 12px; border-radius: 7px; background: #f0f5f2; }
.preview-result p { margin: 0 0 9px; color: #426550; font-size: 12px; line-height: 1.8; white-space: pre-wrap; }
.preview-result > span { color: #407158; }
.preview-result > span.blocked { color: #9c604a; }
.preview-result code { margin-right: 5px; color: #98654e; font-size: 10px; }
.category-list { display: grid; }
.category-list article { display: grid; grid-template-columns: minmax(180px, 1fr) 110px 130px auto; gap: 15px; align-items: center; padding: 14px 4px; border-bottom: 1px solid #e7ede9; }
.category-list strong { color: #3c614e; font-size: 13px; }
.category-list article > span, .category-list small { color: #7c9184; font-size: 11px; }
.field-warning { color: #a16b4f; font-weight: 400; }
.statistics-filter { display: grid; grid-template-columns: 190px 190px auto; gap: 12px; align-items: end; margin-bottom: 20px; }
.statistics-filter button { min-height: 38px; padding-inline: 18px; }
.statistics-table td strong { display: block; color: #3a604c; }
.statistics-table td:not(:first-child) { white-space: nowrap; }
@media (max-width: 1180px) { .workspace.editing { grid-template-columns: minmax(0, 1fr); } .editor-card { max-width: none; } }
@media (max-width: 720px) { .admin-notice { display: grid; gap: 4px; } .admin-tabs { gap: 18px; overflow-x: auto; } .admin-tabs button { white-space: nowrap; } .list-filters, .form-grid, .statistics-filter { grid-template-columns: 1fr; } .policy-options { grid-template-columns: 1fr; } .category-list article { grid-template-columns: 1fr auto; } .category-list article > span, .category-list small { display: none; } }
</style>
