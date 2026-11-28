<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getMultiStewardSummary, listCustomerOwnerOptions, listCustomers } from '../api/customer'
import type { CustomerListItem, CustomerListQuery, CustomerOwnerOption } from '../api/customer'
import { formatCustomerRegion, splitRegionPath } from '../api/region'
import RegionCascader from '../components/RegionCascader.vue'
import { useAuthStore } from '../stores/auth'
import CustomerAssignDialog from './customer/CustomerAssignDialog.vue'
import type { CustomerAssignTarget } from './customer/CustomerAssignDialog.vue'
import CustomerAvatar from './customer/CustomerAvatar.vue'
import CustomerDetailDrawer from './customer/CustomerDetailDrawer.vue'
import CustomerFormDrawer from './customer/CustomerFormDrawer.vue'
import CustomerSourceChannelManageDialog from './customer/CustomerSourceChannelManageDialog.vue'
import CustomerTagManageDialog from './customer/CustomerTagManageDialog.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { useDictionaries, levelLabel } from './customer/shared'
import { endOfTodayInShanghaiIso, shanghaiDateString, todayInShanghai } from '../utils/date'
import { customerStatusTone, customerTierTone } from '../utils/tagTone'

const route = useRoute()
const auth = useAuthStore(), dicts = useDictionaries()
const canMerge = computed(() => auth.hasPermission('customer:merge'))
const canTransfer = computed(() => auth.hasPermission('customer:transfer'))
const canManageTagCatalog = computed(() => auth.hasPermission('customer:tag:manage'))
const canManageSourceChannel = computed(() => auth.hasPermission('customer:source_channel:manage'))
const canPullYouzanCustomers = computed(() => auth.hasPermission('integration:youzan:manage') && auth.hasPermission('customer:write'))
const keywordInput = ref<{ focus?: () => void } | null>(null)
const tableRef = ref<{ clearSelection?: () => void } | null>(null)
const query = reactive({
  keyword: '',
  tierCode: '',
  needsAttention: false,
  ownerId: undefined as number | undefined,
  regionCodes: [] as string[],
  tagCodes: [] as string[],
  tagMatchMode: 'ANY' as 'ANY' | 'ALL',
  page: 1,
  size: 20,
})
const customers = ref<CustomerListItem[]>([])
const ownerOptions = ref<CustomerOwnerOption[]>([])
const selectedCustomers = ref<CustomerListItem[]>([])
const total = ref(0), loading = ref(false), errorMessage = ref('')
const summary = reactive({ s: 0, a: 0, due: 0, multiSteward: 0 })
const formVisible = ref(false), formMode = ref<'create'|'edit'>('create'), formCustomerId = ref<number|null>(null)
const detailVisible = ref(false), detailCustomerId = ref<number|null>(null), detailTab = ref('overview')
const assignVisible = ref(false), assignTargets = ref<CustomerAssignTarget[]>([])
const sourceChannelManageVisible = ref(false)
const tagManageVisible = ref(false)
const canFilterOwner = computed(() => canTransfer.value || ownerOptions.value.length > 1)
const overdue = computed(() => (value: string | null | undefined) => {
  const date = shanghaiDateString(value)
  return date ? date <= todayInShanghai() : false
})

function ownerOptionLabel(option: CustomerOwnerOption): string {
  return option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName
}

function clearSelection() {
  selectedCustomers.value = []
  tableRef.value?.clearSelection?.()
}

function onSelectionChange(rows: CustomerListItem[]) {
  selectedCustomers.value = rows
}

