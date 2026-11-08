<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Redemption } from '../../api/fulfillment'
import { listRedemptions } from '../../api/fulfillment'
import type { SettlementBatchDetail } from '../../api/settlement'
import { createSettlementBatch } from '../../api/settlement'
import { createFinanceIdempotencyKey, settlementPeriodLabel } from '../finance/financeUi'
import { tradingErrorText } from '../trading/tradingUi'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: SettlementBatchDetail]
}>()
const form = reactive({ institutionId: undefined as number | undefined, settlementType: 'MONTHLY' as 'MONTHLY' | 'PER_REDEMPTION', periodMonth: '' })
const redemptions = ref<Redemption[]>([])
const selected = ref<Redemption[]>([])
const loading = ref(false)
const saving = ref(false)
let idempotencyKey = ''
const periodKey = computed(() => form.settlementType === 'MONTHLY' ? form.periodMonth : (selected.value[0] ? `REDEMPTION:${selected.value[0].id}` : ''))

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  form.institutionId = undefined
  form.settlementType = 'MONTHLY'
  form.periodMonth = ''
  redemptions.value = []
  selected.value = []
  idempotencyKey = createFinanceIdempotencyKey('settlement.batch.create')
})

function updateVisible(value: boolean) {
  if (!saving.value) emit('update:modelValue', value)
}

async function loadRedemptionOptions() {
  if (!form.institutionId) {
    ElMessage.warning('请先填写机构 ID')
    return
  }
  loading.value = true
  selected.value = []
  try {
    const result = await listRedemptions({ page: 1, size: 100, institutionId: form.institutionId, status: 'POSTED' })
    redemptions.value = result.items
  } catch (error) {
    redemptions.value = []
    ElMessage.error(tradingErrorText(error))
  } finally {
    loading.value = false
  }
}

function handleSelection(rows: Redemption[]) {
  if (form.settlementType === 'PER_REDEMPTION' && rows.length > 1) {
    ElMessage.warning('次卡次结每批只能选择一笔正式核销，请取消多余选择')
  }
  selected.value = rows
}

async function save() {
  if (!form.institutionId || !periodKey.value || selected.value.length === 0) {
    ElMessage.warning('请选择机构、结算周期和至少一笔正式核销')
    return
  }
  if (form.settlementType === 'PER_REDEMPTION' && selected.value.length !== 1) {
    ElMessage.warning('次卡次结每批必须且只能选择一笔正式核销')
    return
  }
  saving.value = true
  try {
    const saved = await createSettlementBatch({
      institutionId: form.institutionId,
      settlementType: form.settlementType,
      periodKey: periodKey.value,
      redemptionIds: selected.value.map((item) => item.id),
    }, idempotencyKey)
    ElMessage.success('结算批次已创建，金额仍待人工核实')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer :model-value="modelValue" title="创建机构结算批次" size="min(820px, 100vw)" @update:model-value="updateVisible">
    <el-alert type="warning" :closable="false" title="仅选择真实正式核销。创建批次不会自动应用机构费率，金额仍需人工核准。" class="finance-action-note" />
    <el-form label-position="top" @submit.prevent>
      <div class="finance-form-grid">
        <el-form-item label="机构 ID" required><el-input-number v-model="form.institutionId" :min="1" :precision="0" :controls="false" class="finance-field" data-testid="settlement-institution-id" /></el-form-item>
        <el-form-item label="结算方式" required><el-select v-model="form.settlementType" class="finance-field" data-testid="settlement-type"><el-option label="机构月结" value="MONTHLY" /><el-option label="次卡次结" value="PER_REDEMPTION" /></el-select></el-form-item>
        <el-form-item v-if="form.settlementType === 'MONTHLY'" label="结算月份" required><el-date-picker v-model="form.periodMonth" type="month" value-format="YYYY-MM" :clearable="false" class="finance-field" placeholder="选择月份" data-testid="settlement-period" /></el-form-item>
        <el-form-item v-else label="结算周期"><el-input :model-value="settlementPeriodLabel(form.settlementType, periodKey)" disabled class="finance-field" placeholder="选择一笔核销后生成" /></el-form-item>
        <el-form-item class="finance-full"><el-button :loading="loading" @click="loadRedemptionOptions">读取该机构正式核销</el-button></el-form-item>
      </div>
    </el-form>
    <app-table ref="redemptionTable" v-loading="loading" :data="redemptions" row-key="id" size="small" @selection-change="handleSelection">
      <el-table-column type="selection" width="48" reserve-selection />
      <el-table-column label="核销 ID" width="100"><template #default="{ row }">#{{ row.id }}</template></el-table-column>
      <el-table-column label="客户" width="110"><template #default="{ row }">客户 #{{ row.customerId }}</template></el-table-column>
      <el-table-column label="实际服务时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.serviceAt) }}</template></el-table-column>
      <el-table-column label="金额状态" min-width="150"><template #default>待核实</template></el-table-column>
      <template #empty><el-empty description="读取后显示该机构正式核销；已入批记录由后端拒绝重复结算" /></template>
    </app-table>
    <div class="finance-allocation-total"><span>已选择 {{ selected.length }} 笔正式核销</span><strong>{{ periodKey ? `批次：${settlementPeriodLabel(form.settlementType, periodKey)}` : '请选择结算周期' }}</strong></div>
    <template #footer><div class="finance-drawer-footer"><el-button :disabled="saving" @click="updateVisible(false)">取消</el-button><el-button type="primary" :loading="saving" data-testid="settlement-create-submit" @click="save">创建待核实批次</el-button></div></template>
  </el-drawer>
</template>
