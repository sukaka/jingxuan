<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getRedemption, listRedemptions } from '../../api/fulfillment'
import StatCard from '../../components/prototype/StatCard.vue'
import { fetchAllPages, mapWithConcurrency } from '../../utils/asyncBatch'

const count = ref(0), institutions = ref(0), pending = ref(0), customers = ref(0)
const truncated = ref(false)
const errorMessage = ref('')
let disposed = false

onBeforeUnmount(() => {
  disposed = true
})

onMounted(async () => {
  try {
    const [posted, waiting] = await Promise.all([
      fetchAllPages((page, size) => listRedemptions({ page, size, status: 'POSTED' }), { size: 100, maxPages: 10 }),
      listRedemptions({ page: 1, size: 1, status: 'PENDING_INSTITUTION' }),
    ])
    if (disposed) return
    truncated.value = posted.truncated
    const details = await mapWithConcurrency(posted.items, 5, (item) => getRedemption(item.id))
    if (disposed) return
    count.value = details.reduce((sum, item) => sum + (item.status === 'fulfilled' ? item.value.lines.reduce((n, line) => n + line.count, 0) : 0), 0)
    institutions.value = new Set(posted.items.map((item) => item.institutionId)).size
    customers.value = new Set(posted.items.map((item) => item.customerId)).size
    pending.value = waiting.total
  } catch (error) {
    if (!disposed) errorMessage.value = error instanceof Error ? error.message : '核销汇总加载失败'
  }
})
</script>

<template>
  <div>
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" style="margin-bottom:12px" />
    <div class="proto-stat-grid">
      <StatCard label="正式核销次数" :value="`${count} 次`" :sub="truncated ? '仅统计前 1,000 笔正式核销明细' : '按正式项目明细汇总'" icon="check" />
      <StatCard label="发生核销机构" :value="`${institutions} 家`" :sub="truncated ? '前 1,000 笔范围去重' : '当前数据范围'" icon="hospital" />
      <StatCard label="待机构确认" :value="`${pending} 笔`" sub="不提前扣减权益" icon="calendar" />
      <StatCard label="核销客户" :value="`${customers} 位`" :sub="truncated ? '前 1,000 笔范围去重' : '按客户去重'" icon="users" />
    </div>
  </div>
</template>
