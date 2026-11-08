import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDropdown, ElMessage, ElMessageBox, ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { AuditLogItem } from '../src/api/audit'
import { listAuditLogs } from '../src/api/audit'
import type { AuthUser } from '../src/api/auth'
import type { DictionaryItemDetail, DictionaryTypeItem } from '../src/api/dictionaryAdmin'
import {
  createDictionaryItem,
  disableDictionaryItem,
  listDictionaryItems,
  listDictionaryTypes,
  updateDictionaryItem,
} from '../src/api/dictionaryAdmin'
import { useAuthStore } from '../src/stores/auth'
import DictionariesView from '../src/views/DictionariesView.vue'

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn() },
  }
})

vi.mock('../src/api/dictionaryAdmin', () => ({
  listDictionaryTypes: vi.fn(),
  listDictionaryItems: vi.fn(),
  createDictionaryItem: vi.fn(),
  updateDictionaryItem: vi.fn(),
  disableDictionaryItem: vi.fn(),
}))

vi.mock('../src/api/audit', () => ({ listAuditLogs: vi.fn() }))

const types: DictionaryTypeItem[] = [
  { id: 1, dictCode: 'customer_status', dictName: '客户状态', status: 'ACTIVE' },
  { id: 2, dictCode: 'appointment_status', dictName: '预约状态', status: 'ACTIVE' },
]

const items: Record<number, DictionaryItemDetail[]> = {
  1: [
    { id: 101, dictTypeId: 1, optionCode: 'POTENTIAL', optionLabel: '潜在客户', sortNo: 10, status: 'ACTIVE', version: 3 },
    { id: 102, dictTypeId: 1, optionCode: 'CUSTOMER_STATUS_INACTIVE_LONG_CODE', optionLabel: '已失效客户', sortNo: 20, status: 'INACTIVE', version: 1 },
    { id: 103, dictTypeId: 1, optionCode: 'CONVERTED', optionLabel: '已成交', sortNo: 30, status: 'ACTIVE', version: 2 },
  ],
  2: [
    { id: 201, dictTypeId: 2, optionCode: 'PENDING_CONFIRMATION', optionLabel: '待确认预约', sortNo: 10, status: 'ACTIVE', version: 1 },
  ],
}

const audit: AuditLogItem = {
  id: 1,
  actorUserId: 7,
  actionCode: 'DICTIONARY_ITEM_UPDATE',
  targetType: 'DICTIONARY_ITEM',
  targetId: '101',
  beforeJson: null,
  afterJson: null,
  traceId: 'trace-test',
  clientIp: null,
  reason: null,
  createdAt: '2026-10-27T10:00:00+08:00',
}

const wrappers: VueWrapper[] = []

