<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  createCustomer,
  listCustomers,
  updateCustomer,
  updateCustomerTags,
} from '../api/customer'
import type { CustomerDetail, CustomerListItem } from '../api/customer'
import { getDictionaryOptions } from '../api/dictionary'
import type { DictionaryOption } from '../api/dictionary'
import { createFollowup } from '../api/followup'
import { createAppointment, getAppointmentOptions } from '../api/fulfillment'
import type { AppointmentOptionsResponse } from '../api/fulfillment'
import { createTask } from '../api/task'
import { linkWecomSidebarContact } from '../api/wecomSidebar'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

type LinkedActionMode = 'NONE' | 'FOLLOWUP' | 'TAGS' | 'APPOINTMENT'
type UnlinkedMode = 'SEARCH' | 'CREATE'

const DEFAULT_CHANNELS: DictionaryOption[] = [
  { code: 'WECOM', label: '企业微信', sortNo: 1 },
  { code: 'PHONE', label: '电话', sortNo: 2 },
  { code: 'FACE_TO_FACE', label: '面谈', sortNo: 3 },
  { code: 'OTHER', label: '其他', sortNo: 4 },
]

const DEFAULT_TAGS: DictionaryOption[] = [
  { code: 'VIP', label: '贵宾客户', sortNo: 1 },
  { code: 'HIGH_INTENT', label: '高意向', sortNo: 2 },
  { code: 'NEW_7D', label: '近 7 天新增', sortNo: 3 },
  { code: 'REFERRAL_GOOD', label: '优质转介绍', sortNo: 4 },
]

const INTENT_OPTIONS = [
  { code: 'HIGH', label: '高意向' },
  { code: 'MEDIUM', label: '中意向' },
  { code: 'LOW', label: '低意向' },
]

const props = defineProps<{
  customer: CustomerDetail | null
  externalUserid: string
}>()

const emit = defineEmits<{
  updated: []
  linked: [customerId: number]
}>()

const auth = useAuthStore()

// Permissions
const canWriteFollowup = computed(() => auth.hasPermission('followup:write'))
const canWriteTask = computed(() => auth.hasPermission('task:write'))
const canWriteCustomer = computed(() => auth.hasPermission('customer:write'))
const canReadCustomer = computed(() => auth.hasPermission('customer:read'))
const canCreateAppointment = computed(
  () => auth.hasPermission('appointment:write') && auth.hasPermission('appointment:read'),
)

const hasAnyLinkedAction = computed(
  () => canWriteFollowup.value || canWriteTask.value || canWriteCustomer.value || canCreateAppointment.value,
)

// Shared UI state
const activeAction = ref<LinkedActionMode>('NONE')
const busy = ref(false)
const errorMessage = ref('')
const feedback = ref('')

// Follow-up + reminder task state
const channelOptions = ref<DictionaryOption[]>([...DEFAULT_CHANNELS])
const channelsLoaded = ref(false)
const followupChannel = ref('WECOM')
const followupSummary = ref('')
const nextFollowupAt = ref('')
const createReminderTask = ref(false)
const taskTitle = ref('')
const taskPriority = ref<'LOW' | 'MEDIUM' | 'HIGH'>('MEDIUM')
let followupIdempotencyKey = ''

// Tags + intent state
const tagOptions = ref<DictionaryOption[]>([...DEFAULT_TAGS])
const tagsLoaded = ref(false)
const editingIntent = ref('')
const editingTags = ref<string[]>([])
const customTagInput = ref('')

// Quick appointment state
const appointmentOptions = ref<AppointmentOptionsResponse | null>(null)
const appointmentLoading = ref(false)
const appointmentError = ref('')
const selectedInstitutionId = ref<number | null>(null)
const selectedEntitlementId = ref<number | null>(null)
const plannedCount = ref(1)
const scheduledAt = ref('')
const appointmentRemark = ref('')
let appointmentIdempotencyKey = ''

// Unlinked customer search/create + bind state
const unlinkedMode = ref<UnlinkedMode>('SEARCH')
const searchKeyword = ref('')
const searching = ref(false)
const searchDone = ref(false)
const searchResults = ref<CustomerListItem[]>([])
const linkReason = ref('企微侧边栏人工核对关联')
const newDisplayName = ref('')
const newNickname = ref('')
const newMobile = ref('')
const newIntentLevel = ref('')
const newRemark = ref('')
let createCustomerKey = ''
let pendingCreatedCustomerId: number | null = null

const availableEntitlements = computed(() => {
  const list = appointmentOptions.value?.entitlements ?? []
  return list.filter((item) => item.availableCount > 0)
})

