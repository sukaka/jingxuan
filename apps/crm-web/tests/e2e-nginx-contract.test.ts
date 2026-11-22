import { existsSync, readFileSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { AxiosError } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthUser } from '../src/api/auth'
import { fetchMe, login, logout } from '../src/api/auth'
import type { PackageVersionSnapshot, Project } from '../src/api/catalog'
import {
  createChannelFinancialChangeRequest,
  listChannels,
  reviewChannelFinancialChangeRequest,
  toggleChannelStatus,
} from '../src/api/channel'
import { getPublicH5Card, isValidShareKey, SHARE_KEY_PATTERN } from '../src/api/h5Card'
import { http, setUnauthorizedHandler } from '../src/api/http'
import { listRiskCases, syncRiskCases } from '../src/api/risk'
import { installAuthGuard } from '../src/router/guards'
import { routes } from '../src/router/index'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'
import VersionFormDrawer from '../src/views/catalog/VersionFormDrawer.vue'
import { resetReferenceNames, useReferenceNames } from '../src/views/references'

interface CapturedRequest {
  method: string
  url: string
  headers: Record<string, string>
  params: unknown
  data: unknown
}

const originalAdapter = http.defaults.adapter
const wrappers: VueWrapper[] = []

function normalizeHeaders(headers: InternalAxiosRequestConfig['headers']): Record<string, string> {
  const result: Record<string, string> = {}
  if (!headers) return result
  const json = typeof headers.toJSON === 'function' ? headers.toJSON() : (headers as Record<string, unknown>)
  for (const [key, value] of Object.entries(json)) {
    if (value !== undefined && value !== null) {
      result[key] = String(value)
    }
  }
  return result
}

function parseCspDirectives(csp: string): Record<string, string[]> {
  return Object.fromEntries(
    csp
      .split(';')
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => {
        const [name, ...values] = part.split(/\s+/)
        return [name, values]
      }),
  )
}

