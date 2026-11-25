import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listWecomRooms, saveWecomRoom } from '../src/api/wecomRoom'
import type { WecomRoom } from '../src/api/wecomRoom'
import WecomRoomPanel from '../src/views/wecom/WecomRoomPanel.vue'

vi.mock('../src/api/wecomRoom', () => ({ listWecomRooms: vi.fn(), saveWecomRoom: vi.fn() }))
vi.mock('element-plus', async (importOriginal) => ({ ...(await importOriginal<typeof import('element-plus')>()), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const room: WecomRoom = {
  corpId: 'MOCK-CORP-001', roomId: 'wr-synthetic', displayName: '开发测试群', enabled: true, archivable: true,
  membershipConfirmedAt: '2026-11-13T08:00:00Z', version: 0,
  participants: [
    { wecomUserid: 'staff-2', participantType: 'STAFF', staffUserId: 2, externalUserid: null, status: 'ACTIVE' },
    { wecomUserid: 'wm-synthetic', participantType: 'EXTERNAL', staffUserId: null, externalUserid: 'wm-synthetic', status: 'ACTIVE' },
    { wecomUserid: 'wm-removed', participantType: 'EXTERNAL', staffUserId: null, externalUserid: 'wm-removed', status: 'REMOVED' },
  ],
}

describe('WecomRoomPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listWecomRooms).mockResolvedValue([room])
    vi.mocked(saveWecomRoom).mockResolvedValue({ ...room, version: 1, archivable: false, enabled: false })
  })

  it('展示实际群聊身份与历史退出成员，不把群映射成客户', async () => {
    const wrapper = mount(WecomRoomPanel, { props: { corpId: room.corpId, canWrite: true }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listWecomRooms).toHaveBeenCalledWith(room.corpId)
    expect(wrapper.text()).toContain('开发测试群')
    expect(wrapper.text()).toContain('已映射 CRM 员工 #2')
    expect(wrapper.text()).toContain('不代表已关联某个 CRM 客户')
    expect(wrapper.text()).toContain('已退出成员（1）')
    expect(wrapper.text()).toContain('这里没有自动读取群成员名单')
    await wrapper.get('[data-testid="wecom-room-edit"]').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('[data-testid="wecom-room-members"]').element.value).toBe('staff-2\nwm-synthetic')
    expect(wrapper.get<HTMLInputElement>('[data-testid="wecom-room-id"]').element.disabled).toBe(true)
  })

  it('按完整清单和版本保存，失败保留同一幂等键', async () => {
    const wrapper = mount(WecomRoomPanel, { props: { corpId: room.corpId, canWrite: true }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-testid="wecom-room-edit"]').trigger('click')
    await wrapper.get('[data-testid="wecom-room-evidence"]').setValue('synthetic://room-evidence')
    await wrapper.get('[data-testid="wecom-room-reason"]').setValue('核对群成员变更')
    vi.mocked(saveWecomRoom).mockRejectedValueOnce(new Error('网络失败'))
    await wrapper.get('[data-testid="wecom-room-form"]').trigger('submit')
    await flushPromises()
    expect(saveWecomRoom).toHaveBeenNthCalledWith(1, room.roomId, {
      corpId: room.corpId, displayName: room.displayName, enabled: true,
      memberUserids: ['staff-2', 'wm-synthetic'], sourceEvidenceRef: 'synthetic://room-evidence',
      version: 0, reason: '核对群成员变更',
    }, expect.any(String))
    expect(wrapper.text()).toContain('重新提交不会重复保存')
    const firstKey = vi.mocked(saveWecomRoom).mock.calls[0]?.[2]
    await wrapper.get('[data-testid="wecom-room-form"]').trigger('submit')
    await flushPromises()
    expect(vi.mocked(saveWecomRoom).mock.calls[1]?.[2]).toBe(firstKey)
    expect(wrapper.text()).not.toContain('重新提交不会重复保存')
  })

  it('只读员工不能新增或修改配置', async () => {
    const wrapper = mount(WecomRoomPanel, { props: { corpId: room.corpId, canWrite: false }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="wecom-room-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wecom-room-edit"]').exists()).toBe(false)
    expect(saveWecomRoom).not.toHaveBeenCalled()
  })
})
