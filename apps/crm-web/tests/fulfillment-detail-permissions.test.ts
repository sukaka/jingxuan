import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import type { Appointment, AppointmentDetail, RedemptionDetail } from '../src/api/fulfillment'
import AppointmentDetailDrawer from '../src/views/fulfillment/AppointmentDetailDrawer.vue'
import RedemptionDetailDrawer from '../src/views/fulfillment/RedemptionDetailDrawer.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

function appointment(status: Appointment['status']): Appointment {
  return {
    id: 501,
    appointmentNo: 'A000000000501',
    customerId: 42,
    institutionId: 91,
    ownerStaffId: 9,
    teamId: 1,
    status,
    scheduledAt: '2026-10-25T02:00:00Z',
    remark: null,
    createdBy: 9,
    createdAt: '2026-10-24T01:00:00Z',
    version: 2,
  }
}

function detail(status: Appointment['status']): AppointmentDetail {
  return {
    appointment: appointment(status),
    items: [{ id: 601, entitlementId: 81, projectId: 101, plannedCount: 2, postedCount: 0 }],
    history: [],
    redemptions: [],
  }
}

async function mountAppointment(status: Appointment['status'], permissions: { canWrite: boolean; canConfirm: boolean; canPost: boolean }) {
  const wrapper = mount(AppointmentDetailDrawer, {
    props: { modelValue: true, detail: detail(status), ...permissions },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
}

describe('AppointmentDetailDrawer 状态机与角色权限', () => {
  it('FRONTDESK 可确认服务完成但看不到最终核销', async () => {
    await mountAppointment('CHECKED_IN', { canWrite: false, canConfirm: true, canPost: false })
    expect(document.body.querySelector('[data-testid="appointment-service-done"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).toBeNull()
  })

  it('管家即使可读服务完成预约也不能直接正式核销', async () => {
    await mountAppointment('SERVICE_DONE', { canWrite: true, canConfirm: false, canPost: false })
    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).toBeNull()
  })

  it('OPERATOR 只有服务完成后才看到最终核销，CHECKED_IN 不可绕过', async () => {
    await mountAppointment('SERVICE_DONE', { canWrite: false, canConfirm: true, canPost: true })
    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).not.toBeNull()
    while (wrappers.length) wrappers.pop()?.unmount()
    document.body.innerHTML = ''
    await mountAppointment('CHECKED_IN', { canWrite: false, canConfirm: true, canPost: true })
    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).toBeNull()
  })
})

describe('RedemptionDetailDrawer 原核销只读', () => {
  it('POSTED 保持原事实，冲正是独立申请而不是编辑', async () => {
    const detail: RedemptionDetail = {
      redemption: {
        id: 701,
        appointmentId: 501,
        customerId: 42,
        institutionId: 91,
        ownerStaffId: 9,
        teamId: 1,
        serviceAt: '2026-10-25T02:00:00Z',
        evidenceRef: 'SERVICE-EVIDENCE-TEST',
        voucherFileId: null,
        status: 'POSTED',
        settlementStatus: 'PENDING_VERIFICATION',
        idempotencyKey: 'redemption-test-key',
        postedBy: 88,
        postedAt: '2026-10-25T03:00:00Z',
        createdAt: '2026-10-25T03:00:00Z',
        version: 0,
      },
      lines: [{ id: 702, redemptionId: 701, appointmentItemId: 601, entitlementId: 81, projectId: 101, count: 2, createdAt: '2026-10-25T03:00:00Z' }],
      history: [{ id: 703, redemptionId: 701, fromStatus: 'PENDING_INSTITUTION', toStatus: 'POSTED', actorUserId: 88, reason: '机构确认', createdAt: '2026-10-25T03:00:00Z' }],
    }
    const wrapper = mount(RedemptionDetailDrawer, {
      props: { modelValue: true, detail, canReverse: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(document.body.textContent).toContain('机构正式核销')
    expect(document.body.textContent).toContain('不可直接编辑的原始核销事实')
    expect(document.body.textContent).toContain('待核实')
    expect(document.body.textContent).toContain('待机构确认 → 机构正式核销')
    expect(document.body.textContent).not.toContain('PENDING_VERIFICATION')
    expect(document.body.textContent).not.toContain('0.00')
    expect(document.body.textContent).toContain('项目 #101')
    expect(document.body.querySelector('[data-testid="redemption-request-reversal"]')).not.toBeNull()
    expect(document.body.textContent).not.toContain('编辑原核销')
  })
})
