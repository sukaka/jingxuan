import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, provide } from 'vue'
import type { CustomerTierEvaluation, CustomerTierReview, CustomerTierRule } from '../src/api/customerTier'
import {
  activateCustomerTierRule,
  createCustomerTierRule,
  listCustomerTierReviews,
  listCustomerTierRules,
  retireCustomerTierRule,
  reviewCustomerTierDowngrade,
  sampleCustomerTierRule,
  trialCustomerTierRule,
  updateCustomerTierRule,
} from '../src/api/customerTier'
import { useAuthStore } from '../src/stores/auth'
import CustomerTierReviewsView from '../src/views/CustomerTierReviewsView.vue'
import CustomerTierSettingsView from '../src/views/CustomerTierSettingsView.vue'

vi.mock('../src/api/customerTier', () => ({
  activateCustomerTierRule: vi.fn(),
  createCustomerTierRule: vi.fn(),
  listCustomerTierReviews: vi.fn(),
  listCustomerTierRules: vi.fn(),
  retireCustomerTierRule: vi.fn(),
  reviewCustomerTierDowngrade: vi.fn(),
  sampleCustomerTierRule: vi.fn(),
  trialCustomerTierRule: vi.fn(),
  updateCustomerTierRule: vi.fn(),
}))

const levels = [
  { tierCode: 'S', tierName: '核心', sortNo: 1, minScore: '0', minConsumptionFen: 10000 },
  { tierCode: 'A', tierName: '重要', sortNo: 2, minScore: '0', minConsumptionFen: 5000 },
  { tierCode: 'B', tierName: '成长', sortNo: 3, minScore: '0', minConsumptionFen: 1000 },
  { tierCode: 'C', tierName: '普通', sortNo: 4, minScore: '0', minConsumptionFen: 1 },
  { tierCode: 'D', tierName: '培育', sortNo: 5, minScore: '0', minConsumptionFen: 0 },
] as const

const rule: CustomerTierRule = {
  id: 3,
  ruleCode: 'TIER_VIEW',
  versionNo: 1,
  ruleName: '页面测试规则',
  timezone: 'Asia/Shanghai',
  status: 'DRAFT',
  levels: levels.map((level) => ({ ...level })),
  metrics: [],
  activatedAt: null,
  retiredAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  version: 0,
}

const evaluation: CustomerTierEvaluation = {
  snapshotId: 10,
  customerId: 7,
  ruleId: 3,
  asOf: '2026-01-01T00:00:00Z',
  windowStartInclusive: '2025-12-01T00:00:00Z',
  windowEndExclusive: '2026-01-01T00:00:00Z',
  timezone: 'Asia/Shanghai',
  paymentAmountFen: 12345,
  paidAmountFen: 1000000,
  refundAmountFen: 200000,
  actualConsumptionFen: 800000,
  orderCount: 2,
  manualFollowupCount: 1,
  archivedMessageCount: 3,
  communicationCount: 4,
  communicationComplete: true,
  score: null,
  suggestedTierCode: 'B',
  sourceTrigger: 'TRIAL',
}

const review: CustomerTierReview = {
  id: 8,
  customerId: 7,
  ruleId: 3,
  snapshotId: 10,
  fromTierCode: 'S',
  toTierCode: 'D',
  baseCustomerVersion: 4,
  status: 'PENDING_REVIEW',
  requestedBy: null,
  reviewedBy: null,
  reviewReason: null,
  reviewedAt: null,
  effectiveAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  version: 3,
}

