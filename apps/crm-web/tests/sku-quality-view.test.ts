import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import type { Sku, SkuQualityReport } from '../src/api/catalog'
import { disableSku, getPackageVersionSnapshot, getSkuQualityReport, listSkus } from '../src/api/catalog'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import SkuCenterView from '../src/views/SkuCenterView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

vi.mock('../src/api/catalog', () => ({
  activateSku: vi.fn(),
  createSku: vi.fn(),
  disableSku: vi.fn(),
  getPackageVersionSnapshot: vi.fn(),
  getSkuQualityReport: vi.fn(),
  listInstitutions: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
  listPackages: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 100 }),
  listPackageVersions: vi.fn().mockResolvedValue([]),
  listSkuInstitutions: vi.fn().mockResolvedValue([]),
  listSkus: vi.fn(),
  updateSku: vi.fn(),
}))

const sku: Sku = {
  id: 301,
  skuCode: 'SKU-TEST-301',
  packageVersionId: 311,
  packageId: 201,
  packageName: '测试套餐',
  versionNo: 1,
  selectionMode: 'USE_N',
  specName: '3次',
  salePriceFen: 19900,
  status: 'ACTIVE',
  onSaleAt: null,
  offSaleAt: null,
  createdAt: '2026-10-01T00:00:00Z',
  version: 0,
}

const report: SkuQualityReport = {
  from: '2026-07-29',
  to: '2026-10-26',
  timeZone: 'Asia/Shanghai',
  sourceSystem: 'ALL',
  metricVersion: 'P2_FACT_V1',
  sourceWatermark: '2026-10-27T01:00:00Z',
  periodDays: 90,
  lowFrequencyThreshold: 3,
  lowFrequencyAction: 'NONE',
  total: 12,
  page: 1,
  size: 100,
  summary: {
    totalSkuCount: 12,
    namingIssueCount: 0,
    lowFrequencySkuCount: 1,
    lowFrequencyRatio: 1,
  },
  items: [{
    skuId: 301,
    skuCode: 'SKU-TEST-301',
    sales90Days: 2,
    lowFrequency: true,
    qualityStatus: 'ATTENTION',
    issues: ['LOW_FREQUENCY'],
    automaticAction: 'NONE',
  }],
  checks: [
    { checkCode: 'LOW_FREQUENCY', checkName: '90 天低频 SKU', issueCount: 1, handlingRule: '进入长尾复核，不自动下架（自动动作：NONE）' },
    { checkCode: 'MISSING_ACTIVE_INSTITUTION', checkName: 'SKU 未配置启用机构', issueCount: 0, handlingRule: '未配置启用机构禁止上架' },
  ],
}

const user: AuthUser = {
  userId: 1,
  username: 'test-admin',
  displayName: '测试管理员',
  userType: 'EMPLOYEE',
  roles: ['ADMIN'],
  permissions: ['product:read', 'product:write', 'report:read'],
}

const wrappers: VueWrapper[] = []

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  const wrapper = mount(SkuCenterView, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  vi.mocked(listSkus).mockResolvedValue({ items: [sku], total: 1, page: 1, size: 20 })
  vi.mocked(getPackageVersionSnapshot).mockResolvedValue({
    id: 311,
    packageId: 201,
    packageCode: 'PKG-TEST',
    packageName: '测试套餐',
    versionNo: 1,
    selectionMode: 'USE_N',
    choiceCount: 3,
    selectionRules: null,
    status: 'ACTIVE',
    version: 0,
    rulesStatus: 'READY',
    allocationStatus: 'CONFIGURED',
    entitlementIssuable: true,
    projects: [],
    skus: [sku],
    institutions: [],
  })
  vi.mocked(getSkuQualityReport).mockResolvedValue(report)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('SkuCenterView P2 真实质量报表', () => {
  it('主页保持原型单表格，质量抽屉的真实销量、统计与检查项不自动下架', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(getSkuQualityReport).toHaveBeenCalledWith(expect.objectContaining({
      timeZone: 'Asia/Shanghai',
      sourceSystem: 'ALL',
      page: 1,
      size: 100,
      lowFrequencyThreshold: 3,
    }))
    expect(wrapper.find('.proto-stat-card').exists()).toBe(false)
    expect(wrapper.find('.quality-banner').exists()).toBe(false)
    expect(wrapper.get('[data-testid="sku-sales-301"]').text()).toBe('2')
    expect(wrapper.get('[data-testid="sku-quality-301"]').text()).toContain('低频')

    await wrapper.get('[data-testid="sku-quality-301"] button').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('SKU 数据质量与口径')
    expect(document.body.textContent).toContain('12 个')
    expect(document.body.textContent).toContain('100%')
    expect(document.body.querySelector('[data-testid="quality-check-LOW_FREQUENCY"]')!.textContent).toContain('不自动下架')
    expect(document.body.querySelector('[data-testid="quality-check-MISSING_ACTIVE_INSTITUTION"]')!.textContent).toContain('0')
    expect(document.body.textContent).toContain('Asia/Shanghai')
    expect(document.body.textContent).toContain('2026-10-27T01:00:00Z')
    expect(wrapper.text()).not.toContain('待接入')
    expect(wrapper.text()).not.toContain('待评估')
    expect(disableSku).not.toHaveBeenCalled()
  })

  it('质量报表失败时保留 SKU 主数据并明确缺失，不伪造零销量或正常质量', async () => {
    vi.mocked(getSkuQualityReport).mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '报表服务不可用' }),
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('SKU-TEST-301')
    expect(wrapper.text()).toContain('质量报表加载失败')
    expect(wrapper.text()).toContain('报表服务不可用')
    expect(wrapper.get('[data-testid="sku-sales-301"]').text()).toBe('—')
    expect(wrapper.get('[data-testid="sku-quality-301"]').text()).toContain('暂不可用')
    expect(disableSku).not.toHaveBeenCalled()
  })
})
