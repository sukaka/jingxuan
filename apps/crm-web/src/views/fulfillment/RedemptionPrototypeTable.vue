<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getRedemption } from '../../api/fulfillment'
import type { Redemption, RedemptionDetail } from '../../api/fulfillment'
import { getEntitlement } from '../../api/trading'
import { mapWithConcurrency } from '../../utils/asyncBatch'
import { useReferenceNames } from '../references'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{ rows: Redemption[]; canReverse: boolean }>()
const emit = defineEmits<{
  detail: [id: number]
  reverse: [row: Redemption]
  'details-loaded': [details: Record<number, RedemptionDetail>]
}>()
const details = ref<Record<number, RedemptionDetail>>({})
const cumulativeCounts = ref<Record<number, number>>({})
const detailCache = new Map<string, RedemptionDetail>()
const entitlementCache = new Map<number, number>()
let loadSequence = 0
let disposed = false
const { loadCustomerNames, customerLabel, institutionLabel, projectLabel } = useReferenceNames()

const REDEMPTION_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  POSTED: ['机构已确认', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  REVERSED: ['已冲正', 'dark'],
}
function pill(status: string) { return REDEMPTION_STATUS[status] || ['未知状态', 'dark'] }

watch(() => props.rows, async (rows) => {
  const sequence = ++loadSequence
  if (!rows.length) {
    details.value = {}
    cumulativeCounts.value = {}
    emit('details-loaded', {})
    return
  }

  const uncachedRows = rows.filter((row) => !detailCache.has(`${row.id}:${row.version}`))
  const results = await mapWithConcurrency(uncachedRows, 5, (row) => getRedemption(row.id))
  if (disposed || sequence !== loadSequence) return

  results.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      const row = uncachedRows[index]
      detailCache.set(`${row.id}:${row.version}`, result.value)
    }
  })

  const map: Record<number, RedemptionDetail> = {}
  rows.forEach((row) => {
    const cached = detailCache.get(`${row.id}:${row.version}`)
    if (cached) map[row.id] = cached
  })
  details.value = map
  emit('details-loaded', map)
  void loadCustomerNames(rows.map((row) => row.customerId))

  const entitlementIds = [...new Set(Object.values(map).flatMap((detail) => detail.lines.map((line) => line.entitlementId)))]
  const uncachedEntitlements = entitlementIds.filter((id) => !entitlementCache.has(id))
  const ledgers = await mapWithConcurrency(uncachedEntitlements, 5, (id) => getEntitlement(id))
  if (disposed || sequence !== loadSequence) return

  ledgers.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      entitlementCache.set(
        uncachedEntitlements[index],
        result.value.ledger.filter((line) => line.txnType === 'REDEEM').reduce((sum, line) => sum + Math.abs(line.deltaCount), 0),
      )
    }
  })

  const totals: Record<number, number> = {}
  entitlementIds.forEach((id) => {
    const total = entitlementCache.get(id)
    if (total !== undefined) totals[id] = total
  })
  cumulativeCounts.value = totals
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  loadSequence += 1
})

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
