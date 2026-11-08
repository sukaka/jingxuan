<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listCustomers, listOwnershipTransfers } from '../api/customer'
import type { OwnershipTransferItem } from '../api/customer'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { formatDateTime } from './customer/shared'

const rows = ref<(OwnershipTransferItem & { customerName: string })[]>([])
const loading = ref(false), errorMessage = ref('')

const STATUS_MAP: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'info' | 'dark' }> = {
  PENDING: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已审批', tone: 'info' },
  EFFECTIVE: { label: '已生效', tone: 'success' },
  REJECTED: { label: '已拒绝', tone: 'danger' },
  CANCELED: { label: '已取消', tone: 'dark' },
}
function statusMeta(status: string) {
  return STATUS_MAP[status] ?? { label: '未知状态', tone: 'dark' as const }
}
onMounted(async () => {
  loading.value = true
  try {
    const customers = (await listCustomers({ page: 1, size: 100 })).items
    const results = await Promise.allSettled(customers.map((customer) => listOwnershipTransfers(customer.id)))
    rows.value = results.flatMap((result, index) => result.status === 'fulfilled'
      ? result.value.map((item) => ({ ...item, customerName: customers[index].displayName || customers[index].nickname || `客户 #${customers[index].id}` })) : [])
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '归属日志加载失败' }
  finally { loading.value = false }
})
</script>

<template><div class="proto-page"><PageHeader title="客户归属日志" description="每次转交保留前后归属、申请人、审核人、原因和生效时间，历史不可覆盖" /><PageCard><el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" /><div class="proto-table-wrap"><app-table v-loading="loading" :data="rows"><el-table-column label="客户" prop="customerName" min-width="120" /><el-table-column label="原管家" min-width="105"><template #default="{row}">{{ row.fromOwnerName || `#${row.fromOwnerId}` }}</template></el-table-column><el-table-column label="新管家" min-width="105"><template #default="{row}">{{ row.toOwnerName || `#${row.toOwnerId}` }}</template></el-table-column><el-table-column label="申请人" min-width="105"><template #default="{row}">{{ row.requestedByName || '—' }}</template></el-table-column><el-table-column label="审核人" min-width="105"><template #default="{row}">{{ row.reviewedByName || '待审核' }}</template></el-table-column><el-table-column prop="reason" label="转交原因" min-width="180" show-overflow-tooltip /><el-table-column label="状态" width="115"><template #default="{row}"><span class="proto-pill" :class="statusMeta(row.status).tone">{{ statusMeta(row.status).label }}</span></template></el-table-column><el-table-column label="生效时间" min-width="160"><template #default="{row}">{{ row.effectiveAt ? formatDateTime(row.effectiveAt) : '尚未生效' }}</template></el-table-column><template #empty>暂无归属变更记录</template></app-table></div></PageCard><footer class="proto-footer">归属变更只能通过申请与审批生效，不能直接覆盖历史</footer></div></template>
