import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { InstitutionChangeRequest, InstitutionProfile } from '../src/api/institution'
import {
  getInstitutionProfile,
  listInstitutionChanges,
  requestInstitutionActivation,
  reviewInstitutionChangeFinance,
  reviewInstitutionChangeMarket,
} from '../src/api/institution'
import { useAuthStore } from '../src/stores/auth'
import InstitutionsView from '../src/views/InstitutionsView.vue'

vi.mock('../src/api/institution', () => ({
  listInstitutionProfiles: vi.fn(),
  getInstitutionProfile: vi.fn(),
  createInstitutionProfile: vi.fn(),
  updateInstitutionProfile: vi.fn(),
  requestInstitutionActivation: vi.fn(),
  listInstitutionChanges: vi.fn(),
  getInstitutionChange: vi.fn(),
  createInstitutionChange: vi.fn(),
  reviewInstitutionChangeMarket: vi.fn(),
  reviewInstitutionChangeFinance: vi.fn(),
  revalidateInstitutionChange: vi.fn(),
  listInstitutionContracts: vi.fn(),
  getInstitutionContract: vi.fn(),
  createInstitutionContract: vi.fn(),
  requestContractActivation: vi.fn(),
  listInstitutionQualifications: vi.fn(),
  getInstitutionQualification: vi.fn(),
  createInstitutionQualification: vi.fn(),
  requestQualificationReview: vi.fn(),
}))
vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([{ code: 'BJ', label: '北京', sortNo: 1 }]),
}))
vi.mock('../src/api/file', () => ({
  sha256File: vi.fn(),
  initFileUpload: vi.fn(),
  putPresignedFile: vi.fn(),
  completeFileUpload: vi.fn(),
  getFileMetadata: vi.fn(),
  waitForFileClean: vi.fn(),
  getFileDownload: vi.fn(),
}))

const pendingProfile: InstitutionProfile = {
  id: 91,
  institutionNo: 'INST-TEST',
  fullName: '测试机构',
  shortName: '测试',
  cityCode: 'BJ',
  businessTypeCode: 'MED_AESTHETIC',
  ownershipCode: 'PRIVATE',
  gradeCode: 'A',
  address: null,
  contactName: null,
  contactPhoneMasked: null,
  status: 'PENDING',
  settlementType: 'MONTHLY',
  onlineRate: '0.180000',
  offlineRate: '0.120000',
  platformRate: '0.150000',
  otherRateRule: null,
  chargesFee: true,
  depositFen: 0,
  guaranteeRoi: false,
  roiTerms: null,
  settlementName: null,
  bankNameMasked: null,
  bankAccountMasked: null,
  taxIdMasked: null,
  introduction: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 2,
}

const requested: InstitutionChangeRequest = {
  id: 801,
  institutionId: 91,
  changeType: 'ONBOARDING',
  fieldCode: null,
  targetId: 91,
  baseVersion: 2,
  targetVersion: null,
  beforeMasked: { status: 'PENDING' },
  afterMasked: { status: 'ACTIVE' },
  reason: '合作资料齐全',
  evidenceFileId: null,
  status: 'PENDING_REVIEW',
  currentStep: 'MARKET',
  requestedBy: 3,
  requestedAt: '2026-10-24T01:00:00Z',
  completedAt: null,
  version: 1,
  steps: [],
}

const afterMarket: InstitutionChangeRequest = {
  ...requested,
  currentStep: 'FINANCE',
  version: 2,
  steps: [{ id: 1, changeRequestId: 801, sequenceNo: 0, stepCode: 'MARKET', status: 'APPROVED', reviewerUserId: 1, reviewedAt: '2026-10-24T02:00:00Z', comment: '审核通过意见', createdAt: '2026-10-24T02:00:00Z' }],
}

const afterFinance: InstitutionChangeRequest = {
  ...afterMarket,
  status: 'APPROVED',
  currentStep: 'COMPLETED',
  completedAt: '2026-10-24T03:00:00Z',
  version: 3,
  steps: [...afterMarket.steps, { id: 2, changeRequestId: 801, sequenceNo: 1, stepCode: 'FINANCE', status: 'APPROVED', reviewerUserId: 2, reviewedAt: '2026-10-24T03:00:00Z', comment: '审核通过意见', createdAt: '2026-10-24T03:00:00Z' }],
}

const admin: AuthUser = {
  userId: 1,
  username: 'workflow-admin',
  displayName: '流程测试管理员',
  userType: 'EMPLOYEE',
  roles: ['ADMIN'],
  permissions: ['institution:read', 'institution:write', 'institution:change_request', 'institution:approve_market', 'institution:approve_finance'],
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('InstitutionsView PENDING 到双审激活流程', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(listInstitutionChanges)
      .mockResolvedValueOnce({ items: [requested], total: 1, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [afterMarket], total: 1, page: 1, size: 20 })
      .mockResolvedValueOnce({ items: [afterFinance], total: 1, page: 1, size: 20 })
    vi.mocked(getInstitutionProfile)
      .mockResolvedValueOnce(pendingProfile)
      .mockResolvedValueOnce(pendingProfile)
      .mockResolvedValueOnce(pendingProfile)
      .mockResolvedValueOnce({ ...pendingProfile, status: 'ACTIVE', version: 3 })
  })

  it('提交激活、市场通过、财务通过后仅按后端结果显示 ACTIVE', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = admin
    auth.initialized = true
    const { listInstitutionProfiles, listInstitutionContracts, listInstitutionQualifications } = await import('../src/api/institution')
    vi.mocked(listInstitutionProfiles).mockResolvedValue({ items: [pendingProfile], total: 1, page: 1, size: 20 })
    vi.mocked(listInstitutionContracts).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(listInstitutionQualifications).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(requestInstitutionActivation).mockResolvedValue(requested)
    vi.mocked(reviewInstitutionChangeMarket).mockResolvedValue(afterMarket)
    vi.mocked(reviewInstitutionChangeFinance).mockResolvedValue(afterFinance)
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '审核通过意见' } as never)
    const wrapper = mount(InstitutionsView, {
      global: { plugins: [ElementPlus, pinia] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()

    const detailButton = wrapper.findAll('button').find((button) => button.text() === '详情')
    expect(detailButton).toBeDefined()
    await detailButton!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))
    await wrapper.get('[data-testid="profile-activate"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="workflow-reason"]').setValue('合作资料齐全')
    await wrapper.get('[data-testid="workflow-submit"]').trigger('click')
    await flushPromises()

    expect(requestInstitutionActivation).toHaveBeenCalledWith(91, { baseVersion: 2, reason: '合作资料齐全', evidenceFileId: undefined }, expect.any(String))
    expect(wrapper.text()).toContain('待审核')

    await wrapper.get('[data-testid="market-approve"]').trigger('click')
    await flushPromises()
    expect(reviewInstitutionChangeMarket).toHaveBeenCalledWith(801, { version: 1, decision: 'APPROVE', comment: '审核通过意见' }, expect.any(String))

    auth.user = { ...admin, userId: 2, permissions: ['institution:read', 'institution:approve_finance'] }
    await flushPromises()
    await wrapper.get('[data-testid="finance-approve"]').trigger('click')
    await flushPromises()
    expect(reviewInstitutionChangeFinance).toHaveBeenCalledWith(801, { version: 2, decision: 'APPROVE', comment: '审核通过意见' }, expect.any(String))
    expect(wrapper.text()).toContain('已生效')
    expect(wrapper.text()).toContain('合作中')
  })
})
