<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { OperationsDashboard, OperationsDuty, OperationsInstitutionRow } from '../../api/report'
import { getOperationsDashboard } from '../../api/report'
import RollingNumber from '../../components/RollingNumber.vue'
import ShellIcon from '../../components/shell/ShellIcon.vue'
import DashboardChart from './DashboardChart.vue'
import { campaignOption, fulfillmentOption, institutionOption, trendOption } from './dashboardOptions'
import {
  countText,
  displayDateTime,
  hasCompleteRange,
  moneyText,
  reportErrorText,
  reportRangeForPeriod,
  toReportQuery,
} from '../reporting/reportUi'
import type { ReportPeriod } from '../reporting/reportUi'
import './dashboard.css'
import './dashboard-duty.css'

interface DutyRow {
  id: 'appointments' | 'redemptions' | 'followups' | 'refunds'
  title: string
  count: number | null | undefined
  meta: string
  tag: string
  tone: 'amber' | 'blue' | 'jade' | 'coral'
  icon: 'calendar' | 'check' | 'users' | 'clipboard'
}

const router = useRouter()
const filters = reactive({
  range: reportRangeForPeriod('day', 'Asia/Shanghai') as [string, string] | null,
  timeZone: 'Asia/Shanghai',
  sourceSystem: 'ALL',
  receiverType: 'ALL',
  institutionId: undefined as number | undefined,
})
const activePeriod = ref<ReportPeriod | null>('day')
const report = ref<OperationsDashboard | null>(null)
const institutionOptions = ref<OperationsInstitutionRow[]>([])
const loading = ref(false)
const refreshing = ref(false)
const errorMessage = ref('')
const fullscreenMessage = ref('')
const lastRefreshedAt = ref<Date | null>(null)
const screenElement = ref<HTMLElement | null>(null)
const isFullscreen = ref(false)
const autoRefresh = ref(true)
const showAnalysis = ref(false)
const dutyFilter = ref<DutyRow['id'] | null>(null)
let loadSequence = 0
let refreshTimer: number | undefined

const metrics = computed(() => report.value?.metrics)
const duty = computed<OperationsDuty | undefined>(() => report.value?.duty)
const pageTitle = computed(() => activePeriod.value === 'day' ? '今日运营值班'
  : activePeriod.value === 'week' ? '本周经营概览' : activePeriod.value === 'month' ? '本月经营概览' : '自定义区间经营概览')
const periodLabel = computed(() => activePeriod.value === 'day' ? '今日' : activePeriod.value === 'week' ? '本周' : activePeriod.value === 'month' ? '本月' : '当前区间')
const heroLabel = computed(() => `${periodLabel.value}净支付`)
const dateText = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(new Date())
const weekdayText = new Intl.DateTimeFormat('zh-CN', { weekday: 'long', year: 'numeric' }).format(new Date())
const sourceWatermark = computed(() => displayDateTime(report.value?.sourceWatermark))
const refreshedAtText = computed(() => lastRefreshedAt.value ? displayDateTime(lastRefreshedAt.value.toISOString()) : '待确认')
const hasFulfillmentFacts = computed(() => {
  const current = metrics.value
  return Boolean(current && [current.confirmedAppointmentCount, current.checkedInCustomerCount,
    current.serviceCompletedCount, current.redeemedCount].some((value) => value > 0))
})
const validDailyItems = computed(() => (report.value?.dailyItems ?? []).filter((item) =>
  item.statDate && Number.isFinite(item.paidFen) && Number.isFinite(item.refundFen),
))
const trendChart = computed(() => trendOption(validDailyItems.value))
const fulfillmentChart = computed(() => metrics.value ? fulfillmentOption(metrics.value) : {})
const institutionChart = computed(() => institutionOption(report.value?.institutionRows ?? []))
const campaignChart = computed(() => campaignOption(report.value?.campaignRows ?? []))
const metricItems = computed(() => [
  {
    key: 'customers', label: '新增客户', value: duty.value?.newCustomerCount ?? null, unit: '位', tone: 'coral', icon: 'users',
    note: duty.value?.newCustomerCount === null ? '当前账号无客户查看权限'
      : filters.institutionId !== undefined ? '按客户档案归属，不按机构去重' : '按档案创建时间统计',
  },
  { key: 'orders', label: '支付订单', value: metrics.value?.paidOrderCount ?? null, unit: '笔', tone: 'jade', icon: 'clipboard', note: '仅统计已确认支付事实' },
  { key: 'arrivals', label: '到院客户', value: metrics.value?.checkedInCustomerCount ?? null, unit: '人', tone: 'blue', icon: 'calendar', note: '按实际到院客户去重' },
  { key: 'redemptions', label: '正式核销', value: metrics.value?.redeemedCount ?? null, unit: '次', tone: 'amber', icon: 'check', note: '机构授权操作员已确认' },
])
const dutyRows = computed<DutyRow[]>(() => [
  {
    id: 'appointments', title: '个预约等待机构确认', count: duty.value?.pendingAppointmentCount,
    meta: duty.value?.pendingAppointmentCount === null ? '当前账号无预约查看权限' : '等待机构确认接诊', tag: '需催办', tone: 'amber', icon: 'calendar',
  },
  {
    id: 'redemptions', title: '项服务完成后待核销', count: duty.value?.awaitingRedemptionCount,
    meta: duty.value?.awaitingRedemptionCount === null ? '当前账号无预约查看权限' : '由机构确认服务事实后扣减次数', tag: '待核销', tone: 'blue', icon: 'check',
  },
  {
    id: 'followups', title: '位客户今天需要回访', count: duty.value?.dueFollowupCount,
    meta: duty.value?.dueFollowupCount === null ? '当前账号无任务查看权限' : '包含已到期和今日到期的回访任务', tag: '今日回访', tone: 'jade', icon: 'users',
  },
  {
    id: 'refunds', title: '笔退款等待财务复核', count: duty.value?.pendingRefundCount,
    meta: duty.value?.pendingRefundCount === null ? '当前账号无退款查看权限' : '审批金额与退款凭证需人工核对', tag: '需审批', tone: 'coral', icon: 'clipboard',
  },
])
const visibleDutyRows = computed(() => dutyFilter.value
  ? dutyRows.value.filter((row) => row.id === dutyFilter.value) : dutyRows.value)
