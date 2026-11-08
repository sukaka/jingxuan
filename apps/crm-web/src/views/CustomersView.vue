<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { listCustomers, listMergeCandidates } from '../api/customer'
import type { CustomerListItem } from '../api/customer'
import { useAuthStore } from '../stores/auth'
import CustomerAvatar from './customer/CustomerAvatar.vue'
import CustomerDetailDrawer from './customer/CustomerDetailDrawer.vue'
import CustomerFormDrawer from './customer/CustomerFormDrawer.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { useDictionaries, levelLabel, customerTagLabel } from './customer/shared'
import { customerStatusTone, customerTierTone } from '../utils/tagTone'

const auth = useAuthStore(), dicts = useDictionaries()
const query = reactive({ keyword: '', tierCode: '', needsAttention: false, page: 1, size: 20 })
const customers = ref<CustomerListItem[]>([])
const total = ref(0), loading = ref(false), errorMessage = ref('')
const summary = reactive({ s: 0, a: 0, due: 0, merge: 0 })
const today = new Date().toISOString()
const formVisible = ref(false), formMode = ref<'create'|'edit'>('create'), formCustomerId = ref<number|null>(null)
const detailVisible = ref(false), detailCustomerId = ref<number|null>(null), detailTab = ref('overview')
const overdue = computed(() => (value: string | null | undefined) => value ? value.slice(0, 10) <= today.slice(0, 10) : false)

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCustomers({ page, size: query.size, keyword: query.keyword,
      tierCode: query.tierCode || undefined, followupDueBefore: query.needsAttention ? today : undefined })
    customers.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) { errorMessage.value = `加载失败：${error instanceof Error ? error.message : '客户加载失败'}` }
  finally { loading.value = false }
}
async function loadSummary() {
  try {
    const [s, a, due, merge] = await Promise.all([
      listCustomers({ tierCode: 'S', page: 1, size: 1 }), listCustomers({ tierCode: 'A', page: 1, size: 1 }),
      listCustomers({ followupDueBefore: today, page: 1, size: 1 }), listMergeCandidates({ status: 'SUGGESTED', page: 1, size: 1 }),
    ])
    Object.assign(summary, { s: s?.total ?? 0, a: a?.total ?? 0, due: due?.total ?? 0, merge: merge?.total ?? 0 })
  } catch {
    Object.assign(summary, { s: 0, a: 0, due: 0, merge: 0 })
  }
}
function reload() { void load(); void loadSummary() }
function openCreate() { formMode.value = 'create'; formCustomerId.value = null; formVisible.value = true }
function openEdit(id: number) { formMode.value = 'edit'; formCustomerId.value = id; formVisible.value = true }
function openDetail(row: CustomerListItem, tab = 'overview') { detailCustomerId.value = row.id; detailTab.value = tab; detailVisible.value = true }
onMounted(() => { void dicts.load('customerSources','customerChannels','customerStatuses','customerTiers'); reload() })
</script>

