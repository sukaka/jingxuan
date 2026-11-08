import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../src/api/auth'
import { listCustomerOwnerOptions, listCustomers } from '../src/api/customer'
import {
  claimTask,
  completeTask,
  listTaskActions,
  listTasks,
  reassignTask,
  returnTask,
} from '../src/api/task'
import type { TaskItem } from '../src/api/task'
import { useAuthStore } from '../src/stores/auth'
import TasksView from '../src/views/TasksView.vue'

vi.mock('../src/api/task', () => ({
  listTasks: vi.fn(),
  createTask: vi.fn(),
  updateTask: vi.fn(),
  claimTask: vi.fn(),
  reassignTask: vi.fn(),
  returnTask: vi.fn(),
  completeTask: vi.fn(),
  listTaskActions: vi.fn(),
}))

vi.mock('../src/api/customer', () => ({
  listCustomers: vi.fn(),
  listCustomerOwnerOptions: vi.fn(),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockResolvedValue([{ code: 'FOLLOWUP', label: '客户回访', sortNo: 1 }]),
}))

const housekeeper: AuthUser = {
  userId: 2,
  username: 'keeper',
  displayName: '王丽',
  userType: 'EMPLOYEE',
  roles: ['HOUSEKEEPER'],
  permissions: ['task:read', 'task:write', 'dictionary:read'],
}

function task(overrides: Partial<TaskItem> = {}): TaskItem {
  return {
    id: 11,
    customerId: 100,
    originalAssigneeUserId: 7,
    assigneeUserId: null,
    taskTeamId: 3,
    taskType: 'FOLLOWUP',
    priority: 'MEDIUM',
    status: 'OPEN',
    title: '回访陈女士',
    description: '确认术后恢复情况',
    dueAt: null,
    claimedAt: null,
    completedAt: null,
    createdAt: '2026-10-20T08:00:00Z',
    version: 4,
    ...overrides,
  }
}

function page(items: TaskItem[]) {
  return { items, total: items.length, page: 1, size: 100 }
}

function mountView(user: AuthUser = housekeeper) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = user
  auth.initialized = true
  return mount(TasksView, {
    global: {
      plugins: [ElementPlus, pinia],
      stubs: {
        teleport: true,
        ElSelect: {
          props: ['modelValue'],
          emits: ['update:modelValue', 'change'],
          methods: {
            onChange(event: Event) {
              const raw = (event.target as HTMLSelectElement).value
              const value = raw !== '' && !Number.isNaN(Number(raw)) ? Number(raw) : raw
              this.$emit('update:modelValue', value)
              this.$emit('change', value)
            },
          },
          template: '<select :value="modelValue" @change="onChange"><slot /></select>',
        },
        ElOption: {
          props: ['label', 'value'],
          template: '<option :value="value">{{ label }}</option>',
        },
      },
    },
  })
}

async function button(wrapper: ReturnType<typeof mountView>, text: string) {
  const target = wrapper.findAll('button').find((item) => item.text() === text)
  expect(target, `应存在“${text}”按钮`).toBeDefined()
  await target?.trigger('click')
  await flushPromises()
}

