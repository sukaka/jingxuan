<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getAppointment, getRedemption, listRedemptions } from '../../api/fulfillment'
import type { Appointment, AppointmentDetail, Redemption, RedemptionDetail } from '../../api/fulfillment'
import { mapWithConcurrency } from '../../utils/asyncBatch'
import { useReferenceNames } from '../references'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{ appointments: Appointment[]; loading: boolean }>()
const emit = defineEmits<{ detail: [id: number] }>()
const activeTab = ref('appointments')
const appointmentDetails = ref<Record<number, AppointmentDetail>>({})
const redemptions = ref<Redemption[]>([])
const redemptionDetails = ref<Record<number, RedemptionDetail>>({})
const redemptionLoading = ref(false)
const redemptionPage = ref(1)
const redemptionSize = ref(20)
const redemptionTotal = ref(0)
const appointmentCache = new Map<string, AppointmentDetail>()
const redemptionCache = new Map<string, RedemptionDetail>()
let appointmentSeq = 0
let redemptionSeq = 0
let disposed = false
const { loadCustomerNames, customerLabel, institutionLabel, projectLabel } = useReferenceNames()

const APPOINTMENT_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  CONFIRMED: ['机构已确认', 'success'],
  CHECKED_IN: ['客户已到院', 'info'],
  SERVICE_DONE: ['服务完成', 'success'],
  REDEEMED: ['已完成', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  CANCELED: ['已取消', 'dark'],
  NO_SHOW: ['未到院', 'danger'],
}
const REDEMPTION_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  POSTED: ['机构已确认核销', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  REVERSED: ['已冲正', 'dark'],
}
function pill(status: string, map: Record<string, [string, string]>) { return map[status] || ['未知状态', 'dark'] }

watch(() => props.appointments, async (items) => {
  const seq = ++appointmentSeq
  if (!items.length) {
    appointmentDetails.value = {}
    return
  }
  const uncached = items.filter((item) => !appointmentCache.has(`${item.id}:${item.version}`))
  const results = await mapWithConcurrency(uncached, 5, (item) => getAppointment(item.id))
  if (disposed || seq !== appointmentSeq) return
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      const item = uncached[index]
      appointmentCache.set(`${item.id}:${item.version}`, result.value)
    }
  })
  const map: Record<number, AppointmentDetail> = {}
  items.forEach((item) => {
    const cached = appointmentCache.get(`${item.id}:${item.version}`)
    if (cached) map[item.id] = cached
  })
  appointmentDetails.value = map
  void loadCustomerNames(items.map((item) => item.customerId))
}, { immediate: true })

function projects(id: number) {
  return appointmentDetails.value[id]?.items.map((item) => `${projectLabel(item.projectId)} × ${item.plannedCount}`).join('、') || '—'
}

async function loadRedemptions(nextPage = redemptionPage.value) {
  const seq = ++redemptionSeq
  redemptionLoading.value = true
  try {
    const result = await listRedemptions({ page: nextPage, size: redemptionSize.value })
    if (disposed || seq !== redemptionSeq) return
    redemptions.value = result.items
    redemptionTotal.value = result.total
    redemptionPage.value = result.page
    redemptionSize.value = result.size

    const uncached = result.items.filter((item) => !redemptionCache.has(`${item.id}:${item.version}`))
    const details = await mapWithConcurrency(uncached, 5, (item) => getRedemption(item.id))
    if (disposed || seq !== redemptionSeq) return
    details.forEach((entry, index) => {
      if (entry.status === 'fulfilled') {
        const item = uncached[index]
        redemptionCache.set(`${item.id}:${item.version}`, entry.value)
      }
    })
    const map: Record<number, RedemptionDetail> = {}
    result.items.forEach((item) => {
      const cached = redemptionCache.get(`${item.id}:${item.version}`)
      if (cached) map[item.id] = cached
    })
    redemptionDetails.value = map
    void loadCustomerNames(result.items.map((item) => item.customerId))
  } finally {
    if (!disposed && seq === redemptionSeq) redemptionLoading.value = false
  }
}

watch(activeTab, (tab) => { if (tab === 'redemptions') void loadRedemptions(1) })
onBeforeUnmount(() => {
  disposed = true
  appointmentSeq += 1
  redemptionSeq += 1
})
function lines(id: number) { return redemptionDetails.value[id]?.lines || [] }
</script>

<template>
  <el-tabs v-model="activeTab">
    <el-tab-pane label="到院预约" name="appointments">
      <div class="proto-table-wrap">
        <app-table v-loading="loading" :data="appointments">
          <el-table-column prop="appointmentNo" label="预约单" min-width="150" />
          <el-table-column label="客户" min-width="100"><template #default="{row}">{{ customerLabel(row.customerId) }}</template></el-table-column>
          <el-table-column label="项目" min-width="170"><template #default="{row}">{{ projects(row.id) }}</template></el-table-column>
          <el-table-column label="机构" min-width="100"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
          <el-table-column label="日期" min-width="160"><template #default="{row}">{{ formatDateTime(row.scheduledAt) }}</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{row}"><span class="proto-pill" :class="pill(row.status, APPOINTMENT_STATUS)[1]">{{ pill(row.status, APPOINTMENT_STATUS)[0] }}</span></template></el-table-column>
          <el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button v-if="row.status !== 'REDEEMED'" text @click="emit('detail',row.id)">修改</el-button><span v-else class="proto-note">已完成不可改</span></template></el-table-column>
          <template #empty>暂无预约</template>
        </app-table>
      </div>
    </el-tab-pane>
    <el-tab-pane label="项目核销记录" name="redemptions">
      <div class="proto-table-wrap">
        <app-table v-loading="redemptionLoading" :data="redemptions">
          <el-table-column label="核销流水" width="100"><template #default="{row}">#{{ row.id }}</template></el-table-column>
          <el-table-column label="客户" width="100"><template #default="{row}">{{ customerLabel(row.customerId) }}</template></el-table-column>
          <el-table-column label="项目" min-width="160"><template #default="{row}"><div v-for="line in lines(row.id)" :key="line.id">{{ projectLabel(line.projectId) }}</div></template></el-table-column>
          <el-table-column label="次数" width="90" align="right"><template #default="{row}">{{ lines(row.id).reduce((sum,line)=>sum+line.count,0) }}</template></el-table-column>
          <el-table-column label="机构" width="100"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
          <el-table-column label="日期" min-width="160"><template #default="{row}">{{ formatDateTime(row.serviceAt) }}</template></el-table-column>
          <el-table-column label="状态" width="130"><template #default="{row}"><span class="proto-pill" :class="pill(row.status, REDEMPTION_STATUS)[1]">{{ pill(row.status, REDEMPTION_STATUS)[0] }}</span></template></el-table-column>
          <template #empty>暂无核销记录</template>
        </app-table>
      </div>
      <el-pagination
        v-if="redemptionTotal > redemptionSize"
        v-model:current-page="redemptionPage"
        v-model:page-size="redemptionSize"
        :total="redemptionTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="crm-pager"
        @current-change="loadRedemptions"
        @size-change="loadRedemptions(1)"
      />
    </el-tab-pane>
  </el-tabs>
</template>
