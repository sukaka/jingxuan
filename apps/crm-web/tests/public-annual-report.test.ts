import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../src/router'
import PublicAnnualReportView from '../src/views/PublicAnnualReportView.vue'
import type { PublicAnnualReport } from '../src/api/annualReport'

const {
  getPublicMock,
  getMyMock,
  telemetryMock,
  myTelemetryMock,
  bookingMock,
  smsSendMock,
  smsVerifyMock,
  routeState,
} = vi.hoisted(() => ({
  getPublicMock: vi.fn(),
  getMyMock: vi.fn(),
  telemetryMock: vi.fn().mockResolvedValue(undefined),
  myTelemetryMock: vi.fn().mockResolvedValue(undefined),
  bookingMock: vi.fn(),
  smsSendMock: vi.fn(),
  smsVerifyMock: vi.fn(),
  routeState: {
    name: 'public-annual-report',
    params: { shareKey: 'B'.repeat(43) } as Record<string, string>,
    query: {} as Record<string, string>,
  },
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => routeState,
  }
})

vi.mock('../src/api/annualReport', async () => {
  const actual = await vi.importActual<typeof import('../src/api/annualReport')>('../src/api/annualReport')
  return {
    ...actual,
    getPublicAnnualReport: getPublicMock,
    getMyPublicAnnualReport: getMyMock,
    sendPublicAnnualTelemetry: telemetryMock,
    sendMyPublicAnnualTelemetry: myTelemetryMock,
    submitPublicAnnualBookingIntent: bookingMock,
    sendPublicAnnualSmsCode: smsSendMock,
    verifyPublicAnnualSmsCode: smsVerifyMock,
  }
})

const SAMPLE_REPORT: PublicAnnualReport = {
  reportYear: 2026,
  memberNo: 'NO. CUS-001',
  displayName: '林清晚',
  tierLabel: '臻选 S 级缪斯',
  sealText: '臻选S',
  butlerName: '安安',
  firstMeetDate: '2024.05.12',
  daysTogether: 928,
  orderCount: 2,
  netSpendFen: 588000,
  categoryCount: 2,
  redeemCount: 3,
  institutionCount: 1,
  butlerResponseCount: 6,
  toneCode: 'POETIC',
  toneLabel: '诗意松弛风',
  toneReason: '自动匹配',
  copy: {
    salutation: '致 林清晚',
    coverSubTitle: '2026 年度私享纸雕灯箱',
    igniteBtnText: '轻触点亮你的 2026 光采灯箱',
    igniteHint: '仅本人可见',
    firstMeetNote: '相伴 928 天',
    coverNextBtn: '推开四季光影',
    scene1Title: '一整年，你把光留在了这些日子',
    scene2Title: '整座城睡着时，有一扇窗还亮着',
    midnightStory: '深夜下单时刻',
    scene3Title: '年度本命项目',
    favDesc: '超光子陪伴',
    scene4Title: '履约蜕变与权益宝库',
    vaultBadge: '剩余权益',
    vaultCta: '一键通知专属管家 安安 预约',
    personaTitle: '午夜发光收藏家',
    personaQuote: '把松弛感写进每一次焕亮',
    butlerSign: '专属管家 安安 · 敬寄',
    posterHeading: '林清晚 的 2026 年度美学人格',
  },
  midnight: {
    time: '23:47',
    date: '2026.07.18',
    orderTitle: '夏日超光子季卡',
    eventType: 'CRM / 有赞实付下单时刻',
    peakLabel: '高频活跃时段：20:00–02:00',
    hourDistribution: [{ hour: 23.78, weight: 1, latest: true, label: '23:47 下单' }],
  },
  favorite: {
    projectName: 'M22 黄金超光子',
    beatPercent: '高频挚爱',
    tags: ['光电焕亮'],
    dimensions: [{ name: '光电焕亮', percent: 92 }],
    top3: [{ rank: '01', name: 'M22 黄金超光子', countText: '3 次' }],
  },
  remainingEntitlements: {
    totalCount: 2,
    expireDate: '2027.06.30',
    items: [{ name: 'M22 黄金超光子', subtitle: '请联系专属管家预约', countText: '余 2 次' }],
  },
  orders: [
    {
      maskedOrderNo: 'E20260****8421',
      date: '2026.07.18',
      md: '07.18',
      time: '23:47',
      season: '夏',
      title: '夏日超光子季卡',
      category: '光电焕亮',
      amountFen: 588000,
      statusText: '余 2 次待约',
      open: true,
      channel: '有赞官方商城',
      skus: [{ name: 'M22 黄金超光子', qtyText: '3 次' }],
    },
  ],
}