describe('TasksView 任务动作', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTasks).mockImplementation(async ({ status }) => {
      if (status === 'OPEN') return page([task()])
      if (status === 'PROCESSING') {
        return page([task({
          id: 12,
          assigneeUserId: 2,
          status: 'PROCESSING',
          claimedAt: '2026-10-21T09:30:00Z',
          version: 5,
        })])
      }
      return page([task({
        id: 13,
        assigneeUserId: 2,
        status: 'DONE',
        claimedAt: '2026-10-21T09:30:00Z',
        completedAt: '2026-10-22T10:45:00Z',
        version: 6,
      })])
    })
    vi.mocked(listCustomers).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
    vi.mocked(listCustomerOwnerOptions).mockResolvedValue([
      { id: 2, displayName: '王丽', teamId: 3, teamName: '一组' },
      { id: 7, displayName: '陈敏', teamId: 3, teamName: '一组' },
      { id: 8, displayName: '赵青', teamId: 6, teamName: '二组' },
    ])
    vi.mocked(listTaskActions).mockResolvedValue([])
  })

  it('展示首次/当前处理人与接手/完成时间，并按状态提供独立按钮', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('首次负责人')
    expect(wrapper.text()).toContain('陈敏')
    expect(wrapper.text()).toContain('当前处理人')
    expect(wrapper.text()).toContain('接手时间')
    expect(wrapper.text()).toContain('完成时间')
    expect(wrapper.find('[data-testid="claim-11"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="return-12"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="complete-12"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="complete-13"]').exists()).toBe(false)

    await button(wrapper, '接手')
    expect(claimTask).toHaveBeenCalledWith(11, 4, expect.any(String))
  })

  it('普通管家不能退回或完成他人任务，也不能转派', async () => {
    vi.mocked(listTasks).mockImplementation(async ({ status }) => page(status === 'PROCESSING'
      ? [task({ id: 14, assigneeUserId: 8, status: 'PROCESSING', claimedAt: '2026-10-21T09:30:00Z' })]
      : []))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="return-14"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="complete-14"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reassign-14"]').exists()).toBe(false)
  })

  it.each([
    { role: 'MANAGER', visible: false },
    { role: 'ADMIN', visible: true },
    { role: 'MANAGER_LEAD', visible: true },
  ])('$role 的转派按钮可见性为 $visible', async ({ role, visible }) => {
    const wrapper = mountView({ ...housekeeper, roles: [role] })
    await flushPromises()

    expect(wrapper.find('[data-testid="reassign-11"]').exists()).toBe(visible)
  })

  it('转派和退回原因必填，完成只调用完成接口', async () => {
    const manager: AuthUser = { ...housekeeper, roles: ['MANAGER_LEAD'] }
    const wrapper = mountView(manager)
    await flushPromises()

    await wrapper.get('[data-testid="reassign-11"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('新任务团队')
    await wrapper.get('[data-testid="task-action-submit"]').trigger('click')
    expect(reassignTask).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="task-action-reason"]').setValue('人员请假，转交二组')
    await wrapper.get('[data-testid="task-action-assignee"]').setValue(8)
    await wrapper.get('[data-testid="task-action-submit"]').trigger('click')
    await flushPromises()
    expect(reassignTask).toHaveBeenCalledWith(11, {
      assigneeUserId: 8,
      reason: '人员请假，转交二组',
      version: 4,
    }, expect.any(String))

    await wrapper.get('[data-testid="return-12"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="task-action-submit"]').trigger('click')
    expect(returnTask).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="task-action-reason"]').setValue('客户要求换时间')
    await wrapper.get('[data-testid="task-action-submit"]').trigger('click')
    await flushPromises()
    expect(returnTask).toHaveBeenCalledWith(12, { reason: '客户要求换时间', version: 5 }, expect.any(String))

    await wrapper.get('[data-testid="complete-12"]').trigger('click')
    await flushPromises()
    expect(completeTask).toHaveBeenCalledWith(12, 5, expect.any(String))
  })

  it('修改表单不再编辑状态或负责人', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="edit-11"]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[data-testid="task-edit-dialog"]')
    expect(dialog.text()).not.toContain('任务状态')
    expect(dialog.text()).not.toContain('处理管家')
    expect(dialog.text()).toContain('任务类型')
    expect(dialog.text()).toContain('优先级')
  })

  it('流转抽屉按服务端 action/fromStatus/toStatus 展示完整日志', async () => {
    vi.mocked(listTaskActions).mockResolvedValue([{
      id: 89,
      taskId: 11,
      action: 'CLAIM',
      fromStatus: 'OPEN',
      toStatus: 'PROCESSING',
      actorUserId: 7,
      fromAssigneeUserId: null,
      toAssigneeUserId: 7,
      reason: null,
      createdAt: '2026-10-21T09:30:00Z',
    }, {
      id: 90,
      taskId: 11,
      action: 'REASSIGN',
      fromStatus: 'PROCESSING',
      toStatus: 'PROCESSING',
      actorUserId: 2,
      fromAssigneeUserId: 7,
      toAssigneeUserId: 8,
      fromTaskTeamId: 3,
      toTaskTeamId: 6,
      reason: '请假转派',
      createdAt: '2026-10-22T09:00:00Z',
    }])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="actions-11"]').trigger('click')
    await flushPromises()

    const history = wrapper.get('[data-testid="task-action-history"]')
    expect(listTaskActions).toHaveBeenCalledWith(11)
    expect(history.text()).toContain('接手')
    expect(history.text()).toContain('转派')
    expect(history.text()).toContain('状态：待处理 → 处理中')
    expect(history.text()).toContain('状态：处理中 → 处理中')
    expect(history.text()).toContain('处理人：陈敏 → 赵青')
    expect(history.text()).toContain('操作人：王丽')
    expect(history.text()).toContain('原因：请假转派')
    expect(history.text()).toContain('原因：—')
    expect(history.findAll('time')).toHaveLength(2)
    expect(history.findAll('time').every((time) => time.text().includes('2026'))).toBe(true)
  })
})