const rankedInstitutions = computed(() => [...(report.value?.institutionRows ?? [])]
  .filter((row): row is OperationsInstitutionRow & { institutionId: number } => row.institutionId !== null)
  .sort((left, right) => right.redeemedCount - left.redeemedCount
    || (left.institutionId ?? 0) - (right.institutionId ?? 0)).slice(0, 5))
const maxRedeemed = computed(() => Math.max(1, ...rankedInstitutions.value.map((row) => row.redeemedCount)))

function query() {
  return {
    ...toReportQuery(filters.range ?? [], filters.timeZone, filters.sourceSystem, filters.receiverType),
    institutionId: filters.institutionId,
  }
}

async function load(preserveSuccessful = false) {
  if (!hasCompleteRange(filters.range ?? [])) {
    report.value = null
    errorMessage.value = '请选择完整且有效的统计日期范围'
    return
  }
  const preserve = preserveSuccessful && report.value !== null
  const sequence = ++loadSequence
  loading.value = !preserve
  refreshing.value = preserve
  errorMessage.value = ''
  if (!preserve) report.value = null
  try {
    const result = await getOperationsDashboard(query())
    if (sequence !== loadSequence) return
    report.value = result
    lastRefreshedAt.value = new Date()
    if (filters.institutionId === undefined) {
      institutionOptions.value = result.institutionRows.filter((row) => row.institutionId !== null)
    }
  } catch (error) {
    if (sequence !== loadSequence) return
    const detail = reportErrorText(error)
    errorMessage.value = report.value ? `本次刷新失败，当前展示上次成功数据。${detail}` : detail
  } finally {
    if (sequence === loadSequence) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function setPeriod(period: ReportPeriod) {
  activePeriod.value = period
  filters.range = reportRangeForPeriod(period, filters.timeZone)
  void load()
}

function changeDateRange() {
  activePeriod.value = null
  void load()
}

function setInstitution(id: number | undefined) {
  filters.institutionId = id
  void load()
}

function reset() {
  activePeriod.value = 'day'
  filters.range = reportRangeForPeriod('day', filters.timeZone)
  filters.sourceSystem = 'ALL'
  filters.receiverType = 'ALL'
  filters.institutionId = undefined
  institutionOptions.value = []
  dutyFilter.value = null
  void load()
}

function openDuty(row: DutyRow) {
  if (row.count === null || row.count === undefined) return
  if (dutyFilter.value !== row.id) {
    dutyFilter.value = row.id
    return
  }
  dutyFilter.value = null
  if (row.id === 'followups') {
    void router.push('/tasks')
    return
  }
  const status = { appointments: 'PENDING_INSTITUTION', redemptions: 'SERVICE_DONE', refunds: 'REQUESTED' }[row.id]
  const path = row.id === 'refunds' ? '/refunds' : '/appointments'
  void router.push({ path, query: { status, institutionId: filters.institutionId } })
}

function syncFullscreenState() {
  isFullscreen.value = document.fullscreenElement === screenElement.value
}

async function toggleFullscreen() {
  fullscreenMessage.value = ''
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else if (screenElement.value) await screenElement.value.requestFullscreen()
  } catch {
    fullscreenMessage.value = '浏览器未允许全屏，可继续在当前页面查看全部数据'
  }
}

function refreshWhenVisible() {
  if (!document.hidden && autoRefresh.value) void load(true)
}

onMounted(() => {
  void load()
  refreshTimer = window.setInterval(refreshWhenVisible, 30_000)
  document.addEventListener('fullscreenchange', syncFullscreenState)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  loadSequence += 1
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <main id="screen" ref="screenElement" class="dashboard-screen dashboard-screen--duty" data-testid="operations-dashboard">
    <header class="duty-header">
      <div class="duty-identity">
        <span class="duty-brand" aria-hidden="true"><svg viewBox="0 0 40 40" fill="none"><path d="M7 13c6-5 11 4 16 4 4 0 7-3 10-7-1 8-3 12-8 14-7 3-14 0-18-11Z" stroke="currentColor" stroke-width="1.6" /><path d="M8 27c5-4 9 4 14 4s8-4 11-7M11 32c4-2 6 2 10 2 3 0 5-2 8-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" /></svg></span>
        <div><span class="duty-brand-name">鲸选美学 · 运营中心</span><h1>{{ pageTitle }}</h1></div>
      </div>
      <div class="duty-header-side">
        <div class="duty-date"><strong>{{ dateText }}</strong><span>{{ weekdayText }}</span></div>
        <el-button data-testid="dashboard-back" @click="router.push('/operations')">返回运营中心</el-button>
        <el-button type="primary" data-testid="dashboard-fullscreen" @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏查看' }}</el-button>
      </div>
    </header>

    <section class="duty-controls" aria-label="大屏筛选">
      <el-button-group class="duty-period" aria-label="统计周期">
        <el-button :type="activePeriod === 'day' ? 'primary' : 'default'" data-testid="dashboard-period-day" @click="setPeriod('day')">今日</el-button>
        <el-button :type="activePeriod === 'week' ? 'primary' : 'default'" data-testid="dashboard-period-week" @click="setPeriod('week')">本周</el-button>
        <el-button :type="activePeriod === 'month' ? 'primary' : 'default'" data-testid="dashboard-period-month" @click="setPeriod('month')">本月</el-button>
      </el-button-group>
      <el-date-picker v-model="filters.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" class="duty-date-picker" @change="changeDateRange" />
      <el-select v-model="filters.receiverType" class="duty-select" aria-label="收款方" @change="load()">
        <el-option label="全部收款方" value="ALL" /><el-option label="平台收款" value="PLATFORM" /><el-option label="机构收款" value="INSTITUTION" />
      </el-select>
      <el-select v-model="filters.sourceSystem" class="duty-select" aria-label="订单来源" @change="load()">
        <el-option label="全部来源" value="ALL" /><el-option label="手工订单" value="MANUAL" /><el-option label="有赞订单" value="YOUZAN" />
      </el-select>
      <el-select :model-value="filters.institutionId" class="duty-institution" clearable filterable aria-label="授权机构" placeholder="全部授权机构" @change="setInstitution">
        <el-option v-for="row in institutionOptions" :key="row.institutionId" :label="row.institutionName || `机构 #${row.institutionId}`" :value="row.institutionId" />
      </el-select>
      <el-button class="duty-reset" data-testid="dashboard-reset" @click="reset">重置</el-button>
      <div class="duty-freshness"><span class="duty-live-dot" :class="{ 'is-paused': !autoRefresh }" /><span>{{ autoRefresh ? '每 30 秒更新' : '自动刷新已暂停' }}</span><span>业务水位 {{ sourceWatermark }}</span><span>最近刷新 {{ refreshedAtText }}</span></div>
      <el-button :loading="refreshing" data-testid="dashboard-refresh" @click="load(true)">刷新数据</el-button>
      <el-button :aria-pressed="autoRefresh" class="duty-auto" data-testid="dashboard-auto-refresh" @click="autoRefresh = !autoRefresh">{{ autoRefresh ? '自动刷新' : '已暂停' }}</el-button>
    </section>

    <div class="dashboard-messages" aria-live="polite">
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false"><el-button text data-testid="dashboard-retry" @click="load()">重新加载</el-button></el-alert>
      <el-alert v-if="fullscreenMessage" type="warning" :title="fullscreenMessage" @close="fullscreenMessage = ''" />
    </div>

    <section class="duty-overview" aria-label="核心经营指标">
      <article class="duty-hero">
        <div class="duty-hero-top"><span>{{ heroLabel }}</span><span>经营事实</span></div>
        <RollingNumber class="duty-hero-number" :value="metrics?.netPaidFen" :formatter="moneyText" :label="heroLabel" />
        <div class="duty-hero-bottom"><span>支付金额<strong>{{ moneyText(metrics?.paidFen) }}</strong></span><i /><span>成功退款<strong>{{ moneyText(metrics?.refundFen) }}</strong></span><small>净支付不等于净利润</small></div>
      </article>
      <div class="duty-stat-grid">
        <article v-for="item in metricItems" :key="item.key" class="duty-stat" :data-tone="item.tone">
          <div class="duty-stat-label">{{ item.label }}<ShellIcon :name="item.icon" :size="16" /></div>
          <div class="duty-stat-main"><RollingNumber class="duty-stat-number" :value="item.value" :label="item.label" /><span>{{ item.unit }}</span></div>
          <p>{{ item.note }}</p>
        </article>
      </div>
    </section>

    <section v-if="loading && !report" class="duty-loading-metrics" aria-label="正在加载核心指标"><el-skeleton v-for="index in 4" :key="index" animated :rows="2" /></section>
    <div v-if="loading" class="dashboard-loading" role="status">正在读取授权范围内的真实经营事实…</div>

    <div class="duty-main">
      <div class="duty-analysis">
        <section id="trend" class="duty-panel duty-trend-panel" aria-labelledby="trend-title">
          <header class="duty-panel-header"><div><h2 id="trend-title">交易与退款走势</h2><p>按实际发生日汇总 · 只展示有业务事实的日期</p></div><span class="duty-panel-tag">金额单位：元</span></header>
          <div v-if="validDailyItems.length" class="duty-chart-wrap"><DashboardChart :option="trendChart" label="每日支付金额、成功退款金额和支付订单数趋势" /></div>
          <div v-else class="duty-trend-empty">
            <span class="duty-empty-kicker">当前区间暂无交易</span><h3>先看需要处理的事项</h3>
            <p>没有支付或退款记录，趋势图不会补造零值日期。</p>
            <div class="duty-empty-actions"><button type="button" data-testid="dashboard-empty-week" @click="setPeriod('week')">查看本周数据</button><RouterLink to="/tasks">处理待办</RouterLink></div>
          </div>
          <footer class="duty-panel-footer"><span>支付与退款按各自实际发生时间归集</span><button type="button" @click="showAnalysis = !showAnalysis">{{ showAnalysis ? '收起经营分析' : '更多经营分析' }}<ShellIcon name="arrow-right" :size="13" /></button></footer>
        </section>

        <section id="institutions" class="duty-panel duty-institutions" aria-labelledby="institution-title">
          <header class="duty-panel-header"><div><h2 id="institution-title">机构履约排行</h2><p>按正式核销次数排序 · 点击机构可筛选</p></div><button type="button" class="duty-text-button" @click="setInstitution(undefined)">查看全部机构<ShellIcon name="arrow-right" :size="13" /></button></header>
          <div v-if="rankedInstitutions.length" class="duty-ranking">
            <button v-for="(row, index) in rankedInstitutions" :key="row.institutionId" type="button" class="duty-rank-row" :aria-pressed="filters.institutionId === row.institutionId" :aria-label="`${row.institutionName || `机构 #${row.institutionId}`}，正式核销 ${row.redeemedCount} 次，点击筛选`" @click="setInstitution(filters.institutionId === row.institutionId ? undefined : row.institutionId ?? undefined)">
              <span class="duty-rank-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="duty-rank-name">{{ row.institutionName || `机构 #${row.institutionId}` }}</span>
              <span class="duty-rank-track"><i :style="{ transform: `scaleX(${row.redeemedCount / maxRedeemed})` }" /></span>
              <span class="duty-rank-value"><RollingNumber :value="row.redeemedCount" :label="`${row.institutionName || '机构'}正式核销`" /><small>次</small></span>
            </button>
          </div>
          <div v-else class="duty-rank-empty">当前范围没有可展示的机构履约数据。</div>
        </section>
      </div>

      <aside class="duty-panel duty-queue" aria-labelledby="duty-queue-title">
        <div class="duty-queue-heading"><div><h2 id="duty-queue-title">现在需要处理</h2><p>按实际待办与业务状态统计</p></div><div class="duty-queue-total"><RollingNumber :value="duty?.pendingTaskCount" label="待办任务" /><small>项</small></div></div>
        <div class="duty-task-list">
          <button v-for="row in visibleDutyRows" :key="row.id" type="button" class="duty-task" :disabled="row.count === null || row.count === undefined" :aria-pressed="dutyFilter === row.id" :title="dutyFilter === row.id ? '再次点击进入处理列表' : '点击筛选此事项'" :aria-label="`${countText(row.count)}${row.title}，${row.meta}`" @click="openDuty(row)">
            <span class="duty-task-icon" :data-tone="row.tone"><ShellIcon :name="row.icon" :size="16" /></span>
            <span class="duty-task-copy"><strong><b>{{ countText(row.count) }}</b>{{ row.title }}</strong><small>{{ row.meta }}</small></span>
            <span class="duty-task-action"><em :data-tone="row.tone">{{ row.tag }}</em><ShellIcon name="arrow-right" :size="13" /></span>
          </button>
        </div>
        <div v-if="dutyFilter" class="duty-filter-note"><span>已筛选，再次点击进入处理</span><button type="button" @click="dutyFilter = null">查看全部</button></div>
        <RouterLink v-if="duty?.overdueTaskCount !== null && duty?.overdueTaskCount !== undefined" class="duty-overdue" to="/tasks"><span>{{ countText(duty?.overdueTaskCount) }} 项任务已到期</span><span>查看待办<ShellIcon name="arrow-right" :size="13" /></span></RouterLink>
        <div v-else class="duty-no-permission">当前账号无任务查看权限</div>
        <div class="duty-queue-actions"><RouterLink to="/tasks" class="duty-all">全部待办</RouterLink><RouterLink to="/tasks" class="duty-open">查看待办明细<ShellIcon name="arrow-right" :size="14" /></RouterLink></div>
      </aside>
    </div>

    <section v-if="showAnalysis && report" class="duty-analysis-grid" aria-label="更多经营分析">
      <article class="dashboard-panel">
        <header class="dashboard-panel-header"><div><span>履约阶段</span><h2>从预约到正式核销</h2></div><small>真实计数 · 非转化率</small></header>
        <div v-if="hasFulfillmentFacts" class="dashboard-chart-frame"><DashboardChart :option="fulfillmentChart" label="确认预约、到院客户、服务完成和正式核销数量" /></div>
        <div v-else class="dashboard-empty">当前范围没有履约事实。可扩大日期范围或清除机构筛选。</div>
        <footer class="dashboard-panel-note">未确认口径的转化率不展示，不根据阶段数量推算。</footer>
      </article>
      <article class="dashboard-panel">
        <header class="dashboard-panel-header"><div><span>机构履约</span><h2>正式核销排行</h2></div><small>前 5 家授权机构</small></header>
        <div v-if="report.institutionRows.length" class="dashboard-chart-frame"><DashboardChart :option="institutionChart" label="按正式核销次数排序的机构排行" /></div>
        <div v-else class="dashboard-empty">当前范围没有可展示的机构履约数据。</div>
        <footer class="dashboard-panel-note">排行按正式核销次数，不将机构代收金额混作平台实收。</footer>
      </article>
      <article class="dashboard-panel">
        <header class="dashboard-panel-header"><div><span>活动成交</span><h2>活动与批次净支付排行</h2></div><small>成交快照 · 前 5 项</small></header>
        <div v-if="report.campaignRows.length" class="dashboard-chart-frame"><DashboardChart :option="campaignChart" label="按净支付金额排序的活动与批次排行" /></div>
        <div v-else class="dashboard-empty">当前范围没有活动成交记录。无活动订单不会归入虚构活动。</div>
        <footer class="dashboard-panel-note">净支付 = 支付金额 − 成功退款，仅用于经营事实预览。</footer>
      </article>
    </section>

    <footer class="dashboard-footer"><span>只展示授权范围内的数据，客户隐私默认脱敏</span><span>只读展示，不改变业务状态</span></footer>
  </main>
</template>