describe('PublicAnnualReportView & routing', () => {
  beforeEach(() => {
    routeState.name = 'public-annual-report'
    routeState.params = { shareKey: 'B'.repeat(43) }
    routeState.query = {}
    getPublicMock.mockReset()
    getMyMock.mockReset()
    telemetryMock.mockClear()
    myTelemetryMock.mockClear()
    bookingMock.mockReset()
    smsSendMock.mockReset()
    smsVerifyMock.mockReset()
  })

  it('registers public shareKey H5, unified entry H5, and protected management routes', () => {
    const entryRoute = routes.find((r) => r.name === 'public-annual-report-entry')
    expect(entryRoute?.path).toBe('/h5/annual-reports/entry')
    expect(entryRoute?.meta?.public).toBe(true)

    const publicRoute = routes.find((r) => r.name === 'public-annual-report')
    expect(publicRoute?.path).toBe('/h5/annual-reports/:shareKey')
    expect(publicRoute?.meta?.public).toBe(true)

    const adminRoute = routes.find((r) => r.name === 'annual-reports')
    expect(adminRoute?.path).toBe('/customers/annual-reports')
    expect(adminRoute?.meta?.permission).toBe('customer:read')
  })

  it('loads real CRM snapshot, sends telemetry, toggles privacy, and submits butler booking intent', async () => {
    getPublicMock.mockResolvedValue(SAMPLE_REPORT)
    bookingMock.mockResolvedValue({
      submitted: true,
      alreadyRequested: false,
      butlerName: '安安',
      message: '已向专属管家 安安 推送预约提醒',
    })

    const wrapper = mount(PublicAnnualReportView)
    await flushPromises()

    expect(wrapper.text()).toContain('林清晚')
    expect(wrapper.text()).toContain('午夜发光收藏家')
    expect(telemetryMock).toHaveBeenCalledWith('B'.repeat(43), expect.objectContaining({ eventType: 'SESSION_OPEN' }), false)

    await wrapper.get('[data-testid="ignite-lamp-btn"]').trigger('click')
    expect(wrapper.text()).toContain('928')

    expect(wrapper.get('[data-testid="annual-net-spend"]').text()).toBe('¥5,880')
    await wrapper.get('[data-testid="toggle-privacy-btn"]').trigger('click')
    expect(wrapper.get('[data-testid="annual-net-spend"]').text()).toBe('私享保密')

    await wrapper.get('[data-testid="book-butler-btn"]').trigger('click')
    await flushPromises()
    expect(bookingMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="booking-feedback-sheet"]').text()).toContain('已向专属管家 安安 推送预约提醒')

    wrapper.unmount()
  })

  it('renders error view cleanly for invalid shareKey', async () => {
    routeState.params = { shareKey: 'invalid-short' }
    const wrapper = mount(PublicAnnualReportView)
    await flushPromises()
    expect(wrapper.get('[data-testid="public-annual-error"]').text()).toContain('链接无效')
    wrapper.unmount()
  })

  it('supports unified entry auto-login and SMS fallback verification', async () => {
    routeState.name = 'public-annual-report-entry'
    routeState.params = {}
    routeState.query = { year: '2026' }
    getMyMock
      .mockResolvedValueOnce({
        authenticated: false,
        needPhoneBind: true,
        hasPendingWechat: true,
        wechatMpEnabled: true,
        smsEnabled: true,
        smsMockMode: true,
        hasOrders: false,
        authChannel: null,
        dataSource: null,
        shareKey: null,
        report: null,
      })
      .mockResolvedValueOnce({
        authenticated: true,
        needPhoneBind: false,
        hasPendingWechat: false,
        wechatMpEnabled: true,
        smsEnabled: true,
        smsMockMode: true,
        hasOrders: true,
        authChannel: 'SMS_VERIFY',
        dataSource: 'YOUZAN_READONLY_SNAPSHOT',
        shareKey: null,
        report: SAMPLE_REPORT,
      })
    smsSendMock.mockResolvedValue({
      sent: true,
      ttlSeconds: 300,
      cooldownSeconds: 60,
      mobileMasked: '138****8991',
      devPreviewCode: '123456',
    })
    smsVerifyMock.mockResolvedValue({
      authenticated: true,
      matchedProfile: true,
      wechatBound: true,
      displayName: '林清晚',
      message: '验证成功，已自动绑定当前微信',
    })

    const wrapper = mount(PublicAnnualReportView)
    await flushPromises()

    expect(wrapper.get('[data-testid="public-annual-auth-gate"]').text()).toContain('已识别当前微信')
    await wrapper.get('[data-testid="input-sms-mobile"]').setValue('13800138991')
    await wrapper.get('[data-testid="btn-sms-send"]').trigger('click')
    await flushPromises()
    expect(smsSendMock).toHaveBeenCalledWith('13800138991')

    await wrapper.get('[data-testid="input-sms-code"]').setValue('123456')
    await wrapper.get('[data-testid="btn-sms-verify"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('林清晚')
    expect(myTelemetryMock).toHaveBeenCalledWith(2026, expect.objectContaining({ eventType: 'SESSION_OPEN' }), false)
    wrapper.unmount()
  })
})
