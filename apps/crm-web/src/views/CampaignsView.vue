<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { CampaignAnalyticsReport, CampaignAnalyticsRow, ReportFact } from '../api/report'
import { getCampaignAnalytics } from '../api/report'
import type { Campaign, CampaignDetail } from '../api/campaign'
import { getCampaign, listCampaigns } from '../api/campaign'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import { useAuthStore } from '../stores/auth'
import CampaignFormDrawer from './campaign/CampaignFormDrawer.vue'
import ReportContextBar from './reporting/ReportContextBar.vue'
import ReportEvidenceDrawer from './reporting/ReportEvidenceDrawer.vue'
import {
  campaignErrorText,
  countText,
  displayDateTime,
  hasCompleteRange,
  initialMonthRange,
  moneyText,
  ratioText,
  reportErrorText,
  toReportQuery,
} from './reporting/reportUi'
import './reporting/reporting.css'

const auth = useAuthStore()
const canReadReport = computed(() => auth.hasPermission('report:read'))
const canReadCampaigns = computed(() => auth.hasPermission('campaign:read'))
const canWrite = computed(() => auth.hasPermission('campaign:write'))
const activeTab = ref<'analytics' | 'management'>('analytics')

const filters = reactive({
  range: initialMonthRange() as [string, string] | null,
  timeZone: 'Asia/Shanghai',
  sourceSystem: 'ALL',
  receiverType: 'ALL',
  campaignId: undefined as number | undefined,
  drillDownType: 'PAYMENT',
})
const analyticsQuery = reactive({ page: 1, size: 20 })
const report = ref<CampaignAnalyticsReport | null>(null)
const reportLoading = ref(false)
const reportError = ref('')
let reportSequence = 0

function analyticsParams() {
  return {
    ...toReportQuery(filters.range ?? [], filters.timeZone, filters.sourceSystem, filters.receiverType),
    campaignId: filters.campaignId,
    drillDownType: filters.drillDownType,
    drillDownLimit: 50,
    page: analyticsQuery.page,
    size: analyticsQuery.size,
  }
}

async function loadAnalytics() {
  if (!canReadReport.value) return
  if (!hasCompleteRange(filters.range ?? [])) {
    report.value = null
    reportError.value = '请选择完整且有效的统计日期范围'
    return
  }
  const sequence = ++reportSequence
  reportLoading.value = true
  reportError.value = ''
  report.value = null
  try {
    const result = await getCampaignAnalytics(analyticsParams())
    if (sequence !== reportSequence) return
    report.value = result
    analyticsQuery.page = result.page
    analyticsQuery.size = result.size
  } catch (error) {
    if (sequence === reportSequence) reportError.value = reportErrorText(error)
  } finally {
    if (sequence === reportSequence) reportLoading.value = false
  }
}

function resetAnalytics() {
  filters.range = initialMonthRange()
  filters.timeZone = 'Asia/Shanghai'
  filters.sourceSystem = 'ALL'
  filters.receiverType = 'ALL'
  filters.campaignId = undefined
  filters.drillDownType = 'PAYMENT'
  analyticsQuery.page = 1
  void loadAnalytics()
}

const campaigns = ref<Campaign[]>([])
const campaignTotal = ref(0)
const campaignLoading = ref(false)
const campaignError = ref('')
const campaignQuery = reactive({ page: 1, size: 20, keyword: '', status: '', campaignType: '' })

async function loadCampaigns() {
  if (!canReadCampaigns.value) return
  campaignLoading.value = true
  campaignError.value = ''
  try {
    const result = await listCampaigns({
      page: campaignQuery.page,
      size: campaignQuery.size,
      keyword: campaignQuery.keyword || undefined,
      status: campaignQuery.status || undefined,
      campaignType: campaignQuery.campaignType || undefined,
    })
    campaigns.value = result.items
    campaignTotal.value = result.total
    campaignQuery.page = result.page
    campaignQuery.size = result.size
  } catch (error) {
    campaigns.value = []
    campaignTotal.value = 0
    campaignError.value = campaignErrorText(error)
  } finally {
    campaignLoading.value = false
  }
}

function searchCampaigns() {
  campaignQuery.page = 1
  void loadCampaigns()
}

function resetCampaigns() {
  campaignQuery.keyword = ''
  campaignQuery.status = ''
  campaignQuery.campaignType = ''
  campaignQuery.page = 1
  void loadCampaigns()
}

