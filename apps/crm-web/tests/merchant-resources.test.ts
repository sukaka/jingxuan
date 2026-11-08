import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  InstitutionMerchantResource,
  InstitutionProfile,
  MerchantResourceType,
} from '../src/api/institution'
import {
  createInstitutionMerchantResource,
  listInstitutionMerchantResourceSummary,
  listInstitutionMerchantResources,
  listInstitutionProfiles,
  updateInstitutionMerchantResource,
} from '../src/api/institution'
import MerchantLibraryView from '../src/views/MerchantLibraryView.vue'
import MerchantResourcePanel from '../src/views/institution/MerchantResourcePanel.vue'

vi.mock('../src/api/institution', async (importOriginal) => ({
  ...await importOriginal<typeof import('../src/api/institution')>(),
  listInstitutionProfiles: vi.fn(),
  listInstitutionMerchantResources: vi.fn(),
  listInstitutionMerchantResourceSummary: vi.fn(),
  createInstitutionMerchantResource: vi.fn(),
  updateInstitutionMerchantResource: vi.fn(),
}))

function resource(id: number, resourceType: MerchantResourceType, title: string): InstitutionMerchantResource {
  return {
    id,
    resourceNo: `01TESTRESOURCE${String(id).padStart(8, '0')}`,
    institutionId: 1,
    resourceType,
    title,
    subtitle: '合成测试资料',
    description: `${title}说明`,
    fileId: null,
    status: 'PUBLISHED',
    createdBy: 6,
    updatedBy: null,
    createdAt: '2026-11-06T10:00:00Z',
    updatedAt: '2026-11-06T10:00:00Z',
    version: 0,
  }
}

const profile: InstitutionProfile = {
  id: 1,
  institutionNo: 'INST-SYNTHETIC',
  fullName: '合成测试机构',
  shortName: '测试机构',
  cityCode: 'BJ',
  businessTypeCode: 'MED_AESTHETIC',
  ownershipCode: 'PRIVATE',
  gradeCode: 'A',
  address: '测试地址',
  contactName: '测试联系人',
  contactPhoneMasked: '****0000',
  status: 'ACTIVE',
  settlementType: null,
  onlineRate: null,
  offlineRate: null,
  platformRate: null,
  otherRateRule: null,
  chargesFee: null,
  depositFen: null,
  guaranteeRoi: null,
  roiTerms: null,
  settlementName: null,
  bankNameMasked: null,
  bankAccountMasked: null,
  taxIdMasked: null,
  introduction: '合成测试机构简介',
  createdAt: '2026-11-06T10:00:00Z',
  version: 1,
}

const wrappers: VueWrapper[] = []
const stubs = {
  RouterLink: { template: '<a><slot /></a>' },
  FileUploadField: { template: '<div data-testid="file-upload-field" />' },
  FileDownloadButton: { template: '<button>查看文件</button>' },
  ElDialog: {
    props: ['modelValue'],
    template: '<div v-if="modelValue" class="test-dialog"><slot /><slot name="footer" /></div>',
  },
}

function mountPanel(canWrite = false) {
  const wrapper = mount(MerchantResourcePanel, {
    props: { institutionId: 1, canWrite },
    global: { plugins: [ElementPlus], stubs },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.mocked(listInstitutionMerchantResources).mockReset().mockResolvedValue({
    items: [resource(1, 'DOCTOR', '测试医生'), resource(2, 'EQUIPMENT', '测试仪器'), resource(3, 'CASE', '测试案例')],
    total: 3,
    page: 1,
    size: 50,
  })
  vi.mocked(listInstitutionMerchantResourceSummary).mockReset().mockResolvedValue([
    { institutionId: 1, doctorCount: 1, equipmentCount: 2, caseCount: 3 },
  ])
  vi.mocked(listInstitutionProfiles).mockReset().mockResolvedValue({ items: [profile], total: 1, page: 1, size: 100 })
  vi.mocked(createInstitutionMerchantResource).mockReset().mockResolvedValue(resource(4, 'DOCTOR', '新医生'))
  vi.mocked(updateInstitutionMerchantResource).mockReset().mockImplementation(async (id, body) => ({
    ...resource(id, 'DOCTOR', body.title),
    ...body,
    fileId: body.fileId ?? null,
    subtitle: body.subtitle ?? null,
    description: body.description ?? null,
    version: body.version + 1,
  }))
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('机构展示资料维护', () => {
  it('只读一页纸展示真实资料，不提供维护按钮', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('测试医生')
    expect(wrapper.text()).toContain('测试仪器')
    expect(wrapper.text()).toContain('测试案例')
    expect(wrapper.find('[data-testid="merchant-resource-create"]').exists()).toBe(false)
    expect(listInstitutionMerchantResources).toHaveBeenCalledWith({
      institutionId: 1,
      resourceType: undefined,
      status: 'PUBLISHED',
      page: 1,
      size: 50,
    })
  })

  it('新增真实医生资料并刷新列表', async () => {
    const wrapper = mountPanel(true)
    await flushPromises()
    await wrapper.get('[data-testid="merchant-resource-create"]').trigger('click')
    await wrapper.get('[data-testid="merchant-resource-title"]').setValue('  新医生  ')
    await wrapper.get('[data-testid="merchant-resource-subtitle"]').setValue('主治医师')
    await wrapper.get('[data-testid="merchant-resource-save"]').trigger('click')
    await flushPromises()

    expect(createInstitutionMerchantResource).toHaveBeenCalledWith(1, {
      resourceType: 'DOCTOR',
      title: '新医生',
      subtitle: '主治医师',
      description: undefined,
      fileId: undefined,
    }, expect.any(String))
    expect(listInstitutionMerchantResources).toHaveBeenCalledTimes(2)
  })

  it('下架保留资料记录并通过既有更新接口改变展示状态', async () => {
    const wrapper = mountPanel(true)
    await flushPromises()
    await wrapper.get('[data-testid="merchant-resource-toggle-1"]').trigger('click')
    await flushPromises()

    expect(updateInstitutionMerchantResource).toHaveBeenCalledWith(1, expect.objectContaining({
      version: 0,
      title: '测试医生',
      status: 'OFFLINE',
    }), expect.any(String))
  })
})

describe('商户一页纸列表', () => {
  it('显示真实资料数量，支持多维搜索并打开对应一页纸', async () => {
    const wrapper = mount(MerchantLibraryView, { global: { plugins: [ElementPlus], stubs } })
    wrappers.push(wrapper)
    await flushPromises()

    expect(wrapper.text()).toContain('1 位医生')
    expect(wrapper.text()).toContain('2 项仪器')
    expect(wrapper.text()).toContain('3 个案例')
    expect(wrapper.text()).not.toContain('资料待补充')
    await wrapper.get('input[aria-label="搜索商户资料"]').setValue('测试医生')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(listInstitutionMerchantResources).not.toHaveBeenCalled()
    expect(listInstitutionProfiles).toHaveBeenLastCalledWith(expect.objectContaining({ merchantKeyword: '测试医生' }))

    await wrapper.get('[data-testid="merchant-open-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('合成测试机构简介')
    expect(wrapper.text()).toContain('测试医生说明')
    expect(listInstitutionMerchantResources).toHaveBeenCalledWith(expect.objectContaining({
      institutionId: 1,
      status: 'PUBLISHED',
    }))
  })
})
