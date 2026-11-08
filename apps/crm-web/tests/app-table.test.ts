import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElTable, ElTableColumn } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import AppTable from '../src/components/AppTable.vue'
import { useAuthStore } from '../src/stores/auth'

const rows = [{ id: 1, username: 'manager1', displayName: '测试用户' }]
const storageKey = 'crm:table:v1:7:test-table'

function mountTable() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 7,
    username: 'table-test',
    displayName: '表格测试',
    userType: 'EMPLOYEE',
    roles: ['ADMIN'],
    permissions: [],
  }
  return mount(AppTable, {
    props: { tableId: 'test-table', data: rows },
    slots: {
      default: () => [
        h(ElTableColumn, { prop: 'id', label: 'ID', width: 70 }),
        h(ElTableColumn, { prop: 'username', label: '用户名', width: 140 }),
        h(ElTableColumn, { prop: 'displayName', label: '姓名', minWidth: 120 }),
      ],
      empty: () => '暂无数据',
    },
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
}

function headerText(wrapper: ReturnType<typeof mountTable>) {
  return wrapper.get('.el-table__header-wrapper').text()
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('AppTable 列设置', () => {
  it('隐藏和恢复列不改变行数据，至少保留一列，并可恢复默认', async () => {
    const wrapper = mountTable()
    await flushPromises()
    expect(headerText(wrapper)).toContain('用户名')

    const username = wrapper.findAllComponents({ name: 'ElCheckbox' }).find((item) => item.text() === '用户名')
    expect(username).toBeDefined()
    await username!.get('input').setValue(false)
    await flushPromises()
    expect(headerText(wrapper)).not.toContain('用户名')
    expect(wrapper.get('.el-table__body-wrapper').text()).not.toContain('manager1')
    expect(rows[0]!.username).toBe('manager1')
    expect(JSON.parse(localStorage.getItem(storageKey)!).hidden).toContain('prop:username#1')

    await wrapper.findAllComponents({ name: 'ElCheckbox' }).find((item) => item.text() === 'ID')!.get('input').setValue(false)
    await flushPromises()
    const remaining = wrapper.findAllComponents({ name: 'ElCheckbox' }).find((item) => item.text() === '姓名')!
    expect(remaining.get('input').attributes('disabled')).toBeDefined()
    expect(headerText(wrapper)).toContain('姓名')

    await wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === '恢复默认')!.trigger('click')
    await flushPromises()
    expect(headerText(wrapper)).toContain('用户名')
    expect(localStorage.getItem(storageKey)).toBeNull()
    wrapper.unmount()
  })

  it('保存拖宽结果，重新挂载后恢复，损坏设置不会阻断表格', async () => {
    const wrapper = mountTable()
    await flushPromises()
    expect(wrapper.findAllComponents({ name: 'ElTableColumn' }).at(-1)?.props('resizable')).toBe(true)
    wrapper.getComponent(ElTable).vm.$emit('header-dragend', 210, 140, {
      id: 'el-table_1_column_2', property: 'username', label: '用户名', type: 'default',
    })
    await flushPromises()
    expect(JSON.parse(localStorage.getItem(storageKey)!).widths['prop:username#1']).toBe(210)
    wrapper.unmount()

    const restored = mountTable()
    await flushPromises()
    const columnProps = restored.findAllComponents(ElTableColumn)[1]?.props() as Record<string, unknown>
    expect(columnProps.width).toBe(210)
    restored.unmount()

    localStorage.setItem(storageKey, '{损坏的设置')
    const recovered = mountTable()
    await flushPromises()
    expect(headerText(recovered)).toContain('用户名')
    expect(localStorage.getItem(storageKey)).toBeNull()
    recovered.unmount()
  })

  it('保留原生选择列和 selection-change 事件', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const listener = vi.fn()
    const wrapper = mount(AppTable, {
      props: { tableId: 'selection-table', data: rows },
      attrs: { onSelectionChange: listener },
      slots: {
        default: () => [
          h(ElTableColumn, { type: 'selection', width: 48 }),
          h(ElTableColumn, { prop: 'username', label: '用户名' }),
        ],
      },
      global: { plugins: [ElementPlus, pinia] },
    })
    await flushPromises()
    await wrapper.get('.el-table__body-wrapper .el-checkbox__original').setValue(true)
    await flushPromises()
    expect(listener).toHaveBeenCalledWith([rows[0]])
    wrapper.unmount()
  })
})
