<script setup lang="ts">
import { computed } from 'vue'
import type { SettlementBatchDetail } from '../../api/settlement'
import { fenToYuan } from '../../utils/money'
import StatusPill from '../customer/StatusPill.vue'
import { formatDateTime } from '../customer/shared'
import FileDownloadButton from '../institution/FileDownloadButton.vue'
import { amountStatusLabel, settlementAmount, settlementPeriodLabel, settlementStatusLabel, settlementStatusTone, settlementTypeLabel } from '../finance/financeUi'
import { rateDisplay, settlementDayLabel, settlementDaySourceLabel } from '../institution/institutionUi'

const props = defineProps<{
  modelValue: boolean
  detail: SettlementBatchDetail | null
  canVerify: boolean
  canPay: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  verify: []
  pay: []
}>()
const batch = computed(() => props.detail?.batch ?? null)

function formatPendingReason(reason: string | null | undefined): string {
  if (!reason) return '-'
  const cleaned = reason.replace(/Q\d{2}(?:\/Q\d{2})*\s*/g, '').trim()
  return cleaned || '待人工核实'
}

function formatRateSnapshot(raw: string | null | undefined): string {
  if (!raw) return '-'
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return raw
    const record = parsed as Record<string, unknown>
    const parts: string[] = []
    if (record.institutionVersion !== undefined && record.institutionVersion !== null) {
      parts.push(`档案版本：第 ${String(record.institutionVersion)} 版`)
    }
    if (typeof record.settlementType === 'string' && record.settlementType) {
      parts.push(`结算方式：${settlementTypeLabel(record.settlementType)}`)
    }
    if (record.onlineRate !== undefined && record.onlineRate !== null && record.onlineRate !== '') {
      parts.push(`线上佣金率：${rateDisplay(record.onlineRate as number | string)}`)
    }
    if (record.offlineRate !== undefined && record.offlineRate !== null && record.offlineRate !== '') {
      parts.push(`线下佣金率：${rateDisplay(record.offlineRate as number | string)}`)
    }
    if (record.platformRate !== undefined && record.platformRate !== null && record.platformRate !== '') {
      parts.push(`平台佣金率：${rateDisplay(record.platformRate as number | string)}`)
    }
    if (typeof record.otherRateRule === 'string' && record.otherRateRule.trim()) {
      parts.push(`其他佣金规则：${record.otherRateRule.trim()}`)
    }
    return parts.length ? parts.join(' · ') : '已记录费率配置'
  } catch {
    return raw
  }
}
</script>

