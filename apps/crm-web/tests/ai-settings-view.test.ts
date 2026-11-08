import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AiProviderConfig } from '../src/api/ai'
import { getAiConfig, saveAiConfig, testAiConfig } from '../src/api/ai'
import AiSettingsView from '../src/views/AiSettingsView.vue'

vi.mock('../src/api/ai', () => ({
  getAiConfig: vi.fn(),
  saveAiConfig: vi.fn(),
  testAiConfig: vi.fn(),
}))

const config: AiProviderConfig = {
  provider: 'openai_compatible',
  baseUrl: 'https://llm.example.test/v1',
  apiKeyConfigured: true,
  model: 'test-model',
  timeoutSeconds: 30,
  enabled: false,
  connectionStatus: 'FAILED',
  lastErrorMasked: '连接失败，请检查地址或网络',
  lastConnectedAt: null,
  version: 3,
}

function mountView() {
  return mount(AiSettingsView, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('AiSettingsView 管理员大模型配置', () => {
  it('只展示 Key 配置状态，留空保存表示保持原 Key 不变', async () => {
    vi.mocked(getAiConfig).mockResolvedValue(config)
    vi.mocked(saveAiConfig).mockResolvedValue({ ...config, version: 4 })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('已配置，留空保持不变')
    expect(wrapper.text()).toContain('连接失败，请检查地址或网络')
    expect((wrapper.get('[data-testid="ai-api-key"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).not.toContain('api_key_cipher')

    await wrapper.get('[data-testid="ai-model"]').setValue('next-model')
    await wrapper.get('[data-testid="ai-config-save"]').trigger('click')
    await flushPromises()

    const [body, key] = vi.mocked(saveAiConfig).mock.calls[0]
    expect(body).toMatchObject({ model: 'next-model', version: 3, timeoutSeconds: 30 })
    expect(body).not.toHaveProperty('apiKey')
    expect(key).toEqual(expect.any(String))
    expect(testAiConfig).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存成功后可直接测试，并刷新连接状态和版本', async () => {
    vi.mocked(getAiConfig).mockResolvedValue(config)
    vi.mocked(saveAiConfig).mockResolvedValue({ ...config, version: 4, connectionStatus: 'UNVERIFIED', lastErrorMasked: null })
    vi.mocked(testAiConfig).mockResolvedValue({
      ...config,
      version: 4,
      connectionStatus: 'CONNECTED',
      lastErrorMasked: null,
      lastConnectedAt: '2026-10-26T10:00:00+08:00',
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="ai-config-save-test"]').trigger('click')
    await flushPromises()

    expect(saveAiConfig).toHaveBeenCalledTimes(1)
    expect(testAiConfig).toHaveBeenCalledWith(expect.any(String))
    expect(wrapper.text()).toContain('连接正常')
    expect(wrapper.text()).toContain('配置已保存并测试通过')
    wrapper.unmount()
  })

  it('连接测试返回 FAILED 时保留已保存事实并明确展示失败', async () => {
    vi.mocked(getAiConfig).mockResolvedValue(config)
    vi.mocked(saveAiConfig).mockResolvedValue({ ...config, version: 4, connectionStatus: 'UNVERIFIED', lastErrorMasked: null })
    vi.mocked(testAiConfig).mockResolvedValue({ ...config, version: 4, connectionStatus: 'FAILED', lastErrorMasked: '模型服务连接失败' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="ai-config-save-test"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('配置已保存，但连接测试未通过')
    expect(wrapper.text()).toContain('模型服务连接失败')
    expect(wrapper.text()).not.toContain('配置已保存并测试通过')
    wrapper.unmount()
  })

  it('OpenAI 兼容模式缺少地址、模型或初始 Key 时不发保存请求', async () => {
    vi.mocked(getAiConfig).mockResolvedValue({ ...config, baseUrl: '', model: '', apiKeyConfigured: false })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="ai-config-save"]').trigger('click')
    await flushPromises()

    expect(saveAiConfig).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写接口地址、模型和 API Key')
    wrapper.unmount()
  })
})
