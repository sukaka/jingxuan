import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Channel } from '../src/api/channel'
import { createChannel, createChannelFinancialChangeRequest, updateChannel } from '../src/api/channel'
import ChannelFinanceChangeDialog from '../src/views/channel/ChannelFinanceChangeDialog.vue'
import ChannelFormDialog from '../src/views/channel/ChannelFormDialog.vue'

vi.mock('../src/api/channel', () => ({
  createChannel: vi.fn(),
  updateChannel: vi.fn(),
  createChannelFinancialChangeRequest: vi.fn(),
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

  it('老带新与分销一样支持电汇月结和每月两次', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.get('[data-testid="channel-form-type"]').trigger('click')
    await flushPromises()
    const referral = Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item'))
      .find((node) => node.textContent?.trim() === '老带新（老客带新客）')
    expect(referral).not.toBeNull()
    referral!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(wrapper.get('[data-testid="channel-form-settlement"]').classes()).not.toContain('is-disabled')
    await wrapper.get('[data-testid="channel-form-settlement"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('电汇月结')
    expect(document.body.textContent).toContain('电汇每月两次')
    wrapper.unmount()
  })

  it('编辑已配置财务信息/合作中的渠道时：不回填脱敏值、锁定财务字段、PATCH 省略留空敏感字段', async () => {
    vi.mocked(updateChannel).mockResolvedValue({ id: 91 } as Channel)
    const activeChannel: Channel = {
      id: 91,
      channelNo: 'CH-91',
      name: '已合作达人',
      channelType: 'BLOGGER',
      platformCode: 'XHS',
      commissionPercent: 12.5,
      businessOwnerUserId: 6,
      businessOwnerName: '孙周',
      realName: '张三',
      contactPhone: '138****0000',
      contactPhoneConfigured: true,
      idCardNumber: '110105********002X',
      idCardConfigured: true,
      settlementMethod: 'MONTHLY',
      cooperationStartDate: '2026-01-01',
      cooperationEndDate: '2026-12-31',
      paymentAccountName: '张*',
      paymentAccountNo: '****0001',
      paymentAccountConfigured: true,
      paymentBankName: '招商银行',
      status: 'ACTIVE',
      remarks: '原备注',
      createdAt: '2026-01-01T00:00:00Z',
      version: 3,
    }
    const wrapper = mount(ChannelFormDialog, {
      props: { modelValue: false, channel: activeChannel },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const queryField = (id: string) => {
      const root = document.body.querySelector<HTMLElement>(`[data-testid="${id}"]`)
      return root?.matches('input') ? root as HTMLInputElement : root?.querySelector<HTMLInputElement>('input') ?? null
    }
    const phoneInput = queryField('channel-form-phone')
    const idCardInput = queryField('channel-form-id-card')
    const accountNoInput = queryField('channel-form-account-no')
    expect(phoneInput?.value).toBe('')
    expect(idCardInput?.value).toBe('')
    expect(accountNoInput?.value).toBe('')
    expect(accountNoInput?.disabled).toBe(true)
    expect(document.body.querySelector('[data-testid="channel-finance-locked-alert"]')).not.toBeNull()

    await fill('channel-form-name', '已合作达人（改名）')
    await wrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()

    expect(updateChannel).toHaveBeenCalledTimes(1)
    const sent = vi.mocked(updateChannel).mock.calls[0]?.[1]
    expect(sent).toMatchObject({ name: '已合作达人（改名）', version: 3 })
    expect(sent).not.toHaveProperty('contactPhone')
    expect(sent).not.toHaveProperty('idCardNumber')
    expect(sent).not.toHaveProperty('commissionPercent')
    expect(sent).not.toHaveProperty('paymentAccountNo')
    expect(sent).not.toHaveProperty('paymentAccountName')
    wrapper.unmount()
  })

  it('新建与未锁定 INACTIVE 编辑模式下拒绝包含脱敏星号的收款账号和户名', async () => {
    vi.mocked(createChannel).mockResolvedValue({ id: 1 } as Channel)
    vi.mocked(updateChannel).mockResolvedValue({ id: 92 } as Channel)

    const createWrapper = mountDialog()
    await flushPromises()
    await fill('channel-form-name', '新渠道')
    await fill('channel-form-account-name', '李*')
    await fill('channel-form-account-no', '6222********9999')
    await createWrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()

    expect(createChannel).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('不能包含脱敏占位符 *')
    createWrapper.unmount()

    const unlockedInactive: Channel = {
      id: 92,
      channelNo: 'CH-92',
      name: '未配置财务的暂停渠道',
      channelType: 'BLOGGER',
      platformCode: null,
      commissionPercent: null,
      businessOwnerUserId: null,
      businessOwnerName: null,
      realName: null,
      contactPhone: null,
      contactPhoneConfigured: false,
      idCardNumber: null,
      idCardConfigured: false,
      settlementMethod: null,
      cooperationStartDate: null,
      cooperationEndDate: null,
      paymentAccountName: null,
      paymentAccountNo: null,
      paymentAccountConfigured: false,
      paymentBankName: null,
      status: 'INACTIVE',
      remarks: null,
      createdAt: '2026-01-01T00:00:00Z',
      version: 1,
    }
    const editWrapper = mount(ChannelFormDialog, {
      props: { modelValue: true, channel: unlockedInactive },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()

    await fill('channel-form-account-name', '李*')
    await editWrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()
    expect(updateChannel).not.toHaveBeenCalled()

    await fill('channel-form-account-name', '李四')
    await fill('channel-form-account-no', '6222********9999')
    await editWrapper.get('[data-testid="channel-save"]').trigger('click')
    await flushPromises()
    expect(updateChannel).not.toHaveBeenCalled()
    editWrapper.unmount()
  })

  it('ChannelFinanceChangeDialog 拒绝包含脱敏星号的收款账号与户名且不把脱敏值当作提示', async () => {
    vi.mocked(createChannelFinancialChangeRequest).mockResolvedValue({ id: 501 } as never)
    const channel: Channel = {
      id: 93,
      channelNo: 'CH-93',
      name: '已配置渠道',
      channelType: 'BLOGGER',
      platformCode: 'XHS',
      commissionPercent: 10,
      businessOwnerUserId: 6,
      businessOwnerName: '孙周',
      realName: '李四',
      contactPhone: '138****0000',
      contactPhoneConfigured: true,
      idCardNumber: null,
      idCardConfigured: false,
      settlementMethod: 'MONTHLY',
      cooperationStartDate: null,
      cooperationEndDate: null,
      paymentAccountName: '李*',
      paymentAccountNo: '6222********9999',
      paymentAccountConfigured: true,
      paymentBankName: '招商银行',
      status: 'ACTIVE',
      remarks: null,
      createdAt: '2026-01-01T00:00:00Z',
      version: 2,
    }
    const wrapper = mount(ChannelFinanceChangeDialog, {
      props: { modelValue: true, channel },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()

    const accountNameInput = document.body.querySelector<HTMLInputElement>('[data-testid="finance-account-name"] input, input[data-testid="finance-account-name"]')
    const accountNoInput = document.body.querySelector<HTMLInputElement>('[data-testid="finance-account-no"] input, input[data-testid="finance-account-no"]')
    expect(accountNameInput?.placeholder).not.toContain('李*')
    expect(accountNoInput?.placeholder).not.toContain('6222********9999')

    const reasonRoot = document.body.querySelector<HTMLElement>('[data-testid="finance-reason"]')!
    const reasonInput = reasonRoot.matches('textarea') ? reasonRoot as HTMLTextAreaElement : reasonRoot.querySelector<HTMLTextAreaElement>('textarea')!
    reasonInput.value = '变更收款账号'
    reasonInput.dispatchEvent(new Event('input', { bubbles: true }))

    await fill('finance-account-no', '6222********9999')
    document.body.querySelector<HTMLElement>('[data-testid="finance-submit"]')!.click()
    await flushPromises()
    expect(createChannelFinancialChangeRequest).not.toHaveBeenCalled()
    expect(document.body.querySelector('[data-testid="channel-finance-error"]')?.textContent).toContain('脱敏占位符 *')

    await fill('finance-account-no', '')
    await fill('finance-account-name', '李*')
    document.body.querySelector<HTMLElement>('[data-testid="finance-submit"]')!.click()
    await flushPromises()
    expect(createChannelFinancialChangeRequest).not.toHaveBeenCalled()
    expect(document.body.querySelector('[data-testid="channel-finance-error"]')?.textContent).toContain('脱敏占位符 *')
    wrapper.unmount()
  })
})
