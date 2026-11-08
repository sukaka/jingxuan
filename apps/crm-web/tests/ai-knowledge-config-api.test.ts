import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { getKnowledgeConfig, saveKnowledgeConfig, testKnowledgeConfig } from '../src/api/knowledge'
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
      data: { code: 'OK', message: 'success', data: {}, requestId: 'knowledge-config-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('Dify 知识配置 API 契约', () => {
  it('使用独立路径、乐观锁和幂等键', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await getKnowledgeConfig()
    await saveKnowledgeConfig({
      provider: 'dify',
      baseUrl: 'https://dify.example.test/v1',
      timeoutSeconds: 45,
      enabled: true,
      version: 7,
    }, 'save-key')
    await testKnowledgeConfig('test-key')

    expect(calls[0]).toMatchObject({ method: 'get', url: '/ai/knowledge/config' })
    expect(calls[1]).toMatchObject({
      method: 'put', url: '/ai/knowledge/config', key: 'save-key',
      data: { provider: 'dify', timeoutSeconds: 45, enabled: true, version: 7 },
    })
    expect(calls[1].data).not.toHaveProperty('apiKey')
    expect(calls[1].data).not.toHaveProperty('appApiKeyCipher')
    expect(calls[2]).toMatchObject({ method: 'post', url: '/ai/knowledge/config/test', key: 'test-key', data: {} })
  })

  it('只有显式填写新 Key 时才发送给服务端加密', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await saveKnowledgeConfig({
      provider: 'dify',
      baseUrl: 'https://dify.example.test/v1',
      apiKey: 'new-dify-test-key',
      timeoutSeconds: 30,
      enabled: false,
      version: 0,
    }, 'replace-key')

    expect(calls[0].data.apiKey).toBe('new-dify-test-key')
  })
})
