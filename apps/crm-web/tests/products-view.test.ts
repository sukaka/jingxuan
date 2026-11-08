import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { listPackages, listPackageVersions, listProjects, listSkus } from '../src/api/catalog'
import { useAuthStore } from '../src/stores/auth'
import ProductsView from '../src/views/ProductsView.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../src/api/catalog', () => ({
  activatePackageVersion: vi.fn(),
  archivePackageVersion: vi.fn(),
  createPackage: vi.fn(),
  createProject: vi.fn(),
  getPackageVersionSnapshot: vi.fn(),
  listPackages: vi.fn(),
  listPackageVersions: vi.fn(),
  listProjects: vi.fn(),
  listSkus: vi.fn(),
  updatePackage: vi.fn(),
  updateProject: vi.fn(),
}))

const packages = [
  { id: 201, packageCode: 'PKG-SY', packageName: '素颜卡', status: 'ACTIVE', createdAt: '2026-10-24T01:00:00Z', version: 0 },
  { id: 202, packageCode: 'PKG-WG', packageName: '光子水光囤货卡', status: 'ACTIVE', createdAt: '2026-10-24T01:00:00Z', version: 0 },
]
const version = {
  id: 301, packageId: 201, versionNo: 1, selectionMode: 'CHOOSE_N' as const, choiceCount: 3,
  selectionRules: null, status: 'ACTIVE' as const, createdAt: '2026-10-24T01:00:00Z', version: 0,
}
const skus = [
  {
    id: 401, skuCode: 'SKU-SY-3', packageVersionId: 301, packageId: 201, packageName: '素颜卡',
    versionNo: 1, selectionMode: 'CHOOSE_N', specName: '选3项', salePriceFen: 19900,
    status: 'ACTIVE' as const, onSaleAt: null, offSaleAt: null, createdAt: '2026-10-24T01:00:00Z', version: 0,
  },
  {
    id: 402, skuCode: 'SKU-WG-6', packageVersionId: 302, packageId: 202, packageName: '光子水光囤货卡',
    versionNo: 1, selectionMode: 'USE_N', specName: '6次卡', salePriceFen: 39900,
    status: 'ACTIVE' as const, onSaleAt: null, offSaleAt: null, createdAt: '2026-10-24T01:00:00Z', version: 0,
  },
]

beforeEach(() => {
  vi.mocked(listPackages).mockResolvedValue({ items: packages, total: 2, page: 1, size: 100 })
  vi.mocked(listPackageVersions).mockResolvedValue([version])
  vi.mocked(listProjects).mockResolvedValue({ items: [], total: 0, page: 1, size: 100 })
  vi.mocked(listSkus).mockResolvedValue({ items: skus, total: 2, page: 1, size: 100 })
})

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('ProductsView 套餐销售规格', () => {
  it('直接展示各套餐的真实 SKU 规格与售价，不用逐行点击加载', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore(pinia)
    auth.user = {
      userId: 1, username: 'catalog-test', displayName: '产品测试账号', userType: 'EMPLOYEE',
      roles: ['ADMIN'], permissions: ['product:read'],
    } satisfies AuthUser
    auth.initialized = true
    const wrapper = mount(ProductsView, { global: { plugins: [pinia, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('选3项')
    expect(wrapper.text()).toContain('199.00')
    expect(wrapper.text()).toContain('6次卡')
    expect(wrapper.text()).toContain('399.00')
    expect(wrapper.text()).toContain('从候选子项目中选择，不等于使用次数')
    expect(wrapper.text()).not.toContain('选 N 项 3')
    expect(wrapper.text()).not.toContain('点击选择查看')
    expect(listSkus).toHaveBeenCalledWith({ page: 1, size: 100 })
    expect(listPackageVersions).toHaveBeenCalledTimes(1)
    expect(listPackageVersions).toHaveBeenCalledWith(201)
    wrapper.unmount()
  })
})