<template>
  <div class="proto-page customers-page">
    <PageHeader title="客户中心" description="客户分层标签 + 基础档案 + 跟进节奏；S/A/B/C 与意向/消费能力分开管理">
      <template #actions><RouterLink to="/customers/merge" class="el-button">查重合并</RouterLink><button class="el-button" disabled title="后续阶段开放">数据导出 · 后续</button><el-button v-if="auth.hasPermission('customer:write')" type="primary" @click="openCreate">＋ 新建客户</el-button></template>
    </PageHeader>
    <div class="proto-stat-grid"><StatCard label="S 级客户" :value="`${summary.s} 位`" sub="重点维护 / 高价值" icon="users" /><StatCard label="A 级客户" :value="`${summary.a} 位`" sub="高意向或高复购" icon="heart" /><StatCard label="今天需跟进" :value="`${summary.due} 条`" sub="按下次跟进时间提醒" icon="calendar" /><StatCard label="查重候选" :value="`${summary.merge} 组`" sub="合并前必须人工确认" icon="users" /></div>
    <PageCard>
      <div class="proto-toolbar"><div class="proto-toolbar-left"><el-input v-model="query.keyword" placeholder="姓名 / 手机 / 微信昵称 / 标签" clearable style="width:290px" @keyup.enter="load(1)" /><el-select :model-value="query.needsAttention ? 'NEED' : query.tierCode" placeholder="全部分层" clearable style="width:145px" @change="(value:string) => { query.needsAttention = value === 'NEED'; query.tierCode = value && value !== 'NEED' ? value : ''; load(1) }"><el-option label="全部分层" value="" /><el-option v-for="tier in ['S','A','B','C']" :key="tier" :label="`${tier}级客户`" :value="tier" /><el-option label="需要处理" value="NEED" /></el-select><el-button type="primary" :loading="loading" @click="load(1)">查询</el-button></div><span class="proto-note">导出权限正式上线后按角色控制，管家默认禁止批量导出</span></div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
      <div class="proto-table-wrap">
        <app-table v-loading="loading" :data="customers">
          <el-table-column label="客户" min-width="160" fixed="left"><template #default="{row}"><div class="proto-namecell"><CustomerAvatar :name="row.displayName ?? row.nickname" :size="34" /><div><strong>{{ row.displayName ?? row.nickname }}</strong><small>{{ row.nickname }} · {{ row.customerNo }}</small></div></div></template></el-table-column>
          <el-table-column label="分层" width="70"><template #default="{row}"><span class="proto-pill" :class="customerTierTone(row.tierCode)">{{ row.tierCode || '—' }}</span></template></el-table-column>
          <el-table-column label="意向 / 消费能力" min-width="155"><template #default="{row}"><span class="proto-pill info">意向 {{ levelLabel(row.intentLevel) }}</span> <span class="proto-pill dark">消费 {{ levelLabel(row.spendingLevel) }}</span></template></el-table-column>
          <el-table-column label="标签" min-width="150"><template #default="{row}"><span v-for="tag in (row.tagCodes || []).slice(0,2)" :key="tag" class="proto-pill success customer-tag">{{ customerTagLabel(tag) }}</span><span v-if="!row.tagCodes?.length">—</span></template></el-table-column>
          <el-table-column label="生日 / 积分" min-width="120"><template #default="{row}">{{ row.birthday || '未维护' }}<div class="proto-subcell">{{ row.pointsCached.toLocaleString() }} 积分</div></template></el-table-column>
          <el-table-column label="来源渠道" min-width="115"><template #default="{row}">{{ dicts.label('customerSources',row.sourceCode) }}<div class="proto-subcell">{{ dicts.label('customerChannels',row.channelCode) }}</div></template></el-table-column>
          <el-table-column label="所属管家" min-width="90"><template #default="{row}">{{ row.ownerName || `#${row.ownerStaffId}` }}</template></el-table-column>
          <el-table-column label="最近跟进" min-width="115"><template #default="{row}">{{ row.lastFollowupAt?.slice(0,10) || '—' }}</template></el-table-column>
          <el-table-column label="下一次跟进" min-width="135"><template #default="{row}">{{ row.nextFollowupAt?.slice(0,10) || '待设置' }} <span v-if="overdue(row.nextFollowupAt)" class="proto-pill danger">已到期</span></template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{row}"><span class="proto-pill" :class="customerStatusTone(row.customerStatus)">{{ dicts.label('customerStatuses',row.customerStatus) }}</span></template></el-table-column>
          <el-table-column label="操作" width="205" fixed="right"><template #default="{row}"><el-button text @click="openDetail(row)">客户详情</el-button><el-button v-if="auth.hasPermission('customer:write')" text @click="openEdit(row.id)">运营信息</el-button><el-button v-if="auth.hasPermission('customer:transfer')" text @click="openDetail(row,'profile')">转交</el-button></template></el-table-column>
          <template #empty>当前数据范围内暂无客户</template>
        </app-table>
      </div>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="load(1)" />
    </PageCard>
    <footer class="proto-footer">客户唯一 ID 不等于手机号或昵称；分层与消费能力分开管理</footer>
    <CustomerFormDrawer v-model="formVisible" :mode="formMode" :customer-id="formCustomerId" @saved="reload" />
    <CustomerDetailDrawer v-model="detailVisible" :customer-id="detailCustomerId" :initial-tab="detailTab" @edit="openEdit" @changed="reload" />
  </div>
</template>
<style scoped>.customer-tag{margin-right:4px;margin-bottom:3px}.customers-page :deep(.el-table__body-wrapper){overflow-x:auto}</style>
