import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import type { InstitutionChangeRequest, InstitutionProfile } from '../src/api/institution'
import ChangeRequestPanel from '../src/views/institution/ChangeRequestPanel.vue'
import InstitutionDetailDrawer from '../src/views/institution/InstitutionDetailDrawer.vue'
import { changeStatusLabel, changeTypeLabel, cityLabel, contractTypeLabel, documentStatusLabel, fieldLabel, institutionBusinessTypeLabel, institutionGradeLabel, institutionOwnershipLabel, profileStatusLabel, qualificationTypeLabel, reviewStatusLabel, settlementTypeLabel, workflowStepLabel } from '../src/views/institution/institutionUi'

const request: InstitutionChangeRequest = {
  id: 801,
  institutionId: 91,
  changeType: 'ONBOARDING',
  fieldCode: null,
  targetId: 91,
  baseVersion: 2,
  targetVersion: null,
  beforeMasked: { status: 'PENDING' },
  afterMasked: { status: 'ACTIVE' },
  reason: '合作激活',
  evidenceFileId: null,
  status: 'PENDING_REVIEW',
  currentStep: 'MARKET',
  requestedBy: 2,
  requestedAt: '2026-10-24T01:00:00Z',
  completedAt: null,
  version: 1,
  steps: [],
}

const profile: InstitutionProfile = {
  id: 91,
  institutionNo: 'INST-TEST',
  fullName: '测试机构',
  shortName: '测试',
  cityCode: 'BJ',
  businessTypeCode: 'MED_AESTHETIC',
  ownershipCode: 'PRIVATE',
  gradeCode: 'A',
  address: '测试地址',
  contactName: '测试联系人',
  contactPhoneMasked: '****0000',
  status: 'PENDING',
  settlementType: 'MONTHLY',
  onlineRate: '0.180000',
  offlineRate: '0.120000',
  platformRate: '0.150000',
  otherRateRule: null,
  chargesFee: true,
  depositFen: 100000,
  guaranteeRoi: false,
  roiTerms: null,
  settlementName: '测试结款主体',
  bankNameMasked: '***',
  bankAccountMasked: '****8888',
  taxIdMasked: '****6666',
  introduction: '机构服务简介',
  createdAt: '2026-10-24T01:00:00Z',
  version: 2,
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('ChangeRequestPanel 双审权限', () => {
  it('按当前步骤开放审核，申请人不能自审且双审不能由同一人完成', async () => {
    const wrapper = mount(ChangeRequestPanel, {
      props: { requests: [request], loading: false, canMarket: true, canFinance: true, canChange: true, currentUserId: 1 },
      global: { plugins: [ElementPlus] },
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[data-testid="market-approve"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="finance-approve"]').exists()).toBe(false)

    const afterMarket: InstitutionChangeRequest = {
      ...request,
      currentStep: 'FINANCE',
      steps: [{ id: 1, changeRequestId: 801, sequenceNo: 0, stepCode: 'MARKET', status: 'APPROVED', reviewerUserId: 1, reviewedAt: '2026-10-24T02:00:00Z', comment: '通过', createdAt: '2026-10-24T02:00:00Z' }],
    }
    await wrapper.setProps({ requests: [afterMarket] })
    expect(wrapper.find('[data-testid="finance-approve"]').exists()).toBe(false)

    await wrapper.setProps({ requests: [{ ...afterMarket, steps: [{ ...afterMarket.steps[0]!, reviewerUserId: 2 }] }] })
    expect(wrapper.find('[data-testid="finance-approve"]').exists()).toBe(true)

    await wrapper.setProps({ requests: [{ ...afterMarket, requestedBy: 1 }] })
    expect(wrapper.find('[data-testid="finance-approve"]').exists()).toBe(false)

    await wrapper.setProps({ requests: [{ ...afterMarket, status: 'CONFLICT_REVALIDATE', currentStep: 'CONFLICT' }] })
    expect(wrapper.find('[data-testid="change-revalidate"]').exists()).toBe(true)
  })
})

describe('InstitutionDetailDrawer 商户一页纸与权限', () => {
  it('一页纸不含银行税号或合同财务字段，PENDING 才可申请激活', async () => {
    const wrapper = mount(InstitutionDetailDrawer, {
      props: {
        modelValue: true,
        profile,
        contracts: [{ id: 70, institutionId: 91, contractNo: 'C-TEST', versionNo: 1, title: '合同', contractType: 'FRAMEWORK', startDate: '2026-01-01', endDate: null, fileId: 701, amountFen: 100000, notes: null, status: 'ACTIVE', previousContractId: null, createdBy: 1, createdAt: '2026-10-24T01:00:00Z', version: 0 }],
        qualifications: [],
        contractTotal: 1,
        qualificationTotal: 0,
        contractPage: 1,
        qualificationPage: 1,
        canWrite: true,
        canChange: true,
        canViewFinance: false,
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 30))

    const merchantTab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === '商户一页纸')
    await merchantTab?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('机构服务简介')
    const merchantText = wrapper.get('.institution-card-grid').text()
    expect(merchantText).not.toContain('银行账号')
    expect(merchantText).not.toContain('纳税人识别号')
    expect(merchantText).not.toContain('合同金额')
    expect(merchantText).not.toContain('1000.00')
    expect(wrapper.find('[data-testid="profile-activate"]').exists()).toBe(true)
  })
})

describe('机构展示文案', () => {
  it('未知枚举不直接回显英文，中文自定义类型保持原样', () => {
    expect(profileStatusLabel('UNKNOWN')).toBe('未知状态')
    expect(documentStatusLabel('UNKNOWN')).toBe('未知状态')
    expect(changeStatusLabel('UNKNOWN')).toBe('未知状态')
    expect(contractTypeLabel('UNKNOWN_TYPE')).toBe('其他合同类型')
    expect(contractTypeLabel('年度合作协议')).toBe('年度合作协议')
    expect(qualificationTypeLabel('UNKNOWN_TYPE')).toBe('其他资质类型')
    expect(changeTypeLabel('UNKNOWN_TYPE')).toBe('其他申请类型')
    expect(fieldLabel('unknownField')).toBe('未知字段')
    expect(institutionBusinessTypeLabel('UNKNOWN_TYPE')).toBe('其他业务类型')
    expect(institutionOwnershipLabel('UNKNOWN_TYPE')).toBe('其他机构性质')
    expect(institutionGradeLabel('UNKNOWN')).toBe('其他等级')
    expect(institutionGradeLabel('S')).toBe('S类')
    expect(settlementTypeLabel('UNKNOWN_TYPE')).toBe('其他结算方式')
    expect(cityLabel('UNKNOWN')).toBe('其他城市')
    expect(workflowStepLabel('UNKNOWN')).toBe('未知审核步骤')
    expect(reviewStatusLabel('UNKNOWN')).toBe('未知审核状态')
  })
})
