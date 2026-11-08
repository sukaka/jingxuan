import type { EChartsOption } from 'echarts'
import type {
  CampaignAnalyticsRow,
  OperationsInstitutionRow,
  OperationsMetrics,
  PerformanceDailyItem,
} from '../../api/report'

const TEXT = '#24433b'
const MUTED = '#62766d'
const GRID = '#dde6df'
const TEAL = '#22876b'
const AMBER = '#b0792e'
const BLUE = '#557aa0'
const RED = '#b86352'

const base: EChartsOption = {
  backgroundColor: 'transparent',
  animation: false,
  textStyle: { color: TEXT, fontFamily: 'PingFang SC, Microsoft YaHei, sans-serif', fontSize: 14 },
  tooltip: { trigger: 'axis', confine: true },
}

function finite(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && Number.isFinite(value)
}

function yuan(value: number): number {
  return Number((value / 100).toFixed(2))
}

function horizontal(label: string, labels: string[], values: number[], money = false): EChartsOption {
  return {
    ...base,
    aria: { enabled: true, description: label },
    grid: { left: 12, right: 42, top: 16, bottom: 12, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: money ? undefined : 1,
      axisLabel: { color: MUTED, formatter: money ? '¥{value}' : '{value}' },
      splitLine: { lineStyle: { color: GRID } },
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: labels,
      axisLabel: { color: TEXT, width: 112, overflow: 'truncate' },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [{
      name: label,
      type: 'bar',
      data: values,
      barMaxWidth: 20,
      itemStyle: { color: money ? TEAL : BLUE, borderRadius: [0, 2, 2, 0] },
      label: { show: true, position: 'right', color: TEXT, formatter: money ? '¥{c}' : '{c}' },
    }],
  }
}

export function trendOption(items: PerformanceDailyItem[]): EChartsOption {
  const rows = items.filter((item) => item.statDate && finite(item.paidFen) && finite(item.refundFen))
  return {
    ...base,
    aria: { enabled: true, description: '按真实发生日展示支付金额、成功退款金额和支付订单数' },
    color: [TEAL, RED, AMBER],
    legend: { top: 0, right: 0, textStyle: { color: MUTED } },
    grid: { left: 16, right: 20, top: 48, bottom: 12, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((item) => item.statDate.slice(5)),
      axisLabel: { color: MUTED },
      axisLine: { lineStyle: { color: GRID } },
      axisTick: { show: false },
    },
    yAxis: [
      { type: 'value', name: '金额（元）', nameTextStyle: { color: MUTED }, axisLabel: { color: MUTED, formatter: '¥{value}' }, splitLine: { lineStyle: { color: GRID } } },
      { type: 'value', name: '订单（笔）', nameTextStyle: { color: MUTED }, minInterval: 1, axisLabel: { color: MUTED }, splitLine: { show: false } },
    ],
    series: [
      { name: '支付金额（元）', type: 'bar', data: rows.map((item) => yuan(item.paidFen)), barMaxWidth: 24 },
      { name: '成功退款（元）', type: 'bar', data: rows.map((item) => yuan(item.refundFen)), barMaxWidth: 24 },
      { name: '支付订单（笔）', type: 'line', yAxisIndex: 1, data: rows.map((item) => item.paidOrderCount), showSymbol: true, symbolSize: 6, lineStyle: { width: 2 } },
    ],
  }
}

export function fulfillmentOption(metrics: OperationsMetrics): EChartsOption {
  return horizontal('履约阶段真实计数，不表示转化率',
    ['确认预约', '到院客户', '服务完成', '正式核销'],
    [metrics.confirmedAppointmentCount, metrics.checkedInCustomerCount, metrics.serviceCompletedCount, metrics.redeemedCount])
}

export function institutionOption(rows: OperationsInstitutionRow[]): EChartsOption {
  const ranked = [...rows].sort((left, right) => right.redeemedCount - left.redeemedCount
    || (left.institutionId ?? Number.MAX_SAFE_INTEGER) - (right.institutionId ?? Number.MAX_SAFE_INTEGER)).slice(0, 5)
  return horizontal('机构正式核销次数',
    ranked.map((row) => row.institutionName || '机构名称待确认'),
    ranked.map((row) => row.redeemedCount))
}

export function campaignOption(rows: CampaignAnalyticsRow[]): EChartsOption {
  const ranked = [...rows].sort((left, right) => right.netPaidFen - left.netPaidFen
    || (left.campaignId ?? Number.MAX_SAFE_INTEGER) - (right.campaignId ?? Number.MAX_SAFE_INTEGER)
    || (left.campaignBatchId ?? -1) - (right.campaignBatchId ?? -1)).slice(0, 5)
  return horizontal('活动与批次净支付（元）',
    ranked.map((row) => `${row.campaignName || '活动名称待确认'}${row.campaignBatchName ? ` · ${row.campaignBatchName}` : ''}`),
    ranked.map((row) => yuan(row.netPaidFen)), true)
}
