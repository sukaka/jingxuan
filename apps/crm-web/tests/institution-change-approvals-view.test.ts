import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InstitutionChangeRequest } from '../src/api/institution'
import {
  getInstitutionProfile,
  listInstitutionChanges,
  revalidateInstitutionChange,
} from '../src/api/institution'
import { useAuthStore } from '../src/stores/auth'
import InstitutionChangeApprovalsView from '../src/views/InstitutionChangeApprovalsView.vue'

vi.mock('../src/api/institution', () => ({
  getInstitutionProfile: vi.fn(),
  listInstitutionChanges: vi.fn(),
  revalidateInstitutionChange: vi.fn(),
  reviewInstitutionChangeFinance: vi.fn(),
  reviewInstitutionChangeMarket: vi.fn(),
}))

vi.mock('../src/api/catalog', () => ({
  listInstitutions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
  listProjects: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
}))

const conflictRequest: InstitutionChangeRequest = {
  id: 301,
  institutionId: 91,
  changeType: 'SETTLEMENT',
  fieldCode: 'settlementRate',
  targetId: null,
  baseVersion: 2,
  targetVersion: null,
  beforeMasked: '0.18',
  afterMasked: '0.15',
  reason: '费率调整',
  evidenceFileId: null,
  status: 'CONFLICT_REVALIDATE',
  currentStep: 'FINANCE',
  requestedBy: 10,
  requestedAt: '2026-10-25T01:00:00Z',
  completedAt: null,
  version: 4,
  steps: [],
}

const wrappers: VueWrapper[] = []

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 1,
    username: 'admin',
    displayName: '管理员',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions: ['institution:change_request', 'institution:approve_market', 'institution:approve_finance'],
  }
  auth.initialized = true
  const wrapper = mount(InstitutionChangeApprovalsView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listInstitutionChanges).mockResolvedValue({ items: [conflictRequest], total: 1, page: 1, size: 100 })
  vi.mocked(getInstitutionProfile).mockResolvedValue({
    id: 91,
    institutionCode: 'INST-91',
    fullName: '测试合作机构',
    shortName: '测试机构',
    cityCode: 'HZ',
    status: 'ACTIVE',
    version: 7,
  } as never)
  vi.mocked(revalidateInstitutionChange).mockResolvedValue({
    ...conflictRequest,
    baseVersion: 7,
    status: 'PENDING_REVIEW',
    version: 5,
  })
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('InstitutionChangeApprovalsView 重新复核', () => {
  it('重新复核前获取机构最新 version 作为 baseVersion，不传陈旧的 row.baseVersion', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '已核对最新机构档案版本', action: 'confirm' } as never)
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="change-revalidate"]').trigger('click')
    await flushPromises()

    expect(getInstitutionProfile).toHaveBeenCalledWith(91)
    expect(revalidateInstitutionChange).toHaveBeenCalledWith(
      301,
      { version: 4, baseVersion: 7, reason: '已核对最新机构档案版本' },
      expect.any(String),
    )
  })

  it('列表加载失败时展示错误提示而不是抛出未捕获异常', async () => {
    vi.mocked(listInstitutionChanges).mockRejectedValueOnce(new Error('审批队列加载失败'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('审批队列加载失败')
  })
})
