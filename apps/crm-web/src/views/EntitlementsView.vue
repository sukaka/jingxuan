<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getEntitlement, listEntitlements } from '../api/trading'
import type { Entitlement, EntitlementDetail, EntitlementListQuery } from '../api/trading'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import EntitlementDetailDrawer from './trading/EntitlementDetailDrawer.vue'
import { useReferenceNames } from './references'
import { mapWithConcurrency } from '../utils/asyncBatch'
import { addDaysInShanghai, shanghaiDateString, todayInShanghai } from '../utils/date'

const { loadCustomerNames, customerLabel } = useReferenceNames()

const entitlements = ref<Entitlement[]>([]), details = ref<Record<number, EntitlementDetail>>({})
const page = ref(1), size = ref(20), total = ref(0)
const loading = ref(false), errorMessage = ref(''), keyword = ref('')
const status = ref(''), validTo = ref(''), availableOnly = ref(false)
const detailVisible = ref(false), selected = ref<EntitlementDetail | null>(null)
let loadSequence = 0
let disposed = false

const filtered = computed(() => entitlements.value.filter((item) => !keyword.value || `${item.projectName} ${item.customerId} ${item.orderItemId}`.toLowerCase().includes(keyword.value.toLowerCase())))
const totalAvailable = computed(() => entitlements.value.reduce((sum, item) => sum + item.availableCount, 0))
const customerCount = computed(() => new Set(entitlements.value.filter((item) => item.availableCount > 0).map((item) => item.customerId)).size)
const expiringCount = computed(() => {
  const today = todayInShanghai()
  const end = addDaysInShanghai(30)
  return entitlements.value.filter((item) => {
    if (!['AVAILABLE', 'ACTIVE'].includes(item.status) || item.availableCount <= 0 || !item.validTo) return false
    const validDate = shanghaiDateString(item.validTo)
    return Boolean(validDate) && validDate >= today && validDate <= end
  }).length
})
const redeemedCount = computed(() => entitlements.value.reduce((sum, item) => sum + redeemed(item.id), 0))
function redeemed(id: number) { return details.value[id]?.ledger.filter((line) => line.txnType === 'REDEEM').reduce((n, line) => n + Math.abs(line.deltaCount), 0) || 0 }
function returned(id: number) { return details.value[id]?.ledger.filter((line) => ['REFUND_REVOKE','REVOKED'].includes(line.txnType)).reduce((n, line) => n + Math.abs(line.deltaCount), 0) || 0 }
function progress(item: Entitlement) { return item.grantedCount ? Math.round(redeemed(item.id) / item.grantedCount * 100) : 0 }

async function load(nextPage = page.value) {
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const query: EntitlementListQuery = { page: nextPage, size: size.value }
    if (status.value) query.status = status.value
    if (validTo.value) query.validTo = validTo.value
    if (availableOnly.value) query.availableOnly = true
    const result = await listEntitlements(query)
    if (disposed || sequence !== loadSequence) return
    entitlements.value = result.items
    total.value = result.total
    page.value = result.page
    size.value = result.size

    const uncached = result.items.filter((item) => !details.value[item.id] || details.value[item.id].entitlement.version !== item.version)
    if (uncached.length) {
      const detailsResult = await mapWithConcurrency(uncached, 5, (item) => getEntitlement(item.id))
      if (disposed || sequence !== loadSequence) return
      const nextMap = { ...details.value }
      detailsResult.forEach((entry, index) => {
        if (entry.status === 'fulfilled') nextMap[uncached[index].id] = entry.value
      })
      details.value = nextMap
    }
    void loadCustomerNames(result.items.map((item) => item.customerId))
  } catch (error) {
    if (!disposed && sequence === loadSequence) {
      errorMessage.value = error instanceof Error ? error.message : '权益加载失败'
    }
  } finally {
    if (!disposed && sequence === loadSequence) loading.value = false
  }
}

async function openDetail(row: Entitlement) {
  const existing = details.value[row.id]
  if (existing) {
    selected.value = existing
    detailVisible.value = true
    return
  }
  try {
    const loaded = await getEntitlement(row.id)
    if (disposed) return
    details.value = { ...details.value, [row.id]: loaded }
    selected.value = loaded
    detailVisible.value = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '权益流水加载失败'
  }
}

