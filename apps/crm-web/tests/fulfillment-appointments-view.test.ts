import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Appointment, AppointmentDetail } from '../src/api/fulfillment'
import { checkInAppointment, getAppointment, getRedemption, listAppointments, listRedemptions } from '../src/api/fulfillment'
import { getCustomer } from '../src/api/customer'
import { listInstitutions, listProjects } from '../src/api/catalog'
import { useAuthStore } from '../src/stores/auth'
import AppointmentsView from '../src/views/AppointmentsView.vue'

vi.mock('../src/api/fulfillment', () => ({
  listAppointments: vi.fn(),
  createAppointment: vi.fn(),
  getAppointment: vi.fn(),
  confirmAppointment: vi.fn(),
  rejectAppointment: vi.fn(),
  cancelAppointment: vi.fn(),
  checkInAppointment: vi.fn(),
  completeAppointmentService: vi.fn(),
  getAppointmentOptions: vi.fn(),
  createRedemption: vi.fn(),
  listRedemptions: vi.fn(),
  getRedemption: vi.fn(),
}))
vi.mock('../src/api/customer', () => ({ listCustomers: vi.fn(), getCustomer: vi.fn() }))
vi.mock('../src/api/catalog', () => ({ listInstitutions: vi.fn(), listProjects: vi.fn() }))

function makeUser(permissions: string[], institutionRole = 'FRONTDESK'): AuthUser {
  return {
    userId: 88,
    username: 'institution-role-test',
    displayName: '机构角色测试',
    userType: 'INSTITUTION',
    roles: [institutionRole],
    permissions,
    institutionRoles: { 91: institutionRole },
  }
}

function makeDetail(status: Appointment['status']): AppointmentDetail {
  return {
    appointment: {
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
    },
    items: [{ id: 601, entitlementId: 81, projectId: 101, plannedCount: 2, postedCount: 0 }],
    history: [],
    redemptions: [],
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function mountView(user: AuthUser) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(AppointmentsView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

async function openFirstDetail(wrapper: VueWrapper) {
  const detailButton = wrapper.findAll('button').find((button) => button.text() === '修改')
  expect(detailButton).toBeDefined()
  vi.mocked(getAppointment).mockClear()
  await detailButton!.trigger('click')
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(getAppointment).toHaveBeenCalledWith(501)
}

describe('AppointmentsView 机构履约状态与权限', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listAppointments).mockResolvedValue({ items: [makeDetail('CONFIRMED').appointment], total: 1, page: 1, size: 20 })
    vi.mocked(getAppointment).mockImplementation(async () => makeDetail('CONFIRMED'))
    vi.mocked(listRedemptions).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
    vi.mocked(listInstitutions).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
    vi.mocked(listProjects).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
    vi.mocked(getCustomer).mockRejectedValue(new Error('FORBIDDEN'))
  })

  it('FRONTDESK 可确认实际到院并携带幂等键，但不能创建预约或看到最终核销', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.mocked(checkInAppointment).mockImplementation(async () => makeDetail('CHECKED_IN'))
    const wrapper = mountView(makeUser(['appointment:read', 'appointment:confirm']))
    await flushPromises()
    expect(wrapper.text()).toContain('项目 #101 × 2')
    expect(wrapper.text()).toContain('机构 #91')
    expect(getAppointment).toHaveBeenCalledWith(501)
    expect(getRedemption).not.toHaveBeenCalled()
    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons).not.toContain('新建预约')

    await openFirstDetail(wrapper)
    expect(document.body.querySelector('[data-testid="appointment-check-in"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).toBeNull()

    const checkInButton = document.body.querySelector<HTMLButtonElement>('[data-testid="appointment-check-in"]')
    expect(checkInButton).not.toBeNull()
    checkInButton!.click()
    await flushPromises()
    expect(checkInAppointment).toHaveBeenCalledWith(501, { version: 2, reason: '机构人工确认实际到院' }, expect.any(String))
    const key = vi.mocked(checkInAppointment).mock.calls[0]?.[2]
    expect(key).toMatch(/^appointment\.transition:/)
    expect(listAppointments).toHaveBeenCalledTimes(2)
  })

  it('状态视图通过后端状态参数查询，不过滤当前页冒充历史', async () => {
    const wrapper = mountView(makeUser(['appointment:read']))
    await flushPromises()
    vi.mocked(listAppointments).mockClear()
    vi.mocked(listAppointments).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })

    const statusFilter = wrapper.getComponent('.proto-toolbar .el-select') as VueWrapper
    statusFilter.vm.$emit('update:modelValue', 'CHECKED_IN')
    statusFilter.vm.$emit('change', 'CHECKED_IN')
    await flushPromises()

    expect(listAppointments).toHaveBeenCalledWith(expect.objectContaining({ page: 1, status: 'CHECKED_IN' }))
  })

  it('FRONTDESK 即使有 redemption:post 也不能在服务完成后看到最终核销', async () => {
    vi.mocked(listAppointments).mockResolvedValue({ items: [makeDetail('SERVICE_DONE').appointment], total: 1, page: 1, size: 20 })
    vi.mocked(getAppointment).mockImplementation(async () => makeDetail('SERVICE_DONE'))
    const wrapper = mountView(makeUser(['appointment:read', 'redemption:post'], 'FRONTDESK'))
    await flushPromises()
    await openFirstDetail(wrapper)

    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).toBeNull()
  })

  it('只有当前预约机构的 OPERATOR 且具备 redemption:post 才能看到最终核销', async () => {
    vi.mocked(listAppointments).mockResolvedValue({ items: [makeDetail('SERVICE_DONE').appointment], total: 1, page: 1, size: 20 })
    vi.mocked(getAppointment).mockImplementation(async () => makeDetail('SERVICE_DONE'))
    const wrapper = mountView(makeUser(['appointment:read', 'redemption:post'], 'OPERATOR'))
    await flushPromises()
    await openFirstDetail(wrapper)

    expect(document.body.querySelector('[data-testid="appointment-final-redemption"]')).not.toBeNull()
  })
})
