import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DifyKnowledgeConfig } from '../src/api/knowledge'
import { getKnowledgeConfig, saveKnowledgeConfig, testKnowledgeConfig } from '../src/api/knowledge'
import AiKnowledgeSettingsView from '../src/views/AiKnowledgeSettingsView.vue'

vi.mock('../src/api/knowledge', () => ({
  getKnowledgeConfig: vi.fn(),
  saveKnowledgeConfig: vi.fn(),
  testKnowledgeConfig: vi.fn(),
}))

const config: DifyKnowledgeConfig = {
  provider: 'dify',
  baseUrl: 'https://dify.example.test/v1',
  apiKeyConfigured: true,
  datasetId: null,
  datasetApiKeyConfigured: false,
  syncEnabled: false,
  timeoutSeconds: 30,
  enabled: false,
  connectionStatus: 'FAILED',
  lastErrorMasked: 'Dify 连接失败，请检查配置或网络',
  lastConnectedAt: null,
  version: 3,
}

function mountView() {
  return mount(AiKnowledgeSettingsView, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('AiKnowledgeSettingsView 管理员 Dify 配置', () => {
  it('Key 不回显，留空保存表示保持原 Key', async () => {
    vi.mocked(getKnowledgeConfig).mockResolvedValue(config)
    vi.mocked(saveKnowledgeConfig).mockResolvedValue({ ...config, version: 4 })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('已配置，留空保持不变')
    expect(wrapper.text()).toContain('Dify 连接失败，请检查配置或网络')
    expect((wrapper.get('[data-testid="dify-api-key"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).not.toContain('app_api_key_cipher')

    await wrapper.get('[data-testid="dify-config-save"]').trigger('click')
    await flushPromises()

    const [body, key] = vi.mocked(saveKnowledgeConfig).mock.calls[0]
    expect(body).toMatchObject({ baseUrl: config.baseUrl, version: 3, timeoutSeconds: 30 })
    expect(body).not.toHaveProperty('apiKey')
    expect(key).toEqual(expect.any(String))
    expect(testKnowledgeConfig).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存并测试后刷新连接状态和版本', async () => {
    vi.mocked(getKnowledgeConfig).mockResolvedValue(config)
    vi.mocked(saveKnowledgeConfig).mockResolvedValue({ ...config, version: 4, connectionStatus: 'UNVERIFIED', lastErrorMasked: null })
    vi.mocked(testKnowledgeConfig).mockResolvedValue({
      ...config, version: 5, connectionStatus: 'CONNECTED', lastErrorMasked: null,
      lastConnectedAt: '2026-10-27T10:00:00+08:00',
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="dify-config-save-test"]').trigger('click')
    await flushPromises()

    expect(saveKnowledgeConfig).toHaveBeenCalledTimes(1)
    expect(testKnowledgeConfig).toHaveBeenCalledWith(expect.any(String))
    expect(wrapper.text()).toContain('连接正常')
    expect(wrapper.text()).toContain('配置版本5')
    expect(wrapper.text()).toContain('配置已保存并测试通过')
    wrapper.unmount()
  })

  it('连接测试 FAILED 时保留失败事实且不显示成功', async () => {
    vi.mocked(getKnowledgeConfig).mockResolvedValue(config)
    vi.mocked(saveKnowledgeConfig).mockResolvedValue({ ...config, version: 4, connectionStatus: 'UNVERIFIED', lastErrorMasked: null })
    vi.mocked(testKnowledgeConfig).mockResolvedValue({ ...config, version: 5, connectionStatus: 'FAILED', lastErrorMasked: '真实 Dify 接入未启用' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="dify-config-save-test"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('配置已保存，但连接测试未通过')
    expect(wrapper.text()).toContain('真实 Dify 接入未启用')
    expect(wrapper.text()).not.toContain('配置已保存并测试通过')
    wrapper.unmount()
  })

  it('Dify 缺少地址或初始 Key 时不发保存请求', async () => {
    vi.mocked(getKnowledgeConfig).mockResolvedValue({ ...config, baseUrl: null, apiKeyConfigured: false })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="dify-config-save"]').trigger('click')
    await flushPromises()

    expect(saveKnowledgeConfig).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写 Dify 接口地址和 API Key')
    wrapper.unmount()
  })

  it('启用资料同步但缺少 Dataset Key 时不发保存请求', async () => {
    vi.mocked(getKnowledgeConfig).mockResolvedValue({ ...config, datasetId: 'dataset-1', syncEnabled: true })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="dify-config-save"]').trigger('click')
    await flushPromises()

    expect(saveKnowledgeConfig).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写知识数据集 ID 和知识数据集 API 密钥')
    wrapper.unmount()
  })
})