onBeforeUnmount(() => {
  disposed = true
  loadSequence += 1
})
onMounted(() => { void load(1) })
</script>

<template>
  <div class="proto-page">
    <PageHeader title="会员权益" description="按订单和子项目独立计算剩余次数；选 N 项不等于 N 次，每次核销必须经机构确认"><template #actions><RouterLink to="/orders?create=1" class="el-button el-button--primary">＋ 新增权益订单</RouterLink></template></PageHeader>
    <div class="proto-stat-grid"><StatCard label="全部剩余次数" :value="`${totalAvailable} 次`" sub="当前页权益余额" icon="heart" /><StatCard label="有可用权益的客户" :value="`${customerCount} 位`" sub="当前页按客户去重" icon="users" /><StatCard label="临近到期（30天）" :value="`${expiringCount} 项`" sub="未过期且可用权益" icon="calendar" /><StatCard label="已正式核销" :value="`${redeemedCount} 次`" sub="仅统计正式核销流水" icon="check" /></div>
    <PageCard>
      <div class="proto-toolbar"><el-input v-model="keyword" placeholder="搜索客户、项目、订单" clearable style="width:250px" /><el-select v-model="status" placeholder="权益状态" clearable style="width:130px" data-testid="entitlement-status"><el-option label="可用" value="AVAILABLE" /></el-select><el-date-picker v-model="validTo" type="date" value-format="YYYY-MM-DD" placeholder="到期日截至" style="width:160px" data-testid="entitlement-valid-to" /><el-checkbox v-model="availableOnly" data-testid="entitlement-available-only">余额 &gt; 0</el-checkbox><el-button data-testid="apply-entitlement-filters" @click="load(1)">查询</el-button><span class="proto-note">筛选与分页在后端执行</span></div><el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" /><div class="proto-table-wrap">
        <app-table v-loading="loading" :data="filtered">
          <el-table-column label="客户 / 订单" min-width="125"><template #default="{row}">{{ customerLabel(row.customerId) }}<div class="proto-subcell">订单行 #{{ row.orderItemId }}</div></template></el-table-column>
          <el-table-column label="购买卡项" min-width="160"><template #default="{row}">{{ row.projectName || `项目 #${row.projectId}` }}<div class="proto-subcell">V{{ row.versionNo }}</div></template></el-table-column>
          <el-table-column prop="grantedCount" label="购买次数" width="90" align="right" />
          <el-table-column label="已核销" width="85" align="right"><template #default="{row}">{{ redeemed(row.id) }}</template></el-table-column>
          <el-table-column label="已退次数" width="85" align="right"><template #default="{row}">{{ returned(row.id) }}</template></el-table-column>
          <el-table-column label="剩余可用" width="100" align="right"><template #default="{row}"><strong class="available">{{ row.availableCount }}</strong></template></el-table-column>
          <el-table-column label="有效期" min-width="150"><template #default="{row}">{{ row.validFrom || '—' }}<div class="proto-subcell">至 {{ row.validTo || '未设到期日' }}</div></template></el-table-column>
          <el-table-column label="进度" min-width="145"><template #default="{row}"><el-progress :percentage="progress(row)" :stroke-width="8" /><el-button text @click="openDetail(row)">流水详情</el-button></template></el-table-column>
          <template #empty>暂无权益；可信支付确认后才会发放</template>
        </app-table>
      </div>
      <el-pagination
        v-if="total > size"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="crm-pager"
        @current-change="load"
        @size-change="load(1)"
      />
    </PageCard>
    <div class="proto-banner entitlement-note"><div><strong>套餐模型设计提示</strong><p>“选 N 项”是组合规则，不等于 N 次服务。具体项目、次数、机构和有效期均以订单快照为准。</p></div><span class="proto-pill info">只读权威账本</span></div>
    <EntitlementDetailDrawer v-model="detailVisible" :detail="selected" />
  </div>
</template>
<style scoped>.available{color:#0f948b;font-size:15px}.entitlement-note{margin-top:18px}</style>