<template>
  <el-drawer :model-value="modelValue" :title="batch ? `结算批次 #${batch.id}` : '结算批次详情'" size="min(980px, 100vw)" @update:model-value="emit('update:modelValue', $event)">
    <template v-if="detail && batch">
      <div class="order-head">
        <div><div class="customer-name">机构 #{{ batch.institutionId }} · {{ settlementTypeLabel(batch.settlementType) }}</div><div class="crm-muted">{{ settlementPeriodLabel(batch.settlementType, batch.periodKey) }} · 创建于 {{ formatDateTime(batch.createdAt) }}</div></div>
        <StatusPill :text="settlementStatusLabel(batch.status)" :tone="settlementStatusTone(batch.status)" />
      </div>
      <el-alert v-if="batch.amountStatus === 'PENDING_VERIFICATION'" type="warning" :closable="false" title="金额待核实：当前 0 值不代表应结金额，必须完成线下规则与金额核准后才能付款。" class="finance-action-note" data-testid="settlement-pending-alert" />
      <div class="finance-summary">
        <div class="finance-summary-item"><small>核销基数</small><strong :class="{ pending: batch.amountStatus === 'PENDING_VERIFICATION' }">{{ batch.amountStatus === 'PENDING_VERIFICATION' ? '待核实' : `¥${fenToYuan(batch.grossFen)}` }}</strong></div>
        <div class="finance-summary-item"><small>佣金</small><strong :class="{ pending: batch.amountStatus === 'PENDING_VERIFICATION' }">{{ batch.amountStatus === 'PENDING_VERIFICATION' ? '待核实' : `¥${fenToYuan(batch.commissionFen)}` }}</strong></div>
        <div class="finance-summary-item"><small>应结净额</small><strong :class="{ pending: batch.amountStatus === 'PENDING_VERIFICATION' }">{{ batch.amountStatus === 'PENDING_VERIFICATION' ? '待核实' : `¥${fenToYuan(batch.netPayableFen)}` }}</strong></div>
        <div class="finance-summary-item"><small>已登记付款</small><strong>¥{{ fenToYuan(batch.paidFen) }}</strong></div>
        <div class="finance-summary-item"><small>金额状态</small><strong :class="{ pending: batch.amountStatus === 'PENDING_VERIFICATION' }">{{ amountStatusLabel(batch.amountStatus) }}</strong></div>
        <div class="finance-summary-item"><small>剩余待付</small><strong :class="{ pending: batch.amountStatus === 'PENDING_VERIFICATION' }">{{ batch.amountStatus === 'PENDING_VERIFICATION' ? '待核实' : `¥${fenToYuan(Math.max(0, batch.netPayableFen - batch.paidFen))}` }}</strong></div>
      </div>
      <el-descriptions :column="2" border class="trading-descriptions">
        <el-descriptions-item label="实际结算日">{{ batch.settlementType === 'PER_REDEMPTION' ? '次卡次结不适用' : batch.settlementDate || '历史批次未记录' }}</el-descriptions-item>
        <el-descriptions-item label="结算日规则 / 来源">{{ batch.settlementType === 'PER_REDEMPTION' ? '不适用' : batch.settlementDay ? `${settlementDayLabel(batch.settlementDay)} / ${settlementDaySourceLabel(batch.settlementDaySource)}` : '历史批次未记录' }}</el-descriptions-item>
        <el-descriptions-item label="线下核准规则版本">{{ batch.ruleVersion || '待核准' }}</el-descriptions-item>
        <el-descriptions-item label="核准证据">{{ batch.verificationEvidence || '待核准' }}</el-descriptions-item>
      </el-descriptions>

      <section class="finance-section">
        <div class="finance-section-head"><div><h3>正式核销与结算规则</h3><p>入批时锁定的费率规则仅供人工核准参考，不会自动计算应结金额。</p></div></div>
        <app-table :data="detail.lines" size="small">
          <el-table-column label="核销编号" width="100"><template #default="{ row }">#{{ row.redemptionId }}</template></el-table-column>
          <el-table-column label="金额状态" width="120"><template #default="{ row }"><StatusPill :text="amountStatusLabel(row.amountStatus)" :tone="row.amountStatus === 'VERIFIED' ? 'success' : 'warning'" /></template></el-table-column>
          <el-table-column label="核销基数" width="110" align="right"><template #default="{ row }"><span class="finance-amount" :class="{ pending: row.amountStatus === 'PENDING_VERIFICATION' }">{{ settlementAmount(row.amountStatus, row.grossFen) }}</span></template></el-table-column>
          <el-table-column label="佣金" width="110" align="right"><template #default="{ row }"><span class="finance-amount" :class="{ pending: row.amountStatus === 'PENDING_VERIFICATION' }">{{ settlementAmount(row.amountStatus, row.commissionFen) }}</span></template></el-table-column>
          <el-table-column label="应结净额" width="110" align="right"><template #default="{ row }"><span class="finance-amount" :class="{ pending: row.amountStatus === 'PENDING_VERIFICATION' }">{{ settlementAmount(row.amountStatus, row.payableFen) }}</span></template></el-table-column>
          <el-table-column label="待核实原因 / 锁定规则" min-width="240"><template #default="{ row }"><div>{{ formatPendingReason(row.pendingReason) }}</div><div class="finance-snapshot">{{ formatRateSnapshot(row.rateSnapshot) }}</div></template></el-table-column>
        </app-table>
      </section>

      <section class="finance-section">
        <div class="finance-section-head"><div><h3>付款记录</h3><p>此处为人工付款登记（非银行自动到账通知），每笔均保留真实流水与扫描通过的凭证。</p></div></div>
        <app-table :data="detail.payments" size="small">
          <el-table-column prop="bankTradeNo" label="银行流水" min-width="170" />
          <el-table-column label="金额" width="110" align="right"><template #default="{ row }">¥{{ fenToYuan(row.amountFen) }}</template></el-table-column>
          <el-table-column label="实际付款时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.paidAt) }}</template></el-table-column>
          <el-table-column prop="evidenceRef" label="核验证据" min-width="150" />
          <el-table-column label="凭证" width="100"><template #default="{ row }"><FileDownloadButton :file-id="row.voucherFileId" /></template></el-table-column>
          <template #empty><el-empty description="尚无付款记录" /></template>
        </app-table>
      </section>
    </template>
    <template #footer><div class="finance-drawer-footer"><el-button @click="emit('update:modelValue', false)">关闭</el-button><el-button v-if="canVerify" type="primary" data-testid="settlement-detail-verify" @click="emit('verify')">核准金额</el-button><el-button v-if="canPay" type="primary" data-testid="settlement-detail-pay" @click="emit('pay')">登记付款</el-button></div></template>
  </el-drawer>
</template>

<style scoped>
.order-head { display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:18px; }
.customer-name { margin-bottom:5px;font-size:18px;font-weight:700; }
</style>