function installHttpRecorder(
  responder: (req: CapturedRequest, config: InternalAxiosRequestConfig) => {
    status?: number
    data: unknown
  },
): CapturedRequest[] {
  const captured: CapturedRequest[] = []
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const parsedData =
      typeof config.data === 'string' && config.data.startsWith('{')
        ? (JSON.parse(config.data) as unknown)
        : config.data
    const req: CapturedRequest = {
      method: (config.method ?? 'get').toUpperCase(),
      url: config.url ?? '',
      headers: normalizeHeaders(config.headers),
      params: config.params,
      data: parsedData,
    }
    captured.push(req)
    const outcome = responder(req, config)
    const status = outcome.status ?? 200
    if (status >= 400) {
      throw new AxiosError(`HTTP ${status}`, 'ERR_BAD_REQUEST', config, {}, {
        status,
        statusText: `HTTP ${status}`,
        headers: {},
        config,
        data: outcome.data,
      })
    }
    return {
      data: outcome.data,
      status,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
  http.defaults.adapter = adapter
  return captured
}

beforeEach(() => {
  setActivePinia(createPinia())
  resetReferenceNames()
  document.cookie = 'CRM_CSRF=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  http.defaults.adapter = originalAdapter
  setUnauthorizedHandler(null)
  document.cookie = 'CRM_CSRF=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('Nginx 安全头、CSP 与路由配置契约 (H-BUG-01 / M-FE-01)', () => {
  const nginxConf = readFileSync(resolve(__dirname, '../nginx-default.conf'), 'utf8')
  const wecomSdkSource = readFileSync(resolve(__dirname, '../src/services/wecomSdk.ts'), 'utf8')

  it('校验安全响应头、CSP server 级指令与企微 JS-SDK 精确白名单', () => {
    const sdkMatch = wecomSdkSource.match(/const SDK_URL = '([^']+)'/)
    expect(sdkMatch).not.toBeNull()
    const sdkUrl = sdkMatch![1]
    expect(sdkUrl).toBe('https://wwcdn.weixin.qq.com/node/wework/wwopen/js/wecom-jssdk-2.4.0.js')

    expect(nginxConf).toMatch(/add_header\s+X-Frame-Options\s+DENY\s+always;/)
    expect(nginxConf).toMatch(/add_header\s+X-Content-Type-Options\s+nosniff\s+always;/)
    expect(nginxConf).toMatch(/add_header\s+Referrer-Policy\s+strict-origin-when-cross-origin\s+always;/)

    const cspMatch = nginxConf.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;/)
    expect(cspMatch).not.toBeNull()
    const csp = cspMatch![1]

    const firstLocationIndex = nginxConf.indexOf('location ')
    const cspIndex = nginxConf.indexOf('Content-Security-Policy')
    expect(cspIndex).toBeGreaterThan(-1)
    expect(cspIndex).toBeLessThan(firstLocationIndex)

    const directives = parseCspDirectives(csp)
    expect(directives['default-src']).toEqual(["'self'"])
    expect(directives['script-src']).toEqual(["'self'", sdkUrl])
    expect(directives['script-src']).not.toContain("'unsafe-inline'")
    expect(directives['script-src']).not.toContain("'unsafe-eval'")
    expect(directives['script-src']).not.toContain('*')

    expect(directives['connect-src']).toEqual(["'self'", 'https:'])
    expect(directives['connect-src']).not.toContain('http:')
    expect(directives['connect-src']).not.toContain('*')

    expect(directives['object-src']).toEqual(["'none'"])
    expect(directives['base-uri']).toEqual(["'self'"])
    expect(directives['frame-ancestors']).toEqual(["'none'"])
    expect(directives['form-action']).toEqual(["'self'"])
  })

  it('校验 /actuator/ 返回 404、/api/ 代理、点文件拒绝与 SPA 回退路由', () => {
    expect(nginxConf).toMatch(/location\s+\/actuator\/\s*\{\s*return\s+404;\s*\}/)
    expect(nginxConf).toMatch(/location\s+\/api\/\s*\{[\s\S]*?proxy_pass\s+http:\/\/crm-api:8080;[\s\S]*?proxy_set_header\s+X-Forwarded-Proto\s+\$forwarded_proto;/)
    expect(nginxConf).toMatch(/location\s+~\s+\/\\\.\s*\{\s*deny\s+all;\s*\}/)
    expect(nginxConf).toMatch(/location\s+\/\s*\{\s*try_files\s+\$uri\s+\$uri\/\s+\/index\.html;\s*\}/)
    expect(nginxConf).toMatch(/location\s+=\s+\/healthz\s*\{[\s\S]*?return\s+200\s+"ok\\n";/)
  })
})

describe('前后端 API 契约对齐与拦截器端到端校验', () => {
  it('auth.ts 契约: login 透传 tenantCode 且免 CSRF，logout 携带 X-CSRF-Token', async () => {
    document.cookie = 'CRM_CSRF=csrf-token-abc'
    const authUser: AuthUser = {
      userId: 101,
      username: 'admin2',
      displayName: '二号租户管理员',
      userType: 'EMPLOYEE',
      roles: ['ADMIN'],
      permissions: ['channel:read', 'channel:write', 'channel:approve_finance'],
    }
    const captured = installHttpRecorder((req) => {
      if (req.url === '/auth/login') {
        return { data: { code: 'OK', message: 'ok', data: authUser, requestId: 'req-login' } }
      }
      if (req.url === '/auth/me') {
        return { data: { code: 'OK', message: 'ok', data: authUser, requestId: 'req-me' } }
      }
      return { data: { code: 'OK', message: 'ok', data: null, requestId: 'req-logout' } }
    })

    const loggedIn = await login({
      username: 'admin2',
      password: 'SecretPassword!1',
      captchaId: 'cap-1',
      captchaAnswer: '8',
      tenantCode: 'TENANT_02',
    })
    expect(loggedIn.userId).toBe(101)
    expect(captured[0].method).toBe('POST')
    expect(captured[0].url).toBe('/auth/login')
    expect(captured[0].data).toEqual({
      username: 'admin2',
      password: 'SecretPassword!1',
      captchaId: 'cap-1',
      captchaAnswer: '8',
      tenantCode: 'TENANT_02',
    })
    expect(captured[0].headers['X-CSRF-Token']).toBeUndefined()
    expect(captured[0].headers['X-Request-Id']).toBeTruthy()

    const me = await fetchMe()
    expect(me.username).toBe('admin2')
    expect(captured[1].method).toBe('GET')
    expect(captured[1].url).toBe('/auth/me')

    await logout()
    expect(captured[2].method).toBe('POST')
    expect(captured[2].url).toBe('/auth/logout')
    expect(captured[2].headers['X-CSRF-Token']).toBe('csrf-token-abc')

    const loginViewSource = readFileSync(resolve(__dirname, '../src/views/LoginView.vue'), 'utf8')
    expect(loginViewSource).toContain("error.code === 'AMBIGUOUS_TENANT'")
    expect(loginViewSource).toContain("field === 'tenantCode'")
    expect(loginViewSource).toContain('id="login-tenant-code"')
  })

  it('channel.ts 契约 (H-SEC-03): *Configured 脱敏字段、/status 切换、财务变更申请与审批', async () => {
    document.cookie = 'CRM_CSRF=csrf-channel-1'
    const captured = installHttpRecorder((req) => {
      if (req.method === 'GET' && req.url === '/channels') {
        return {
          data: {
            code: 'OK',
            message: 'ok',
            requestId: 'req-ch-list',
            data: {
              items: [
                {
                  id: 12,
                  channelNo: 'CH-012',
                  name: '星耀渠道',
                  channelType: 'DISTRIBUTION',
                  platformCode: 'DOUYIN',
                  commissionPercent: 10.5,
                  businessOwnerUserId: 8,
                  businessOwnerName: '张商务',
                  realName: '王渠道',
                  contactPhone: '138****0000',
                  contactPhoneConfigured: true,
                  idCardNumber: '1101**********002X',
                  idCardConfigured: true,
                  settlementMethod: 'MONTHLY',
                  cooperationStartDate: '2026-01-01',
                  cooperationEndDate: '2026-12-31',
                  paymentAccountName: '王**',
                  paymentAccountNo: '****0001',
                  paymentAccountConfigured: true,
                  paymentBankName: '招商银行',
                  status: 'ACTIVE',
                  remarks: null,
                  createdAt: '2026-10-01T00:00:00Z',
                  version: 3,
                },
              ],
              total: 1,
              page: 1,
              size: 20,
            },
          },
        }
      }
      if (req.method === 'POST' && req.url === '/channels/12/status') {
        return {
          data: {
            code: 'OK',
            message: 'ok',
            requestId: 'req-ch-status',
            data: { id: 12, status: 'INACTIVE', version: 4, contactPhoneConfigured: true },
          },
        }
      }
      if (req.method === 'POST' && req.url === '/channels/12/financial-change-requests') {
        return {
          status: 201,
          data: {
            code: 'OK',
            message: 'ok',
            requestId: 'req-ch-fin-create',
            data: {
              id: 88,
              channelId: 12,
              status: 'PENDING_REVIEW',
              requestedBy: 8,
              requestedAt: '2026-10-20T01:00:00Z',
              reason: '结算账户变更',
              baseVersion: 4,
              version: 0,
            },
          },
        }
      }
      return {
        data: {
          code: 'OK',
          message: 'ok',
          requestId: 'req-ch-fin-review',
          data: {
            id: 88,
            channelId: 12,
            status: 'APPROVED',
            requestedBy: 8,
            requestedAt: '2026-10-20T01:00:00Z',
            reason: '结算账户变更',
            baseVersion: 4,
            version: 1,
            reviewerUserId: 2,
          },
        },
      }
    })

    const page = await listChannels({ page: 1, size: 20 })
    expect(page.items[0].contactPhoneConfigured).toBe(true)
    expect(page.items[0].idCardConfigured).toBe(true)
    expect(page.items[0].paymentAccountConfigured).toBe(true)

    const toggled = await toggleChannelStatus(12, { status: 'INACTIVE', version: 3, reason: '季度复核停用' })
    expect(toggled.status).toBe('INACTIVE')
    expect(captured[1].method).toBe('POST')
    expect(captured[1].url).toBe('/channels/12/status')
    expect(captured[1].headers['X-CSRF-Token']).toBe('csrf-channel-1')

    const createdReq = await createChannelFinancialChangeRequest(
      12,
      {
        commissionPercent: 12,
        settlementMethod: 'MONTHLY',
        paymentAccountName: '新收款账户',
        paymentAccountNo: '6222021001112223',
        paymentBankName: '工商银行',
        baseVersion: 4,
        reason: '结算账户变更',
      },
      'idem-ch-fin-1',
    )
    expect(createdReq.status).toBe('PENDING_REVIEW')
    expect(captured[2].method).toBe('POST')
    expect(captured[2].url).toBe('/channels/12/financial-change-requests')
    expect(captured[2].headers['Idempotency-Key']).toBe('idem-ch-fin-1')
    expect(captured[2].headers['X-CSRF-Token']).toBe('csrf-channel-1')

    const reviewed = await reviewChannelFinancialChangeRequest(
      88,
      { decision: 'APPROVE', version: 0, comment: '核对无误' },
      'idem-ch-review-1',
    )
    expect(reviewed.status).toBe('APPROVED')
    expect(captured[3].method).toBe('POST')
    expect(captured[3].url).toBe('/channel-change-requests/88/review')
    expect(captured[3].headers['Idempotency-Key']).toBe('idem-ch-review-1')
    expect(captured[3].headers['X-CSRF-Token']).toBe('csrf-channel-1')
  })

  it('risk.ts 契约 (H-SEC-09 #4): GET /risks 只读查询，POST /risks/sync 显式同步', async () => {
    document.cookie = 'CRM_CSRF=csrf-risk-1'
    const captured = installHttpRecorder((req) => {
      if (req.method === 'GET' && req.url === '/risks') {
        return {
          data: { code: 'OK', message: 'ok', data: { items: [], total: 0, page: 1, size: 20 } },
        }
      }
      return {
        data: {
          code: 'OK',
          message: 'ok',
          data: { createdCount: 2, updatedCount: 1, syncedAt: '2026-10-25T10:00:00Z' },
        },
      }
    })

    const listRes = await listRiskCases({ page: 1, size: 20, status: 'OPEN' })
    expect(listRes.items).toEqual([])
    expect(captured[0].method).toBe('GET')
    expect(captured[0].url).toBe('/risks')

    const syncRes = await syncRiskCases('idem-risk-sync-1')
    expect(syncRes).toEqual({ createdCount: 2, updatedCount: 1, syncedAt: '2026-10-25T10:00:00Z' })
    expect(captured[1].method).toBe('POST')
    expect(captured[1].url).toBe('/risks/sync')
    expect(captured[1].headers['Idempotency-Key']).toBe('idem-risk-sync-1')
    expect(captured[1].headers['X-CSRF-Token']).toBe('csrf-risk-1')
  })

  it('VersionFormDrawer.vue 契约 (H-BIZ-02): 默认 FIXED，禁用并拦截 USE_N', async () => {
    const captured = installHttpRecorder(() => ({ data: { code: 'OK', message: 'ok', data: {} } }))
    const warnSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: () => undefined }))

    const projects: Project[] = [
      {
        id: 1,
        projectCode: 'HYDRA',
        projectName: '基础水光',
        categoryCode: 'SKIN',
        listPriceFen: 120000,
        status: 'ACTIVE',
        createdAt: '2026-10-01T00:00:00Z',
        version: 0,
      },
    ]

    const createWrapper = mount(VersionFormDrawer, {
      props: { modelValue: false, packageId: 50, projects, snapshot: null },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(createWrapper)
    await createWrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(createWrapper.find('[data-testid="version-use-n-warning"]').exists()).toBe(false)
    const selectCmp = createWrapper.findComponent({ name: 'ElSelect' })
    expect(selectCmp.props('modelValue')).toBe('FIXED')
    const optionItems = createWrapper.findAllComponents({ name: 'ElOption' })
    const useNOption = optionItems.find((opt) => opt.props('value') === 'USE_N')
    expect(useNOption).toBeDefined()
    expect(useNOption!.props('disabled')).toBe(true)

    const useNSnapshot: PackageVersionSnapshot = {
      id: 99,
      packageId: 50,
      packageCode: 'PKG-LEGACY',
      packageName: '历史 N 次卡',
      versionNo: 1,
      selectionMode: 'USE_N',
      choiceCount: 3,
      selectionRules: null,
      status: 'DRAFT',
      version: 0,
      rulesStatus: 'PENDING_Q01',
      allocationStatus: 'CONFIGURED',
      entitlementIssuable: false,
      projects: [
        {
          id: 1,
          packageVersionId: 99,
          projectId: 1,
          projectCode: 'HYDRA',
          projectName: '基础水光',
          categoryCode: 'SKIN',
          listPriceFen: 120000,
          projectStatus: 'ACTIVE',
          grantCount: 3,
          selectionWeight: 1,
          allocationPriceFen: 0,
          optionGroup: 'MAIN',
          referencePriceFen: 120000,
          version: 0,
        },
      ],
      skus: [],
      institutions: [],
      groups: [],
    }

    const legacyWrapper = mount(VersionFormDrawer, {
      props: { modelValue: false, packageId: 50, projects, snapshot: useNSnapshot },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    wrappers.push(legacyWrapper)
    await legacyWrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(legacyWrapper.get('[data-testid="version-use-n-warning"]').text()).toContain('Q01')
    const saveButton = legacyWrapper.findAll('button').find((btn) => btn.text().includes('保存草稿'))
    expect(saveButton).toBeDefined()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('USE_N'))
    expect(captured).toHaveLength(0)
  })

  it('guards.ts 契约 (M-FE-01): 401 清空用户态与字典缓存并跳回 /login，忽略 /auth/me 401 避免循环', async () => {
    const authUser: AuthUser = {
      userId: 9,
      username: 'manager',
      displayName: '王管家',
      userType: 'EMPLOYEE',
      roles: ['MANAGER'],
      permissions: ['channel:read'],
    }
    installHttpRecorder((req) => {
      if (req.url === '/auth/me') {
        return { data: { code: 'OK', message: 'ok', data: authUser } }
      }
      if (req.url === '/institutions') {
        return {
          data: {
            code: 'OK',
            message: 'ok',
            data: { items: [{ id: 1, shortName: '朝阳分院', fullName: '朝阳美学医院' }], total: 1, page: 1, size: 100 },
          },
        }
      }
      if (req.url === '/projects') {
        return {
          data: {
            code: 'OK',
            message: 'ok',
            data: { items: [{ id: 2, projectCode: 'P02', projectName: '舒敏补水' }], total: 1, page: 1, size: 100 },
          },
        }
      }
      if (req.url === '/channels') {
        return { status: 401, data: { code: 'UNAUTHORIZED', message: '登录态失效' } }
      }
      return { data: { code: 'OK', message: 'ok', data: {} } }
    })

    const testRouter = createRouter({ history: createMemoryHistory(), routes })
    installAuthGuard(testRouter)

    await testRouter.push('/channels')
    const auth = useAuthStore()
    expect(auth.isLoggedIn).toBe(true)

    const refs = useReferenceNames()
    await flushPromises()
    expect(refs.projectLabel(2)).toBe('舒敏补水')

    await expect(listChannels({ page: 1, size: 20 })).rejects.toBeInstanceOf(ApiError)
    await vi.waitFor(() => {
      expect(testRouter.currentRoute.value.name).toBe('login')
    })

    expect(auth.isLoggedIn).toBe(false)
    expect(auth.user).toBeNull()
    expect(refs.projectLabel(2)).toBe('项目 #2')
    expect(testRouter.currentRoute.value.query.redirect).toBe('/channels')

    // Verify /auth/me 401 does NOT re-trigger unauthorized redirect when unauthenticated on /login
    installHttpRecorder(() => ({ status: 401, data: { code: 'UNAUTHORIZED', message: '未登录' } }))
    await expect(fetchMe()).rejects.toBeInstanceOf(ApiError)
    expect(testRouter.currentRoute.value.name).toBe('login')
  })

  it('h5Card.ts 契约: SHARE_KEY_PATTERN 拦截路径穿越，合法 key 使用 encodeURIComponent', async () => {
    expect(SHARE_KEY_PATTERN.test('valid_share_key_01')).toBe(true)
    expect(isValidShareKey('../admin')).toBe(false)
    expect(isValidShareKey('too-short')).toBe(false)
    expect(isValidShareKey('a'.repeat(129))).toBe(false)

    const captured = installHttpRecorder(() => ({
      data: {
        code: 'OK',
        message: 'ok',
        data: {
          projectName: '水光护理',
          title: '项目介绍',
          summary: '摘要',
          content: '正文',
          imageUrl: 'https://crm.example.com/h5-card-cover.svg',
        },
      },
    }))

    for (const badKey of ['../admin', 'short', 'invalid key 123456', 'slash/key_12345678']) {
      await expect(getPublicH5Card(badKey)).rejects.toMatchObject({
        status: 400,
        code: 'INVALID_SHARE_KEY',
      })
    }
    expect(captured).toHaveLength(0)

    const card = await getPublicH5Card('AbCdEfGh_123456-7890')
    expect(card.projectName).toBe('水光护理')
    expect(captured).toHaveLength(1)
    expect(captured[0].method).toBe('GET')
    expect(captured[0].url).toBe('/public/h5-cards/AbCdEfGh_123456-7890')
  })
})

describe('构建产物 dist/index.html 与静态资源安全校验', () => {
  it('dist/index.html 存在时加载正常，引用有效的本地 hashed 资源且无内联或未放行外链脚本', () => {
    const distDir = resolve(__dirname, '../dist')
    const indexPath = resolve(distDir, 'index.html')
    if (!existsSync(indexPath)) {
      return
    }

    const html = readFileSync(indexPath, 'utf8')
    const doc = new DOMParser().parseFromString(html, 'text/html')

    expect(doc.querySelector('#app')).not.toBeNull()

    const scripts = Array.from(doc.querySelectorAll('script'))
    expect(scripts.length).toBeGreaterThan(0)
    for (const script of scripts) {
      // No inline script content allowed under script-src 'self' without 'unsafe-inline'
      expect((script.textContent ?? '').trim()).toBe('')
      const src = script.getAttribute('src')
      expect(src).toBeTruthy()
      expect(src!.startsWith('/assets/')).toBe(true)
      const assetPath = resolve(distDir, src!.slice(1))
      expect(existsSync(assetPath)).toBe(true)
      expect(statSync(assetPath).size).toBeGreaterThan(0)
    }

    const links = Array.from(doc.querySelectorAll('link[href]'))
    for (const link of links) {
      const href = link.getAttribute('href')!
      expect(href).not.toMatch(/^https?:\/\//i)
      if (href.startsWith('/')) {
        const assetPath = resolve(distDir, href.slice(1))
        expect(existsSync(assetPath)).toBe(true)
      }
    }
  })
})
