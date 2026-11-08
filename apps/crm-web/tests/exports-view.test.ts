import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { ExportJob } from '../src/api/exporting'
import {
  createExportJob,
  getExportJobDownload,
  listExportAccessLogs,
  listExportJobs,
} from '../src/api/exporting'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import ExportsView from '../src/views/ExportsView.vue'

vi.mock('../src/api/exporting', () => ({
  createExportJob: vi.fn(),
  listExportJobs: vi.fn(),
  getExportJob: vi.fn(),
  getExportJobDownload: vi.fn(),
  listExportAccessLogs: vi.fn(),
}))

function job(status: string): ExportJob {
  return {
    id: 91,
    jobNo: 'EXP-TEST',
    exportType: 'CUSTOMER',
    status,
    filters: { from: null, to: null },
    columns: ['customerNo', 'displayName', 'mobile'],
    scope: { restriction: { type: 'OWNER', userId: 1 } },
    reason: '自动化测试导出理由',
    rowCount: status === 'SUCCEEDED' ? 2 : null,
    fileId: status === 'SUCCEEDED' ? 801 : null,
    expiresAt: status === 'SUCCEEDED' ? '2099-01-01T00:00:00Z' : null,
    lastError: status === 'FAILED' ? '测试执行失败' : null,
    startedAt: null,
    finishedAt: null,
    createdAt: '2026-10-25T01:00:00Z',
    updatedAt: '2026-10-25T01:00:00Z',
    version: 0,
  }
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.useRealTimers()
  vi.restoreAllMocks()
})

function mountView(permissions = ['export:read', 'export:create']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 1,
    username: 'export-test',
    displayName: '导出测试账号',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  } satisfies AuthUser
  auth.initialized = true
  const wrapper = mount(ExportsView, {
    global: { plugins: [pinia, ElementPlus] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function element(testId: string): HTMLElement {
  const hit = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  expect(hit, `缺少 ${testId}`).not.toBeNull()
  return hit!
}

async function click(testId: string) {
  element(testId).dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

async function input(testId: string, value: string) {
  const root = element(testId)
  const field = root.matches('input,textarea')
    ? root as HTMLInputElement | HTMLTextAreaElement
    : root.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')
  expect(field, `缺少输入框 ${testId}`).not.toBeNull()
  field!.value = value
  field!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

describe('ExportsView 服务端任务、权限与审计', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listExportJobs).mockResolvedValue({ items: [job('RUNNING')], total: 1, page: 1, size: 20 })
    vi.mocked(listExportAccessLogs).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(createExportJob).mockResolvedValue(job('PENDING'))
    vi.mocked(getExportJobDownload).mockResolvedValue({
      fileId: 801,
      downloadUrl: 'https://storage.example.test/export-signed',
      expiresAt: '2099-01-01T00:10:00Z',
    })
  })

  it('四类数据集使用后端白名单字段；失败重试保持幂等键且不生成浏览器 CSV', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="export-dataset-"]')).toHaveLength(4)
    expect(wrapper.text()).toContain('客户中心')
    expect(wrapper.text()).toContain('订单售卖')
    expect(wrapper.text()).toContain('核销记录')
    expect(wrapper.text()).toContain('每日跟进')

    await click('export-dataset-CUSTOMER')
    await click('export-submit')
    expect(createExportJob).not.toHaveBeenCalled()
    await input('export-reason', '核对授权范围内的客户分层字段')
    vi.mocked(createExportJob)
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '导出请求超时测试' }))
      .mockResolvedValueOnce(job('PENDING'))
    await click('export-submit')
    expect(wrapper.text()).toContain('导出请求超时测试')
    await click('export-submit')

    expect(createExportJob).toHaveBeenCalledTimes(2)
    const first = vi.mocked(createExportJob).mock.calls[0]
    const second = vi.mocked(createExportJob).mock.calls[1]
    expect(first?.[1]).toBeTruthy()
    expect(second?.[1]).toBe(first?.[1])
    expect(first?.[0]).toEqual({
      exportType: 'CUSTOMER',
      filters: {},
      columns: ['customerNo', 'displayName', 'mobile', 'customerStatus', 'ownerStaffId', 'teamId', 'createdAt'],
      reason: '核对授权范围内的客户分层字段',
    })
    expect(wrapper.find('a[download]').exists()).toBe(false)
  })

  it('创建、任务与审计权限分开展示空态，审计遵循后端 export:read', async () => {
    const wrapper = mountView(['export:read'])
    await flushPromises()

    expect(element('export-dataset-CUSTOMER').hasAttribute('disabled')).toBe(true)
    expect(wrapper.text()).toContain('当前账号没有创建导出任务权限')
    expect(listExportAccessLogs).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="export-audit-empty"]').exists()).toBe(false)
    wrapper.unmount()

    mountView(['export:create'])
    await flushPromises()
    expect(element('export-audit-empty').textContent).toContain('没有导出审计查看权限')
    expect(listExportAccessLogs).toHaveBeenCalledTimes(1)
    expect(listExportJobs).toHaveBeenCalledTimes(1)
  })

  it('卸载后清理任务轮询，慢定时器不再请求', async () => {
    vi.useFakeTimers()
    const wrapper = mountView()
    await flushPromises()
    expect(listExportJobs).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(15000)

    expect(listExportJobs).toHaveBeenCalledTimes(1)
  })

  it('只有成功且未过期任务提供下载，下载地址来自服务端', async () => {
    vi.mocked(listExportJobs).mockResolvedValue({ items: [job('SUCCEEDED')], total: 1, page: 1, size: 20 })
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    mountView()
    await flushPromises()

    await click('export-download-91')

    expect(getExportJobDownload).toHaveBeenCalledWith(91)
    expect(open).toHaveBeenCalledWith('https://storage.example.test/export-signed', '_blank', 'noopener,noreferrer')
  })
})
