<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  CustomerPrivacyFieldCode,
  CustomerPrivacyRevealResult,
  CustomerPrivacyStatus,
} from '../../api/customerPrivacy'
import {
  DURATION_OPTIONS,
  approverSourceLabel,
  cancelCustomerPrivacyRequest,
  createCustomerPrivacyRequest,
  durationLabel,
  getCustomerPrivacyStatus,
  privacyFieldLabel,
  revealCustomerPrivacy,
} from '../../api/customerPrivacy'
import { ApiError } from '../../types/api'
import StatusPill from './StatusPill.vue'
import { formatDateTime } from './shared'

const props = defineProps<{
  customerId: number
  visible: boolean
  readonly?: boolean
}>()

const loading = ref(false)
const errorMessage = ref('')
const statusData = ref<CustomerPrivacyStatus | null>(null)

// 明文仅存组件局部内存，关闭抽屉/切客户/手动隐藏/倒计时结束立即销毁
const revealedResult = ref<CustomerPrivacyRevealResult | null>(null)
const autoHideRemaining = ref(0)
const grantRemaining = ref(0)
let countdownTimer: number | undefined
let loadSeq = 0

// ADMIN 显式查看弹窗
const adminRevealDialogVisible = ref(false)
const adminRevealSubmitting = ref(false)
const adminRevealForm = reactive({
  fields: [] as CustomerPrivacyFieldCode[],
  reason: '',
})

// 非 ADMIN 申请查看弹窗
const requestDialogVisible = ref(false)
const requestSubmitting = ref(false)
const requestForm = reactive({
  fields: [] as CustomerPrivacyFieldCode[],
  durationMinutes: 30,
  reason: '',
})
let requestIdempotencyKey = ''

const revealingByGrant = ref(false)
const canceling = ref(false)

const presentFields = computed(() => (statusData.value?.fields ?? []).filter((f) => f.present))
const hasAnyPrivacyField = computed(() => presentFields.value.length > 0)
const isRevealed = computed(() => revealedResult.value !== null)

const activeGrantApprovedFields = computed<CustomerPrivacyFieldCode[]>(() => {
  const grant = statusData.value?.activeGrant
  if (!grant || grant.effectiveStatus !== 'APPROVED') return []
  return grant.approvedFields.filter((code) => presentFields.value.some((f) => f.fieldCode === code))
})

function formatSeconds(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds))
  const hours = Math.floor(safe / 3600)
  const mins = Math.floor((safe % 3600) / 60)
  const secs = safe % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return hours > 0 ? `${pad(hours)}:${pad(mins)}:${pad(secs)}` : `${pad(mins)}:${pad(secs)}`
}

function clearRevealed() {
  revealedResult.value = null
  autoHideRemaining.value = 0
}

function stopTimer() {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer)
    countdownTimer = undefined
  }
}