async function loadOwnerOptions() {
  if (!canTransfer.value) return
  try {
    ownerOptions.value = await listCustomerOwnerOptions()
  } catch {
    ownerOptions.value = []
  }
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const dueBefore = query.needsAttention ? endOfTodayInShanghaiIso() : undefined
    const params: CustomerListQuery = {
      page,
      size: query.size,
      keyword: query.keyword,
      tierCode: query.tierCode || undefined,
      followupDueBefore: dueBefore,
    }
    if (query.ownerId !== undefined) {
      params.ownerId = query.ownerId
    }
    if (query.regionCodes.length > 0) {
      const region = splitRegionPath(query.regionCodes)
      if (region.provinceCode) params.provinceCode = region.provinceCode
      if (region.regionCityCode) params.regionCityCode = region.regionCityCode
      if (region.districtCode) params.districtCode = region.districtCode
    }
    if (query.tagCodes.length > 0) {
      params.tagCodes = [...query.tagCodes]
      params.tagMatchMode = query.tagMatchMode
    }
    const result = await listCustomers(params)
    customers.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
    clearSelection()
  } catch (error) { errorMessage.value = `加载失败：${error instanceof Error ? error.message : '客户加载失败'}` }
  finally { loading.value = false }
}
async function loadSummary() {
  const dueBefore = endOfTodayInShanghaiIso()
  const [sRes, aRes, dueRes, multiRes] = await Promise.allSettled([
    listCustomers({ tierCode: 'S', page: 1, size: 1 }),
    listCustomers({ tierCode: 'A', page: 1, size: 1 }),
    listCustomers({ followupDueBefore: dueBefore, page: 1, size: 1 }),
    canMerge.value ? getMultiStewardSummary() : Promise.resolve(null),
  ])
  summary.s = sRes.status === 'fulfilled' ? (sRes.value?.total ?? 0) : 0
  summary.a = aRes.status === 'fulfilled' ? (aRes.value?.total ?? 0) : 0
  summary.due = dueRes.status === 'fulfilled' ? (dueRes.value?.total ?? 0) : 0
  summary.multiSteward = multiRes.status === 'fulfilled' ? (multiRes.value?.multiStewardCustomerCount ?? 0) : 0
}
async function onSourceChannelCatalogChanged() {
  await dicts.load('customerSources', 'customerChannels')
}
function onSearch() { void load(1) }
function reload() { void load(); void loadSummary() }
function onFormSaved() {
  void dicts.load('customerTags', 'customerSources', 'customerChannels')
  if (formMode.value === 'create') {
    query.keyword = ''
    query.tierCode = ''
    query.needsAttention = false
    query.ownerId = undefined
    query.regionCodes = []
    query.tagCodes = []
    query.tagMatchMode = 'ANY'
    query.page = 1
    void load(1)
    void loadSummary()
    return
  }
  reload()
}
function onDetailChanged() {
  void dicts.load('customerTags')
  reload()
}
function onTagCatalogChanged() {
  void dicts.load('customerTags')
}
function openCreate() { formMode.value = 'create'; formCustomerId.value = null; formVisible.value = true }
function openEdit(id: number) { formMode.value = 'edit'; formCustomerId.value = id; formVisible.value = true }
function openDetail(row: CustomerListItem, tab = 'overview') { detailCustomerId.value = row.id; detailTab.value = tab; detailVisible.value = true }
function openAssign(rows: CustomerListItem[]) {
  if (!rows.length) return
  assignTargets.value = rows.map((row) => ({
    id: row.id,
    customerNo: row.customerNo,
    displayName: row.displayName,
    nickname: row.nickname,
    ownerStaffId: row.ownerStaffId,
    ownerName: row.ownerName,
    version: row.version,
  }))
  assignVisible.value = true
}
function onAssigned() {
  clearSelection()
  reload()
}

function applyRouteFocus() {
  if (route?.query?.focus === 'search') {
    void nextTick(() => keywordInput.value?.focus?.())
  }
}

watch(() => route?.query?.focus, () => applyRouteFocus())
onMounted(() => {
  void dicts.load('cities', 'customerSources', 'customerChannels', 'customerStatuses', 'customerTiers', 'customerTags')
  void loadOwnerOptions()
  reload()
  applyRouteFocus()
})
</script>

