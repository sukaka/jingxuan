<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerOwnerOption } from '../../api/customer'
import {
  assignCustomerOwner,
  batchAssignCustomerOwners,
  listCustomerOwnerOptions,
} from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'

export interface CustomerAssignTarget {
  id: number
  customerNo: string
  displayName: string | null
  nickname: string | null
  ownerStaffId: number
  ownerName: string | null
  version: number
}

const props = defineProps<{
  modelValue: boolean
  customers: CustomerAssignTarget[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  assigned: []
}>()

const auth = useAuthStore()
const isAdmin = computed(() => auth.hasRole('ADMIN'))
const isBatch = computed(() => props.customers.length > 1)
const singleTarget = computed(() => (props.customers.length === 1 ? props.customers[0] : null))

const ownerOptions = ref<CustomerOwnerOption[]>([])
const ownerOptionsLoading = ref(false)
const ownerOptionsError = ref('')
const submitting = ref(false)
const form = reactive({
  newOwnerId: undefined as number | undefined,
  reason: '',
})
let idempotencyKey = ''

function customerName(item: CustomerAssignTarget): string {
  return item.displayName || item.nickname || item.customerNo
}

function ownerOptionLabel(option: CustomerOwnerOption): string {
  const base = option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName
  if (singleTarget.value && option.id === singleTarget.value.ownerStaffId) {
    return `${base} · 当前归属`
  }
  return base
}

const alreadyOwnedCount = computed(() => {
  if (!form.newOwnerId) return 0
  return props.customers.filter((item) => item.ownerStaffId === form.newOwnerId).length
})

const effectiveAssignCount = computed(() => props.customers.length - alreadyOwnedCount.value)

const allSameOwner = computed(() => Boolean(form.newOwnerId) && props.customers.length > 0 && effectiveAssignCount.value === 0)

const scopeNotice = computed(() => (
  isAdmin.value
    ? '系统管理员可跨团队分配客户给任意在职管家；提交后立即生效，自动追加不可修改的归属历史与审计日志，不影响历史订单与核销归属快照。'
    : '管家主管可将本团队客户直接分配给本团队在职管家；提交后立即生效，自动追加不可修改的归属历史与审计日志，不影响历史订单与核销归属快照。'
))

function resetIdempotencyKey() {
  idempotencyKey = crypto.randomUUID()
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '客户不存在或不在您的可见范围内'
    if (error.status === 403) return error.message || '您无权执行该范围的客户分配'
    if (error.status === 409) return error.message || '客户资料已变更或存在待审批转交单，请刷新后重试'
    return error.message
  }
  return error instanceof Error ? error.message : '分配失败，请稍后重试'
}

async function loadOwnerOptions() {
  ownerOptionsLoading.value = true
  ownerOptionsError.value = ''
  try {
    ownerOptions.value = await listCustomerOwnerOptions()
  } catch (error) {
    ownerOptions.value = []
    ownerOptionsError.value = errorText(error)
  } finally {
    ownerOptionsLoading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    form.newOwnerId = undefined
    form.reason = ''
    resetIdempotencyKey()
    void loadOwnerOptions()
  },
)

watch(() => [form.newOwnerId, form.reason], () => {
  resetIdempotencyKey()
})

