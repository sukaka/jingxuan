<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getRedemption, listRedemptions } from '../../api/fulfillment'
import StatCard from '../../components/prototype/StatCard.vue'

const count = ref(0), institutions = ref(0), pending = ref(0), customers = ref(0)
onMounted(async () => {
  const [posted, waiting] = await Promise.all([
    listRedemptions({ page: 1, size: 100, status: 'POSTED' }),
    listRedemptions({ page: 1, size: 1, status: 'PENDING_INSTITUTION' }),
  ])
  const details = await Promise.allSettled(posted.items.map((item) => getRedemption(item.id)))
  count.value = details.reduce((sum, item) => sum + (item.status === 'fulfilled' ? item.value.lines.reduce((n, line) => n + line.count, 0) : 0), 0)
  institutions.value = new Set(posted.items.map((item) => item.institutionId)).size
  customers.value = new Set(posted.items.map((item) => item.customerId)).size
  pending.value = waiting.total
})
</script>

<template><div class="proto-stat-grid"><StatCard label="正式核销次数" :value="`${count} 次`" sub="按正式项目明细汇总" icon="check" /><StatCard label="发生核销机构" :value="`${institutions} 家`" sub="当前数据范围" icon="hospital" /><StatCard label="待机构确认" :value="`${pending} 笔`" sub="不提前扣减权益" icon="calendar" /><StatCard label="核销客户" :value="`${customers} 位`" sub="按客户去重" icon="users" /></div></template>
