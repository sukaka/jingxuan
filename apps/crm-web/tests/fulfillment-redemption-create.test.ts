import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AppointmentDetail, RedemptionDetail } from '../src/api/fulfillment'
import { createRedemption, getAppointmentOptions } from '../src/api/fulfillment'
import { ApiError } from '../src/types/api'
import RedemptionCreateDrawer from '../src/views/fulfillment/RedemptionCreateDrawer.vue'

vi.mock('../src/api/fulfillment', () => ({
  createRedemption: vi.fn(),
  getAppointmentOptions: vi.fn(),
}))
vi.mock('../src/views/fulfillment/fulfillmentUi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/views/fulfillment/fulfillmentUi')>()
  return { ...actual, createFulfillmentIdempotencyKey: vi.fn(() => 'redemption-post-fixed') }
})

const detail: AppointmentDetail = {
  appointment: {
    id: 501,
    appointmentNo: 'A000000000501',
    customerId: 42,
    institutionId: 91,
    ownerStaffId: 9,
    teamId: 1,
    status: 'SERVICE_DONE',
    scheduledAt: '2026-10-25T02:00:00Z',
    remark: null,
    createdBy: 9,
    createdAt: '2026-10-24T01:00:00Z',
    version: 3,
  },
  items: [{ id: 601, entitlementId: 81, projectId: 101, plannedCount: 2, postedCount: 0 }],
  history: [],
  redemptions: [],
}

const postedDetail: RedemptionDetail = {
  redemption: {
    id: 701,
    appointmentId: 501,
    customerId: 42,
    institutionId: 91,
    ownerStaffId: 9,
    teamId: 1,
    serviceAt: '2026-10-25T03:00:00.000Z',
    evidenceRef: 'SERVICE-EVIDENCE-TEST',
    voucherFileId: null,
    status: 'POSTED',
    settlementStatus: 'PENDING_VERIFICATION',
    idempotencyKey: 'redemption-post-fixed',
    postedBy: 88,
    postedAt: '2026-10-25T03:01:00.000Z',
    createdAt: '2026-10-25T03:01:00.000Z',
    version: 0,
  },
  lines: [{ id: 702, redemptionId: 701, appointmentItemId: 601, entitlementId: 81, projectId: 101, count: 2, createdAt: '2026-10-25T03:01:00.000Z' }],
  history: [],
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

function component(wrapper: VueWrapper, testId: string): VueWrapper {
  return wrapper.getComponent(`[data-testid="${testId}"]`) as VueWrapper
}

async function clickSubmit() {
  const button = document.body.querySelector<HTMLButtonElement>('[data-testid="redemption-submit"]')
  expect(button).not.toBeNull()
  button!.click()
  await flushPromises()
}

describe('RedemptionCreateDrawer 机构正式核销', () => {
  it('二次确认真实服务时间与项目行，失败重试固定同一幂等键', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.mocked(getAppointmentOptions).mockResolvedValue({
      customerId: 42,
      institutions: [{ id: 91, fullName: '测试合作机构', shortName: '测试机构' }],
      entitlements: [
        { id: 81, orderId: 71, projectId: 101, projectName: '测试项目', availableCount: 6, validFrom: null, validTo: null, institutionIds: [91], allocationStatus: 'PENDING_Q02' },
        { id: 999, orderId: 71, projectId: 999, projectName: '非本预约项目', availableCount: 6, validFrom: null, validTo: null, institutionIds: [91], allocationStatus: 'PENDING_Q02' },
      ],
    })
    vi.mocked(createRedemption)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '测试网络失败' }))
      .mockResolvedValueOnce(postedDetail)
    const wrapper = mount(RedemptionCreateDrawer, {
      props: { modelValue: false, detail },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(getAppointmentOptions).toHaveBeenCalledWith({ appointmentId: 501 })
    component(wrapper, 'redemption-entitlement-select').vm.$emit('update:modelValue', 81)
    component(wrapper, 'redemption-count').vm.$emit('update:modelValue', 2)
    wrapper.getComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', new Date('2026-10-25T03:00:00.000Z'))
    const evidence = document.body.querySelector<HTMLInputElement>('input[data-testid="redemption-evidence"], [data-testid="redemption-evidence"] input')
    expect(evidence).not.toBeNull()
    evidence!.value = 'SERVICE-EVIDENCE-TEST'
    evidence!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    await clickSubmit()
    await clickSubmit()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(2)
    const confirmationText = String(vi.mocked(ElMessageBox.confirm).mock.calls[0]?.[0])
    expect(confirmationText).toContain('测试项目 2 次')
    expect(confirmationText).toContain('实际服务时间')
    expect(confirmationText).toContain('SERVICE-EVIDENCE-TEST')
    expect(confirmationText).not.toContain('POSTED')
    expect(createRedemption).toHaveBeenCalledTimes(2)
    expect(createRedemption).toHaveBeenNthCalledWith(1, 501, expect.objectContaining({
      appointmentVersion: 3,
      serviceAt: '2026-10-25T03:00:00.000Z',
      evidenceRef: 'SERVICE-EVIDENCE-TEST',
      lines: [{ entitlementId: 81, count: 2 }],
    }), 'redemption-post-fixed')
    expect(createRedemption).toHaveBeenNthCalledWith(2, 501, expect.objectContaining({
      appointmentVersion: 3,
      lines: [{ entitlementId: 81, count: 2 }],
    }), 'redemption-post-fixed')
  })

  it('OPERATOR 通过 appointmentId 加载本预约批次，失败保留提示且可重试', async () => {
    vi.mocked(getAppointmentOptions).mockReset()
    vi.mocked(getAppointmentOptions)
      .mockRejectedValueOnce(new ApiError({ status: 403, code: 'FORBIDDEN', message: '机构选项读取失败测试' }))
      .mockResolvedValueOnce({
        customerId: 42,
        institutions: [{ id: 91, fullName: '测试合作机构', shortName: '测试机构' }],
        entitlements: [
          { id: 81, orderId: 71, projectId: 101, projectName: '测试项目', availableCount: 6, validFrom: null, validTo: null, institutionIds: [91], allocationStatus: 'PENDING_Q02' },
        ],
      })
    const wrapper = mount(RedemptionCreateDrawer, {
      props: { modelValue: false, detail },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(getAppointmentOptions).toHaveBeenCalledWith({ appointmentId: 501 })
    expect(document.body.textContent).toContain('权益批次加载失败：机构选项读取失败测试')

    await wrapper.get('[data-testid="redemption-options-retry"]').trigger('click')
    await flushPromises()
    expect(getAppointmentOptions).toHaveBeenCalledTimes(2)
    expect(getAppointmentOptions).toHaveBeenNthCalledWith(2, { appointmentId: 501 })
    expect(document.body.textContent).not.toContain('权益批次加载失败：')

    await component(wrapper, 'redemption-entitlement-select').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('测试项目')
    expect(document.body.textContent).toContain('批次 #81')
  })
})
