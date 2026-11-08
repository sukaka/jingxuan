import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstitutionContract } from '../src/api/institution'
import { createInstitutionContract } from '../src/api/institution'
import DocumentFormDrawer from '../src/views/institution/DocumentFormDrawer.vue'
import FileUploadField from '../src/views/institution/FileUploadField.vue'

vi.mock('../src/api/institution', () => ({ createInstitutionContract: vi.fn() }))
vi.mock('../src/api/file', () => ({
  sha256File: vi.fn(),
  initFileUpload: vi.fn(),
  putPresignedFile: vi.fn(),
  completeFileUpload: vi.fn(),
  getFileMetadata: vi.fn(),
  waitForFileClean: vi.fn(),
}))

const previousContract: InstitutionContract = {
  id: 70,
  institutionId: 91,
  contractNo: 'C-TEST',
  versionNo: 1,
  title: '原合同',
  contractType: 'FRAMEWORK',
  startDate: '2026-01-01',
  endDate: null,
  fileId: 700,
  amountFen: null,
  notes: null,
  status: 'ACTIVE',
  previousContractId: null,
  createdBy: 1,
  createdAt: '2026-01-01T00:00:00Z',
  version: 0,
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

async function mountDrawer() {
  const wrapper = mount(DocumentFormDrawer, {
    props: { modelValue: false, mode: 'contract', institutionId: 91, previousContract },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 30))
  return wrapper
}

describe('DocumentFormDrawer 合同版本与 CLEAN 文件约束', () => {
  it('未 CLEAN 时不创建，真实上传事件后才创建下一版本并精确提交金额', async () => {
    vi.mocked(createInstitutionContract).mockResolvedValue({ ...previousContract, id: 71, versionNo: 2, status: 'PENDING_REVIEW', previousContractId: 70 })
    const wrapper = await mountDrawer()

    await wrapper.get('[data-testid="document-save"]').trigger('click')
    expect(createInstitutionContract).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="document-type"]').setValue('FRAMEWORK')
    await wrapper.get('[data-testid="document-title"]').setValue('新合同标题')
    const datePicker = wrapper.getComponent({ name: 'ElDatePicker' })
    datePicker.vm.$emit('update:modelValue', '2027-01-01')
    await wrapper.get('[data-testid="document-amount"]').setValue('1000.00')
    wrapper.getComponent(FileUploadField).vm.$emit('update:modelValue', 701)
    await flushPromises()
    await wrapper.get('[data-testid="document-save"]').trigger('click')
    await flushPromises()

    expect(createInstitutionContract).toHaveBeenCalledWith(expect.objectContaining({
      institutionId: 91,
      previousContractId: 70,
      contractNo: 'C-TEST',
      title: '新合同标题',
      contractType: 'FRAMEWORK',
      startDate: '2027-01-01',
      fileId: 701,
      amountFen: 100000,
    }), expect.any(String))
    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({ status: 'PENDING_REVIEW', previousContractId: 70 })
  })
})