const eligibleInstitutions = computed(() => {
  const options = appointmentOptions.value
  if (!options) return []
  const allowedIds = new Set<number>()
  for (const item of availableEntitlements.value) {
    for (const id of item.institutionIds) allowedIds.add(id)
  }
  return options.institutions.filter((inst) => allowedIds.has(inst.id))
})

const filteredEntitlements = computed(() => {
  if (!selectedInstitutionId.value) return []
  return availableEntitlements.value.filter((item) => item.institutionIds.includes(selectedInstitutionId.value!))
})

const currentEntitlement = computed(
  () => filteredEntitlements.value.find((item) => item.id === selectedEntitlementId.value) ?? null,
)

function newIdempotencyKey(prefix: string): string {
  const rand = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${rand}`
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return `${error.message}（数据可能已更新，请刷新后重试）`
    return error.message
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function toIsoDateTime(localValue: string): string | undefined {
  const trimmed = localValue.trim()
  if (!trimmed) return undefined
  const parsed = new Date(trimmed)
  if (Number.isNaN(parsed.getTime())) return undefined
  return parsed.toISOString()
}

function tagLabel(code: string): string {
  return tagOptions.value.find((item) => item.code === code)?.label
    ?? DEFAULT_TAGS.find((item) => item.code === code)?.label
    ?? code
}

function syncProfileDraft(): void {
  editingIntent.value = props.customer?.intentLevel ?? ''
  editingTags.value = [...(props.customer?.tagCodes ?? [])]
  customTagInput.value = ''
}

watch(
  () => props.customer?.id,
  () => {
    activeAction.value = 'NONE'
    errorMessage.value = ''
    feedback.value = ''
    followupSummary.value = ''
    nextFollowupAt.value = ''
    createReminderTask.value = false
    taskTitle.value = ''
    followupIdempotencyKey = ''
    appointmentOptions.value = null
    appointmentError.value = ''
    selectedInstitutionId.value = null
    selectedEntitlementId.value = null
    plannedCount.value = 1
    scheduledAt.value = ''
    appointmentRemark.value = ''
    appointmentIdempotencyKey = ''
    syncProfileDraft()
  },
  { immediate: true },
)

watch(
  () => [props.customer?.intentLevel, props.customer?.tagCodes?.join(',')],
  () => {
    if (activeAction.value !== 'TAGS') syncProfileDraft()
  },
)

watch(
  () => props.externalUserid,
  () => {
    searchResults.value = []
    searchDone.value = false
    errorMessage.value = ''
    feedback.value = ''
    createCustomerKey = ''
    pendingCreatedCustomerId = null
  },
)

watch(selectedInstitutionId, () => {
  const first = filteredEntitlements.value[0]
  selectedEntitlementId.value = first?.id ?? null
  plannedCount.value = 1
})

async function ensureChannelsLoaded(): Promise<void> {
  if (channelsLoaded.value) return
  channelsLoaded.value = true
  try {
    const loaded = await getDictionaryOptions('communicationChannels')
    if (loaded.length) {
      channelOptions.value = loaded
      if (!loaded.some((item) => item.code === followupChannel.value)) {
        followupChannel.value = loaded[0]!.code
      }
    }
  } catch {
    // Keep DEFAULT_CHANNELS fallback silently
  }
}

async function ensureTagsLoaded(): Promise<void> {
  if (tagsLoaded.value) return
  try {
    const loaded = await getDictionaryOptions('customerTags')
    if (loaded.length) {
      tagOptions.value = loaded
    }
    tagsLoaded.value = true
  } catch {
    // Keep DEFAULT_TAGS fallback silently
  }
}

async function loadAppointmentData(): Promise<void> {
  if (!props.customer || !canCreateAppointment.value) return
  appointmentLoading.value = true
  appointmentError.value = ''
  try {
    const options = await getAppointmentOptions(props.customer.id)
    appointmentOptions.value = options
    const firstInst = eligibleInstitutions.value[0]
    selectedInstitutionId.value = firstInst?.id ?? null
    const firstEnt = filteredEntitlements.value[0]
    selectedEntitlementId.value = firstEnt?.id ?? null
    plannedCount.value = 1
  } catch (error) {
    appointmentError.value = errorText(error)
  } finally {
    appointmentLoading.value = false
  }
}

async function toggleAction(mode: LinkedActionMode): Promise<void> {
  errorMessage.value = ''
  feedback.value = ''
  if (activeAction.value === mode) {
    activeAction.value = 'NONE'
    return
  }
  activeAction.value = mode
  if (mode === 'FOLLOWUP') {
    await ensureChannelsLoaded()
  } else if (mode === 'TAGS') {
    syncProfileDraft()
    await ensureTagsLoaded()
  } else if (mode === 'APPOINTMENT') {
    await loadAppointmentData()
  }
}

async function submitFollowup(): Promise<void> {
  if (!props.customer || busy.value) return
  const summary = followupSummary.value.trim()
  const dueIso = toIsoDateTime(nextFollowupAt.value)
  if (canWriteFollowup.value && !summary) {
    errorMessage.value = '请填写本次跟进沟通内容'
    return
  }
  if (!canWriteFollowup.value && canWriteTask.value && !taskTitle.value.trim()) {
    errorMessage.value = '请填写回访提醒标题'
    return
  }
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    if (canWriteFollowup.value) {
      if (!followupIdempotencyKey) followupIdempotencyKey = newIdempotencyKey('sidebar-followup')
      await createFollowup(
        {
          customerId: props.customer.id,
          channelCode: followupChannel.value || 'WECOM',
          summary,
          nextFollowupAt: dueIso,
        },
        followupIdempotencyKey,
      )
      followupIdempotencyKey = ''
    }

    const shouldCreateTask = canWriteTask.value && (createReminderTask.value || !canWriteFollowup.value)
    if (shouldCreateTask) {
      const displayName = props.customer.displayName || props.customer.nickname || props.customer.customerNo
      await createTask({
        customerId: props.customer.id,
        taskType: 'FOLLOWUP',
        priority: taskPriority.value,
        title: taskTitle.value.trim() || `回访提醒：${displayName}`,
        description: summary || undefined,
        dueAt: dueIso,
      })
    }

    followupSummary.value = ''
    nextFollowupAt.value = ''
    createReminderTask.value = false
    taskTitle.value = ''
    feedback.value = shouldCreateTask
      ? '跟进记录与回访提醒任务已保存'
      : '跟进记录已追加保存（不覆盖历史流水）'
    emit('updated')
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

function togglePresetTag(code: string): void {
  if (editingTags.value.includes(code)) {
    editingTags.value = editingTags.value.filter((item) => item !== code)
  } else {
    editingTags.value = [...editingTags.value, code]
  }
}

function addCustomTag(): void {
  const value = customTagInput.value.trim()
  if (!value) return
  if (!editingTags.value.includes(value)) {
    editingTags.value = [...editingTags.value, value]
  }
  customTagInput.value = ''
}

function removeTag(code: string): void {
  editingTags.value = editingTags.value.filter((item) => item !== code)
}

async function submitTagsAndIntent(): Promise<void> {
  if (!props.customer || !canWriteCustomer.value || busy.value) return
  const intentChanged = (editingIntent.value || null) !== (props.customer.intentLevel || null)
  const prevTags = [...props.customer.tagCodes].sort().join('\u0000')
  const nextTags = [...editingTags.value].sort().join('\u0000')
  const tagsChanged = prevTags !== nextTags
  if (!intentChanged && !tagsChanged) {
    feedback.value = '标签与意向未发生变化'
    return
  }

  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    if (intentChanged && editingIntent.value) {
      await updateCustomer(props.customer.id, {
        intentLevel: editingIntent.value,
        version: props.customer.version,
      })
    }
    if (tagsChanged) {
      await updateCustomerTags(props.customer.id, editingTags.value)
    }
    feedback.value = '客户标签与意向已更新并记录审计'
    emit('updated')
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function submitQuickAppointment(): Promise<void> {
  if (!props.customer || !canCreateAppointment.value || busy.value) return
  if (!selectedInstitutionId.value || !selectedEntitlementId.value) {
    errorMessage.value = '请选择服务机构和有效权益批次'
    return
  }
  const isoScheduledAt = toIsoDateTime(scheduledAt.value)
  if (!isoScheduledAt) {
    errorMessage.value = '请选择计划到院时间'
    return
  }
  const entitlement = currentEntitlement.value
  if (!entitlement || !Number.isInteger(plannedCount.value) || plannedCount.value <= 0 || plannedCount.value > entitlement.availableCount) {
    errorMessage.value = '计划次数须为正整数且不超过当前可用次数'
    return
  }

  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  if (!appointmentIdempotencyKey) appointmentIdempotencyKey = newIdempotencyKey('sidebar-appt')
  try {
    await createAppointment(
      {
        customerId: props.customer.id,
        institutionId: selectedInstitutionId.value,
        scheduledAt: isoScheduledAt,
        remark: appointmentRemark.value.trim() || undefined,
        items: [{ entitlementId: selectedEntitlementId.value, count: plannedCount.value }],
      },
      appointmentIdempotencyKey,
    )
    appointmentIdempotencyKey = ''
    scheduledAt.value = ''
    appointmentRemark.value = ''
    plannedCount.value = 1
    feedback.value = '预约申请已创建（待合作机构确认接诊），未扣减权益次数'
    emit('updated')
  } catch (error) {
    errorMessage.value = `${errorText(error)}；重试将沿用同一幂等键`
  } finally {
    busy.value = false
  }
}

async function searchExistingCustomers(): Promise<void> {
  if (!canReadCustomer.value || searching.value) return
  searching.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    const res = await listCustomers({
      page: 1,
      size: 10,
      keyword: searchKeyword.value.trim() || undefined,
    })
    searchResults.value = res.items
    searchDone.value = true
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    searching.value = false
  }
}

async function bindExistingCustomer(targetCustomerId: number): Promise<void> {
  if (!canWriteCustomer.value || !props.externalUserid || busy.value) return
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    await linkWecomSidebarContact({
      externalUserid: props.externalUserid,
      customerId: targetCustomerId,
      reason: linkReason.value.trim() || '企微侧边栏人工核对关联',
    })
    feedback.value = '已成功关联 CRM 客户，正在刷新档案…'
    emit('linked', targetCustomerId)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}

async function createAndBindCustomer(): Promise<void> {
  if (!canWriteCustomer.value || !props.externalUserid || busy.value) return
  if (!newDisplayName.value.trim() && !newNickname.value.trim()) {
    errorMessage.value = '客户称呼或微信昵称至少填写一项'
    return
  }
  busy.value = true
  errorMessage.value = ''
  feedback.value = ''
  try {
    let customerId = pendingCreatedCustomerId
    if (!customerId) {
      if (!createCustomerKey) createCustomerKey = newIdempotencyKey('sidebar-customer-create')
      const created = await createCustomer(
        {
          displayName: newDisplayName.value.trim() || undefined,
          nickname: newNickname.value.trim() || undefined,
          mobile: newMobile.value.trim() || undefined,
          intentLevel: newIntentLevel.value || undefined,
          remark: newRemark.value.trim() || undefined,
        },
        createCustomerKey,
      )
      customerId = created.id
      pendingCreatedCustomerId = created.id
      createCustomerKey = ''
    }

    await linkWecomSidebarContact({
      externalUserid: props.externalUserid,
      customerId,
      reason: '企微侧边栏新建客户并关联',
    })
    pendingCreatedCustomerId = null
    newDisplayName.value = ''
    newNickname.value = ''
    newMobile.value = ''
    newIntentLevel.value = ''
    newRemark.value = ''
    feedback.value = '新客户已建档并完成企微关联，正在刷新…'
    emit('linked', customerId)
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!-- Mode 1: Linked customer quick CRM actions -->
  <section
    v-if="customer && hasAnyLinkedAction"
    class="quick-actions-panel"
    data-testid="wecom-quick-actions"
  >
    <div class="qa-bar" role="toolbar" aria-label="快捷业务操作">
      <button
        v-if="canWriteFollowup || canWriteTask"
        type="button"
        class="qa-trigger"
        :class="{ active: activeAction === 'FOLLOWUP' }"
        data-testid="quick-action-followup"
        @click="toggleAction('FOLLOWUP')"
      >
        + 记跟进
      </button>
      <button
        v-if="canWriteCustomer"
        type="button"
        class="qa-trigger"
        :class="{ active: activeAction === 'TAGS' }"
        data-testid="quick-action-tags"
        @click="toggleAction('TAGS')"
      >
        标签与意向
      </button>
      <button
        v-if="canCreateAppointment"
        type="button"
        class="qa-trigger"
        :class="{ active: activeAction === 'APPOINTMENT' }"
        data-testid="quick-action-appointment"
        @click="toggleAction('APPOINTMENT')"
      >
        快捷预约
      </button>
    </div>

    <div v-if="errorMessage" class="qa-alert" role="alert">{{ errorMessage }}</div>
    <div v-if="feedback" class="qa-feedback" role="status">{{ feedback }}</div>

    <!-- Fast Follow-up + Reminder Task -->
    <form
      v-if="activeAction === 'FOLLOWUP'"
      class="qa-card"
      data-testid="quick-followup-form"
      @submit.prevent="submitFollowup"
    >
      <div class="qa-card-head">
        <strong>快速登记跟进</strong>
        <button type="button" class="qa-close" @click="activeAction = 'NONE'">收起</button>
      </div>
      <template v-if="canWriteFollowup">
        <label for="qa-channel">沟通方式</label>
        <select id="qa-channel" v-model="followupChannel" data-testid="quick-followup-channel" :disabled="busy">
          <option v-for="opt in channelOptions" :key="opt.code" :value="opt.code">{{ opt.label }}</option>
        </select>

        <label for="qa-summary">跟进摘要</label>
        <textarea
          id="qa-summary"
          v-model="followupSummary"
          data-testid="quick-followup-summary"
          rows="3"
          maxlength="1000"
          :disabled="busy"
          placeholder="记录客户关注项目、顾虑或下一步计划（追加流水，不覆盖历史）"
        />

        <label for="qa-next-at">下次跟进时间 <small>选填</small></label>
        <input
          id="qa-next-at"
          v-model="nextFollowupAt"
          type="datetime-local"
          data-testid="quick-followup-next-at"
          :disabled="busy"
        >
      </template>

      <label v-if="canWriteTask && canWriteFollowup" class="qa-checkbox">
        <input v-model="createReminderTask" type="checkbox" data-testid="quick-followup-create-task" :disabled="busy">
        <span>同时创建回访提醒待办</span>
      </label>

      <div v-if="canWriteTask && (createReminderTask || !canWriteFollowup)" class="qa-subbox">
        <label for="qa-task-title">待办标题</label>
        <input
          id="qa-task-title"
          v-model="taskTitle"
          data-testid="quick-task-title"
          maxlength="120"
          :disabled="busy"
          :placeholder="`回访提醒：${customer.displayName || customer.nickname || customer.customerNo}`"
        >
        <label for="qa-task-priority">优先级</label>
        <select id="qa-task-priority" v-model="taskPriority" data-testid="quick-task-priority" :disabled="busy">
          <option value="HIGH">高优先级</option>
          <option value="MEDIUM">中优先级</option>
          <option value="LOW">低优先级</option>
        </select>
      </div>

      <button type="submit" class="qa-submit" data-testid="quick-followup-submit" :disabled="busy">
        {{ busy ? '正在提交…' : '保存跟进记录' }}
      </button>
    </form>

    <!-- Quick Tags + Intent Level -->
    <form
      v-else-if="activeAction === 'TAGS'"
      class="qa-card"
      data-testid="quick-tags-form"
      @submit.prevent="submitTagsAndIntent"
    >
      <div class="qa-card-head">
        <strong>更新意向与客户标签</strong>
        <button type="button" class="qa-close" @click="activeAction = 'NONE'">收起</button>
      </div>

      <label>意向程度</label>
      <div class="qa-pills">
        <button
          v-for="opt in INTENT_OPTIONS"
          :key="opt.code"
          type="button"
          class="qa-pill"
          :class="{ selected: editingIntent === opt.code }"
          :data-testid="`quick-intent-${opt.code}`"
          :disabled="busy"
          @click="editingIntent = opt.code"
        >
          {{ opt.label }}
        </button>
      </div>

      <label>客户标签</label>
      <div class="qa-pills">
        <button
          v-for="tag in tagOptions"
          :key="tag.code"
          type="button"
          class="qa-pill"
          :class="{ selected: editingTags.includes(tag.code) }"
          :data-testid="`quick-preset-tag-${tag.code}`"
          :disabled="busy"
          @click="togglePresetTag(tag.code)"
        >
          {{ tag.label }}
        </button>
      </div>

      <label for="qa-custom-tag">自定义标签</label>
      <div class="qa-inline-input">
        <input
          id="qa-custom-tag"
          v-model="customTagInput"
          data-testid="quick-custom-tag-input"
          maxlength="32"
          :disabled="busy"
          placeholder="输入标签后点击添加"
          @keydown.enter.prevent="addCustomTag"
        >
        <button type="button" class="qa-minor" data-testid="quick-custom-tag-add" :disabled="busy || !customTagInput.trim()" @click="addCustomTag">添加</button>
      </div>

      <div v-if="editingTags.length" class="qa-selected-tags">
        <span v-for="code in editingTags" :key="code" class="qa-tag-chip">
          {{ tagLabel(code) }}
          <button type="button" :aria-label="`移除标签 ${tagLabel(code)}`" :disabled="busy" @click="removeTag(code)">×</button>
        </span>
      </div>

      <button type="submit" class="qa-submit" data-testid="quick-tags-submit" :disabled="busy">
        {{ busy ? '正在保存…' : '保存标签与意向' }}
      </button>
    </form>

    <!-- Quick Appointment Request from Active Entitlements -->
    <form
      v-else-if="activeAction === 'APPOINTMENT'"
      class="qa-card"
      data-testid="quick-appointment-form"
      @submit.prevent="submitQuickAppointment"
    >
      <div class="qa-card-head">
        <strong>发起到院预约申请</strong>
        <button type="button" class="qa-close" @click="activeAction = 'NONE'">收起</button>
      </div>
      <p class="qa-note">预约仅登记履约计划，不扣减权益次数；实际到院后须由合作机构授权操作员确认核销。</p>

      <div v-if="appointmentLoading" class="qa-empty">正在读取可用权益与合作机构…</div>
      <div v-else-if="appointmentError" class="qa-alert" role="alert">
        {{ appointmentError }}
        <button type="button" class="qa-retry" @click="loadAppointmentData">重试</button>
      </div>
      <div v-else-if="!eligibleInstitutions.length" class="qa-empty">
        当前客户暂无可预约的生效权益或可用合作机构。
      </div>
      <template v-else>
        <label for="qa-appt-inst">合作机构</label>
        <select id="qa-appt-inst" v-model.number="selectedInstitutionId" data-testid="quick-appt-institution" :disabled="busy">
          <option v-for="inst in eligibleInstitutions" :key="inst.id" :value="inst.id">
            {{ inst.shortName || inst.fullName }}
          </option>
        </select>

        <label for="qa-appt-ent">可用权益批次</label>
        <select id="qa-appt-ent" v-model.number="selectedEntitlementId" data-testid="quick-appt-entitlement" :disabled="busy">
          <option v-for="ent in filteredEntitlements" :key="ent.id" :value="ent.id">
            {{ ent.projectName || `项目 #${ent.projectId}` }}（余 {{ ent.availableCount }} 次）
          </option>
        </select>

        <div class="qa-two-col">
          <div>
            <label for="qa-appt-count">计划次数</label>
            <input
              id="qa-appt-count"
              v-model.number="plannedCount"
              type="number"
              min="1"
              :max="currentEntitlement?.availableCount ?? 1"
              data-testid="quick-appt-count"
              :disabled="busy"
            >
          </div>
          <div>
            <label for="qa-appt-time">计划到院时间</label>
            <input
              id="qa-appt-time"
              v-model="scheduledAt"
              type="datetime-local"
              data-testid="quick-appt-time"
              :disabled="busy"
            >
          </div>
        </div>

        <label for="qa-appt-remark">预约备注 <small>选填</small></label>
        <input
          id="qa-appt-remark"
          v-model="appointmentRemark"
          data-testid="quick-appt-remark"
          maxlength="300"
          :disabled="busy"
          placeholder="仅填写履约提醒，不记录无关敏感信息"
        >

        <button type="submit" class="qa-submit" data-testid="quick-appointment-submit" :disabled="busy">
          {{ busy ? '正在创建预约…' : '提交预约申请' }}
        </button>
      </template>
    </form>
  </section>

  <!-- Mode 2: Unlinked WeCom external contact -> Search/Create & Link -->
  <section
    v-else-if="!customer && externalUserid"
    class="unlinked-actions-panel"
    data-testid="unlinked-customer-actions"
  >
    <div v-if="!canWriteCustomer" class="qa-empty">
      当前账号无客户写入权限（`customer:write`），请由有权限的管家或管理员完成人工匹配。
    </div>
    <template v-else>
      <div class="qa-mode-switch" role="tablist" aria-label="客户关联方式">
        <button
          type="button"
          role="tab"
          :aria-selected="unlinkedMode === 'SEARCH'"
          data-testid="unlinked-mode-search"
          @click="unlinkedMode = 'SEARCH'"
        >
          搜索并关联已有客户
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="unlinkedMode === 'CREATE'"
          data-testid="unlinked-mode-create"
          @click="unlinkedMode = 'CREATE'"
        >
          新建客户并关联
        </button>
      </div>

      <div v-if="errorMessage" class="qa-alert" role="alert">{{ errorMessage }}</div>
      <div v-if="feedback" class="qa-feedback" role="status">{{ feedback }}</div>

      <!-- Search & link existing CRM customer -->
      <div v-if="unlinkedMode === 'SEARCH'" class="qa-card">
        <p class="qa-note">仅可搜索并关联当前数据权限范围内的 CRM 客户；关联操作将写入审计日志。</p>
        <div v-if="!canReadCustomer" class="qa-empty">当前账号无客户查询权限（`customer:read`），请切换「新建客户并关联」。</div>
        <template v-else>
          <div class="qa-inline-input">
            <input
              v-model="searchKeyword"
              data-testid="unlinked-search-input"
              placeholder="输入姓名、昵称、客户编号或手机尾号"
              :disabled="searching || busy"
              @keydown.enter.prevent="searchExistingCustomers"
            >
            <button
              type="button"
              class="qa-minor"
              data-testid="unlinked-search-btn"
              :disabled="searching || busy"
              @click="searchExistingCustomers"
            >
              {{ searching ? '搜索中…' : '搜索' }}
            </button>
          </div>

          <label for="qa-link-reason">关联说明</label>
          <input
            id="qa-link-reason"
            v-model="linkReason"
            data-testid="unlinked-link-reason"
            maxlength="200"
            :disabled="busy"
            placeholder="例如：核对手机号与姓名后关联"
          >

          <div v-if="searchResults.length" class="qa-search-list">
            <article v-for="item in searchResults" :key="item.id" class="qa-search-item">
              <div>
                <strong>{{ item.displayName || item.nickname || item.customerNo }}</strong>
                <small>{{ item.customerNo }} · {{ item.mobileMasked || '未留手机号' }} · {{ item.ownerName || '未分配' }}</small>
              </div>
              <button
                type="button"
                class="qa-bind-btn"
                :data-testid="`link-customer-${item.id}`"
                :disabled="busy"
                @click="bindExistingCustomer(item.id)"
              >
                关联
              </button>
            </article>
          </div>
          <div v-else-if="searchDone" class="qa-empty">未找到匹配客户，可换关键词重试或新建 CRM 客户。</div>
        </template>
      </div>

      <!-- Create new CRM customer & link -->
      <form v-else class="qa-card" data-testid="unlinked-create-form" @submit.prevent="createAndBindCustomer">
        <p class="qa-note">将为该企微联系人新建 CRM 客户主档并建立映射，不把 external_userid 作为主键。</p>
        <label for="qa-new-name">客户称呼</label>
        <input
          id="qa-new-name"
          v-model="newDisplayName"
          data-testid="unlinked-new-display-name"
          maxlength="96"
          :disabled="busy"
          placeholder="例如：林女士"
        >
        <label for="qa-new-nick">微信昵称 <small>称呼或昵称至少填一项</small></label>
        <input
          id="qa-new-nick"
          v-model="newNickname"
          data-testid="unlinked-new-nickname"
          maxlength="128"
          :disabled="busy"
          placeholder="选填"
        >
        <label for="qa-new-mobile">手机号 <small>选填</small></label>
        <input
          id="qa-new-mobile"
          v-model="newMobile"
          data-testid="unlinked-new-mobile"
          maxlength="32"
          :disabled="busy"
          placeholder="留空也可建档"
        >
        <label for="qa-new-intent">意向程度 <small>选填</small></label>
        <select id="qa-new-intent" v-model="newIntentLevel" data-testid="unlinked-new-intent" :disabled="busy">
          <option value="">暂不评估</option>
          <option v-for="opt in INTENT_OPTIONS" :key="opt.code" :value="opt.code">{{ opt.label }}</option>
        </select>
        <label for="qa-new-remark">建档备注 <small>选填</small></label>
        <textarea
          id="qa-new-remark"
          v-model="newRemark"
          data-testid="unlinked-new-remark"
          rows="2"
          maxlength="500"
          :disabled="busy"
          placeholder="记录客户初步需求"
        />
        <button type="submit" class="qa-submit" data-testid="unlinked-create-submit" :disabled="busy">
          {{ busy ? '正在建档并关联…' : '新建客户并完成关联' }}
        </button>
      </form>
    </template>
  </section>
</template>

<style scoped>
.quick-actions-panel { margin-bottom: 18px; }
.unlinked-actions-panel { padding: 18px; }
.qa-bar { display: flex; gap: 8px; flex-wrap: wrap; }
.qa-trigger { flex: 1 1 88px; min-height: 36px; padding: 7px 10px; border: 1px solid #cfe0d6; border-radius: 8px; color: #2b6550; background: #f2f8f4; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.qa-trigger.active { border-color: #276c59; color: #f4faf6; background: #276c59; }
.qa-mode-switch { display: flex; gap: 6px; margin-bottom: 14px; padding: 4px; border-radius: 9px; background: #eaf0ec; }
.qa-mode-switch button { flex: 1; padding: 8px 10px; border: 0; border-radius: 6px; color: #758e81; background: transparent; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.qa-mode-switch button[aria-selected='true'] { color: #2e5d4a; background: #fcfefc; box-shadow: 0 1px 4px rgb(38 79 59 / 7%); }
.qa-card { display: grid; gap: 8px; margin-top: 12px; padding: 14px; border: 1px solid #dce6e0; border-radius: 10px; background: #fdfefd; }
.qa-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2px; }
.qa-card-head strong { color: #294c3e; font-size: 12px; }
.qa-close { padding: 2px 6px; border: 0; color: #7e9489; background: transparent; font: inherit; font-size: 10px; cursor: pointer; }
.qa-card label { margin-top: 4px; color: #547163; font-size: 11px; font-weight: 600; }
.qa-card label small { color: #8da096; font-weight: 400; }
.qa-card input:not([type='checkbox']), .qa-card select, .qa-card textarea { box-sizing: border-box; width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid #d5e2da; border-radius: 7px; outline: none; color: #2a4c3e; background: #fcfefc; font: inherit; font-size: 12px; }
.qa-card textarea { resize: vertical; line-height: 1.65; }
.qa-card input:focus, .qa-card select:focus, .qa-card textarea:focus { border-color: #77a48e; box-shadow: 0 0 0 3px rgb(47 108 83 / 8%); }
.qa-checkbox { display: flex; align-items: center; gap: 7px; margin-top: 6px; color: #3d6352; font-size: 11px; font-weight: 500; cursor: pointer; }
.qa-subbox { display: grid; gap: 7px; padding: 10px; border-radius: 8px; background: #f3f7f4; }
.qa-pills { display: flex; gap: 6px; flex-wrap: wrap; }
.qa-pill { padding: 5px 10px; border: 1px solid #d5e2da; border-radius: 6px; color: #5f7a6d; background: #f8fbf9; font: inherit; font-size: 11px; cursor: pointer; }
.qa-pill.selected { border-color: #276c59; color: #24634e; background: #e8f3ed; font-weight: 650; }
.qa-inline-input { display: flex; gap: 7px; }
.qa-inline-input input { flex: 1; min-width: 0; }
.qa-minor { flex: 0 0 auto; padding: 0 12px; border: 1px solid #b8cec1; border-radius: 7px; color: #306851; background: #f3f8f5; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.qa-selected-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
.qa-tag-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 8px; border: 1px solid #d2e2d9; border-radius: 6px; color: #3d6654; background: #eef5f1; font-size: 10px; }
.qa-tag-chip button { padding: 0; border: 0; color: #6c887b; background: transparent; font-size: 12px; line-height: 1; cursor: pointer; }
.qa-two-col { display: grid; grid-template-columns: 96px 1fr; gap: 8px; }
.qa-two-col > div { display: grid; gap: 6px; }
.qa-submit { margin-top: 6px; min-height: 38px; padding: 8px 14px; border: 1px solid #276c59; border-radius: 8px; color: #f4faf6; background: #276c59; font: inherit; font-size: 12px; font-weight: 650; cursor: pointer; }
.qa-submit:disabled, .qa-minor:disabled, .qa-bind-btn:disabled { opacity: .45; cursor: not-allowed; }
.qa-note { margin: 0; color: #7e9388; font-size: 10px; line-height: 1.7; }
.qa-alert, .qa-feedback { margin-top: 10px; padding: 9px 11px; border-radius: 7px; font-size: 11px; line-height: 1.65; }
.qa-alert { color: #9c5240; background: #fff0eb; }
.qa-feedback { color: #346c53; background: #eaf5ee; }
.qa-retry { margin-left: 8px; padding: 2px 7px; border: 1px solid #d4a699; border-radius: 5px; color: #9c5240; background: transparent; font: inherit; font-size: 10px; cursor: pointer; }
.qa-empty { padding: 16px 10px; color: #85998f; font-size: 11px; text-align: center; line-height: 1.7; }
.qa-search-list { display: grid; gap: 8px; margin-top: 4px; }
.qa-search-item { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; border: 1px solid #e1e9e5; border-radius: 8px; background: #fafcfa; }
.qa-search-item > div { min-width: 0; display: grid; gap: 3px; }
.qa-search-item strong { color: #2b4e40; font-size: 12px; }
.qa-search-item small { color: #7f9489; font-size: 10px; overflow-wrap: anywhere; }
.qa-bind-btn { flex: 0 0 auto; padding: 6px 11px; border: 1px solid #276c59; border-radius: 6px; color: #f4faf6; background: #276c59; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
</style>
