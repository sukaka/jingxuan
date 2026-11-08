import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { getAiConfig, saveAiConfig, testAiConfig } from '../src/api/ai'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture() {
  const calls: { method: string; url: string; data: Record<string, unknown>; key: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : {},
      key: config.headers?.get('Idempotency-Key'),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: {}, requestId: 'ai-config-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('AI 管理配置 API 契约', () => {
  it('读取、乐观锁保存与连接测试使用约定路径和幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await getAiConfig()
    await saveAiConfig({
      provider: 'openai_compatible',
      baseUrl: 'https://llm.example.test/v1',
      model: 'test-model',
      timeoutSeconds: 45,
      enabled: true,
      version: 7,
    }, 'save-key')
    await testAiConfig('test-key')

    expect(calls[0]).toMatchObject({ method: 'get', url: '/ai/config' })
    expect(calls[1]).toMatchObject({
      method: 'put',
      url: '/ai/config',
      key: 'save-key',
      data: {
        provider: 'openai_compatible',
        baseUrl: 'https://llm.example.test/v1',
        model: 'test-model',
        timeoutSeconds: 45,
        enabled: true,
        version: 7,
      },
    })
    expect(calls[1].data).not.toHaveProperty('apiKey')
    expect(calls[1].data).not.toHaveProperty('apiKeyCipher')
    expect(calls[2]).toMatchObject({ method: 'post', url: '/ai/config/test', key: 'test-key', data: {} })
  })

  it('只有管理员显式填写新 Key 时才发送 apiKey 明文给服务端加密', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await saveAiConfig({
      provider: 'openai_compatible',
      baseUrl: 'https://llm.example.test/v1',
      apiKey: 'new-test-key',
      model: 'test-model',
      timeoutSeconds: 30,
      enabled: false,
      version: 0,
    }, 'replace-key')

    expect(calls[0].data.apiKey).toBe('new-test-key')
  })
})