<template>
  <div class="proto-page customers-page">
    <PageHeader title="客户中心" description="客户等级（S/A/B/C/D）+ 基础档案 + 跟进节奏；与意向/消费能力分开管理">
      <template #actions><el-button v-if="canManageTagCatalog" data-testid="customer-tag-manage-btn" @click="tagManageVisible = true">标签管理</el-button><el-button v-if="canManageSourceChannel" data-testid="customer-source-channel-manage-btn" @click="sourceChannelManageVisible = true">来源与渠道管理</el-button><RouterLink v-if="canMerge" to="/customers/merge" class="el-button" data-testid="customer-merge-link">多管家排查</RouterLink><RouterLink v-if="canPullYouzanCustomers" to="/integrations/youzan?tab=customer-pull" class="el-button" data-testid="customer-youzan-pull-link">从有赞拉取客户</RouterLink><button class="el-button" disabled title="批量导出功能暂未开放">数据导出（暂未开放）</button><el-button v-if="auth.hasPermission('customer:write')" type="primary" @click="openCreate">＋ 新建客户</el-button></template>
    </PageHeader>
    <div class="proto-stat-grid"><StatCard label="S 级客户" :value="`${summary.s} 位`" sub="高消费 / 重点维护" icon="users" /><StatCard label="A 级客户" :value="`${summary.a} 位`" sub="较高消费 / 持续维护" icon="heart" /><StatCard label="今天需跟进" :value="`${summary.due} 条`" sub="按下次跟进时间提醒" icon="calendar" /><StatCard v-if="canMerge" label="多管家客户" :value="`${summary.multiSteward} 位`" sub="同一顾客已加多位管家 · 无需合并" icon="users" /></div>
    <PageCard>
      <div class="proto-toolbar">
        <div class="proto-toolbar-left">
          <el-input ref="keywordInput" v-model="query.keyword" placeholder="姓名 / 手机 / 微信昵称 / 标签" clearable style="width:290px" data-testid="customer-keyword" @keyup.enter="load(1)" />
          <el-select :model-value="query.needsAttention ? 'NEED' : query.tierCode" placeholder="全部客户等级" clearable style="width:145px" @change="(value:string) => { query.needsAttention = value === 'NEED'; query.tierCode = value && value !== 'NEED' ? value : ''; load(1) }"><el-option label="全部客户等级" value="" /><el-option v-for="tier in ['S','A','B','C','D']" :key="tier" :label="`${tier}级客户`" :value="tier" /><el-option label="需要处理" value="NEED" /></el-select>
          <el-select
            v-if="canFilterOwner"
            v-model="query.ownerId"
            placeholder="全部所属管家"
            clearable
            filterable
            style="width:175px"
            data-testid="customer-owner-filter"
            @change="load(1)"
          >
            <el-option v-for="option in ownerOptions" :key="option.id" :label="ownerOptionLabel(option)" :value="option.id" />
          </el-select>
          <RegionCascader
            v-model="query.regionCodes"
            placeholder="按省 / 市 / 区筛选"
            style="width:220px"
            data-testid="customer-region-filter"
            @change="onSearch"
          />
          <el-select
            v-model="query.tagCodes"
            multiple
            collapse-tags
            collapse-tags-tooltip
            clearable
            filterable
            placeholder="按标签筛选"
            style="width:210px"
            data-testid="customer-tag-filter"
            @change="onSearch"
          >
            <el-option v-for="option in dicts.options('customerTags')" :key="option.code" :label="option.label" :value="option.code" />
          </el-select>
          <el-select
            v-if="query.tagCodes.length > 1"
            v-model="query.tagMatchMode"
            style="width:110px"
            data-testid="customer-tag-match-mode"
            @change="onSearch"
          >
            <el-option label="满足任一" value="ANY" />
            <el-option label="同时满足" value="ALL" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="load(1)">查询</el-button>
        </div>
        <span class="proto-note">数据导出按角色权限控制，管家默认不可批量导出</span>
      </div>
      <div v-if="canTransfer && selectedCustomers.length > 0" class="batch-assign-bar" data-testid="batch-assign-bar">
        <span>已选择 <strong>{{ selectedCustomers.length }}</strong> 位客户</span>
        <div class="batch-assign-actions">
          <el-button type="primary" size="small" data-testid="batch-assign-btn" @click="openAssign(selectedCustomers)">批量分配管家</el-button>
          <el-button size="small" @click="clearSelection">清空选择</el-button>
        </div>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
      <div class="proto-table-wrap">
        <app-table ref="tableRef" v-loading="loading" :data="customers" row-key="id" @selection-change="onSelectionChange">
          <el-table-column v-if="canTransfer" type="selection" width="48" fixed="left" />
          <el-table-column label="客户" min-width="160" fixed="left"><template #default="{row}"><div class="proto-namecell"><CustomerAvatar :name="row.displayName ?? row.nickname" :size="34" /><div><strong>{{ row.displayName ?? row.nickname }}</strong><small>{{ row.nickname }} · {{ row.customerNo }}</small></div></div></template></el-table-column>
          <el-table-column label="客户等级" width="90"><template #default="{row}"><span class="proto-pill" :class="customerTierTone(row.tierCode)">{{ dicts.label('customerTiers', row.tierCode) }}</span></template></el-table-column>
          <el-table-column label="意向 / 消费能力" min-width="155"><template #default="{row}"><span class="proto-pill info">意向 {{ levelLabel(row.intentLevel) }}</span> <span class="proto-pill dark">消费 {{ levelLabel(row.spendingLevel) }}</span></template></el-table-column>
          <el-table-column label="标签" min-width="150"><template #default="{row}"><span v-for="tag in (row.tagCodes || []).slice(0,2)" :key="tag" class="proto-pill success customer-tag">{{ dicts.tagLabel(tag) }}</span><span v-if="(row.tagCodes?.length ?? 0) > 2" class="proto-pill info customer-tag">+{{ row.tagCodes.length - 2 }}</span><span v-if="!row.tagCodes?.length">—</span></template></el-table-column>
          <el-table-column label="生日 / 积分" min-width="120"><template #default="{row}">{{ row.birthday || '未维护' }}<div class="proto-subcell">{{ row.pointsCached.toLocaleString() }} 积分</div></template></el-table-column>
          <el-table-column label="来源渠道" min-width="115"><template #default="{row}">{{ dicts.label('customerSources',row.sourceCode) }}<div class="proto-subcell">{{ dicts.label('customerChannels',row.channelCode) }}</div></template></el-table-column>
          <el-table-column label="地区" min-width="165"><template #default="{row}"><div>{{ formatCustomerRegion(row, row.cityCode ? dicts.label('cities', row.cityCode) : null) }}</div><div v-if="row.detailAddress" class="proto-subcell region-address-sub" :title="row.detailAddress">{{ row.detailAddress }}</div></template></el-table-column>
          <el-table-column label="所属管家" min-width="115"><template #default="{row}"><div>{{ row.ownerName || `#${row.ownerStaffId}` }}</div><button v-if="canMerge && row.addedStewardCount && row.addedStewardCount > 1" type="button" class="multi-steward-badge" :data-testid="`customer-multi-steward-${row.id}`" :title="row.addedStewardNames?.length ? `已加管家：${row.addedStewardNames.join('、')}` : ''" @click="openDetail(row, 'overview')">已加 {{ row.addedStewardCount }} 位管家</button></template></el-table-column>
          <el-table-column label="最近跟进" min-width="115"><template #default="{row}">{{ shanghaiDateString(row.lastFollowupAt) || '—' }}</template></el-table-column>
          <el-table-column label="下一次跟进" min-width="135"><template #default="{row}">{{ shanghaiDateString(row.nextFollowupAt) || '待设置' }} <span v-if="overdue(row.nextFollowupAt)" class="proto-pill danger">已到期</span></template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{row}"><span class="proto-pill" :class="customerStatusTone(row.customerStatus)">{{ dicts.label('customerStatuses',row.customerStatus) }}</span></template></el-table-column>
          <el-table-column label="操作" :width="canTransfer ? 265 : 205" fixed="right"><template #default="{row}"><el-button text @click="openDetail(row)">客户详情</el-button><el-button v-if="auth.hasPermission('customer:write')" text @click="openEdit(row.id)">运营信息</el-button><el-button v-if="canTransfer" text type="primary" data-testid="row-assign-btn" @click="openAssign([row])">分配管家</el-button><el-button v-if="canTransfer" text @click="openDetail(row,'profile')">转交</el-button></template></el-table-column>
          <template #empty>当前数据范围内暂无客户</template>
        </app-table>
      </div>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="load(1)" />
    </PageCard>
    <footer class="proto-footer">客户编号独立于手机号和昵称；客户等级与消费能力分开管理</footer>
    <CustomerFormDrawer v-model="formVisible" :mode="formMode" :customer-id="formCustomerId" @saved="onFormSaved" />
    <CustomerDetailDrawer v-model="detailVisible" :customer-id="detailCustomerId" :initial-tab="detailTab" @edit="openEdit" @changed="onDetailChanged" />
    <CustomerAssignDialog v-model="assignVisible" :customers="assignTargets" @assigned="onAssigned" />
    <CustomerTagManageDialog v-if="canManageTagCatalog" v-model="tagManageVisible" @changed="onTagCatalogChanged" />
    <CustomerSourceChannelManageDialog v-if="canManageSourceChannel" v-model="sourceChannelManageVisible" @changed="onSourceChannelCatalogChanged" />
  </div>
</template>
<style scoped>
.customer-tag{margin-right:4px;margin-bottom:3px}
.customers-page :deep(.el-table__body-wrapper){overflow-x:auto}
.batch-assign-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 12px;padding:10px 16px;border-radius:10px;background:#eef7f6;border:1px solid #cde8e4;font-size:13px;color:#1d4e4a}
.batch-assign-actions{display:flex;align-items:center;gap:8px}
.multi-steward-badge{display:inline-flex;align-items:center;margin-top:4px;padding:2px 7px;border:1px solid #e6c68b;border-radius:999px;background:#fff8eb;color:#8a5a12;font-size:11px;font-weight:600;cursor:pointer}
.multi-steward-badge:hover{background:#feefd4}
.region-address-sub{max-width:190px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
