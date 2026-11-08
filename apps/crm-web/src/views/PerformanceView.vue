<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { PerformanceDailyItem, PerformanceReport, PerformanceRole, PerformanceRow, ReportFact } from '../api/report'
import { getPerformanceReport } from '../api/report'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import ReportContextBar from './reporting/ReportContextBar.vue'
import ReportEvidenceDrawer from './reporting/ReportEvidenceDrawer.vue'
import {
  attributionStatusText,
  countText,
  hasCompleteRange,
  initialMonthRange,
  moneyText,
  percentageText,
  reportErrorText,
  reportRangeForPeriod,
  toReportQuery,
} from './reporting/reportUi'
import type { ReportPeriod } from './reporting/reportUi'
import './reporting/reporting.css'

const filters = reactive({
  range: initialMonthRange() as [string, string] | null,
  timeZone: 'Asia/Shanghai',
  sourceSystem: 'ALL',
  receiverType: 'ALL',
  drillDownType: 'PAYMENT',
})
const query = reactive({ page: 1, size: 20 })
const activeRole = ref<PerformanceRole>('MANAGER')
const activePeriod = ref<ReportPeriod | null>('month')
const report = ref<PerformanceReport | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let loadSequence = 0

function reportParams() {
  return {
    ...toReportQuery(filters.range ?? [], filters.timeZone, filters.sourceSystem, filters.receiverType),
    performanceRole: activeRole.value,
    drillDownType: filters.drillDownType,
    drillDownLimit: 50,
    page: query.page,
    size: query.size,
  }
}

