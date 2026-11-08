import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstitutionChangeRequest, InstitutionProfile } from '../src/api/institution'
import { createInstitutionChange } from '../src/api/institution'
import SensitiveChangeDrawer from '../src/views/institution/SensitiveChangeDrawer.vue'

vi.mock('../src/api/institution', () => ({ createInstitutionChange: vi.fn() }))
vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([{ code: 'A', label: 'A', sortNo: 1 }]),
}))
vi.mock('../src/api/file', () => ({
  sha256File: vi.fn(),
  initFileUpload: vi.fn(),
  putPresignedFile: vi.fn(),
  completeFileUpload: vi.fn(),
  getFileMetadata: vi.fn(),
  waitForFileClean: vi.fn(),
}))

const profile: InstitutionProfile = {
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
  status: 'ACTIVE',
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
  introduction: null,
  createdAt: '2026-10-24T01:00:00Z',
  version: 2,
}

const changeResponse: InstitutionChangeRequest = {
  id: 801,
  institutionId: 91,
  changeType: 'PROFILE_FIELD',
  fieldCode: 'onlineRate',
  targetId: 91,
  baseVersion: 2,
  targetVersion: 2,
  beforeMasked: { value: '0.180000' },
  afterMasked: { value: '0.182500' },
  reason: '年度费率调整',
  evidenceFileId: null,
  status: 'PENDING_REVIEW',
  currentStep: 'MARKET',
  requestedBy: 2,
  requestedAt: '2026-10-24T01:00:00Z',
  completedAt: null,
  version: 1,
  steps: [],
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

async function mountDrawer() {
  const wrapper = mount(SensitiveChangeDrawer, {
    props: { modelValue: false, profile },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
  return wrapper
}

async function selectField(wrapper: VueWrapper, code: string) {
  component(wrapper, 'sensitive-field').vm.$emit('update:modelValue', code)
  await flushPromises()
}

describe('SensitiveChangeDrawer 敏感申请与掩码', () => {
  it('费率按 JSON 数字精确提交，baseVersion 使用当前档案版本', async () => {
    vi.mocked(createInstitutionChange).mockResolvedValue(changeResponse)
    const wrapper = await mountDrawer()
    await selectField(wrapper, 'onlineRate')
    await wrapper.get('[data-testid="sensitive-rate-value"]').setValue('18.25')
    await wrapper.get('[data-testid="sensitive-reason"]').setValue('年度费率调整')
    await wrapper.get('[data-testid="sensitive-submit"]').trigger('click')
    await flushPromises()

    expect(createInstitutionChange).toHaveBeenCalledWith(expect.objectContaining({
      institutionId: 91,
      fieldCode: 'onlineRate',
      newValue: 0.1825,
      baseVersion: 2,
      reason: '年度费率调整',
    }), expect.any(String))
  })

  it('保证金按元转整数分，银行账号只回显掩码', async () => {
    vi.mocked(createInstitutionChange).mockResolvedValue(changeResponse)
    const wrapper = await mountDrawer()
    await selectField(wrapper, 'depositFen')
    await wrapper.get('[data-testid="sensitive-fen-value"]').setValue('30000')
    await wrapper.get('[data-testid="sensitive-reason"]').setValue('保证金调整')
    await wrapper.get('[data-testid="sensitive-submit"]').trigger('click')
    await flushPromises()
    expect(createInstitutionChange).toHaveBeenCalledWith(expect.objectContaining({ newValue: 3000000 }), expect.any(String))

    await selectField(wrapper, 'bankAccount')
    expect(wrapper.text()).toContain('****8888')
    expect(wrapper.text()).not.toContain('1234567890')
  })
})