function ensureTimer() {
  stopTimer()
  countdownTimer = window.setInterval(() => {
    if (autoHideRemaining.value > 0) {
      autoHideRemaining.value -= 1
      if (autoHideRemaining.value <= 0) {
        clearRevealed()
      }
    }
    if (grantRemaining.value > 0) {
      grantRemaining.value -= 1
      if (grantRemaining.value <= 0) {
        if (revealedResult.value?.accessMode === 'APPROVED_GRANT_REVEAL') {
          clearRevealed()
        }
        void loadStatus()
      }
    }
    if (autoHideRemaining.value <= 0 && grantRemaining.value <= 0) {
      stopTimer()
    }
  }, 1000)
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

async function loadStatus() {
  if (!props.visible || !props.customerId) return
  const seq = ++loadSeq
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await getCustomerPrivacyStatus(props.customerId)
    if (seq !== loadSeq || !props.visible) return
    statusData.value = res
    grantRemaining.value = res.activeGrant?.remainingSeconds ?? 0
    if (grantRemaining.value > 0 || autoHideRemaining.value > 0) {
      ensureTimer()
    }
  } catch (error) {
    if (seq !== loadSeq) return
    statusData.value = null
    errorMessage.value = errorText(error)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function displayValue(fieldCode: CustomerPrivacyFieldCode, maskedValue: string | null, present: boolean): string {
  if (!present) return '未维护'
  const plain = revealedResult.value?.values?.[fieldCode]
  if (plain) return plain
  return maskedValue || '已脱敏保护'
}

function isFieldRevealed(fieldCode: CustomerPrivacyFieldCode): boolean {
  return Boolean(revealedResult.value?.values?.[fieldCode])
}

function openAdminRevealDialog() {
  adminRevealForm.fields = presentFields.value.map((f) => f.fieldCode)
  adminRevealForm.reason = ''
  adminRevealDialogVisible.value = true
}

async function submitAdminReveal() {
  if (adminRevealSubmitting.value) return
  if (adminRevealForm.fields.length === 0) {
    ElMessage.warning('请至少选择一项要查看的隐私字段')
    return
  }
  if (!adminRevealForm.reason.trim()) {
    ElMessage.warning('请填写查看隐私信息的业务原因（将记入审计日志）')
    return
  }
  adminRevealSubmitting.value = true
  try {
    const res = await revealCustomerPrivacy(props.customerId, {
      fields: [...adminRevealForm.fields],
      reason: adminRevealForm.reason.trim(),
      mode: 'ADMIN_DIRECT',
    })
    revealedResult.value = res
    autoHideRemaining.value = res.autoHideSeconds || 120
    ensureTimer()
    adminRevealDialogVisible.value = false
    ElMessage.success('隐私信息已临时展开，本次查看已记入审计日志')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    adminRevealSubmitting.value = false
  }
}

function openRequestDialog() {
  requestForm.fields = presentFields.value.map((f) => f.fieldCode)
  requestForm.durationMinutes = 30
  requestForm.reason = ''
  requestIdempotencyKey = crypto.randomUUID()
  requestDialogVisible.value = true
}

async function submitPrivacyRequest() {
  if (requestSubmitting.value) return
  if (requestForm.fields.length === 0) {
    ElMessage.warning('请至少选择一项需要查看的隐私字段')
    return
  }
  if (!requestForm.reason.trim()) {
    ElMessage.warning('请填写申请查看原因')
    return
  }
  requestSubmitting.value = true
  try {
    await createCustomerPrivacyRequest(
      props.customerId,
      {
        fields: [...requestForm.fields],
        durationMinutes: requestForm.durationMinutes,
        reason: requestForm.reason.trim(),
      },
      requestIdempotencyKey,
    )
    ElMessage.success('已提交隐私查看申请，已通知上级主管审批')
    requestDialogVisible.value = false
    await loadStatus()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    requestSubmitting.value = false
  }
}

async function handleCancelPending() {
  const pending = statusData.value?.pendingRequest
  if (!pending || canceling.value) return
  try {
    await ElMessageBox.confirm('确认撤回这条待审批的隐私查看申请吗？', '撤回申请', {
      type: 'warning',
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  canceling.value = true
  try {
    await cancelCustomerPrivacyRequest(pending.id, pending.version)
    ElMessage.success('已撤回隐私查看申请')
    await loadStatus()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    canceling.value = false
  }
}

async function handleRevealByGrant() {
  const grant = statusData.value?.activeGrant
  if (!grant || activeGrantApprovedFields.value.length === 0 || revealingByGrant.value) return
  revealingByGrant.value = true
  try {
    const res = await revealCustomerPrivacy(props.customerId, {
      fields: [...activeGrantApprovedFields.value],
      requestId: grant.id,
      mode: 'APPROVED_GRANT',
    })
    revealedResult.value = res
    const maxVisible = grantRemaining.value > 0
      ? Math.min(res.autoHideSeconds || 120, grantRemaining.value)
      : (res.autoHideSeconds || 120)
    autoHideRemaining.value = maxVisible
    ensureTimer()
    ElMessage.success('已按审批授权临时展开明文，本次查看已记入审计日志')
    await loadStatus()
  } catch (error) {
    ElMessage.error(errorText(error))
    await loadStatus()
  } finally {
    revealingByGrant.value = false
  }
}

watch(
  () => [props.visible, props.customerId],
  ([visible]) => {
    clearRevealed()
    stopTimer()
    statusData.value = null
    grantRemaining.value = 0
    if (visible && props.customerId) {
      void loadStatus()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  clearRevealed()
  stopTimer()
})
</script>

<template>
  <section class="privacy-card" data-testid="customer-privacy-panel">
    <div class="privacy-head">
      <div>
        <div class="privacy-title-row">
          <strong>客户隐私信息（默认全员脱敏）</strong>
          <StatusPill v-if="isRevealed" text="明文已展开 · 已审计" tone="warning" data-testid="privacy-revealed-pill" />
          <StatusPill v-else text="脱敏保护中" tone="info" />
        </div>
        <p class="privacy-sub">
          手机号、身份证号、邮箱、微信号默认严禁直接显示明文；任何明文查看均强制留痕不可篡改审计日志。
        </p>
      </div>
      <div v-if="statusData && !readonly" class="privacy-actions">
        <template v-if="isRevealed">
          <span v-if="autoHideRemaining > 0" class="countdown-chip" data-testid="privacy-autohide-countdown">
            {{ formatSeconds(autoHideRemaining) }} 后自动隐藏
          </span>
          <el-button size="small" data-testid="privacy-hide-btn" @click="clearRevealed">
            隐藏隐私信息
          </el-button>
        </template>

        <template v-else-if="statusData.canAdminDirectReveal">
          <el-button
            type="warning"
            plain
            size="small"
            :disabled="!hasAnyPrivacyField"
            data-testid="privacy-admin-reveal-btn"
            @click="openAdminRevealDialog"
          >
            查看隐私信息
          </el-button>
        </template>

        <template v-else-if="statusData.canRequestAccess">
          <el-button
            v-if="statusData.activeGrant && activeGrantApprovedFields.length > 0"
            type="primary"
            size="small"
            :loading="revealingByGrant"
            data-testid="privacy-grant-reveal-btn"
            @click="handleRevealByGrant"
          >
            打开查看明文（剩余 {{ formatSeconds(grantRemaining) }}）
          </el-button>
          <el-button
            v-else-if="statusData.pendingRequest"
            size="small"
            :loading="canceling"
            data-testid="privacy-cancel-request-btn"
            @click="handleCancelPending"
          >
            撤回待审申请
          </el-button>
          <el-button
            v-else
            type="primary"
            plain
            size="small"
            :disabled="!hasAnyPrivacyField"
            data-testid="privacy-request-btn"
            @click="openRequestDialog"
          >
            向上级申请查看
          </el-button>
        </template>
      </div>
    </div>

    <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" class="privacy-alert" />

    <div v-else v-loading="loading" class="privacy-grid">
      <div
        v-for="field in statusData?.fields ?? []"
        :key="field.fieldCode"
        class="privacy-field-cell"
        :class="{ 'is-revealed': isFieldRevealed(field.fieldCode) }"
        :data-testid="`privacy-field-${field.fieldCode}`"
      >
        <div class="privacy-field-label">
          <span>{{ field.label }}</span>
          <span v-if="isFieldRevealed(field.fieldCode)" class="revealed-tag">明文</span>
        </div>
        <div class="privacy-field-value" :class="{ 'is-empty': !field.present }">
          {{ displayValue(field.fieldCode, field.maskedValue, field.present) }}
        </div>
      </div>
    </div>

    <div v-if="statusData?.pendingRequest" class="privacy-status-bar is-pending" data-testid="privacy-pending-banner">
      <span>
        已提交查看申请 <strong>{{ statusData.pendingRequest.requestNo }}</strong>
        （字段：{{ statusData.pendingRequest.requestedFields.map(privacyFieldLabel).join('、') }}），
        等待 <strong>{{ statusData.pendingRequest.assignedApproverName || `#${statusData.pendingRequest.assignedApproverId}` }}</strong>
        （{{ approverSourceLabel(statusData.pendingRequest.approverSource) }}）审批。
      </span>
    </div>

    <div v-else-if="statusData?.activeGrant && !isRevealed" class="privacy-status-bar is-approved" data-testid="privacy-grant-banner">
      <span>
        上级已批准限时查看（<strong>{{ statusData.activeGrant.requestNo }}</strong>，
        获批字段：{{ statusData.activeGrant.approvedFields.map(privacyFieldLabel).join('、') }}，
        有效期至 {{ formatDateTime(statusData.activeGrant.grantExpiresAt) }}）；点击上方「打开查看明文」后记录审计日志并展示。
      </span>
    </div>

    <div v-if="isRevealed && revealedResult" class="privacy-status-bar is-revealed" data-testid="privacy-audit-banner">
      <span>
        本次揭盲已记入审计日志（日志号 #{{ revealedResult.accessLogId }}），明文仅在当前页面内存中临时展示，切勿截屏或外传。
      </span>
    </div>

    <!-- ADMIN 显式揭盲确认弹窗 -->
    <el-dialog
      v-model="adminRevealDialogVisible"
      title="管理员查看客户隐私信息（强制审计）"
      width="480px"
      append-to-body
    >
      <el-alert
        type="warning"
        :closable="false"
        title="管理员通过按钮查看客户隐私明文将写入不可篡改审计日志（含操作人、IP、时间、字段与原因），并在 120 秒或关闭页面后自动重新脱敏。"
        class="dialog-alert"
      />
      <el-form label-position="top" @submit.prevent="submitAdminReveal">
        <el-form-item label="选择要查看的隐私字段" required>
          <el-checkbox-group v-model="adminRevealForm.fields">
            <el-checkbox
              v-for="field in presentFields"
              :key="field.fieldCode"
              :value="field.fieldCode"
            >
              {{ field.label }}（{{ field.maskedValue }}）
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="查看原因（必填，写入审计日志）" required>
          <el-input
            v-model="adminRevealForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请说明本次查看客户隐私信息的具体业务原因，例如：核查客诉回访联系方式"
            data-testid="privacy-admin-reason-input"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adminRevealDialogVisible = false">取消</el-button>
        <el-button
          type="warning"
          :loading="adminRevealSubmitting"
          data-testid="privacy-admin-reveal-submit"
          @click="submitAdminReveal"
        >
          确认查看明文
        </el-button>
      </template>
    </el-dialog>

    <!-- 非 ADMIN 向上级申请弹窗 -->
    <el-dialog
      v-model="requestDialogVisible"
      title="向上级主管申请查看客户隐私信息"
      width="500px"
      append-to-body
    >
      <el-alert
        v-if="statusData?.resolvedApprover"
        type="info"
        :closable="false"
        :title="`审批上级：${statusData.resolvedApprover.approverName}（${approverSourceLabel(statusData.resolvedApprover.approverSource)}）`"
        class="dialog-alert"
      />
      <el-alert
        v-else
        type="error"
        :closable="false"
        title="当前账号未匹配到可用的上级审批主管，请联系管理员在用户管理中配置直属上级或团队负责人。"
        class="dialog-alert"
      />
      <el-form label-position="top" @submit.prevent="submitPrivacyRequest">
        <el-form-item label="申请查看的隐私字段" required>
          <el-checkbox-group v-model="requestForm.fields">
            <el-checkbox
              v-for="field in presentFields"
              :key="field.fieldCode"
              :value="field.fieldCode"
            >
              {{ field.label }}（{{ field.maskedValue }}）
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="申请授权有效期" required>
          <el-select v-model="requestForm.durationMinutes" style="width: 100%">
            <el-option
              v-for="opt in DURATION_OPTIONS"
              :key="opt.minutes"
              :label="opt.label"
              :value="opt.minutes"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申请查看原因（必填，提交给上级审批并留痕）" required>
          <el-input
            v-model="requestForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请说明业务场景，例如：预约术前确认、客户售后电话回访等"
            data-testid="privacy-request-reason-input"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="requestDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="requestSubmitting"
          :disabled="!statusData?.resolvedApprover"
          data-testid="privacy-request-submit"
          @click="submitPrivacyRequest"
        >
          提交审批申请 ({{ durationLabel(requestForm.durationMinutes) }})
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.privacy-card {
  margin-bottom: 16px;
  padding: 16px 18px;
  background: #fbfdfc;
  border: 1px solid #d9e8e4;
  border-radius: 12px;
}

.privacy-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.privacy-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #1c3d38;
}

.privacy-sub {
  margin: 4px 0 0;
  color: #6d8580;
  font-size: 12px;
}

.privacy-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.countdown-chip {
  padding: 3px 8px;
  border-radius: 999px;
  background: #fff3df;
  color: #9a5d14;
  font-size: 11px;
  font-weight: 600;
}

.privacy-alert,
.dialog-alert {
  margin-bottom: 12px;
}

.privacy-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.privacy-field-cell {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #e6eeec;
  background: #fff;
}

.privacy-field-cell.is-revealed {
  border-color: #e6b86a;
  background: #fffaf0;
}

.privacy-field-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #708892;
  margin-bottom: 4px;
}

.revealed-tag {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f59e0b;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
}

.privacy-field-value {
  font-size: 13px;
  font-weight: 600;
  color: #21404c;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.privacy-field-value.is-empty {
  color: #99abb4;
  font-weight: 400;
}

.privacy-status-bar {
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.6;
}

.privacy-status-bar.is-pending {
  background: #eef6ff;
  border: 1px solid #cce2fa;
  color: #22548a;
}

.privacy-status-bar.is-approved {
  background: #ecf9f4;
  border: 1px solid #c3ebd9;
  color: #166147;
}

.privacy-status-bar.is-revealed {
  background: #fff7e6;
  border: 1px solid #f2d399;
  color: #8a5612;
}

@media (max-width: 640px) {
  .privacy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
