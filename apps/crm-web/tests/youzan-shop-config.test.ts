import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { YouzanShopConfig } from '../src/api/youzan'
import { saveYouzanShop, testYouzanShop } from '../src/api/youzan'
import YouzanShopConfigPanel from '../src/views/youzan/YouzanShopConfigPanel.vue'

vi.mock('../src/api/youzan', () => ({
  saveYouzanShop: vi.fn(),
  testYouzanShop: vi.fn(),
}))

const shop: YouzanShopConfig = {
  id: 1,
  shopId: '123456',
  shopName: '虚构测试店',
  clientId: 'client-test',
  secretConfigured: true,
  tokenExpiresAt: null,
  connectionStatus: 'UNVERIFIED',
  syncEnabled: false,
  lastError: null,
  lastConnectedAt: null,
  webhookPath: '/api/v1/webhooks/youzan/official',
  version: 0,
}

function mountPanel() {
  return mount(YouzanShopConfigPanel, {
    props: { shops: [shop], loading: false, canWrite: true },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

async function setDialogInput(index: number, value: string) {
  const input = document.body.querySelectorAll<HTMLInputElement>('.el-dialog input')[index]
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function clickDialogTestId(testId: string) {
  const button = document.body.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

describe('YouzanShopConfigPanel 店铺接入配置', () => {
  it('只显示密钥配置状态，不回显秘密或Token', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('虚构测试店')
    expect(wrapper.text()).toContain('已配置')
    expect(wrapper.text()).not.toContain('clientSecretCipher')
    expect(wrapper.text()).not.toContain('accessToken')
    wrapper.unmount()
  })

  it('新建店铺提交凭证和同步开关', async () => {
    vi.mocked(saveYouzanShop).mockResolvedValue(shop)
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-testid="youzan-shop-create"]').trigger('click')
    await flushPromises()
    await setDialogInput(0, '123456')
    await setDialogInput(1, '虚构测试店')
    await setDialogInput(2, 'client-test')
    await setDialogInput(3, '虚构密钥')
    await clickDialogTestId('youzan-shop-save')

    expect(saveYouzanShop).toHaveBeenCalledWith(expect.objectContaining({
      shopId: '123456', shopName: '虚构测试店', clientId: 'client-test', clientSecret: '虚构密钥', syncEnabled: false,
    }), expect.any(String))
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })

  it('编辑时密钥留空表示不变，并可发起连接测试', async () => {
    vi.mocked(saveYouzanShop).mockResolvedValue({ ...shop, version: 1 })
    vi.mocked(testYouzanShop).mockResolvedValue({ ...shop, connectionStatus: 'CONNECTED' })
    const wrapper = mountPanel()
    await flushPromises()
    const edit = wrapper.findAll('button').find(button => button.text() === '编辑')
    await edit?.trigger('click')
    await flushPromises()
    await clickDialogTestId('youzan-shop-save')
    expect(saveYouzanShop).toHaveBeenCalledWith(expect.objectContaining({ clientSecret: undefined, version: 0 }), expect.any(String))

    await wrapper.get('[data-testid="youzan-shop-test"]').trigger('click')
    await flushPromises()
    expect(testYouzanShop).toHaveBeenCalledWith('123456', expect.any(String))
    wrapper.unmount()
  })
})
