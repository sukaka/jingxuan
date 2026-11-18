import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createQuickReply,
  createQuickReplyCategory,
  disableQuickReply,
  disableQuickReplyCategory,
  getQuickReplyStatistics,
  previewQuickReply,
  recordQuickReplyUsage,
  setQuickReplyFavorite,
  updateQuickReply,
  updateQuickReplyCategory,
} from '../src/api/quickReply'
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
})

describe('快捷话术前端契约', () => {
  it('预览、日志、收藏和统计携带 CSRF、幂等键及正确参数', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=quick-reply-csrf`
    const calls: Array<Record<string, unknown>> = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push({
        method: config.method, url: config.url, params: config.params,
        data: typeof config.data === 'string' && config.data.length ? JSON.parse(config.data) : undefined,
        headers: config.headers.toJSON(),
      })
      return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: {}, requestId: 'req-quick', timestamp: '' } }
    }
    http.defaults.adapter = adapter

    await previewQuickReply(1, 1010, 'PACKAGE_REMAINING')
    await recordQuickReplyUsage(1, {
      customerId: 1010, externalUserid: 'external-1001', scene: 'PACKAGE_REMAINING',
      originalContent: '还剩6次', finalContent: '还剩6次', edited: false,
      sendStatus: 'COPIED', clientResult: 'navigator.clipboard:ok',
    }, 'usage-1')
    await setQuickReplyFavorite(1, true, 'favorite-1')
    await getQuickReplyStatistics({ employeeId: 2, from: '2026-11-01', to: '2026-11-13' })

    expect(calls[0]).toMatchObject({ method: 'post', url: '/quick-replies/1/preview', data: { customerId: 1010, scene: 'PACKAGE_REMAINING' } })
    expect(calls[1]).toMatchObject({
      method: 'post', url: '/quick-replies/1/usage', data: { sendStatus: 'COPIED', finalContent: '还剩6次' },
      headers: { 'Idempotency-Key': 'usage-1', [CSRF_HEADER_NAME]: 'quick-reply-csrf' },
    })
    expect(calls[1]?.data).not.toHaveProperty('tenantId')
    expect(calls[1]?.data).not.toHaveProperty('remainingCount')
    expect(calls[2]).toMatchObject({ method: 'put', url: '/quick-replies/1/favorite', headers: { 'Idempotency-Key': 'favorite-1' } })
    expect(calls[3]).toMatchObject({ method: 'get', url: '/quick-replies/statistics', params: { employeeId: 2, from: '2026-11-01', to: '2026-11-13' } })
  })
})


describe('快捷话术管理 API 契约', () => {
  it('新增、编辑、停用分类和话术均使用正确方法、版本与幂等头', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=quick-reply-admin-csrf`
    const calls: Array<Record<string, unknown>> = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push({
        method: config.method, url: config.url,
        data: typeof config.data === 'string' ? JSON.parse(config.data) : undefined,
        headers: config.headers.toJSON(),
      })
      return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: {}, requestId: 'req-admin', timestamp: '' } }
    }
    http.defaults.adapter = adapter
    const templateBody = {
      categoryId: 5, name: '术后关怀', code: 'POSTOPERATIVE_CARE', messageType: 'TEXT' as const,
      content: '{customer_name}您好', scene: 'POSTOPERATIVE', scopeType: 'GLOBAL' as const, scopeId: null,
      allowEdit: true, requirePreview: true, aiEnabled: true, featured: false, sortNo: 1, reason: '维护话术',
    }
    const categoryBody = {
      name: '术后', code: 'POSTOPERATIVE', parentId: null, scopeType: 'GLOBAL' as const,
      scopeId: null, sortNo: 1, reason: '维护分类',
    }

    await createQuickReply(templateBody, 'template-create')
    await updateQuickReply(1, { ...templateBody, version: 2 }, 'template-update')
    await disableQuickReply(1, { version: 3, reason: '停用话术' }, 'template-disable')
    await createQuickReplyCategory(categoryBody, 'category-create')
    await updateQuickReplyCategory(5, { ...categoryBody, version: 2 }, 'category-update')
    await disableQuickReplyCategory(5, { version: 3, reason: '停用分类' }, 'category-disable')

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['post', '/quick-replies'], ['patch', '/quick-replies/1'], ['post', '/quick-replies/1/disable'],
      ['post', '/quick-replies/categories'], ['patch', '/quick-replies/categories/5'], ['post', '/quick-replies/categories/5/disable'],
    ])
    expect(calls[0]?.data).not.toHaveProperty('version')
    expect(calls[0]?.data).not.toHaveProperty('tenantId')
    expect(calls[1]?.data).toMatchObject({ version: 2 })
    expect(calls[2]?.data).toMatchObject({ version: 3, reason: '停用话术' })
    expect(calls[3]?.data).not.toHaveProperty('version')
    expect(calls[4]?.data).toMatchObject({ version: 2 })
    expect(calls[5]?.data).toMatchObject({ version: 3, reason: '停用分类' })
    for (const [index, key] of ['template-create', 'template-update', 'template-disable', 'category-create', 'category-update', 'category-disable'].entries()) {
      expect(calls[index]?.headers).toMatchObject({ 'Idempotency-Key': key, [CSRF_HEADER_NAME]: 'quick-reply-admin-csrf' })
    }
  })
})
