<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getWecomAnalytics } from '../api/wecomAnalytics'
import type { WecomAnalytics } from '../api/wecomAnalytics'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { formatDateTime } from './customer/shared'

interface Metric { label: string; value: string | number; detail: string }
const auth = useAuthStore()
const canRead = computed(() => auth.user?.userType !== 'INSTITUTION' && auth.hasPermission('report:read'))
const from = ref(''), to = ref(''), employeeId = ref<number | undefined>()
const report = ref<WecomAnalytics | null>(null)
const loading = ref(false), errorMessage = ref('')
let requestSequence = 0
const scopeLabels: Record<string, string> = { ALL: '当前租户授权范围', TEAM: '本人及授权团队', SELF: '本人负责客户', NONE: '无可用数据范围' }
const unsupportedLabels: Record<string, string> = {
  MESSAGE_READ_RATE: '客户已读率', H5_OPEN_RATE: 'H5 打开率',
  CHAT_CAUSAL_ATTRIBUTION: '聊天对成交的因果归因', QUICK_REPLY_BUSINESS_OUTCOME: '话术对业务结果的因果效果',
}
const quickMetrics = computed<Metric[]>(() => {
  const data = report.value?.quickReply
  if (!data) return []
  const sdkResults = data.sdkSuccessCount + data.sdkFailedCount + data.sdkUnknownCount
  return [
    { label: '使用日志', value: data.usageCount, detail: '复制与 SDK 结果分别记录' },
    { label: '复制', value: data.copiedCount, detail: '不代表已发送或已读' },
    { label: 'SDK 成功 / 失败', value: `${data.sdkSuccessCount} / ${data.sdkFailedCount}`, detail: '以实际客户端回调为准' },
    { label: '结果未知', value: data.sdkUnknownCount, detail: '不自动改为成功' },
    { label: '编辑后使用', value: data.editedCount, detail: '以员工最终内容记录为准' },
    { label: 'AI 关联使用', value: data.aiLinkedUsageCount, detail: '仅限已采纳候选的关联日志' },
    { label: 'SDK 成功率', value: rate(data.sdkSuccessRate), detail: sdkResults ? `${data.sdkSuccessCount} / ${sdkResults} 条 SDK 结果` : '暂无 SDK 结果分母' },
  ]
})
const aiMetrics = computed<Metric[]>(() => {
  const data = report.value?.aiReply
  if (!data) return []
  return [
    { label: '生成候选', value: data.generatedCount, detail: `${data.mockGeneratedCount} 条 Mock，需区分真实模型` },
    { label: '已采纳 / 已驳回', value: `${data.acceptedCount} / ${data.rejectedCount}`, detail: '创建批次的当前处理状态' },
    { label: '待人工处理', value: data.pendingCount, detail: '未采纳不等于已复制或发送' },
    { label: '采纳时经过编辑', value: data.editedAcceptedCount, detail: '不改变模型原始候选' },
    { label: '采纳率', value: rate(data.cohortAcceptanceRate), detail: data.generatedCount ? `${data.acceptedCount} / ${data.generatedCount} 条创建候选` : '暂无生成候选分母' },
    { label: '候选复制 / SDK 成功', value: `${data.copiedCount} / ${data.sdkSuccessCount}`, detail: '与采纳状态分开统计' },
    { label: 'SDK 失败 / 未知', value: `${data.sdkFailedCount} / ${data.sdkUnknownCount}`, detail: '未知结果不冒充发送成功' },
  ]
})
const unsupportedText = computed(() => report.value?.unsupportedMetrics.map((item) => unsupportedLabels[item] || '其他未接入指标').join('、') || '')
const scopeText = computed(() => scopeLabels[report.value?.scopeType || ''] || '未知数据范围')