function mountView(permissions = ['dictionary:read', 'dictionary:write', 'audit:read']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  const user: AuthUser = {
    userId: 7,
    username: 'dictionary-admin',
    displayName: '字典管理员',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions,
  }
  auth.user = user
  auth.initialized = true
  const wrapper = mount(DictionariesView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

function visibleItemIds(wrapper: VueWrapper) {
  return wrapper.findAll('.dictionary-item').map((row) => row.attributes('data-testid'))
}

beforeEach(() => {
  vi.mocked(listDictionaryTypes).mockResolvedValue(types)
  vi.mocked(listDictionaryItems).mockImplementation(async (typeId) => items[typeId].map((item) => ({ ...item })))
  vi.mocked(listAuditLogs).mockResolvedValue({ items: [audit], total: 1, page: 1, size: 30 })
  vi.mocked(ElMessageBox.confirm).mockImplementation(() => Promise.resolve({ value: 'confirm', action: 'confirm' }) as ReturnType<typeof ElMessageBox.confirm>)
  vi.mocked(updateDictionaryItem).mockImplementation(async (id, request) => ({ ...items[1].find((item) => item.id === id)!, ...request }))
  vi.mocked(disableDictionaryItem).mockImplementation(async (id) => ({ ...items[1].find((item) => item.id === id)!, status: 'INACTIVE' }))
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('DictionariesView 分类与筛选', () => {
  it('默认选中首类并展示中文分类、启用/总数和次要 dictCode，切换后仅显示当前分类', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('[data-testid="dictionary-type-1"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="dictionary-type-1"]').text()).toContain('2 启用 / 3 总数')
    expect(wrapper.get('[data-testid="dictionary-type-1"]').text()).toContain('customer_status')
    expect(wrapper.get('.dictionary-items-panel').text()).toContain('维护「客户状态」')
    expect(visibleItemIds(wrapper)).toEqual(['dictionary-item-101', 'dictionary-item-102', 'dictionary-item-103'])

    await wrapper.get('[data-testid="dictionary-type-2"]').trigger('click')
    expect(wrapper.get('[data-testid="dictionary-type-2"]').attributes('aria-pressed')).toBe('true')
    expect(visibleItemIds(wrapper)).toEqual(['dictionary-item-201'])
    expect(wrapper.get('.dictionary-items-panel').text()).toContain('待确认预约')
    expect(wrapper.get('.dictionary-items-panel').text()).not.toContain('潜在客户')
  })

  it('按名称或保存值搜索，并按状态过滤当前分类', async () => {
    const wrapper = mountView()
    await flushPromises()
    const search = wrapper.get('[data-testid="dictionary-search"]')

    await search.setValue('已成交')
    expect(visibleItemIds(wrapper)).toEqual(['dictionary-item-103'])
    await search.setValue('potential')
    expect(visibleItemIds(wrapper)).toEqual(['dictionary-item-101'])
    await search.setValue('')
    await wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'INACTIVE')
    await nextTick()
    expect(visibleItemIds(wrapper)).toEqual(['dictionary-item-102'])
    expect(wrapper.get('.dictionary-result-count').text()).toBe('显示 1 / 3 项')

    await wrapper.get('[data-testid="dictionary-type-2"]').trigger('click')
    await wrapper.get('[data-testid="dictionary-type-1"]').trigger('click')
    expect(visibleItemIds(wrapper)).toHaveLength(3)
    expect((search.element as HTMLInputElement).value).toBe('')
  })
})

describe('DictionariesView 操作与权限', () => {
  it('新增和修改打开原表单，修改仍携带版本并刷新当前分类', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="dictionary-create"]').trigger('click')
    expect(document.body.textContent).toContain('新增配置')
    const dialog = document.body.querySelector('.el-dialog')
    const typeInput = Array.from(dialog?.querySelectorAll<HTMLInputElement>('input') ?? []).find((input) => input.value === '客户状态')
    expect(typeInput).toBeDefined()
    const cancel = Array.from(dialog?.querySelectorAll('button') ?? []).find((button) => button.textContent === '取消')
    cancel?.click()
    await flushPromises()

    await wrapper.get('[data-testid="dictionary-item-101"] [data-testid="dictionary-edit"]').trigger('click')
    const labelInput = Array.from(document.body.querySelectorAll<HTMLInputElement>('.el-dialog input')).find((input) => input.value === '潜在客户')
    expect(labelInput).toBeDefined()
    labelInput!.value = '重点潜在客户'
    labelInput!.dispatchEvent(new Event('input', { bubbles: true }))
    const save = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog button')).find((button) => button.textContent === '保存')
    save?.click()
    await flushPromises()

    expect(updateDictionaryItem).toHaveBeenCalledWith(101, { optionLabel: '重点潜在客户', sortNo: 10, version: 3 })
    expect(listDictionaryItems).toHaveBeenLastCalledWith(1)
    expect(ElMessage.success).toHaveBeenCalledWith('配置已保存')
  })

  it('更多菜单收拢停用与删除，停用走确认和原 API，删除说明清晰', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="dictionary-item-101"] [data-testid="dictionary-more"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('删除不可用')
    expect(document.body.textContent).toContain('已引用配置仅可停用，不可物理删除')

    wrapper.findAllComponents(ElDropdown)[0].vm.$emit('command', 'disable')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('潜在客户'), '停用配置', { type: 'warning' })
    expect(disableDictionaryItem).toHaveBeenCalledWith(101)
    expect(listDictionaryItems).toHaveBeenLastCalledWith(1)
  })

  it('只读用户不显示写操作，审计区继续展示真实字典日志', async () => {
    const wrapper = mountView(['dictionary:read', 'audit:read'])
    await flushPromises()

    expect(wrapper.find('[data-testid="dictionary-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dictionary-edit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dictionary-more"]').exists()).toBe(false)
    expect(wrapper.get('.dictionary-audits').text()).toContain(audit.createdAt)
    expect(wrapper.get('.dictionary-audits').text()).toContain('修改字典配置')
    expect(wrapper.get('.dictionary-audits').text()).toContain('用户 #7')
    expect(createDictionaryItem).not.toHaveBeenCalled()
  })
})

describe('DictionariesView 响应式布局', () => {
  it('桌面左右布局，移动端上下布局且仅分类导航自身横向滚动', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/DictionariesView.vue'), 'utf8')

    expect(source).toMatch(/\.dictionary-layout \{ display: grid; grid-template-columns: 250px minmax\(0, 1fr\)/)
    expect(source).toMatch(/@media \(max-width: 900px\)[\s\S]*?\.dictionary-layout \{ grid-template-columns: minmax\(0, 1fr\); \}/)
    expect(source).toMatch(/@media \(max-width: 900px\)[\s\S]*?\.dictionary-type-list \{[^}]*overflow-x: auto;[^}]*flex-direction: row;/)
    expect(source).toMatch(/@media \(max-width: 640px\)[\s\S]*?\.dictionary-list-head \{ display: none; \}/)
    expect(source).toMatch(/@media \(max-width: 640px\)[\s\S]*?grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/)
    expect(source).toMatch(/\.dictionary-item-code code \{[^}]*overflow-wrap: anywhere;/)
    expect(source).toMatch(/\.dictionary-row-actions \{ display: flex;[^}]*white-space: nowrap;/)
  })
})