const formVisible = ref(false)
const editingDetail = ref<CampaignDetail | null>(null)
const detailLoadingId = ref<number | null>(null)
function openCreate() {
  editingDetail.value = null
  formVisible.value = true
}
async function openEdit(row: Campaign) {
  if (row.status === 'ARCHIVED') return
  detailLoadingId.value = row.id
  try {
    editingDetail.value = await getCampaign(row.id)
    formVisible.value = true
  } catch (error) {
    ElMessage.error(campaignErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

function refreshAfterChange() {
  void Promise.allSettled([loadAnalytics(), loadCampaigns()])
}

const factsVisible = ref(false)
const facts = ref<ReportFact[]>([])
const factRow = ref<CampaignAnalyticsRow | null>(null)
const factLoadingId = ref<number | null>(null)
const factError = ref('')
const factTitle = computed(() => `${factRow.value?.campaignName ?? '活动'} · ${factRow.value?.campaignBatchName ?? '批次'} · 原始事实`)

async function openFacts(row: CampaignAnalyticsRow) {
  if (row.campaignId === null || row.campaignBatchId === null) return
  factRow.value = row
  factLoadingId.value = row.campaignBatchId
  factError.value = ''
  facts.value = []
  try {
    const result = await getCampaignAnalytics({
      ...analyticsParams(),
      campaignId: row.campaignId,
      campaignBatchId: row.campaignBatchId,
      page: 1,
    })
    facts.value = result.drillDown
    factsVisible.value = true
  } catch (error) {
    factError.value = reportErrorText(error)
  } finally {
    factLoadingId.value = null
  }
}

function campaignTypeText(type: string): string {
  return ({ REGULAR: '普通销售', FLASH: '快闪', PROMOTION: '大促' } as Record<string, string>)[type] ?? '未知活动类型'
}
function campaignStatusText(status: string): string {
  return ({ DRAFT: '草稿', ACTIVE: '进行中', INACTIVE: '已停用', ARCHIVED: '已归档' } as Record<string, string>)[status] ?? '未知活动状态'
}
function campaignStatusTone(status: string): string {
  return ({ DRAFT: 'warning', ACTIVE: 'success', INACTIVE: 'dark', ARCHIVED: 'dark' } as Record<string, string>)[status] ?? 'dark'
}
function touchStatusText(status: string): string {
  return status === 'SOURCE_NOT_AVAILABLE' ? '触达来源未接入' : status === 'PENDING_DEFINITION' ? 'Q13 口径待确认' : '按当前来源口径'
}

onMounted(() => {
  void loadAnalytics()
  void loadCampaigns()
})
</script>

<template>
  <div class="proto-page report-page">
    <PageHeader title="活动 / 大促分析" description="普通销售、快闪和大促按活动与批次独立统计；活动维护与经营分析共用真实主数据，不拆分线下台账">
      <template #actions>
        <router-link v-if="auth.hasPermission('order:read')" to="/orders"><el-button>查看订单</el-button></router-link>
        <el-button v-if="canReadReport" :loading="reportLoading" @click="loadAnalytics">刷新分析</el-button>
        <el-button v-if="canWrite" type="primary" data-testid="campaign-create" @click="openCreate">＋ 创建活动</el-button>
      </template>
    </PageHeader>

    <template v-if="activeTab === 'analytics'">
      <div v-if="report" class="proto-stat-grid">
        <StatCard label="活动触达" :value="countText(report.totals.touchCustomerCount, '人')" :sub="touchStatusText(report.totals.touchStatus)" icon="users" />
        <StatCard label="活动成交" :value="countText(report.totals.paidOrderCount, '单')" sub="触达成交率待 Q13 确认，不在前端补算" icon="bag" />
        <StatCard label="活动成交额" :value="moneyText(report.totals.paidFen)" :sub="`成功退款 ${moneyText(report.totals.refundFen)}`" icon="wallet" />
        <StatCard label="预约完成率" value="待确认" :sub="`${countText(report.totals.checkedInAppointmentCount)} / ${countText(report.totals.confirmedAppointmentCount)} · 汇总比率字段待确认`" icon="calendar" />
      </div>
      <div v-else-if="reportLoading" class="proto-stat-grid" aria-hidden="true"><el-skeleton v-for="index in 4" :key="index" animated :rows="2" class="proto-stat-card" /></div>
    </template>

    <div class="proto-callout report-definition">
      <div><strong>活动指标与归因均需要可追溯事实</strong><p>Q13 比率待确认；渠道归因以成交时快照为准，不在活动表单中编辑或推断商务归属。</p></div>
      <span class="proto-pill warning">仅真实事实</span>
    </div>

    <PageCard>
      <el-tabs v-model="activeTab"><el-tab-pane label="活动批次表现" name="analytics" /><el-tab-pane label="活动管理" name="management" /></el-tabs>

      <template v-if="activeTab === 'analytics'">
        <div class="proto-toolbar">
          <div class="report-filter">
            <el-date-picker v-model="filters.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" class="report-date" @change="analyticsQuery.page = 1; loadAnalytics" />
            <el-select v-model="filters.campaignId" class="campaign-filter" placeholder="全部活动" clearable filterable @change="analyticsQuery.page = 1; loadAnalytics"><el-option v-for="item in campaigns" :key="item.id" :label="`${item.campaignName}（${item.campaignCode}）`" :value="item.id" /></el-select>
            <el-select v-model="filters.sourceSystem" class="report-source" aria-label="订单来源" @change="analyticsQuery.page = 1; loadAnalytics"><el-option label="全部来源" value="ALL" /><el-option label="手工订单" value="MANUAL" /><el-option label="有赞订单" value="YOUZAN" /></el-select>
            <el-select v-model="filters.receiverType" class="report-source" aria-label="收款方" @change="analyticsQuery.page = 1; loadAnalytics"><el-option label="全部收款方" value="ALL" /><el-option label="平台收款" value="PLATFORM" /><el-option label="机构收款" value="INSTITUTION" /></el-select>
            <el-select v-model="filters.drillDownType" class="report-source" aria-label="查看明细事实类型"><el-option label="支付流水" value="PAYMENT" /><el-option label="成功退款" value="REFUND" /><el-option label="正式核销" value="REDEMPTION" /><el-option label="预约确认" value="APPOINTMENT_CONFIRMED" /><el-option label="到院事实" value="CHECKED_IN" /></el-select>
            <el-button type="primary" :loading="reportLoading" @click="analyticsQuery.page = 1; loadAnalytics">查询</el-button><el-button @click="resetAnalytics">重置</el-button>
          </div>
        </div>
        <div v-if="reportLoading" class="report-loading" role="status">正在加载活动分析，请稍候。</div>
        <el-alert v-if="reportError" type="error" :title="reportError" :closable="false" class="report-error"><el-button text type="primary" data-testid="campaign-analytics-retry" @click="loadAnalytics">重试</el-button></el-alert>
        <el-alert v-if="!canReadReport" type="warning" title="当前账号无经营报表查看权限" :closable="false" />

        <template v-if="report">
          <div class="proto-card-body"><ReportContextBar :context="report" /></div>
          <div class="proto-table-wrap">
            <app-table :data="report.items">
              <el-table-column label="活动" min-width="170"><template #default="{ row }"><strong>{{ row.campaignName || '活动名称待确认' }}</strong><div class="proto-subcell">{{ row.campaignCode || '活动编码待确认' }}</div></template></el-table-column>
              <el-table-column label="批次" min-width="145"><template #default="{ row }"><span class="proto-pill purple">{{ row.campaignBatchName || '批次名称待确认' }}</span><div class="proto-subcell">{{ row.campaignBatchCode || '批次编码待确认' }}</div></template></el-table-column>
              <el-table-column label="触达" width="95" align="right"><template #default="{ row }"><span :class="{ 'report-pending': row.touchCustomerCount === null }">{{ countText(row.touchCustomerCount) }}</span></template></el-table-column>
              <el-table-column label="成交" width="80" align="right"><template #default="{ row }">{{ countText(row.paidOrderCount) }}</template></el-table-column>
              <el-table-column label="触达成交率" width="120"><template #default="{ row }">{{ ratioText(row.conversionRates.contactToPaid) }}</template></el-table-column>
              <el-table-column label="确认预约" width="95" align="right"><template #default="{ row }">{{ countText(row.confirmedAppointmentCount) }}</template></el-table-column>
              <el-table-column label="到院预约" width="95" align="right"><template #default="{ row }">{{ countText(row.checkedInAppointmentCount) }}</template></el-table-column>
              <el-table-column label="到院率" width="105"><template #default="{ row }">{{ ratioText(row.conversionRates.appointmentToCheckedIn) }}</template></el-table-column>
              <el-table-column label="核销次数" width="95" align="right"><template #default="{ row }">{{ countText(row.redeemedCount) }}</template></el-table-column>
              <el-table-column label="成交额" width="120" align="right"><template #default="{ row }"><span class="report-cell-money">{{ moneyText(row.paidFen) }}</span></template></el-table-column>
              <el-table-column label="退款" width="115" align="right"><template #default="{ row }"><span class="report-cell-refund">{{ moneyText(row.refundFen) }}</span></template></el-table-column>
              <el-table-column label="净支付事实" width="125" align="right"><template #default="{ row }">{{ moneyText(row.netPaidFen) }}</template></el-table-column>
              <el-table-column label="事实" width="90" fixed="right"><template #default="{ row }"><el-button text :disabled="row.campaignId === null || row.campaignBatchId === null" :loading="factLoadingId === row.campaignBatchId" data-testid="campaign-report-drilldown" @click="openFacts(row)">查看明细</el-button></template></el-table-column>
              <template #empty><el-empty description="当前筛选范围没有活动批次表现数据" /></template>
            </app-table>
          </div>
          <el-alert v-if="factError" type="error" :title="factError" :closable="false" class="report-error"><el-button text type="primary" @click="factRow && openFacts(factRow)">重试查看明细</el-button></el-alert>
          <el-pagination v-model:current-page="analyticsQuery.page" v-model:page-size="analyticsQuery.size" :total="report.total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadAnalytics" @size-change="analyticsQuery.page = 1; loadAnalytics()" />
        </template>
      </template>

      <template v-else>
        <div class="proto-toolbar">
          <div class="report-filter">
            <el-input v-model="campaignQuery.keyword" class="campaign-keyword" placeholder="活动编码 / 名称" clearable @keyup.enter="searchCampaigns" />
            <el-select v-model="campaignQuery.campaignType" class="campaign-status" placeholder="全部类型" clearable><el-option label="普通销售" value="REGULAR" /><el-option label="快闪" value="FLASH" /><el-option label="大促" value="PROMOTION" /></el-select>
            <el-select v-model="campaignQuery.status" class="campaign-status" placeholder="全部状态" clearable><el-option label="草稿" value="DRAFT" /><el-option label="进行中" value="ACTIVE" /><el-option label="已停用" value="INACTIVE" /><el-option label="已归档" value="ARCHIVED" /></el-select>
            <el-button type="primary" :loading="campaignLoading" @click="searchCampaigns">查询</el-button><el-button @click="resetCampaigns">重置</el-button>
          </div>
          <span class="proto-note">编辑表单内维护批次与状态，固定编码不可覆盖</span>
        </div>
        <div v-if="campaignLoading" class="report-loading" role="status">正在加载活动列表，请稍候。</div>
        <el-alert v-if="campaignError" type="error" :title="campaignError" :closable="false" class="report-error"><el-button text type="primary" @click="loadCampaigns">重试</el-button></el-alert>
        <el-alert v-if="!canReadCampaigns" type="warning" title="当前账号无活动管理查看权限" :closable="false" />
        <div v-else class="proto-table-wrap">
          <app-table v-loading="campaignLoading" :data="campaigns" row-key="id">
            <el-table-column label="活动" min-width="190"><template #default="{ row }"><strong>{{ row.campaignName }}</strong><div class="proto-subcell">{{ row.campaignCode }}</div></template></el-table-column>
            <el-table-column label="类型" width="110"><template #default="{ row }">{{ campaignTypeText(row.campaignType) }}</template></el-table-column>
            <el-table-column label="开始时间" min-width="175"><template #default="{ row }">{{ displayDateTime(row.startsAt) }}</template></el-table-column>
            <el-table-column label="结束时间" min-width="175"><template #default="{ row }">{{ displayDateTime(row.endsAt) }}</template></el-table-column>
            <el-table-column label="状态" width="105"><template #default="{ row }"><span class="proto-pill" :class="campaignStatusTone(row.status)">{{ campaignStatusText(row.status) }}</span></template></el-table-column>
            <el-table-column prop="version" label="版本" width="80" align="right" />
            <el-table-column v-if="canWrite" label="操作" width="150" fixed="right"><template #default="{ row }"><el-button v-if="row.status !== 'ARCHIVED'" text :loading="detailLoadingId === row.id" data-testid="campaign-edit" @click="openEdit(row)">编辑与批次</el-button><span v-else class="proto-muted">归档只读</span></template></el-table-column>
            <template #empty><el-empty description="暂无活动，具备维护权限时可创建活动与批次" /></template>
          </app-table>
        </div>
        <el-pagination v-if="canReadCampaigns" v-model:current-page="campaignQuery.page" v-model:page-size="campaignQuery.size" :total="campaignTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadCampaigns" @size-change="campaignQuery.page = 1; loadCampaigns()" />
      </template>
    </PageCard>

    <CampaignFormDrawer v-model="formVisible" :detail="editingDetail" @saved="refreshAfterChange" @changed="refreshAfterChange" />
    <ReportEvidenceDrawer v-model="factsVisible" :title="factTitle" :facts="facts" description="按活动与批次重新请求当前报表端点；不提供成交归因或历史事实编辑。" />
    <footer class="proto-footer">鲸选美学 CRM · 活动与批次独立追溯，历史成交快照只读</footer>
  </div>
</template>

<style scoped>
.campaign-filter { width: 220px; }
.campaign-keyword { width: min(240px, 100%); }
.campaign-status { width: 150px; }
@media (max-width: 760px) { .campaign-filter, .campaign-keyword, .campaign-status { width: 100%; } }
</style>