function dateValue(date: Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}
function rate(value: number | null): string { return value === null ? '—' : `${value.toFixed(2)}%` }
function errorText(error: unknown): string { return error instanceof Error ? error.message : '分析加载失败，请稍后重试' }
async function load() {
  if (!canRead.value) return
  if (!from.value || !to.value || from.value > to.value) {
    report.value = null
    errorMessage.value = '请选择有效日期，开始日期不能晚于结束日期'
    return
  }
  if (employeeId.value !== undefined && (!Number.isSafeInteger(employeeId.value) || employeeId.value < 1)) {
    errorMessage.value = '员工筛选 ID 必须为正整数'
    return
  }
  const sequence = ++requestSequence
  loading.value = true
  errorMessage.value = ''
  report.value = null
  try {
    const result = await getWecomAnalytics({ from: from.value, to: to.value, employeeId: employeeId.value })
    if (sequence === requestSequence) report.value = result
  } catch (error) {
    if (sequence === requestSequence) errorMessage.value = errorText(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}
onMounted(() => {
  if (!canRead.value) return
  to.value = dateValue(new Date())
  from.value = dateValue(new Date(Date.now() - 29 * 86400000))
  void load()
})
</script>

<template>
  <div class="proto-page analytics-page">
    <PageHeader title="企微运营分析" description="真实话术使用、AI 采纳与业务履约聚合；每个比率均显示口径和分子 / 分母">
      <template #actions><RouterLink v-if="auth.hasPermission('wecom:read')" to="/integrations/wecom" class="el-button">会话工作台</RouterLink></template>
    </PageHeader>
    <el-alert v-if="!canRead" type="error" :closable="false" :title="auth.user?.userType === 'INSTITUTION' ? '机构账号无企微客户运营分析入口' : '缺少 report:read 权限'" />
    <template v-else>
      <PageCard>
        <form class="analytics-filters" @submit.prevent="load">
          <label>开始日期<input v-model="from" type="date" required data-testid="analytics-from"></label>
          <label>结束日期<input v-model="to" type="date" required data-testid="analytics-to"></label>
          <label>员工 ID（可选）<el-input-number v-model="employeeId" :min="1" :precision="0" :controls="false" placeholder="当前范围全部员工" data-testid="analytics-employee" /></label>
          <el-button type="primary" native-type="submit" :loading="loading" data-testid="analytics-search">查询分析</el-button>
        </form>
        <div v-if="report" class="report-context"><span>{{ report.from }} 至 {{ report.to }}</span><span>{{ scopeText }}</span><span>生成于 {{ formatDateTime(report.generatedAt) }}</span><span>口径 {{ report.metricVersion }}</span></div>
      </PageCard>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="analytics-alert" data-testid="analytics-error" />
      <el-alert v-if="report" type="info" :closable="false" class="analytics-alert" :title="`${unsupportedText}暂无可靠回执或关联证据，不提供虚构指标。`" />
      <el-alert v-if="report && report.aiReply.mockGeneratedCount > 0" type="warning" :closable="false" class="analytics-alert" title="结果含 Mock 候选；不能据此评估真实模型质量或生产转化效果。" />

      <div v-loading="loading" class="analytics-content">
        <section v-if="report" class="metric-section" data-testid="quick-reply-analysis">
          <div class="section-heading"><div><span class="section-index">01</span><h2>快捷话术使用与 SDK 结果</h2></div><p>复制不是发送，SDK 成功也不等于客户已读；不以使用日志推断业务效果。</p></div>
          <dl class="metric-grid"><div v-for="metric in quickMetrics" :key="metric.label" class="metric-item"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd><dd class="metric-detail">{{ metric.detail }}</dd></div></dl>
        </section>
        <section v-if="report" class="metric-section" data-testid="ai-reply-analysis">
          <div class="section-heading"><div><span class="section-index">02</span><h2>AI 候选采纳与使用</h2></div><p>采纳率 = 期内创建且当前已采纳的候选 ÷ 期内生成候选；处理、复制、SDK 结果独立计数。</p></div>
          <dl class="metric-grid"><div v-for="metric in aiMetrics" :key="metric.label" class="metric-item"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd><dd class="metric-detail">{{ metric.detail }}</dd></div></dl>
        </section>
        <section v-if="report" class="metric-section" data-testid="conversion-analysis">
          <div class="section-heading"><div><span class="section-index">03</span><h2>真实履约与复购</h2></div><p>仅使用真实预约状态历史与支付流水，不把聊天或话术使用当成成交原因。</p></div>
          <div class="funnel-grid">
            <article class="funnel-item"><span class="funnel-label">确认预约 → 实际到院</span><strong>{{ rate(report.conversion.appointmentArrivalRate) }}</strong><p>期内确认且已到院 {{ report.conversion.checkedInFromConfirmedCount }} 条 / 期内确认预约 {{ report.conversion.confirmedAppointmentCount }} 条</p><small>按预约 CONFIRMED 历史归属批次，到院采用实际 CHECKED_IN 事件；无分母时显示 —。</small></article>
            <article class="funnel-item"><span class="funnel-label">期内付费客户 → 多次付费</span><strong>{{ rate(report.conversion.repeatCustomerRate) }}</strong><p>期内有至少两笔不同已支付订单 {{ report.conversion.repeatPaidCustomerCount }} 人 / 付费客户 {{ report.conversion.paidCustomerCount }} 人</p><small>期内已支付订单 {{ report.conversion.paidOrderCount }} 笔；同一订单多笔流水不重复计数，不把复购与 AI 推荐混为因果。</small></article>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.analytics-page { --analytics-ink: #233f38; --analytics-muted: #778c83; --analytics-line: #e2ebe6; --analytics-accent: #32765e; }
.analytics-filters { display: grid; grid-template-columns: minmax(155px,1fr) minmax(155px,1fr) minmax(180px,1fr) auto; align-items: end; gap: 14px; }
.analytics-filters label { display: grid; gap: 7px; min-width: 0; color: #5e786d; font-size: 12px; font-weight: 600; }
.analytics-filters input { box-sizing: border-box; width: 100%; height: 40px; padding: 0 11px; border: 1px solid #d5e3dc; border-radius: 6px; color: #304e40; background: #fcfdfc; font: inherit; font-size: 12px; }
.analytics-filters input:focus { border-color: #7cab93; outline: 3px solid rgb(80 138 106 / 9%); }
.analytics-filters .el-input-number { width: 100%; }
.analytics-filters .el-button { min-height: 40px; }
.report-context { display: flex; flex-wrap: wrap; gap: 8px 22px; margin-top: 18px; padding-top: 15px; border-top: 1px solid var(--analytics-line); color: var(--analytics-muted); font-size: 11px; line-height: 1.7; }
.analytics-alert { margin-top: 14px; }
.metric-section { margin-top: 30px; padding-top: 24px; border-top: 1px solid var(--analytics-line); }
.section-heading { display: grid; grid-template-columns: minmax(240px,.85fr) minmax(280px,1fr); gap: 24px; align-items: end; margin-bottom: 19px; }
.section-heading > div { display: flex; align-items: baseline; gap: 13px; }
.section-index { color: #84a393; font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.section-heading h2 { margin: 0; color: var(--analytics-ink); font-size: 17px; font-weight: 680; }
.section-heading p { margin: 0; color: var(--analytics-muted); font-size: 11px; line-height: 1.8; }
.metric-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); margin: 0; border-top: 1px solid var(--analytics-line); border-left: 1px solid var(--analytics-line); }
.metric-item { min-width: 0; padding: 16px 17px; border-right: 1px solid var(--analytics-line); border-bottom: 1px solid var(--analytics-line); background: #fbfdfc; }
.metric-item dt { color: #6d8479; font-size: 11px; }
.metric-item dd { margin: 9px 0 0; color: var(--analytics-ink); font-size: 23px; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.metric-item .metric-detail { margin-top: 7px; color: var(--analytics-muted); font-size: 10px; font-weight: 400; line-height: 1.7; font-variant-numeric: normal; }
.funnel-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 26px; }
.funnel-item { padding: 21px 22px; border-top: 2px solid var(--analytics-accent); background: #f4f8f5; }
.funnel-label { display: block; color: #527a65; font-size: 12px; font-weight: 650; }
.funnel-item strong { display: block; margin: 12px 0 10px; color: #235b43; font-size: 30px; font-weight: 650; font-variant-numeric: tabular-nums; }
.funnel-item p { margin: 0; color: #486b58; font-size: 12px; line-height: 1.8; }
.funnel-item small { display: block; margin-top: 10px; color: #7b9181; font-size: 10px; line-height: 1.8; }
@media (max-width: 1120px) { .metric-grid { grid-template-columns: repeat(3,minmax(0,1fr)); } .section-heading { grid-template-columns: 1fr; gap: 9px; } }
@media (max-width: 850px) { .analytics-filters { grid-template-columns: repeat(2,minmax(0,1fr)); } .analytics-filters .el-button { width: 100%; } .metric-grid { grid-template-columns: repeat(2,minmax(0,1fr)); } .funnel-grid { grid-template-columns: 1fr; gap: 18px; } }
@media (max-width: 520px) { .analytics-filters { grid-template-columns: 1fr; } .metric-grid { grid-template-columns: 1fr; } .metric-item { padding: 14px; } .report-context { flex-direction: column; gap: 3px; } }
</style>
