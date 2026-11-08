<script setup lang="ts">
import { ref, watch } from 'vue'
import { getRedemption } from '../../api/fulfillment'
import type { Redemption, RedemptionDetail } from '../../api/fulfillment'
import { getEntitlement } from '../../api/trading'
import { useReferenceNames } from '../references'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{ rows: Redemption[]; canReverse: boolean }>()
const emit = defineEmits<{ detail: [id: number]; reverse: [row: Redemption] }>()
const details = ref<Record<number, RedemptionDetail>>({})
const cumulativeCounts = ref<Record<number, number>>({})
const { loadCustomerNames, customerLabel, institutionLabel, projectLabel } = useReferenceNames()

const REDEMPTION_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  POSTED: ['机构已确认', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  REVERSED: ['已冲正', 'dark'],
}
function pill(status: string) { return REDEMPTION_STATUS[status] || ['未知状态', 'dark'] }

watch(() => props.rows, async (rows) => {
  const results = await Promise.allSettled(rows.map((row) => getRedemption(row.id)))
  const map: Record<number, RedemptionDetail> = {}
  results.forEach((result, index) => { if (result.status === 'fulfilled') map[rows[index].id] = result.value })
  details.value = map
  void loadCustomerNames(rows.map((row) => row.customerId))
  const entitlementIds = [...new Set(Object.values(map).flatMap((detail) => detail.lines.map((line) => line.entitlementId)))]
  const ledgers = await Promise.allSettled(entitlementIds.map((id) => getEntitlement(id)))
  const totals: Record<number, number> = {}
  ledgers.forEach((result, index) => {
    if (result.status === 'fulfilled') totals[entitlementIds[index]] = result.value.ledger
      .filter((line) => line.txnType === 'REDEEM').reduce((sum, line) => sum + Math.abs(line.deltaCount), 0)
  })
  cumulativeCounts.value = totals
}, { immediate: true })
function lines(id: number) { return details.value[id]?.lines || [] }
function count(id: number) { return lines(id).reduce((sum, line) => sum + line.count, 0) }
function cumulative(id: number) { return lines(id).reduce((sum, line) => sum + (cumulativeCounts.value[line.entitlementId] || 0), 0) }
</script>

<template>
  <app-table :data="rows">
    <el-table-column label="核销流水" width="100"><template #default="{row}">#{{ row.id }}</template></el-table-column>
    <el-table-column label="客户 / 订单" min-width="125"><template #default="{row}">{{ customerLabel(row.customerId) }}<div class="proto-subcell">预约 #{{ row.appointmentId }}</div></template></el-table-column>
    <el-table-column label="核销项目" min-width="155"><template #default="{row}"><div v-for="line in lines(row.id)" :key="line.id">{{ projectLabel(line.projectId) }}</div></template></el-table-column>
    <el-table-column label="核销机构" min-width="100"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
    <el-table-column label="本次次数" width="90" align="right"><template #default="{row}">{{ count(row.id) }}</template></el-table-column>
    <el-table-column label="累计已核销" width="105" align="right"><template #default="{row}">{{ cumulative(row.id) }}</template></el-table-column>
    <el-table-column label="服务日期" min-width="155"><template #default="{row}">{{ formatDateTime(row.serviceAt) }}</template></el-table-column>
    <el-table-column label="状态" width="115"><template #default="{row}"><span class="proto-pill" :class="pill(row.status)[1]">{{ pill(row.status)[0] }}</span></template></el-table-column>
    <el-table-column label="操作" width="165" fixed="right"><template #default="{row}"><el-button text @click="emit('detail',row.id)">只读详情</el-button><el-button v-if="canReverse && row.status==='POSTED'" text @click="emit('reverse',row)">申请冲正</el-button></template></el-table-column>
    <template #empty>暂无核销记录</template>
  </app-table>
</template>
