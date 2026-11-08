import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstitutionProfile } from '../src/api/institution'
import { createInstitutionProfile, updateInstitutionProfile } from '../src/api/institution'
import ProfileFormDrawer from '../src/views/institution/ProfileFormDrawer.vue'

vi.mock('../src/api/institution', () => ({
  createInstitutionProfile: vi.fn(),
  updateInstitutionProfile: vi.fn(),
}))
vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockImplementation(async (code: string) => {
    const values: Record<string, string[]> = {
      cities: ['BJ'],
      institutionBusinessTypes: ['MED_AESTHETIC'],
      institutionOwnerships: ['PRIVATE'],
      merchantGrades: ['A'],
      settlementTypes: ['MONTHLY'],
    }
    return (values[code] ?? []).map((value) => ({ code: value, label: value, sortNo: 1 }))
  }),
}))

const profile: InstitutionProfile = {
  id: 91,
  institutionNo: 'INST-TEST',
  fullName: '测试机构',
  shortName: '旧简称',
  cityCode: 'BJ',
  businessTypeCode: 'MED_AESTHETIC',
  ownershipCode: 'PRIVATE',
  gradeCode: 'A',
  address: '旧址',
  contactName: '测试联系人',
  contactPhoneMasked: '****0000',
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
  settlementName: '测试结款主体',
  bankNameMasked: '***',
  bankAccountMasked: '****8888',
  taxIdMasked: '****6666',
  introduction: '旧简介',
  createdAt: '2026-10-24T01:00:00Z',
  version: 2,
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

async function mountForm(current?: InstitutionProfile) {
  const wrapper = mount(ProfileFormDrawer, {
    props: { modelValue: false, profile: current ?? null },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
  return wrapper
}

async function select(wrapper: VueWrapper, testId: string, value: string) {
  component(wrapper, testId).vm.$emit('update:modelValue', value)
  await flushPromises()
}

describe('ProfileFormDrawer 创建与普通更新', () => {
  it('创建 PENDING 机构并精确提交费率与保证金分', async () => {
    vi.mocked(createInstitutionProfile).mockResolvedValue(profile)
    const wrapper = await mountForm()
    await wrapper.get('[data-testid="profile-institution-no"]').setValue('INST-TEST')
    await wrapper.get('[data-testid="profile-full-name"]').setValue('测试机构')
    await select(wrapper, 'profile-business-type', 'MED_AESTHETIC')
    await select(wrapper, 'profile-ownership', 'PRIVATE')
    await select(wrapper, 'profile-grade', 'A')
    await select(wrapper, 'profile-settlement', 'MONTHLY')
    await wrapper.get('[data-testid="profile-online-rate"]').setValue('18.25')
    await wrapper.get('[data-testid="profile-offline-rate"]').setValue('12')
    await wrapper.get('[data-testid="profile-platform-rate"]').setValue('15')
    await wrapper.get('[data-testid="profile-deposit-yuan"]').setValue('30000')
    await wrapper.get('[data-testid="profile-save"]').trigger('click')
    await flushPromises()

    expect(createInstitutionProfile).toHaveBeenCalledWith(expect.objectContaining({
      institutionNo: 'INST-TEST',
      fullName: '测试机构',
      settlementType: 'MONTHLY',
      onlineRate: '0.182500',
      offlineRate: '0.120000',
      platformRate: '0.150000',
      depositFen: 3000000,
    }), expect.any(String))
    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({ status: 'PENDING' })
  })

  it('普通 PATCH 只提交 DTO 允许字段，不夹带佣金或银行税务字段', async () => {
    vi.mocked(updateInstitutionProfile).mockResolvedValue(profile)
    const wrapper = await mountForm(profile)
    await wrapper.get('[data-testid="profile-short-name"]').setValue('新简称')
    await wrapper.get('[data-testid="profile-contact-phone"]').setValue('13800000000')
    await wrapper.get('[data-testid="profile-save"]').trigger('click')
    await flushPromises()

    const body = vi.mocked(updateInstitutionProfile).mock.calls[0]?.[1]
    expect(updateInstitutionProfile).toHaveBeenCalledWith(91, expect.any(Object), expect.any(String))
    expect(body).toMatchObject({ version: 2, shortName: '新简称', contactPhone: '13800000000' })
    expect(body).not.toHaveProperty('onlineRate')
    expect(body).not.toHaveProperty('bankAccount')
    expect(body).not.toHaveProperty('taxId')
  })
})