async function load() {
  if (!hasCompleteRange(filters.range ?? [])) {
    report.value = null
    errorMessage.value = '请选择完整且有效的统计日期范围'
    return
  }
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  report.value = null
  try {
    const result = await getPerformanceReport(reportParams())
    if (sequence !== loadSequence) return
    report.value = result
    query.page = result.page
    query.size = result.size
  } catch (error) {
    if (sequence === loadSequence) errorMessage.value = reportErrorText(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function changeRole(value: string | number) {
  activeRole.value = String(value) as PerformanceRole
  query.page = 1
  void load()
}

function setPeriod(period: ReportPeriod) {
  activePeriod.value = period
  filters.range = reportRangeForPeriod(period, filters.timeZone)
  query.page = 1
  void load()
}

function changeDateRange() {
  activePeriod.value = null
  query.page = 1
  void load()
}

function reset() {
  activePeriod.value = 'month'
  filters.range = initialMonthRange()
  filters.timeZone = 'Asia/Shanghai'
  filters.sourceSystem = 'ALL'
  filters.receiverType = 'ALL'
  filters.drillDownType = 'PAYMENT'
  query.page = 1
  void load()
}

function reportStatusText(status: string): string {
  return ({
    UNCONFIGURED: '未配置',
    PENDING_VERIFICATION: '待核实',
    VERIFIED: '已核实',
    APPROVED: '已核准',
    CALCULATED: '已计算',
    NOT_PAYROLL: '非工资核算依据',
    PENDING_DEFINITION: '口径待确认',
  } as Record<string, string>)[status] ?? '待确认'
}

const dailyItems = computed(() => report.value?.dailyItems ?? [])
const chartableDailyItems = computed(() => dailyItems.value.filter((item) =>
  item.paidFen !== null && Number.isFinite(item.paidFen) && item.paidFen >= 0,
))
const maxDailyPaidFen = computed(() => Math.max(0, ...chartableDailyItems.value.map((item) => item.paidFen ?? 0)))
const hasDailyChart = computed(() => chartableDailyItems.value.length > 0 && maxDailyPaidFen.value > 0)

function dailyBarHeight(item: PerformanceDailyItem): string {
  if (item.paidFen === null || maxDailyPaidFen.value <= 0) return '0%'
  return `${(item.paidFen / maxDailyPaidFen.value) * 100}%`
}

const receiverRows = computed(() => {
  const totals = report.value?.totals
  if (!totals) return []
  return [
    { label: '全部收款方', paid: moneyText(totals.paidFen), refund: moneyText(totals.refundFen), net: moneyText(totals.netPaidFen) },
    { label: '平台收款', paid: moneyText(totals.platformPaidFen), refund: moneyText(totals.platformRefundFen), net: moneyText(totals.platformNetPaidFen) },
    { label: '机构收款', paid: moneyText(totals.institutionPaidFen), refund: moneyText(totals.institutionRefundFen), net: moneyText(totals.institutionNetPaidFen) },
  ]
})

const factsVisible = ref(false)
const facts = ref<ReportFact[]>([])
const factOwner = ref<PerformanceRow | null>(null)
const factLoadingId = ref<number | null>(null)
const factError = ref('')
const factTitle = computed(() => `${factOwner.value?.displayName ?? '员工'} · 原始事实`)

async function openFacts(row: PerformanceRow) {
  if (row.userId === null) return
  factOwner.value = row
  factLoadingId.value = row.userId
  factError.value = ''
  facts.value = []
  try {
    const result = await getPerformanceReport({ ...reportParams(), staffUserId: row.userId, page: 1 })
    facts.value = result.drillDown
    factsVisible.value = true
  } catch (error) {
    factError.value = reportErrorText(error)
  } finally {
    factLoadingId.value = null
  }
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page report-page">
    <PageHeader title="业绩分析" description="管家与商务分岗位汇总，使用成交时归属快照、真实支付和成功退款；团队目标与日趋势直接读取服务端事实">
      <template #actions>
        <el-popover placement="bottom-end" trigger="click" width="430">
          <template #reference><el-button>指标口径说明</el-button></template>
          <div class="metric-definition">
            <strong>业绩预览不直接作为工资或提成结算依据</strong>
            <p>支付金额、成功退款和净支付事实按成交时归属快照汇总；日趋势保留服务端实际发生日。</p>
            <p>团队目标未配置时显示待配置，完成率未确认时不补算；Q13 转化指标仍需运营与财务确认。</p>
          </div>
        </el-popover>
        <el-button :loading="loading" @click="load">刷新报表</el-button>
      </template>
    </PageHeader>

    <div class="proto-banner report-definition">
      <div><strong>业绩预览不直接作为工资或提成结算依据</strong><p>团队目标、完成率和日金额均保留服务端状态；缺失目标不按 0 处理，缺失日金额不补柱形。</p></div>
      <span class="proto-pill warning">{{ report?.usageStatus === 'NOT_PAYROLL' ? '非工资核算依据' : '预览口径' }}</span>
    </div>

    <div v-if="report" class="proto-stat-grid three">
      <StatCard label="团队指标" :value="moneyText(report.teamTargetFen, report.teamTargetStatus === 'UNCONFIGURED' ? '待配置' : '待确认')" :sub="`真实汇总状态 · ${reportStatusText(report.teamTargetStatus)}`" icon="chart" />
      <StatCard label="净支付事实" :value="moneyText(report.totals.netPaidFen)" :sub="`支付 ${moneyText(report.totals.paidFen)} · 成功退款 ${moneyText(report.totals.refundFen)}`" icon="wallet" />
      <StatCard label="团队完成率" :value="percentageText(report.teamTargetCompletionRate, report.teamTargetCompletionStatus)" :sub="`真实汇总状态 · ${reportStatusText(report.teamTargetCompletionStatus)}`" icon="activity" />
    </div>
    <div v-else-if="loading" class="proto-stat-grid three" aria-hidden="true"><el-skeleton v-for="index in 3" :key="index" animated :rows="2" class="proto-stat-card" /></div>

    <PageCard>
      <el-tabs v-model="activeRole" @tab-change="changeRole"><el-tab-pane label="管家业绩" name="MANAGER" /><el-tab-pane label="商务业绩" name="BUSINESS" /></el-tabs>
      <div class="proto-toolbar">
        <div class="report-filter">
          <el-button-group class="report-period-group" aria-label="快捷统计范围">
            <el-button :type="activePeriod === 'day' ? 'primary' : 'default'" data-testid="performance-period-day" @click="setPeriod('day')">日</el-button>
            <el-button :type="activePeriod === 'week' ? 'primary' : 'default'" data-testid="performance-period-week" @click="setPeriod('week')">周</el-button>
            <el-button :type="activePeriod === 'month' ? 'primary' : 'default'" data-testid="performance-period-month" @click="setPeriod('month')">月</el-button>
          </el-button-group>
          <el-date-picker v-model="filters.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" class="report-date" @change="changeDateRange" />
          <el-select v-model="filters.sourceSystem" class="report-source" aria-label="订单来源" @change="query.page = 1; load"><el-option label="全部来源" value="ALL" /><el-option label="手工订单" value="MANUAL" /><el-option label="有赞订单" value="YOUZAN" /></el-select>
          <el-select v-model="filters.receiverType" class="report-source" aria-label="收款方" @change="query.page = 1; load"><el-option label="全部收款方" value="ALL" /><el-option label="平台收款" value="PLATFORM" /><el-option label="机构收款" value="INSTITUTION" /></el-select>
          <el-select v-model="filters.drillDownType" class="report-source" aria-label="明细事实类型"><el-option label="支付流水" value="PAYMENT" /><el-option label="成功退款" value="REFUND" /></el-select>
          <el-button type="primary" :loading="loading" @click="query.page = 1; load">查询</el-button><el-button @click="reset">重置</el-button>
        </div>
        <span class="proto-note">日为今天、周为本周一至今天、月为本月一日至今天，均按所选时区计算</span>
      </div>
      <div v-if="loading" class="report-loading" role="status">正在加载报表，请稍候。</div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="report-error"><el-button text type="primary" data-testid="performance-retry" @click="load">重试</el-button></el-alert>

      <template v-if="report">
        <div class="proto-card-body"><ReportContextBar :context="report" /></div>
        <div class="proto-table-wrap">
          <app-table :data="report.items">
            <el-table-column :label="activeRole === 'MANAGER' ? '管家' : '商务'" min-width="155"><template #default="{ row }"><strong>{{ row.displayName || '用户名称待确认' }}</strong><div class="proto-subcell">{{ row.userId === null ? '归属用户未配置' : `用户 #${row.userId}` }}</div></template></el-table-column>
            <el-table-column label="归因" width="130"><template #default="{ row }">{{ attributionStatusText(row.attributionStatus) }}</template></el-table-column>
            <el-table-column label="支付订单" width="95" align="right"><template #default="{ row }">{{ countText(row.paidOrderCount) }}</template></el-table-column>
            <el-table-column label="支付金额" width="120" align="right"><template #default="{ row }"><span class="report-cell-money">{{ moneyText(row.paidFen) }}</span></template></el-table-column>
            <el-table-column label="成功退款" width="115" align="right"><template #default="{ row }"><span class="report-cell-refund">{{ moneyText(row.refundFen) }}</span></template></el-table-column>
            <el-table-column label="净支付事实" width="125" align="right"><template #default="{ row }">{{ moneyText(row.netPaidFen) }}</template></el-table-column>
            <el-table-column label="目标" width="120" align="right"><template #default="{ row }">{{ moneyText(row.targetFen, '待配置') }}<div class="proto-subcell">{{ reportStatusText(row.targetStatus) }}</div></template></el-table-column>
            <el-table-column label="目标完成率" width="120"><template #default="{ row }"><span :class="{ 'report-pending': row.targetCompletionRate === null }">{{ percentageText(row.targetCompletionRate, row.targetCompletionStatus) }}</span></template></el-table-column>
            <el-table-column label="佣金" width="115" align="right"><template #default="{ row }">{{ moneyText(row.commissionFen) }}<div class="proto-subcell">{{ reportStatusText(row.commissionStatus) }}</div></template></el-table-column>
            <el-table-column label="事实" width="105" fixed="right"><template #default="{ row }"><el-button text :disabled="row.userId === null" :loading="factLoadingId === row.userId" data-testid="performance-fact-drilldown" @click="openFacts(row)">查看明细</el-button></template></el-table-column>
            <template #empty><el-empty description="暂无该岗位业绩数据" /></template>
          </app-table>
        </div>
        <el-alert v-if="factError" type="error" :title="factError" :closable="false" class="report-error"><el-button text type="primary" @click="factOwner && openFacts(factOwner)">重试查看明细</el-button></el-alert>
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="report.total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page = 1; load()" />
      </template>
    </PageCard>

    <div v-if="report" class="report-grid-two">
      <PageCard title="每日支付与退款趋势" subtitle="柱形和表格均来自每日明细；不补缺失日期，不把未知金额当作 0">
        <div class="report-daily-section">
          <el-empty v-if="dailyItems.length === 0" description="当前范围没有日事实记录，不生成趋势或补零" />
          <template v-else>
            <el-empty v-if="!hasDailyChart" description="日事实缺少可绘制的支付金额，不生成趋势或补零" />
            <div v-else class="report-daily-chart" data-testid="performance-daily-chart" aria-label="每日支付金额柱形图">
              <div v-for="item in chartableDailyItems" :key="item.statDate" class="report-daily-bar-item" data-testid="daily-bar" :aria-label="`${item.statDate} 支付金额 ${moneyText(item.paidFen)}`">
                <span class="report-daily-bar-value">{{ moneyText(item.paidFen) }}</span>
                <div class="report-daily-bar-track"><i :style="{ height: dailyBarHeight(item) }" /></div>
                <strong>{{ item.statDate.slice(5) }}</strong>
              </div>
            </div>
            <div class="proto-table-wrap" data-testid="performance-daily-table">
              <app-table :data="dailyItems" row-key="statDate" max-height="360">
                <el-table-column prop="statDate" label="日期" min-width="115" />
                <el-table-column label="支付金额" min-width="115" align="right"><template #default="{ row }">{{ moneyText(row.paidFen, '待核实') }}</template></el-table-column>
                <el-table-column label="成功退款" min-width="115" align="right"><template #default="{ row }"><span class="report-cell-refund">{{ moneyText(row.refundFen, '待核实') }}</span></template></el-table-column>
                <el-table-column label="净支付事实" min-width="125" align="right"><template #default="{ row }">{{ moneyText(row.netPaidFen, '待核实') }}</template></el-table-column>
              </app-table>
            </div>
          </template>
        </div>
      </PageCard>
      <PageCard title="绩效核算规则" subtitle="只读预览，不修改归属、退款、目标或工资结果" padded>
        <div class="report-rules">
          <p><strong>归属</strong>使用成交时管家/商务快照，未配置归因单独标记，不回填当前客户归属。</p>
          <p><strong>目标</strong>团队目标与完成率只读取真实汇总字段和状态，不使用分页行或空目标推算。</p>
          <p><strong>日趋势</strong>只展示每日明细返回的日期与金额；日、周、月快捷筛选通过重新查询获得数据。</p>
        </div>
      </PageCard>
    </div>

    <PageCard v-if="report" title="收款方拆分" subtitle="平台与机构收款分别呈现，避免把机构代收当作平台实收">
      <div class="proto-table-wrap"><app-table :data="receiverRows" row-key="label"><el-table-column prop="label" label="收款方" min-width="115" /><el-table-column prop="paid" label="支付" min-width="115" align="right" /><el-table-column prop="refund" label="退款" min-width="115" align="right" /><el-table-column prop="net" label="净支付事实" min-width="125" align="right" /></app-table></div>
    </PageCard>

    <ReportEvidenceDrawer v-model="factsVisible" :title="factTitle" :facts="facts" description="按所选员工重新请求当前报表端点；仅展示服务端授权范围内返回的真实事实。" />
    <footer class="proto-footer">鲸选美学 CRM · 业绩预览须经财务规则审核后方可用于后续核算</footer>
  </div>
</template>

<style scoped>
.report-rules p { margin: 0; padding: 14px 0; border-bottom: 1px solid var(--line); color: #78909e; font-size: 12px; line-height: 1.8; }
.report-rules p:first-child { padding-top: 0; }
.report-rules p:last-child { border-bottom: 0; }
.report-rules strong { margin-right: 8px; color: #355164; }
.metric-definition { color: #587482; font-size: 12px; line-height: 1.8; }
.metric-definition strong { color: #294b59; }
.metric-definition p { margin: 10px 0 0; }
</style>
