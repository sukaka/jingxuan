<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getEntitlement, listEntitlements } from '../api/trading'
import type { Entitlement, EntitlementDetail } from '../api/trading'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import EntitlementDetailDrawer from './trading/EntitlementDetailDrawer.vue'
import { useReferenceNames } from './references'

const { loadCustomerNames, customerLabel } = useReferenceNames()

const entitlements = ref<Entitlement[]>([]), details = ref<Record<number, EntitlementDetail>>({})
const loading = ref(false), errorMessage = ref(''), keyword = ref('')
const detailVisible = ref(false), selected = ref<EntitlementDetail | null>(null)
const filtered = computed(() => entitlements.value.filter((item) => !keyword.value || `${item.projectName} ${item.customerId} ${item.orderItemId}`.toLowerCase().includes(keyword.value.toLowerCase())))
const totalAvailable = computed(() => entitlements.value.reduce((sum, item) => sum + item.availableCount, 0))
const customerCount = computed(() => new Set(entitlements.value.filter((item) => item.availableCount > 0).map((item) => item.customerId)).size)
const expiringCount = computed(() => {
  const end = new Date(); end.setDate(end.getDate() + 30)
  return entitlements.value.filter((item) => item.availableCount > 0 && item.validTo && new Date(item.validTo) <= end).length
})
const redeemedCount = computed(() => Object.values(details.value).reduce((sum, detail) => sum + detail.ledger.filter((line) => line.txnType === 'REDEEM').reduce((n, line) => n + Math.abs(line.deltaCount), 0), 0))
function redeemed(id: number) { return details.value[id]?.ledger.filter((line) => line.txnType === 'REDEEM').reduce((n, line) => n + Math.abs(line.deltaCount), 0) || 0 }
function returned(id: number) { return details.value[id]?.ledger.filter((line) => ['REFUND_REVOKE','REVOKED'].includes(line.txnType)).reduce((n, line) => n + Math.abs(line.deltaCount), 0) || 0 }
function progress(item: Entitlement) { return item.grantedCount ? Math.round(redeemed(item.id) / item.grantedCount * 100) : 0 }
async function load() {
  loading.value = true
  try {
    const result = await listEntitlements({ page: 1, size: 100 })
    entitlements.value = result.items
    const detailsResult = await Promise.allSettled(result.items.map((item) => getEntitlement(item.id)))
    const map: Record<number, EntitlementDetail> = {}
    detailsResult.forEach((entry, index) => { if (entry.status === 'fulfilled') map[result.items[index].id] = entry.value })
    details.value = map
    void loadCustomerNames(result.items.map((item) => item.customerId))
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '权益加载失败' }
  finally { loading.value = false }
}
function openDetail(detail: EntitlementDetail) { selected.value = detail; detailVisible.value = true }
onMounted(load)
</script>

<template>
  <div class="proto-page">
    <PageHeader title="会员权益" description="按订单和子项目独立计算剩余次数；选 N 项不等于 N 次，每次核销必须经机构确认"><template #actions><RouterLink to="/orders?create=1" class="el-button el-button--primary">＋ 新增权益订单</RouterLink></template></PageHeader>
    <div class="proto-stat-grid"><StatCard label="全部剩余次数" :value="`${totalAvailable} 次`" sub="当前真实权益余额" icon="heart" /><StatCard label="有可用权益的客户" :value="`${customerCount} 位`" sub="按客户去重" icon="users" /><StatCard label="临近到期（30天）" :value="`${expiringCount} 项`" sub="按权益有效期计算" icon="calendar" /><StatCard label="已正式核销" :value="`${redeemedCount} 次`" sub="仅统计正式核销流水" icon="check" /></div>
    <PageCard>
      <div class="proto-toolbar"><el-input v-model="keyword" placeholder="搜索客户、项目、订单" clearable style="width:290px" /><span class="proto-note">机构确认核销后才扣减次数，退款回收按实际流水展示</span></div><el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" /><div class="proto-table-wrap">
        <app-table v-loading="loading" :data="filtered">
          <el-table-column label="客户 / 订单" min-width="125"><template #default="{row}">{{ customerLabel(row.customerId) }}<div class="proto-subcell">订单行 #{{ row.orderItemId }}</div></template></el-table-column>
          <el-table-column label="购买卡项" min-width="160"><template #default="{row}">{{ row.projectName || `项目 #${row.projectId}` }}<div class="proto-subcell">V{{ row.versionNo }}</div></template></el-table-column>
          <el-table-column prop="grantedCount" label="购买次数" width="90" align="right" />
          <el-table-column label="已核销" width="85" align="right"><template #default="{row}">{{ redeemed(row.id) }}</template></el-table-column>
          <el-table-column label="已退次数" width="85" align="right"><template #default="{row}">{{ returned(row.id) }}</template></el-table-column>
          <el-table-column label="剩余可用" width="100" align="right"><template #default="{row}"><strong class="available">{{ row.availableCount }}</strong></template></el-table-column>
          <el-table-column label="有效期" min-width="150"><template #default="{row}">{{ row.validFrom || '—' }}<div class="proto-subcell">至 {{ row.validTo || '未设到期日' }}</div></template></el-table-column>
          <el-table-column label="进度" min-width="145"><template #default="{row}"><el-progress :percentage="progress(row)" :stroke-width="8" /><el-button text @click="openDetail(details[row.id])">流水详情</el-button></template></el-table-column>
          <template #empty>暂无权益；可信支付确认后才会发放</template>
        </app-table>
      </div>
    </PageCard>
    <div class="proto-banner entitlement-note"><div><strong>套餐模型设计提示</strong><p>“选 N 项”是组合规则，不等于 N 次服务。具体项目、次数、机构和有效期均以订单快照为准。</p></div><span class="proto-pill info">只读权威账本</span></div>
    <EntitlementDetailDrawer v-model="detailVisible" :detail="selected" />
  </div>
</template>
<style scoped>.available{color:#0f948b;font-size:15px}.entitlement-note{margin-top:18px}</style>
