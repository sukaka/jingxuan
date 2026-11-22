<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerTierCode, CustomerTierHistory, CustomerTierView } from '../../api/customerTier'
import {
  adjustCustomerTier,
  getCustomerTier,
  listCustomerTierHistory,
  recalculateCustomerTier,
} from '../../api/customerTier'
import { useAuthStore } from '../../stores/auth'
import { fenToYuan } from '../../utils/money'

const props = defineProps<{ customerId: number }>()
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()
const view = ref<CustomerTierView | null>(null)
const history = ref<CustomerTierHistory[]>([])
const loading = ref(false)
const recalculating = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const adjustVisible = ref(false)
const adjustForm = reactive<{ tierCode: CustomerTierCode | ''; reason: string; lock: boolean }>({
  tierCode: '', reason: '', lock: true,
})
let loadSequence = 0
let disposed = false
const historyTypeLabels: Record<CustomerTierHistory['changeType'], string> = {
  AUTO_UPGRADE: '自动升级',
  DOWNGRADE_APPROVED: '降级复核通过',
  MANUAL_ADJUST: '人工调整',
}

onBeforeUnmount(() => {
  disposed = true
  loadSequence += 1
})

async function load() {
  if (disposed) return
  const targetCustomerId = props.customerId
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const [tierView, items] = await Promise.all([
      getCustomerTier(targetCustomerId),
      listCustomerTierHistory(targetCustomerId),
    ])
    if (disposed || sequence !== loadSequence || props.customerId !== targetCustomerId) return
    view.value = tierView
    history.value = items
  } catch (error) {
    if (disposed || sequence !== loadSequence || props.customerId !== targetCustomerId) return
    errorMessage.value = error instanceof Error ? error.message : '客户等级加载失败'
  } finally {
    if (!disposed && sequence === loadSequence) loading.value = false
  }
}

watch(() => props.customerId, () => {
  if (disposed) return
  loadSequence += 1
  view.value = null
  history.value = []
  errorMessage.value = ''
  adjustVisible.value = false
  recalculating.value = false
  saving.value = false
  void load()
}, { immediate: true })

async function recalculate() {
  const targetCustomerId = props.customerId
  const sequence = loadSequence
  if (disposed || !view.value) return
  recalculating.value = true
  try {
    await recalculateCustomerTier(targetCustomerId, view.value.customerVersion, crypto.randomUUID())
    if (disposed || sequence !== loadSequence || props.customerId !== targetCustomerId) return
    ElMessage.success('重算完成；降级仍需人工复核')
    await load()
    if (!disposed && props.customerId === targetCustomerId) emit('changed')
  } catch (error) {
    if (!disposed && sequence === loadSequence && props.customerId === targetCustomerId) {
      ElMessage.error(error instanceof Error ? error.message : '重算失败')
    }
  } finally {
    if (!disposed && sequence === loadSequence && props.customerId === targetCustomerId) recalculating.value = false
  }
}

function openAdjustment() {
  if (!view.value) return
  adjustForm.tierCode = view.value.tierCode ?? ''
  adjustForm.reason = ''
  adjustForm.lock = view.value.locked
  adjustVisible.value = true
}

async function saveAdjustment() {
  const targetCustomerId = props.customerId
  const sequence = loadSequence
  if (disposed || !view.value || !adjustForm.tierCode || !adjustForm.reason.trim()) {
    if (!disposed) ElMessage.warning('请选择等级并填写人工调整理由')
    return
  }
  saving.value = true
  try {
    const updated = await adjustCustomerTier(targetCustomerId, {
      tierCode: adjustForm.tierCode,
      reason: adjustForm.reason.trim(),
      lock: adjustForm.lock,
      version: view.value.customerVersion,
    }, crypto.randomUUID())
    if (disposed || sequence !== loadSequence || props.customerId !== targetCustomerId) return
    view.value = updated
    adjustVisible.value = false
    ElMessage.success('等级已调整并追加历史')
    await load()
    if (!disposed && props.customerId === targetCustomerId) emit('changed')
  } catch (error) {
    if (!disposed && sequence === loadSequence && props.customerId === targetCustomerId) {
      ElMessage.error(error instanceof Error ? error.message : '人工调整失败')
    }
  } finally {
    if (!disposed && sequence === loadSequence && props.customerId === targetCustomerId) saving.value = false
  }
}

function fen(value: number | null): string {
  return value === null ? '—' : `¥${fenToYuan(value)}`
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: view.value?.latestSnapshot?.timezone ?? 'Asia/Shanghai',
    hour12: false,
  }).format(new Date(value))
}
</script>

