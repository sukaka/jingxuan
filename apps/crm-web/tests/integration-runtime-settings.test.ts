import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IntegrationRuntimeConfig } from '../src/api/integration'
import { getIntegrationRuntimeConfig, saveIntegrationRuntimeConfig } from '../src/api/integration'
import IntegrationRuntimeSettings from '../src/components/IntegrationRuntimeSettings.vue'

vi.mock('../src/api/integration', () => ({
  getIntegrationRuntimeConfig: vi.fn(),
  saveIntegrationRuntimeConfig: vi.fn(),
}))

const config: IntegrationRuntimeConfig = {
  youzanRealEnabled: false,
  wecomArchiveRealEnabled: false,
  wecomOfficialEnabled: false,
  version: 0,
  source: 'ENVIRONMENT',
}

function mountPanel(provider: 'youzan' | 'wecom') {
  return mount(IntegrationRuntimeSettings, {
    props: { provider },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('IntegrationRuntimeSettings 全局集成开关', () => {
  it('有赞开关保存完整配置和当前版本并显示页面配置来源', async () => {
    vi.mocked(getIntegrationRuntimeConfig).mockResolvedValue(config)
    vi.mocked(saveIntegrationRuntimeConfig).mockResolvedValue({
      ...config,
      youzanRealEnabled: true,
      version: 1,
      source: 'DATABASE',
    })
    const wrapper = mountPanel('youzan')
    await flushPromises()

    expect(wrapper.text()).toContain('环境变量默认值')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="integration-runtime-save"]').trigger('click')
    await flushPromises()

    const [body, key] = vi.mocked(saveIntegrationRuntimeConfig).mock.calls[0]
    expect(body).toEqual({
      youzanRealEnabled: true,
      wecomArchiveRealEnabled: false,
      wecomOfficialEnabled: false,
      version: 0,
    })
    expect(key).toEqual(expect.any(String))
    expect(wrapper.text()).toContain('配置来源：页面配置')
    wrapper.unmount()
  })

  it('企微页同时提供客户联系和会话归档开关并保留授权提示', async () => {
    vi.mocked(getIntegrationRuntimeConfig).mockResolvedValue(config)
    const wrapper = mountPanel('wecom')
    await flushPromises()

    expect(wrapper.find('[data-testid="wecom-official-enabled"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wecom-archive-real-enabled"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Q10')
    expect(wrapper.text()).toContain('开启不代表已采集真实聊天')
    expect(wrapper.find('[data-testid="youzan-real-enabled"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
