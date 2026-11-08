<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { OperationsInstitutionRow, OperationsReport } from '../api/report'
import { getOperationsReport } from '../api/report'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import ReportContextBar from './reporting/ReportContextBar.vue'
import {
  countText,
  displayDateTime,
  factTypeText,
  hasCompleteRange,
  initialMonthRange,
  moneyText,
  orderSourceText,
  ratioText,
  receiverTypeText,
  reportErrorText,
  toReportQuery,
} from './reporting/reportUi'
import './reporting/reporting.css'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const filters = reactive({
  range: initialMonthRange() as [string, string] | null,
  timeZone: 'Asia/Shanghai',
  sourceSystem: 'ALL',
  receiverType: 'ALL',
  customerId: undefined as number | undefined,
  staffUserId: undefined as number | undefined,
  institutionId: undefined as number | undefined,
  drillDownType: 'PAYMENT',
})
const report = ref<OperationsReport | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let loadSequence = 0

async function load() {
  const range = filters.range ?? []
  if (!hasCompleteRange(range)) {
    report.value = null
    errorMessage.value = '请选择完整且有效的统计日期范围'
    return
  }
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  report.value = null
  try {
    const result = await getOperationsReport({
      ...toReportQuery(range, filters.timeZone, filters.sourceSystem, filters.receiverType),
      customerId: filters.customerId,
      staffUserId: filters.staffUserId,
      institutionId: filters.institutionId,
      drillDownType: filters.drillDownType,
      drillDownLimit: 50,
    })
    if (sequence === loadSequence) report.value = result
  } catch (error) {
    if (sequence === loadSequence) errorMessage.value = reportErrorText(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function reset() {
  filters.range = initialMonthRange()
  filters.timeZone = 'Asia/Shanghai'
  filters.sourceSystem = 'ALL'
  filters.receiverType = 'ALL'
  filters.customerId = undefined
  filters.staffUserId = undefined
  filters.institutionId = undefined
  filters.drillDownType = 'PAYMENT'
  void load()
}

const funnelRows = computed(() => {
  const metrics = report.value?.metrics
  return [
    { label: '触达', value: metrics?.contactCustomerCount },
    { label: '成交', value: metrics?.paidOrderCount },
    { label: '预约', value: metrics?.confirmedAppointmentCount },
    { label: '服务完成', value: metrics?.serviceCompletedCount },
    { label: '到院', value: metrics?.checkedInCustomerCount },
    { label: '已核销客户', value: metrics?.redeemedCustomerCount },
  ]
})

const moneyRows = computed(() => {
  const metrics = report.value?.metrics
  if (!metrics) return []
  return [
    { label: '支付金额', value: moneyText(metrics.paidFen), note: '全部收款方' },
    { label: '平台支付', value: moneyText(metrics.platformPaidFen), note: '平台收款事实' },
    { label: '机构支付', value: moneyText(metrics.institutionPaidFen), note: '机构代收事实，不混作平台实收' },
    { label: '成功退款', value: moneyText(metrics.refundFen), note: '全部收款方' },
    { label: '平台退款', value: moneyText(metrics.platformRefundFen), note: '平台成功退款' },
    { label: '机构退款', value: moneyText(metrics.institutionRefundFen), note: '机构成功退款' },
    { label: '净支付事实', value: moneyText(metrics.netPaidFen), note: '支付减退款；不等于已批准的净业绩' },
    { label: '平台净支付', value: moneyText(metrics.platformNetPaidFen), note: '平台支付与退款差额' },
    { label: '机构净支付', value: moneyText(metrics.institutionNetPaidFen), note: '机构支付与退款差额' },
  ]
})

function conversion(key: string): string {
  return ratioText(report.value?.conversionRates[key])
}

function reconciliationText(row: OperationsInstitutionRow): string {
  if (!['VERIFIED', 'CONFIRMED'].includes(row.pendingReconciliationStatus)) return '待核实'
  return moneyText(row.pendingReconciliationFen, '待核实')
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page report-page">
    <PageHeader title="运营数据中心" description="触达、成交、预约、服务、到院与核销按授权范围汇总；管家和机构分组均可追溯到真实业务流水">
      <template #actions>
        <router-link to="/operations/dashboard"><el-button type="primary" data-testid="operations-dashboard-entry">运营大屏</el-button></router-link>
        <router-link v-if="auth.hasPermission('campaign:read')" to="/campaigns"><el-button>活动分析</el-button></router-link>
        <router-link v-if="auth.hasPermission('export:read')" to="/exports"><el-button>导出数据</el-button></router-link>
        <el-button :loading="loading" data-testid="operations-refresh" @click="load">刷新报表</el-button>
      </template>
    </PageHeader>

    <div v-if="report" class="proto-stat-grid">
      <StatCard label="触达客户" :value="countText(report.metrics.contactCustomerCount, '人')" sub="当前筛选范围的去重客户" icon="chat" />
      <StatCard label="成交单数" :value="countText(report.metrics.paidOrderCount, '单')" :sub="`触达→成交 ${conversion('contactToPaid')}`" icon="bag" />
      <StatCard label="服务完成 / 确认预约" :value="`${countText(report.metrics.serviceCompletedCount)} / ${countText(report.metrics.confirmedAppointmentCount)}`" :sub="`服务完成率 ${conversion('appointmentToServiceCompleted')}`" icon="calendar" />
      <StatCard label="到院客户" :value="countText(report.metrics.checkedInCustomerCount, '人')" :sub="`预约→到院 ${conversion('appointmentToCheckedIn')}`" icon="hospital" />
    </div>
    <div v-else-if="loading" class="proto-stat-grid" aria-hidden="true"><el-skeleton v-for="index in 4" :key="index" animated :rows="2" class="proto-stat-card" /></div>

    <div class="proto-callout report-definition">
      <div><strong>Q13 指标口径待运营与财务确认</strong><p>管家与机构分组使用真实响应行；空比率只显示待确认，待对账金额未核实时显示待核实而不是 0 元。</p></div>
      <span class="proto-pill warning">事实先行</span>
    </div>

    <PageCard>
      <div class="proto-toolbar">
        <div class="report-filter">
          <el-date-picker v-model="filters.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" class="report-date" @change="load" />
          <el-select v-model="filters.sourceSystem" class="report-source" aria-label="订单来源" @change="load"><el-option label="全部来源" value="ALL" /><el-option label="手工订单" value="MANUAL" /><el-option label="有赞订单" value="YOUZAN" /></el-select>
          <el-select v-model="filters.receiverType" class="report-source" aria-label="收款方" @change="load"><el-option label="全部收款方" value="ALL" /><el-option label="平台收款" value="PLATFORM" /><el-option label="机构收款" value="INSTITUTION" /></el-select>
          <el-input-number v-model="filters.staffUserId" :min="1" :precision="0" :controls="false" placeholder="管家用户 ID" class="report-id-filter" />
          <el-input-number v-model="filters.institutionId" :min="1" :precision="0" :controls="false" placeholder="机构 ID" class="report-id-filter" />
          <el-input-number v-model="filters.customerId" :min="1" :precision="0" :controls="false" placeholder="客户 ID" class="report-id-filter" />
          <el-button type="primary" :loading="loading" @click="load">查询</el-button><el-button @click="reset">重置</el-button>
        </div>
        <span class="proto-note">ID 仅用于筛选，后端仍强制租户与数据范围</span>
      </div>
    </PageCard>

    <div v-if="loading" class="report-loading" role="status">正在加载报表，请稍候；加载期间不保留上一口径的数据。</div>
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="report-error"><el-button text type="primary" data-testid="operations-retry" @click="load">重试</el-button></el-alert>

    <template v-if="report">
      <ReportContextBar :context="report" />
      <div class="report-grid-two">
        <PageCard title="管家转化明细" subtitle="触达 → 成交 → 预约 → 服务完成 → 到院">
          <div class="proto-table-wrap" data-testid="operations-staff-rows">
            <app-table :data="report.staffRows" row-key="staffUserId">
              <el-table-column label="管家" min-width="140"><template #default="{ row }"><strong>{{ row.staffName || '管家名称待确认' }}</strong><div class="proto-subcell">{{ row.staffUserId === null ? '归属用户待确认' : `用户 #${row.staffUserId}` }}</div></template></el-table-column>
              <el-table-column label="触达" width="75" align="right"><template #default="{ row }">{{ countText(row.contactCustomerCount) }}</template></el-table-column>
              <el-table-column label="成交单" width="85" align="right"><template #default="{ row }">{{ countText(row.paidOrderCount) }}</template></el-table-column>
              <el-table-column label="成交率" width="100"><template #default="{ row }">{{ ratioText(row.conversionRates.contactToPaid) }}</template></el-table-column>
              <el-table-column label="预约" width="75" align="right"><template #default="{ row }">{{ countText(row.confirmedAppointmentCount) }}</template></el-table-column>
              <el-table-column label="服务完成" width="95" align="right"><template #default="{ row }">{{ countText(row.serviceCompletedCount) }}</template></el-table-column>
              <el-table-column label="完成率" width="100"><template #default="{ row }">{{ ratioText(row.conversionRates.appointmentToServiceCompleted) }}</template></el-table-column>
              <el-table-column label="到院" width="75" align="right"><template #default="{ row }">{{ countText(row.checkedInCustomerCount) }}</template></el-table-column>
              <el-table-column label="成交金额" width="120" align="right"><template #default="{ row }"><span class="report-cell-money">{{ moneyText(row.paidFen) }}</span></template></el-table-column>
              <template #empty><el-empty description="暂无管家转化数据" /></template>
            </app-table>
          </div>
        </PageCard>

        <PageCard title="漏斗口径" subtitle="所有指标均可追溯业务流水；不用进度条暗示未确认比率">
          <div class="report-funnel">
            <div v-for="item in funnelRows" :key="item.label" class="report-funnel-row"><span>{{ item.label }}</span><span>服务端真实计数</span><strong>{{ countText(item.value) }}</strong></div>
            <div class="proto-subcell">服务完成数与已核销客户数分别统计，互不替代。</div>
          </div>
        </PageCard>
      </div>

      <PageCard title="商家 / 机构履约明细" subtitle="从预约、到院、完成服务、正式核销到待对账统一查看">
        <div class="proto-table-wrap" data-testid="operations-institution-rows">
          <app-table :data="report.institutionRows" row-key="institutionId">
            <el-table-column label="机构" min-width="170"><template #default="{ row }"><strong>{{ row.institutionName || '机构名称待确认' }}</strong><div class="proto-subcell">{{ row.institutionId === null ? '机构归属待确认' : `机构 #${row.institutionId}` }}</div></template></el-table-column>
            <el-table-column label="预约" width="85" align="right"><template #default="{ row }">{{ countText(row.confirmedAppointmentCount) }}</template></el-table-column>
            <el-table-column label="到院" width="85" align="right"><template #default="{ row }">{{ countText(row.checkedInAppointmentCount) }}</template></el-table-column>
            <el-table-column label="到院率" width="105"><template #default="{ row }">{{ ratioText(row.conversionRates.appointmentToCheckedIn) }}</template></el-table-column>
            <el-table-column label="完成服务" width="105" align="right"><template #default="{ row }">{{ countText(row.serviceCompletedCount) }}</template></el-table-column>
            <el-table-column label="核销次数" width="105" align="right"><template #default="{ row }">{{ countText(row.redeemedCount) }}</template></el-table-column>
            <el-table-column label="待对账" width="125" align="right"><template #default="{ row }"><span class="report-cell-money" data-testid="institution-reconciliation">{{ reconciliationText(row) }}</span></template></el-table-column>
            <template #empty><el-empty description="暂无机构履约数据" /></template>
          </app-table>
        </div>
      </PageCard>

      <PageCard title="收款与退款事实" subtitle="金额以接口整数分格式化；净支付不等于未经批准的净业绩">
        <div class="proto-table-wrap"><app-table :data="moneyRows" row-key="label"><el-table-column prop="label" label="金额口径" min-width="140" /><el-table-column prop="value" label="金额" width="130" align="right"><template #default="{ row }"><span class="report-cell-money">{{ row.value }}</span></template></el-table-column><el-table-column prop="note" label="说明" min-width="220" /></app-table></div>
      </PageCard>

      <PageCard title="原始业务事实" :subtitle="`服务端按 ${factTypeText(report.drillDownType)} 返回，最多 ${report.drillDownLimit} 条`">
        <template #actions><el-select v-model="filters.drillDownType" class="report-fact-type" aria-label="明细事实类型" @change="load"><el-option label="支付流水" value="PAYMENT" /><el-option label="成功退款" value="REFUND" /><el-option label="正式核销" value="REDEMPTION" /><el-option label="客户跟进" value="FOLLOWUP" /><el-option label="预约确认" value="APPOINTMENT_CONFIRMED" /><el-option label="到院事实" value="CHECKED_IN" /><el-option label="服务完成" value="SERVICE_DONE" /></el-select></template>
        <div class="proto-table-wrap" data-testid="operations-facts">
          <app-table :data="report.drillDown">
            <el-table-column label="事实 / 来源" min-width="145"><template #default="{ row }"><strong>{{ factTypeText(row.factType) }}</strong><div class="proto-subcell">来源 #{{ row.sourceId ?? '待确认' }}</div></template></el-table-column>
            <el-table-column label="订单 / 客户" min-width="140"><template #default="{ row }">订单 #{{ row.orderId ?? '—' }}<div class="proto-subcell">客户 #{{ row.customerId ?? '—' }}</div></template></el-table-column>
            <el-table-column label="金额" width="120" align="right"><template #default="{ row }">{{ moneyText(row.amountFen, '不适用') }}</template></el-table-column>
            <el-table-column label="数量" width="85" align="right"><template #default="{ row }">{{ row.quantity === null ? '—' : countText(row.quantity) }}</template></el-table-column>
            <el-table-column label="事件时间" min-width="190"><template #default="{ row }">{{ displayDateTime(row.eventAt) }}</template></el-table-column>
            <el-table-column label="来源 / 收款方" min-width="175"><template #default="{ row }">{{ orderSourceText(row.sourceSystem) }}<div class="proto-subcell">{{ receiverTypeText(row.receiverType) }}</div></template></el-table-column>
            <template #empty><el-empty description="当前筛选没有对应事实记录" /></template>
          </app-table>
        </div>
      </PageCard>
    </template>
    <footer class="proto-footer">鲸选美学 CRM · 经营指标仅基于授权范围内的真实业务数据</footer>
  </div>
</template>

<style scoped>
.report-id-filter { width: 145px; }
.report-fact-type { width: 150px; }
@media (max-width: 760px) { .report-id-filter, .report-fact-type { width: 100%; } }
</style>
