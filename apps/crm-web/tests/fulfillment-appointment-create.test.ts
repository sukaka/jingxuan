import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Institution } from '../src/api/catalog'
import { listInstitutions } from '../src/api/catalog'
import type { CustomerListItem } from '../src/api/customer'
import { listCustomers } from '../src/api/customer'
import type { AppointmentDetail } from '../src/api/fulfillment'
import { createAppointment, getAppointmentOptions } from '../src/api/fulfillment'
import AppointmentCreateDrawer from '../src/views/fulfillment/AppointmentCreateDrawer.vue'

vi.mock('../src/api/customer', () => ({ listCustomers: vi.fn() }))
vi.mock('../src/api/catalog', () => ({ listInstitutions: vi.fn() }))
vi.mock('../src/api/fulfillment', () => ({
  createAppointment: vi.fn(),
  getAppointmentOptions: vi.fn(),
}))
vi.mock('../src/views/fulfillment/fulfillmentUi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/views/fulfillment/fulfillmentUi')>()
  return { ...actual, createFulfillmentIdempotencyKey: vi.fn(() => 'appointment-create-fixed') }
})

const customer: CustomerListItem = {
  id: 42,
  customerNo: 'C0000000042',
  displayName: '测试客户',
  nickname: null,
  mobileMasked: null,
  cityCode: null,
  customerStatus: 'ACTIVE',
  tierCode: null,
  intentLevel: null,
  ownerStaffId: 9,
  ownerName: '测试管家',
  teamId: 1,
  pointsCached: 0,
  lastFollowupAt: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 0,
}

const institution: Institution = {
  id: 91,
  institutionNo: 'INST-TEST-91',
  fullName: '测试合作机构',
  shortName: '测试机构',
  cityCode: null,
  status: 'ACTIVE',
  version: 0,
}

const savedDetail: AppointmentDetail = {
  appointment: {
    id: 501,
    appointmentNo: 'A000000000501',
    customerId: 42,
    institutionId: 91,
    ownerStaffId: 9,
    teamId: 1,
    status: 'PENDING_INSTITUTION',
    scheduledAt: '2026-10-25T02:00:00.000Z',
    remark: null,
    createdBy: 9,
    createdAt: '2026-10-24T01:00:00Z',
    version: 0,
  },
  items: [{ id: 601, entitlementId: 81, projectId: 101, plannedCount: 2, postedCount: 0 }],
  history: [],
  redemptions: [],
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

async function choose(wrapper: VueWrapper, testId: string, id: number) {
  const target = component(wrapper, testId)
  target.vm.$emit('update:modelValue', id)
  target.vm.$emit('change', id)
  await flushPromises()
}

describe('AppointmentCreateDrawer 真实远程选项与逐项目预约', () => {
  it('仅传 customerId 获取选项，按机构过滤后创建逐项目预约', async () => {
    vi.mocked(listCustomers).mockResolvedValue({ items: [customer], total: 1, page: 1, size: 20 })
    vi.mocked(listInstitutions).mockResolvedValue({ items: [institution], total: 1, page: 1, size: 20 })
    vi.mocked(getAppointmentOptions).mockResolvedValue({
      customerId: 42,
      institutions: [{ id: 91, fullName: '测试合作机构', shortName: '测试机构' }],
      entitlements: [
        { id: 81, orderId: 71, projectId: 101, projectName: '测试项目', availableCount: 6, validFrom: null, validTo: null, institutionIds: [91], allocationStatus: 'PENDING_Q02' },
        { id: 82, orderId: 71, projectId: 102, projectName: '其他机构项目', availableCount: 6, validFrom: null, validTo: null, institutionIds: [92], allocationStatus: 'PENDING_Q02' },
      ],
    })
    vi.mocked(createAppointment).mockResolvedValue(savedDetail)
    const wrapper = mount(AppointmentCreateDrawer, {
      props: { modelValue: false },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(listCustomers).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined })
    expect(listInstitutions).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined, status: 'ACTIVE' })
    await choose(wrapper, 'appointment-customer-select', 42)
    await choose(wrapper, 'appointment-institution-select', 91)
    expect(getAppointmentOptions).toHaveBeenCalledWith(42)

    await component(wrapper, 'appointment-entitlement-select').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('测试项目')
    expect(document.body.textContent).not.toContain('其他机构项目')

    await choose(wrapper, 'appointment-entitlement-select', 81)
    component(wrapper, 'appointment-planned-count').vm.$emit('update:modelValue', 2)
    wrapper.getComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', new Date('2026-10-25T02:00:00.000Z'))
    await flushPromises()
    await wrapper.get('[data-testid="appointment-create-submit"]').trigger('click')
    await flushPromises()

    expect(createAppointment).toHaveBeenCalledWith(expect.objectContaining({
      customerId: 42,
      institutionId: 91,
      scheduledAt: '2026-10-25T02:00:00.000Z',
      items: [{ entitlementId: 81, count: 2 }],
    }), 'appointment-create-fixed')
  })
})
