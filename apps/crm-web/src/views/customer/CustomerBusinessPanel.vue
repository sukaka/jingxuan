<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { listEntitlements, listOrders } from '../../api/trading'
import type { CrmOrder, Entitlement } from '../../api/trading'
import { listRedemptions } from '../../api/fulfillment'
import type { Redemption } from '../../api/fulfillment'
import { fenToYuan } from '../../utils/money'
import { formatDateTime } from './shared'
import { useReferenceNames } from '../references'
import { entitlementStatusLabel, entitlementStatusTone, orderStatusLabel, orderStatusTone, orderTypeLabel } from '../trading/tradingUi'

const { institutionLabel } = useReferenceNames()

const REDEMPTION_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  POSTED: ['机构已确认', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  REVERSED: ['已冲正', 'dark'],
}
function redemptionPill(status: string) { return REDEMPTION_STATUS[status] || ['未知状态', 'dark'] }
function localizedLabel(value: string, label: string, fallback = '未知状态') {
  return label === value ? fallback : label
}

const props = defineProps<{ customerId: number; type: 'entitlements' | 'redemptions' | 'orders' }>()
const orders = ref<CrmOrder[]>([])
const entitlements = ref<Entitlement[]>([])
const redemptions = ref<Redemption[]>([])
const loading = ref(false)
const errorMessage = ref('')
async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    if (props.type === 'orders') orders.value = (await listOrders({ customerId: props.customerId, page: 1, size: 100 })).items
    else if (props.type === 'entitlements') entitlements.value = (await listEntitlements({ customerId: props.customerId, page: 1, size: 100 })).items
    else redemptions.value = (await listRedemptions({ customerId: props.customerId, page: 1, size: 100 })).items
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '业务记录加载失败'
  } finally { loading.value = false }
}
watch(() => [props.customerId, props.type], load)
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
    <app-table v-else-if="type === 'orders'" :data="orders">
      <el-table-column prop="orderNo" label="订单号" min-width="155" />
      <el-table-column label="类型" width="105"><template #default="{row}">{{ localizedLabel(row.orderTypeCode, orderTypeLabel(row.orderTypeCode), '未知类型') }}</template></el-table-column>
      <el-table-column label="应付（元）" width="110" align="right"><template #default="{row}">{{ fenToYuan(row.payableFen) }}</template></el-table-column>
      <el-table-column label="实付（元）" width="110" align="right"><template #default="{row}">{{ fenToYuan(row.paidFen) }}</template></el-table-column>
      <el-table-column label="已退款（元）" width="110" align="right"><template #default="{row}">{{ fenToYuan(row.refundedFen) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{row}"><span class="proto-pill" :class="orderStatusTone(row.status)">{{ localizedLabel(row.status, orderStatusLabel(row.status)) }}</span></template></el-table-column>
      <el-table-column label="下单时间" min-width="160"><template #default="{row}">{{ formatDateTime(row.orderedAt) }}</template></el-table-column>
      <template #empty>暂无订单或退款记录</template>
    </app-table>
    <app-table v-else-if="type === 'entitlements'" :data="entitlements">
      <el-table-column prop="projectName" label="项目" min-width="160" />
      <el-table-column prop="grantedCount" label="发放次数" width="100" align="right" />
      <el-table-column prop="availableCount" label="剩余次数" width="100" align="right" />
      <el-table-column label="状态" width="110"><template #default="{row}"><span class="proto-pill" :class="entitlementStatusTone(row.status)">{{ localizedLabel(row.status, entitlementStatusLabel(row.status)) }}</span></template></el-table-column>
      <el-table-column label="有效期至" width="120"><template #default="{row}">{{ row.validTo || '长期' }}</template></el-table-column>
      <template #empty>暂无权益记录</template>
    </app-table>
    <app-table v-else :data="redemptions">
      <el-table-column label="核销流水" min-width="110"><template #default="{row}">#{{ row.id }}</template></el-table-column>
      <el-table-column label="预约" min-width="150"><template #default="{row}">#{{ row.appointmentId }}</template></el-table-column>
      <el-table-column label="机构" min-width="100"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
      <el-table-column label="服务时间" min-width="160"><template #default="{row}">{{ formatDateTime(row.serviceAt) }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{row}"><span class="proto-pill" :class="redemptionPill(row.status)[1]">{{ redemptionPill(row.status)[0] }}</span></template></el-table-column>
      <template #empty>暂无核销记录</template>
    </app-table>
  </div>
</template>
