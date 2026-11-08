import { describe, expect, it } from 'vitest'
import type {
  CampaignAnalyticsRow,
  OperationsInstitutionRow,
  OperationsMetrics,
  PerformanceDailyItem,
} from '../src/api/report'
import {
  campaignOption,
  fulfillmentOption,
  institutionOption,
  trendOption,
} from '../src/views/dashboard/dashboardOptions'

function metrics(): OperationsMetrics {
  return {
    contactCustomerCount: 0, contactEventCount: 0, paidOrderCount: 3, confirmedAppointmentCount: 8,
    serviceCompletedCount: 6, checkedInCustomerCount: 2, checkedInAppointmentCount: 4,
    redemptionBatchCount: 1, redeemedCustomerCount: 1, redeemedCount: 5, refundCount: 1,
    paidFen: 123456, platformPaidFen: 123456, institutionPaidFen: 0,
    refundFen: 2345, platformRefundFen: 2345, institutionRefundFen: 0,
    netPaidFen: 121111, platformNetPaidFen: 121111, institutionNetPaidFen: 0,
  }
}

function institution(id: number, redeemedCount: number): OperationsInstitutionRow {
  return {
    institutionId: id, institutionName: `机构${id}`, confirmedAppointmentCount: 0,
    serviceCompletedCount: 0, checkedInCustomerCount: 0, checkedInAppointmentCount: 0,
    redemptionBatchCount: 0, redeemedCustomerCount: 0, redeemedCount,
    pendingReconciliationFen: null, pendingReconciliationStatus: 'PENDING_VERIFICATION', conversionRates: {},
  }
}

function campaign(id: number, netPaidFen: number): CampaignAnalyticsRow {
  return {
    campaignId: id, campaignBatchId: id * 10, campaignCode: `C${id}`, campaignBatchCode: `B${id}`,
    campaignName: `活动${id}`, campaignBatchName: `批次${id}`, paidOrderCount: 1, refundCount: 0,
    confirmedAppointmentCount: 0, checkedInCustomerCount: 0, checkedInAppointmentCount: 0,
    redemptionBatchCount: 0, redeemedCount: 0, paidFen: netPaidFen,
    platformPaidFen: netPaidFen, institutionPaidFen: 0, refundFen: 0,
    platformRefundFen: 0, institutionRefundFen: 0, netPaidFen,
    platformNetPaidFen: netPaidFen, institutionNetPaidFen: 0,
    touchCustomerCount: null, touchStatus: 'SOURCE_NOT_AVAILABLE', conversionRates: {}, sourceWatermark: null,
  }
}

function daily(statDate: string, paidFen: number, refundFen: number): PerformanceDailyItem {
  return { statDate, paidOrderCount: 3, refundCount: 1, paidFen, refundFen, netPaidFen: paidFen - refundFen }
}

describe('运营大屏图表数据映射', () => {
  it('日趋势保留真实日期、整数分换算和订单数，不补无事实日期', () => {
    const option = trendOption([daily('2026-10-18', 123456, 0), daily('2026-10-20', 0, 2345)])
    const xAxis = option.xAxis as { data: string[] }
    const series = option.series as Array<{ data: number[] }>

    expect(xAxis.data).toEqual(['10-18', '10-20'])
    expect(series[0]?.data).toEqual([1234.56, 0])
    expect(series[1]?.data).toEqual([0, 23.45])
    expect(series[2]?.data).toEqual([3, 3])
    expect(xAxis.data).not.toContain('10-19')
    expect(option.textStyle).toMatchObject({ fontSize: 14 })
  })

  it('非法日金额不进入图表，阶段图只展示真实计数', () => {
    const empty = trendOption([{ ...daily('2026-10-18', Number.NaN, 0) }])
    expect((empty.xAxis as { data: string[] }).data).toEqual([])

    const option = fulfillmentOption(metrics())
    expect((option.yAxis as { data: string[] }).data).toEqual(['确认预约', '到院客户', '服务完成', '正式核销'])
    expect((option.series as Array<{ data: number[] }>)[0]?.data).toEqual([8, 2, 6, 5])
    expect(option.aria).toMatchObject({ description: expect.not.stringContaining('百分比') })
  })

  it('机构按核销次数排序取前五且不修改原数组', () => {
    const rows = [institution(1, 2), institution(2, 9), institution(3, 5), institution(4, 1), institution(5, 8), institution(6, 4)]
    const option = institutionOption(rows)

    expect((option.yAxis as { data: string[] }).data).toEqual(['机构2', '机构5', '机构3', '机构6', '机构1'])
    expect((option.series as Array<{ data: number[] }>)[0]?.data).toEqual([9, 8, 5, 4, 2])
    expect(rows[0]?.institutionId).toBe(1)
  })

  it('活动按净支付排序取前五，名称使用成交快照', () => {
    const option = campaignOption([campaign(1, 100), campaign(2, 90000), campaign(3, 500), campaign(4, -100), campaign(5, 800), campaign(6, 400)])

    expect((option.yAxis as { data: string[] }).data).toEqual([
      '活动2 · 批次2', '活动5 · 批次5', '活动3 · 批次3', '活动6 · 批次6', '活动1 · 批次1',
    ])
    expect((option.series as Array<{ data: number[] }>)[0]?.data).toEqual([900, 8, 5, 4, 1])
  })
})
