import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Channel } from '../src/api/channel'
import { createChannel, updateChannel } from '../src/api/channel'
import ChannelFormDialog from '../src/views/channel/ChannelFormDialog.vue'

vi.mock('../src/api/channel', () => ({
  createChannel: vi.fn(),
  updateChannel: vi.fn(),
  listChannelOwnerOptions: vi.fn().mockResolvedValue([{ id: 6, displayName: '孙周' }]),
}))

vi.mock('../src/api/dictionary', () => ({
  getDictionaryOptions: vi.fn().mockImplementation((code: string) => Promise.resolve(
    code === 'settlementTypes'
      ? [{ code: 'MONTHLY', label: '月结', sortNo: 1 }]
      : [{ code: 'XHS', label: '小红书', sortNo: 1 }],
  )),
}))

function mountDialog() {
  return mount(ChannelFormDialog, {
    props: { modelValue: true, channel: null },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

async function fill(testId: string, value: string) {
  const root = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  const input = root?.matches('input')
    ? root as HTMLInputElement
    : root?.querySelector<HTMLInputElement>('input')
  expect(input, `缺少 ${testId}`).not.toBeNull()
  input!.value = value
  input!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('ChannelFormDialog 合作资料校验', () => {
  it('拒绝 12 位手机号和 8 位身份证，修正后才提交完整资料', async () => {
    vi.mocked(createChannel).mockResolvedValue({ id: 1 } as Channel)
    const wrapper = mountDialog()
    await flushPromises()
    await fill('channel-form-name', '测试博主')
    await fill('channel-form-phone', '138000000000')
    await fill('channel-form-id-card', '12345678')
    await wrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()

    expect(createChannel).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('有效的 11 位中国大陆手机号')

    await fill('channel-form-phone', '13800000000')
    await wrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()
    expect(createChannel).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('有效的 18 位身份证号')

    await fill('channel-form-id-card', '11010519491231002x')
    await wrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()

    expect(createChannel).toHaveBeenCalledTimes(1)
    expect(vi.mocked(createChannel).mock.calls[0]?.[0]).toMatchObject({
      name: '测试博主',
      contactPhone: '13800000000',
      idCardNumber: '11010519491231002X',
    })
    expect(updateChannel).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('分销可选择电汇每月两次', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.get('[data-testid="channel-form-type"]').trigger('click')
    await flushPromises()
    const distribution = Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item'))
      .find((node) => node.textContent?.trim() === '分销')
    expect(distribution).not.toBeNull()
    distribution!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    await wrapper.get('[data-testid="channel-form-settlement"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('电汇每月两次')
    wrapper.unmount()
  })
})