function stubs() {
  return {
    teleport: true,
    ElTable: {
      name: 'ElTable',
      props: { data: { type: Array, default: () => [] } },
      setup(props: { data?: unknown[] }) {
        provide('tierRows', computed(() => props.data ?? []))
      },
      template: '<div><slot /><slot name="empty" /></div>',
    },
    ElTableColumn: {
      name: 'ElTableColumn',
      props: { prop: String, formatter: Function },
      inject: { tierRows: { default: () => [] } },
      template: `<div><template v-for="(row, index) in tierRows" :key="row.id ?? index"><slot :row="row" :index="index">{{ formatter ? formatter(row, {}, prop ? row[prop] : undefined, index) : (prop ? row[prop] : '') }}</slot></template></div>`,
    },
    ElDialog: {
      props: { modelValue: Boolean },
      template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
    },
    ElPagination: true,
    ElSelect: {
      name: 'ElSelect',
      props: { modelValue: { type: [String, Number], default: '' } },
      emits: ['update:modelValue', 'change'],
      template: `<select :value="modelValue" @change="$emit('update:modelValue', $event.target.value); $emit('change', $event.target.value)"><slot /></select>`,
    },
    ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  }
}

async function mountView(component: unknown) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1, username: 'admin', displayName: '管理员', userType: 'EMPLOYEE',
    roles: ['ADMIN'], permissions: ['customer:read', 'customer:write'],
  }
  const wrapper = mount(component, {
    global: { plugins: [ElementPlus, pinia], stubs: stubs() },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

async function selectRule(wrapper: VueWrapper, selected: CustomerTierRule = rule) {
  wrapper.findComponent({ name: 'ElTable' }).vm.$emit('current-change', selected)
  await flushPromises()
}

function getButton(wrapper: VueWrapper, text: string | RegExp) {
  const button = wrapper.findAll('button').find((item) => (
    typeof text === 'string' ? item.text() === text : text.test(item.text())
  ))
  if (!button) throw new Error(`未找到按钮：${text}`)
  return button
}

function getInputByValue(wrapper: VueWrapper, value: string) {
  const input = wrapper.findAll('input').find((item) => (item.element as HTMLInputElement).value === value)
  if (!input) throw new Error(`未找到值为 ${value} 的输入框`)
  return input
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listCustomerTierRules).mockResolvedValue([rule])
  vi.mocked(createCustomerTierRule).mockResolvedValue({ ...rule, id: 5, versionNo: 2 })
  vi.mocked(updateCustomerTierRule).mockResolvedValue(rule)
  vi.mocked(activateCustomerTierRule).mockResolvedValue({ ...rule, status: 'ACTIVE', version: 1 })
  vi.mocked(retireCustomerTierRule).mockResolvedValue({ ...rule, status: 'RETIRED', version: 1 })
  vi.mocked(trialCustomerTierRule).mockResolvedValue(evaluation)
  vi.mocked(sampleCustomerTierRule).mockResolvedValue([evaluation])
  vi.mocked(listCustomerTierReviews).mockResolvedValue({ items: [review], total: 1, page: 1, size: 20 })
  vi.mocked(reviewCustomerTierDowngrade).mockResolvedValue({ ...review, status: 'APPROVED', version: 4 })
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'info').mockImplementation(() => ({ close: vi.fn() }))
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('客户分级设置与复核页面', () => {
  it('版本列表用中文展示草稿、使用中和已停用状态', async () => {
    vi.mocked(listCustomerTierRules).mockResolvedValue([
      rule,
      { ...rule, id: 4, versionNo: 2, status: 'ACTIVE' },
      { ...rule, id: 5, versionNo: 3, status: 'RETIRED' },
    ])
    const wrapper = await mountView(CustomerTierSettingsView)

    const tableText = wrapper.findComponent({ name: 'ElTable' }).text()
    expect(tableText).toContain('草稿')
    expect(tableText).toContain('使用中')
    expect(tableText).toContain('已停用')
    expect(tableText).not.toMatch(/DRAFT|ACTIVE|RETIRED/)
  })

  it('业务文案只说明实际消费金额，不暴露评分配置或技术术语', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    const text = wrapper.text()
    for (const label of ['实际消费金额', '有效订单实付金额', '成功退款金额', '滚动近 12 个月']) {
      expect(text).toContain(label)
    }
    expect(text).not.toMatch(/DRAFT|ACTIVE|RETIRED|GTE|LTE|asOf|ISO-8601|激活|退役/)
    expect(text).not.toMatch(/PAYMENT_AMOUNT_FEN|PAID_AMOUNT_FEN|REFUND_AMOUNT_FEN|ORDER_COUNT|COMMUNICATION_COUNT/)
    expect(text).not.toMatch(/（分）|加权|权重|最低得分|添加指标/)
  })

  it('统计时区保留 Asia/Shanghai 值并使用中文选项和日期时间选择器', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)

    const timezone = wrapper.findAll('option').find((option) => option.text() === '北京时间（UTC+8）')
    expect(timezone?.attributes('value')).toBe('Asia/Shanghai')
    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    expect(picker.exists()).toBe(true)
    expect(picker.props('type')).toBe('datetime')
  })

  it('选择草稿后启用规则仍携带规则 version', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    const activateButton = wrapper.get('[data-testid="tier-activate-rule"]')
    expect(activateButton.text()).toBe('启用规则')
    await activateButton.trigger('click')
    await flushPromises()

    expect(activateCustomerTierRule).toHaveBeenCalledWith(3, 0, expect.any(String))
    expect(ElMessage.success).toHaveBeenCalledWith(expect.not.stringContaining('激活'))
    expect(wrapper.text()).not.toContain('任意表达式编辑器')
  })

  it('使用中的规则可停用且仍携带规则 version', async () => {
    const activeRule: CustomerTierRule = { ...rule, status: 'ACTIVE', activatedAt: '2026-01-02T00:00:00Z' }
    vi.mocked(listCustomerTierRules).mockResolvedValue([activeRule])
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper, activeRule)

    await getButton(wrapper, '停用规则').trigger('click')
    await flushPromises()

    expect(retireCustomerTierRule).toHaveBeenCalledWith(3, 0, expect.any(String))
    expect(ElMessage.success).toHaveBeenCalledWith(expect.not.stringContaining('退役'))
  })

  it('读取消费金额门槛时将分转换为两位小数的元', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    const values = wrapper.findAll('input').map((input) => (input.element as HTMLInputElement).value)
    expect(values).toEqual(expect.arrayContaining(['100.00', '50.00', '10.00', '0.01', '0.00']))
    expect(values).not.toContain('10000')
    expect(values).not.toContain('5000')
    expect(values).not.toContain('1000')
    expect(wrapper.text()).not.toMatch(/加权|权重|最低得分|添加指标/)
  })

  it('编辑金额门槛按字符串精确转换为整数分且不发送权重或指标门槛', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    await getInputByValue(wrapper, '100.00').setValue('123456789.01')
    await getInputByValue(wrapper, '50.00').setValue('0.29')
    await getInputByValue(wrapper, '10.00').setValue('10.00')
    await getInputByValue(wrapper, '0.01').setValue('0.01')
    await getInputByValue(wrapper, '0.00').setValue('0')
    await wrapper.get('[data-testid="tier-save-rule"]').trigger('click')
    await flushPromises()

    expect(updateCustomerTierRule).toHaveBeenCalledTimes(1)
    const call = vi.mocked(updateCustomerTierRule).mock.calls[0]
    expect(call?.[0]).toBe(3)
    expect(call?.[2]).toBe(0)
    expect(call?.[1].timezone).toBe('Asia/Shanghai')
    expect(call?.[1].metrics).toEqual([])
    expect(call?.[1].levels.map((level) => level.minConsumptionFen)).toEqual([
      12345678901, 29, 1000, 1, 0,
    ])
    expect(call?.[1].levels.every((level) => level.minScore === '0')).toBe(true)
    expect(ElMessage.success).toHaveBeenCalledWith(
      expect.not.stringMatching(/DRAFT|ACTIVE|RETIRED|激活|退役/),
    )
  })

  it.each(['', '1.001', '-0.01', '1e2', 'abc'])('拒绝无效金额门槛 %s 并说明金额格式', async (value) => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    await getInputByValue(wrapper, '100.00').setValue(value)
    await wrapper.get('[data-testid="tier-save-rule"]').trigger('click')
    await flushPromises()

    expect(updateCustomerTierRule).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith(expect.stringMatching(/金额|元/))
  })

  it('历史版本没有消费门槛时保持空白且不伪造金额', async () => {
    const legacyRule: CustomerTierRule = {
      ...rule,
      levels: rule.levels.map((level) => ({ ...level, minConsumptionFen: null })),
    }
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper, legacyRule)

    expect(wrapper.get('[data-testid="tier-min-consumption-S"]').text()).toBe('')
    expect((wrapper.get('[data-testid="tier-min-consumption-S"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).toContain('尚未配置消费金额门槛')
  })

  it('复制新版本后保存仍调用创建接口并保持元分往返一致', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    expect(wrapper.get('[data-testid="tier-new-rule"]').text()).toBe('新建规则')
    await selectRule(wrapper)

    await getButton(wrapper, /复制.*新版本/).trigger('click')
    expect(ElMessage.info).toHaveBeenCalledWith(expect.not.stringMatching(/DRAFT|激活|退役/))
    await wrapper.get('[data-testid="tier-save-rule"]').trigger('click')
    await flushPromises()

    expect(createCustomerTierRule).toHaveBeenCalledTimes(1)
    expect(updateCustomerTierRule).not.toHaveBeenCalled()
    const request = vi.mocked(createCustomerTierRule).mock.calls[0]?.[0]
    expect(request?.levels.map((level) => level.minConsumptionFen)).toEqual([10000, 5000, 1000, 1, 0])
    expect(request?.metrics).toEqual([])
  })

  it('单客户试算留空计算时间时展示实际消费金额和退款扣减结果', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    await wrapper.get('[data-testid="tier-trial-customer"]').setValue('7')
    await getButton(wrapper, /试算/).trigger('click')
    await flushPromises()

    expect(trialCustomerTierRule).toHaveBeenCalledWith(3, 7, undefined)
    const text = wrapper.text()
    expect(text).toContain('实际消费金额')
    expect(text).toContain('¥8000.00')
    expect(text).toContain('¥10000.00')
    expect(text).toContain('¥2000.00')
    expect(text).toContain('B 级')
    expect(text).not.toMatch(/支付流水（分）|实付（分）|退款（分）|加权分/)
  })

  it('抽样仍按 20 人调用且结果金额以元展示', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)
    await selectRule(wrapper)

    await getButton(wrapper, /抽样|抽取/).trigger('click')
    await flushPromises()

    expect(sampleCustomerTierRule).toHaveBeenCalledWith(3, 20)
    expect(wrapper.text()).toContain('¥8000.00')
  })

  it('批准降级携带复核单 version 和必填理由', async () => {
    const wrapper = await mountView(CustomerTierReviewsView)

    await wrapper.get('[data-testid="tier-review-approve-8"]').trigger('click')
    await wrapper.get('[data-testid="tier-review-reason"]').setValue('确认指标下降')
    await wrapper.get('[data-testid="tier-review-submit"]').trigger('click')
    await flushPromises()

    expect(reviewCustomerTierDowngrade).toHaveBeenCalledWith(8, 'approve', {
      version: 3, reason: '确认指标下降',
    }, expect.any(String))
  })

  it('新建规则自动填写代码和五级默认名称，名称用途清楚且仍可修改', async () => {
    const wrapper = await mountView(CustomerTierSettingsView)

    const code = wrapper.get('[data-testid="tier-rule-code"]').element as HTMLInputElement
    expect(code.value).toMatch(/^TIER_[A-Z0-9_]+$/)
    for (const [tier, name] of Object.entries({ S: '核心客户', A: '重要客户', B: '成长客户', C: '普通客户', D: '培育客户' })) {
      const input = wrapper.get(`[data-testid="tier-level-name-${tier}"]`).element as HTMLInputElement
      expect(input.value).toBe(name)
    }
    expect(wrapper.text()).toContain('名称仅用于页面显示')
    expect(wrapper.text()).toContain('不影响分级计算')
  })
})