async function submit() {
  if (!props.customers.length) {
    ElMessage.warning('请先选择待分配客户')
    return
  }
  if (!form.newOwnerId) {
    ElMessage.warning('请选择目标管家')
    return
  }
  if (allSameOwner.value) {
    ElMessage.warning(isBatch.value ? '所选客户均已归属该管家，无需重复分配' : '目标管家与当前归属管家相同，请选择其他管家')
    return
  }
  const reason = form.reason.trim()
  if (!reason) {
    ElMessage.warning('请填写分配原因（将写入不可修改的归属日志）')
    return
  }

  submitting.value = true
  try {
    if (singleTarget.value) {
      const target = singleTarget.value
      const result = await assignCustomerOwner(
        target.id,
        { newOwnerId: form.newOwnerId, reason, version: target.version },
        idempotencyKey,
      )
      const oldLabel = result.oldOwnerName || target.ownerName || `#${result.oldOwnerId}`
      const newLabel = result.newOwnerName || `#${result.newOwnerId}`
      ElMessage.success(`已将「${result.customerName}」由 ${oldLabel} 分配给 ${newLabel}`)
    } else {
      const result = await batchAssignCustomerOwners(
        {
          items: props.customers.map((item) => ({ customerId: item.id, version: item.version })),
          newOwnerId: form.newOwnerId,
          reason,
        },
        idempotencyKey,
      )
      const newLabel = result.newOwnerName || `#${result.newOwnerId}`
      const skippedText = result.skippedCount > 0 ? `，已跳过 ${result.skippedCount} 位原已归属客户` : ''
      ElMessage.success(`已将 ${result.assignedCount} 位客户分配给 ${newLabel}${skippedText}`)
    }
    emit('update:modelValue', false)
    emit('assigned')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="isBatch ? `批量分配客户（已选 ${customers.length} 位）` : '分配客户给管家'"
    width="min(520px, 96vw)"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    data-testid="customer-assign-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert type="info" :closable="false" :title="scopeNotice" class="assign-alert" />
    <el-alert
      v-if="ownerOptionsError"
      type="error"
      :closable="false"
      :title="`可选管家加载失败：${ownerOptionsError}`"
      class="assign-alert"
    />

    <div v-if="singleTarget" class="assign-target-card" data-testid="assign-single-summary">
      <div class="assign-target-row">
        <span class="assign-target-label">待分配客户</span>
        <strong>{{ customerName(singleTarget) }}</strong>
        <span class="assign-target-meta">{{ singleTarget.customerNo }}</span>
      </div>
      <div class="assign-target-row">
        <span class="assign-target-label">原归属管家</span>
        <span>{{ singleTarget.ownerName || `#${singleTarget.ownerStaffId}` }}</span>
      </div>
    </div>

    <div v-else-if="customers.length > 1" class="assign-target-card" data-testid="assign-batch-summary">
      <div class="assign-batch-title">已选择 {{ customers.length }} 位客户：</div>
      <div class="assign-batch-chips">
        <span v-for="item in customers.slice(0, 6)" :key="item.id" class="assign-chip">
          {{ customerName(item) }}（原：{{ item.ownerName || `#${item.ownerStaffId}` }}）
        </span>
        <span v-if="customers.length > 6" class="assign-chip is-more">等共 {{ customers.length }} 位</span>
      </div>
      <div v-if="form.newOwnerId && alreadyOwnedCount > 0 && effectiveAssignCount > 0" class="assign-skip-hint">
        其中 {{ alreadyOwnedCount }} 位已归属目标管家，提交时将自动跳过，实际变更 {{ effectiveAssignCount }} 位。
      </div>
    </div>

    <el-form label-position="top" @submit.prevent>
      <el-form-item label="目标管家" required>
        <el-select
          v-model="form.newOwnerId"
          filterable
          :loading="ownerOptionsLoading"
          :disabled="ownerOptionsError !== ''"
          :placeholder="ownerOptionsError ? '管家选项不可用' : '请选择接收客户的在职管家'"
          class="full-width"
          data-testid="assign-target-owner-select"
        >
          <el-option
            v-for="option in ownerOptions"
            :key="option.id"
            :label="ownerOptionLabel(option)"
            :value="option.id"
            :disabled="Boolean(singleTarget && option.id === singleTarget.ownerStaffId)"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="分配原因" required>
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="必填：如人员休假交接、客户负荷调整等（将写入不可修改的归属日志）"
          data-testid="assign-reason-input"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="ownerOptionsError !== '' || allSameOwner"
        data-testid="assign-submit-btn"
        @click="submit"
      >
        {{ isBatch ? `确认分配（${effectiveAssignCount || customers.length} 位）` : '确认分配' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.assign-alert {
  margin-bottom: 14px;
}

.assign-target-card {
  background: #f5f9fb;
  border: 1px solid #e3edf2;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
  font-size: 13px;
}

.assign-target-row {
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 1.8;
}

.assign-target-label {
  color: #6c8494;
  min-width: 72px;
}

.assign-target-meta {
  color: #7c93a3;
  font-size: 12px;
}

.assign-batch-title {
  font-weight: 600;
  color: #2e4859;
  margin-bottom: 8px;
}

.assign-batch-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.assign-chip {
  display: inline-block;
  padding: 3px 9px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid #d7e5ec;
  color: #315062;
  font-size: 12px;
}

.assign-chip.is-more {
  background: #eaf3f7;
  color: #4f6f82;
}

.assign-skip-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #b26b00;
}

.full-width {
  width: 100%;
}
</style>
