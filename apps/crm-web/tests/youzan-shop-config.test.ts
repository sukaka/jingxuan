import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { YouzanShopConfig, YouzanSyncPolicy } from '../src/api/youzan'
import { getYouzanSyncPolicy, saveYouzanShop, saveYouzanSyncPolicy, testYouzanShop } from '../src/api/youzan'
import { formatDateTime } from '../src/views/customer/shared'
import YouzanShopConfigPanel from '../src/views/youzan/YouzanShopConfigPanel.vue'
import YouzanSyncPolicyPanel from '../src/views/youzan/YouzanSyncPolicyPanel.vue'

vi.mock('../src/api/youzan', () => ({
  saveYouzanShop: vi.fn(),
  testYouzanShop: vi.fn(),
  getYouzanSyncPolicy: vi.fn(),
  saveYouzanSyncPolicy: vi.fn(),
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

  it('新建店铺拒绝纯空格密钥，并对凭证字段自动去空白', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    vi.mocked(saveYouzanShop).mockResolvedValue(shop)
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-testid="youzan-shop-create"]').trigger('click')
    await flushPromises()
    await setDialogInput(0, ' 123456 ')
    await setDialogInput(1, ' 虚构测试店 ')
    await setDialogInput(2, ' client-test ')
    await setDialogInput(3, '   ')
    await clickDialogTestId('youzan-shop-save')

    expect(saveYouzanShop).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('请完整填写店铺、应用和密钥信息')

    await setDialogInput(3, '  trimmed-secret  ')
    await clickDialogTestId('youzan-shop-save')
    expect(saveYouzanShop).toHaveBeenCalledWith(expect.objectContaining({
      shopId: '123456',
      shopName: '虚构测试店',
      clientId: 'client-test',
      clientSecret: 'trimmed-secret',
      syncEnabled: false,
    }), expect.any(String))
    wrapper.unmount()
  })

  it('编辑店铺回显已配置的回调路径，且纯空格密钥按不变处理', async () => {
    const customShop: YouzanShopConfig = { ...shop, webhookPath: '/api/v1/webhooks/youzan/official?tenant=custom' }
    vi.mocked(saveYouzanShop).mockResolvedValue({ ...customShop, connectionStatus: 'CONNECTED', version: 1 })
    const wrapper = mount(YouzanShopConfigPanel, {
      props: { shops: [customShop], loading: false, canWrite: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()
    const edit = wrapper.findAll('button').find(button => button.text() === '编辑')
    await edit?.trigger('click')
    await flushPromises()

    const webhookInput = document.body.querySelector<HTMLInputElement>('[data-testid="youzan-shop-webhook-path"]')
    expect(webhookInput?.value).toBe('/api/v1/webhooks/youzan/official?tenant=custom')
    expect(document.body.textContent).toContain('HTTP 200 {"code":0,"msg":"success"}')
    expect(document.body.textContent).toContain('IP 白名单')

    await setDialogInput(3, '   ')
    await clickDialogTestId('youzan-shop-save')
    expect(saveYouzanShop).toHaveBeenCalledWith(expect.objectContaining({
      shopId: '123456',
      clientSecret: undefined,
      version: 0,
    }), expect.any(String))
    wrapper.unmount()
  })
})

describe('YouzanSyncPolicyPanel 同步策略展示与保存', () => {
  it('格式化展示最近调度时间，并在保存后同步服务端返回的下次执行时间', async () => {
    const policy: YouzanSyncPolicy = {
      id: 1,
      shopId: '123456',
      initialStartTime: '2026-01-01T00:00:00Z',
      intervalSeconds: 300,
      overlapSeconds: 300,
      pageSize: 50,
      enabled: true,
      nextRunAt: '2026-11-01T07:00:00Z',
      lastScheduledAt: '2026-11-01T06:30:00Z',
      version: 0,
    }
    vi.mocked(getYouzanSyncPolicy).mockResolvedValue(policy)
    vi.mocked(saveYouzanSyncPolicy)
      .mockResolvedValueOnce({ ...policy, nextRunAt: '2026-11-01T09:15:00.000Z', lastScheduledAt: '2026-11-01T08:00:00Z', version: 1 })
      .mockResolvedValueOnce({ ...policy, nextRunAt: '2026-11-01T09:15:00.000Z', lastScheduledAt: '2026-11-01T08:00:00Z', version: 2 })

    const wrapper = mount(YouzanSyncPolicyPanel, {
      props: { shopId: '123456', canWrite: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.text()).toContain(formatDateTime('2026-11-01T06:30:00Z'))
    expect(wrapper.text()).not.toContain('2026-11-01T06:30:00Z')

    await wrapper.get('[data-testid="youzan-policy-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(formatDateTime('2026-11-01T08:00:00Z'))

    await wrapper.get('[data-testid="youzan-policy-save"]').trigger('click')
    await flushPromises()
    expect(saveYouzanSyncPolicy).toHaveBeenNthCalledWith(2, '123456', expect.objectContaining({
      nextRunAt: '2026-11-01T09:15:00.000Z',
      version: 1,
    }), expect.any(String))
    wrapper.unmount()
  })
})