<template>
  <section v-loading="loading" class="tier-panel" aria-label="客户自动分级">
    <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" />
    <template v-else-if="view">
      <div class="tier-heading">
        <div>
          <span class="tier-label">当前等级</span>
          <strong data-testid="tier-current">{{ view.tierCode ?? '未分级' }}</strong>
          <el-tag v-if="view.locked" type="warning" size="small">人工锁定</el-tag>
        </div>
        <div v-if="auth.hasPermission('customer:write')" class="tier-actions">
          <el-button
            data-testid="tier-recalculate"
            :loading="recalculating"
            :disabled="!view.activeRuleId"
            @click="recalculate"
          >
            立即重算
          </el-button>
          <el-button data-testid="tier-adjust" :disabled="!view.activeRuleId" @click="openAdjustment">
            人工调整/锁定
          </el-button>
        </div>
      </div>
      <el-alert
        v-if="!view.activeRuleId"
        type="info"
        :closable="false"
        title="尚未启用分级规则，不能自动重算或人工定级"
      />
      <el-alert
        v-if="view.pendingReview"
        type="warning"
        :closable="false"
        :title="`降级复核 #${view.pendingReview.id}：${view.pendingReview.fromTierCode} → ${view.pendingReview.toTierCode}，批准前当前等级不变`"
        data-testid="tier-pending-review"
      />

      <template v-if="view.latestSnapshot">
        <div class="metric-grid">
          <div class="consumption-card">
            <span>实际消费金额</span>
            <strong data-testid="tier-actual-consumption">{{ fen(view.latestSnapshot.actualConsumptionFen) }}</strong>
          </div>
          <div><span>有效订单实付金额</span><strong>{{ fen(view.latestSnapshot.paidAmountFen) }}</strong></div>
          <div><span>成功退款金额</span><strong>{{ fen(view.latestSnapshot.refundAmountFen) }}</strong></div>
          <div><span>建议等级</span><strong>{{ view.latestSnapshot.suggestedTierCode ?? '—' }}</strong></div>
        </div>
        <p class="window-note">
          统计近 12 个月：{{ formatDateTime(view.latestSnapshot.windowStartInclusive) }} 至 {{ formatDateTime(view.latestSnapshot.windowEndExclusive) }}（不含结束时刻）
        </p>
        <p class="window-note">实际消费金额＝有效订单实付金额－成功退款金额，负数按 0 计算；降级须人工复核。</p>
      </template>
      <el-empty v-else description="尚无消费快照" :image-size="56" />

      <h4>等级生效历史</h4>
      <ul v-if="history.length" class="tier-history">
        <li v-for="item in history" :key="item.id">
          <span>{{ item.fromTierCode ?? '未分级' }} → {{ item.toTierCode }}</span>
          <span>{{ historyTypeLabels[item.changeType] }}</span>
          <small>{{ item.effectiveAt }}<template v-if="item.reason"> · {{ item.reason }}</template></small>
        </li>
      </ul>
      <p v-else class="window-note">暂无等级变更历史</p>
    </template>

    <el-dialog v-model="adjustVisible" title="人工调整等级" width="min(520px, 92vw)">
      <el-alert type="warning" :closable="false" title="调整会追加历史并使待复核单失效；锁定后自动升级和降级均不执行" />
      <el-form label-width="96px" class="adjust-form">
        <el-form-item label="目标等级" required>
          <el-select v-model="adjustForm.tierCode" data-testid="tier-adjust-code">
            <el-option v-for="code in ['S', 'A', 'B', 'C', 'D']" :key="code" :label="code" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="调整理由" required>
          <el-input v-model="adjustForm.reason" type="textarea" :rows="3" maxlength="1000" data-testid="tier-adjust-reason" />
        </el-form-item>
        <el-form-item label="人工锁定">
          <el-switch v-model="adjustForm.lock" data-testid="tier-adjust-lock" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="tier-adjust-submit" @click="saveAdjustment">
          确认调整
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.tier-panel { display: grid; gap: 16px; }
.tier-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.tier-heading > div:first-child { display: flex; align-items: center; gap: 10px; }
.tier-label { color: #667085; }
.tier-heading strong { font-size: 26px; }
.tier-actions { display: flex; gap: 8px; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.metric-grid > div { display: grid; gap: 6px; padding: 12px; border: 1px solid #e5eaf0; border-radius: 10px; }
.metric-grid span { color: #667085; font-size: 12px; }
.metric-grid .consumption-card { border-color: #bfddd5; background: #f0f8f5; }
.consumption-card strong { color: #126f64; }
.window-note { margin: 0; color: #667085; font-size: 12px; overflow-wrap: anywhere; line-height: 1.7; }
.tier-history { display: grid; gap: 8px; padding: 0; list-style: none; }
.tier-history li { display: grid; grid-template-columns: 100px 140px 1fr; gap: 10px; align-items: center; }
.tier-history small { color: #667085; }
.adjust-form { margin-top: 16px; }
@media (max-width: 760px) {
  .metric-grid { grid-template-columns: 1fr; }
  .tier-heading { align-items: flex-start; flex-direction: column; }
  .tier-history li { grid-template-columns: 1fr; }
}
</style>
