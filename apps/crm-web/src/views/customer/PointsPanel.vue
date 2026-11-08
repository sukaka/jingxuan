<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerPoints } from '../../api/customer'
import { adjustCustomerPoints, getCustomerPoints } from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import { formatDateTime } from './shared'

const props = defineProps<{ customerId: number; birthday?: string | null }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()

const points = ref<CustomerPoints | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const REASON_OPTIONS = [
  { code: 'MANUAL_ADJUST', label: '人工调整' },
  { code: 'CORRECTION', label: '纠错补录' },
  { code: 'ACTIVITY_GIFT', label: '活动赠送（人工）' },
]
const REASON_LABELS: Record<string, string> = {
  ...Object.fromEntries(REASON_OPTIONS.map((option) => [option.code, option.label])),
  WELCOME: '新客赠送',
  BIRTHDAY_GIFT: '生日赠送（历史流水）',
}
const SOURCE_TYPE_LABELS: Record<string, string> = {
  MANUAL_ADJUST: '人工调整',
}

function reasonLabel(value: string): string {
  return REASON_LABELS[value] ?? '其他调整'
}

function sourceTypeLabel(value: string): string {
  return SOURCE_TYPE_LABELS[value] ?? '其他来源'
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '客户不存在或不在你的数据范围内'
    if (error.status === 409 && error.message.includes('余额不足')) {
      return '积分余额不足，调整后不得为负；请修改扣减分值'
    }
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    points.value = await getCustomerPoints(props.customerId)
  } catch (error) {
    points.value = null
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

const adjustDialogVisible = ref(false)
const adjustSubmitting = ref(false)
const adjustForm = reactive({
  delta: undefined as number | undefined,
  reasonCode: 'MANUAL_ADJUST',
  reason: '',
})
let idempotencyKey = ''
let idempotencyPayload = ''

function openAdjustDialog() {
  adjustForm.delta = undefined
  adjustForm.reasonCode = 'MANUAL_ADJUST'
  adjustForm.reason = ''
  idempotencyKey = ''
  idempotencyPayload = ''
  adjustDialogVisible.value = true
}

function submissionKey(request: { delta: number; reasonCode: string; reason: string }): string {
  const payload = JSON.stringify(request)
  if (payload !== idempotencyPayload) {
    idempotencyPayload = payload
    idempotencyKey = crypto.randomUUID()
  }
  return idempotencyKey
}

async function submitAdjust() {
  if (adjustSubmitting.value) return
  if (adjustForm.delta === undefined || adjustForm.delta === 0 || !Number.isInteger(adjustForm.delta)) {
    ElMessage.warning('请填写非零整数调整分值(扣减填负数)')
    return
  }
  if (!adjustForm.reasonCode) {
    ElMessage.warning('请选择调整类型')
    return
  }
  if (!adjustForm.reason.trim()) {
    ElMessage.warning('请填写调整原因(将写入审计)')
    return
  }
  const request = {
    delta: adjustForm.delta,
    reasonCode: adjustForm.reasonCode,
    reason: adjustForm.reason.trim(),
  }
  adjustSubmitting.value = true
  try {
    points.value = await adjustCustomerPoints(props.customerId, request, submissionKey(request))
    ElMessage.success('积分流水已追加,余额已同步刷新')
    adjustDialogVisible.value = false
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    adjustSubmitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="points-panel">
    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      :title="`加载失败:${errorMessage}`"
      class="crm-table-alert"
    />
    <template v-else>
      <div class="balance-row">
        <div class="profile-facts">
          <div class="balance-card">
            <div class="balance-label">当前积分余额</div>
            <div class="balance-value" data-testid="points-balance">{{ points?.balance ?? '—' }}</div>
            <div class="crm-muted">余额以积分流水逐笔合计为准，不可直接修改</div>
          </div>
          <div class="birthday-card">
            <div class="balance-label">客户生日</div>
            <div class="birthday-value" data-testid="customer-birthday">{{ birthday || '未维护' }}</div>
            <div class="crm-muted">来自客户档案，仅作档案展示</div>
          </div>
        </div>
        <el-button
          v-if="auth.hasPermission('customer:points_adjust')"
          type="primary"
          data-testid="points-open-adjust"
          @click="openAdjustDialog"
        >
          积分调整
        </el-button>
      </div>
      <el-alert
        type="info"
        :closable="false"
        title="积分不会因生日或消费自动赠送；只有授权人工调整才追加流水，错误调整需另追冲正。"
        class="points-rule"
      />

      <app-table v-loading="loading" :data="points?.ledger ?? []">
        <el-table-column prop="id" label="流水号" width="90" />
        <el-table-column label="变动" width="90" align="right">
          <template #default="{ row }">
            <span :class="row.delta >= 0 ? 'delta-plus' : 'delta-minus'">
              {{ row.delta >= 0 ? `+${row.delta}` : row.delta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="150">
          <template #default="{ row }">{{ reasonLabel(row.reasonCode) }}</template>
        </el-table-column>
        <el-table-column label="来源" min-width="160">
          <template #default="{ row }">{{ sourceTypeLabel(row.sourceType) }} / {{ row.sourceRef }}</template>
        </el-table-column>
        <el-table-column prop="operatorUserId" label="操作人" width="90" />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <span>暂无积分流水</span>
        </template>
      </app-table>
    </template>

    <el-dialog v-model="adjustDialogVisible" title="积分调整" width="460px">
      <el-alert
        type="warning"
        :closable="false"
        title="只追加积分流水并写审计；相同内容重试沿用幂等键，不会按生日或消费自动赠送"
        class="crm-table-alert"
      />
      <el-form label-width="90px">
        <el-form-item label="调整分值" required>
          <el-input-number
            v-model="adjustForm.delta"
            :precision="0"
            :disabled="adjustSubmitting"
            controls-position="right"
            class="full-width"
            data-testid="points-delta"
          />
          <span class="crm-muted">增加填正整数，扣减填负整数；后端拒绝负余额</span>
        </el-form-item>
        <el-form-item label="调整类型" required>
          <el-select
            v-model="adjustForm.reasonCode"
            :disabled="adjustSubmitting"
            filterable
            allow-create
            default-first-option
            class="full-width"
            data-testid="points-reason-code"
          >
            <el-option
              v-for="option in REASON_OPTIONS"
              :key="option.code"
              :label="option.label"
              :value="option.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input
            v-model="adjustForm.reason"
            :disabled="adjustSubmitting"
            type="textarea"
            :rows="2"
            placeholder="写入审计快照，必填"
            data-testid="points-reason"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="adjustSubmitting" @click="adjustDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSubmitting" data-testid="points-submit-adjust" @click="submitAdjust">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.balance-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.profile-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(210px, 1fr));
  gap: 12px;
  flex: 1;
}

.balance-card,
.birthday-card {
  background: #fff;
  border: 1px solid #e9eff3;
  border-radius: 14px;
  padding: 16px 20px;
}

.balance-label {
  font-size: 12px;
  color: #79909f;
}

.balance-value,
.birthday-value {
  margin: 4px 0;
  font-size: 28px;
  font-weight: 700;
  color: #0f948b;
}

.birthday-value {
  color: #294456;
  font-size: 20px;
}

.points-rule {
  margin-bottom: 14px;
}

.delta-plus {
  color: #218361;
  font-weight: 600;
}

.delta-minus {
  color: #c95667;
  font-weight: 600;
}

.full-width {
  width: 100%;
}

@media (max-width: 720px) {
  .balance-row {
    align-items: stretch;
    flex-direction: column;
  }

  .profile-facts {
    grid-template-columns: 1fr;
  }
}
</style>
