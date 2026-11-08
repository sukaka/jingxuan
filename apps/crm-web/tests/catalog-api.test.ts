import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  activateSku,
  createPackageVersion,
  createProject,
  getSkuQualityReport,
  listInstitutions,
  listSkuInstitutions,
  listSkus,
} from '../src/api/catalog'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture(response: unknown) {
  const calls: { method: string; url: string; data: unknown; params: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({ method: config.method ?? 'get', url: config.url ?? '', data: config.data ? JSON.parse(config.data as string) : undefined, params: config.params })
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: response, requestId: 'r', timestamp: '' } }
  }
  return { adapter, calls }
}

describe('产品目录 API 契约', () => {
  it('SKU 查询保留套餐/状态/分页筛选', async () => {
    const { adapter, calls } = capture({ items: [], total: 0, page: 2, size: 20 })
    http.defaults.adapter = adapter
    await listSkus({ page: 2, size: 20, packageId: 201, status: 'ACTIVE', keyword: '水光' })
    expect(calls[0]?.method).toBe('get')
    expect(calls[0]?.url).toBe('/skus')
    expect(calls[0]?.params).toMatchObject({ page: 2, size: 20, packageId: 201, status: 'ACTIVE', keyword: '水光' })
  })

  it('版本创建传递选择模式和项目整数次数', async () => {
    const { adapter, calls } = capture({ id: 301 })
    http.defaults.adapter = adapter
    await createPackageVersion(201, { selectionMode: 'USE_N', choiceCount: 6, projects: [{ projectId: 101, grantCount: 6, allocationPriceFen: 181100 }] })
    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.url).toBe('/packages/201/versions')
    expect(calls[0]?.data).toMatchObject({ selectionMode: 'USE_N', choiceCount: 6, projects: [{ projectId: 101, grantCount: 6, allocationPriceFen: 181100 }] })
  })

  it('项目价格直接使用整数分，上架只携带乐观锁版本', async () => {
    const { adapter, calls } = capture({ id: 1 })
    http.defaults.adapter = adapter
    await createProject({ projectCode: 'PRJ-TEST', projectName: '测试项目', listPriceFen: 19900 })
    await activateSku(301, 2)
    expect(calls[0]?.data).toMatchObject({ listPriceFen: 19900 })
    expect(calls[1]?.url).toBe('/skus/301/activate')
    expect(calls[1]?.data).toEqual({ version: 2 })
  })

  it('机构选项按真实端点读取', async () => {
    const { adapter, calls } = capture([])
    http.defaults.adapter = adapter
    await listSkuInstitutions(301)
    await listInstitutions({ page: 1, size: 100, status: 'ACTIVE' })
    expect(calls.map((call) => call.url)).toEqual(['/skus/301/institutions', '/institutions'])
    expect(calls[1]?.params).toMatchObject({ status: 'ACTIVE' })
  })

  it('SKU 质量按实际 DTO 映射销量、问题码和当前页统计，只读 reporting/sku-quality', async () => {
    const { adapter, calls } = capture({
      from: '2026-07-29',
      to: '2026-10-26',
      fromUtc: '2026-07-28T16:00:00Z',
      toUtcExclusive: '2026-10-26T16:00:00Z',
      timeZone: 'Asia/Shanghai',
      sourceSystem: 'ALL',
      receiverType: 'ALL',
      metricVersion: 'P2_FACT_V1',
      sourceWatermark: '2026-10-27T01:00:00Z',
      lowFrequencyThreshold: 3,
      periodDays: 90,
      lowFrequencyAction: 'NONE',
      qualityRules: [
        { code: 'LOW_FREQUENCY', description: '90 天销量小于等于 3' },
        { code: 'MISSING_STANDARD_NAME', description: '标准名称或规格缺失' },
      ],
      items: [
        {
          skuId: 301,
          skuCode: 'SKU-TEST-301',
          packageVersionId: 311,
          packageName: '测试套餐',
          specName: '3次',
          standardName: '测试套餐｜3次',
          salePriceFen: 19900,
          skuStatus: 'ACTIVE',
          activeInstitutionCount: 1,
          salesQuantity: 2,
          paidOrderCount: 1,
          paidLineCount: 1,
          lastSoldAt: '2026-10-20T01:00:00Z',
          lowFrequency: true,
          qualityStatus: 'ATTENTION',
          issueCodes: ['LOW_FREQUENCY'],
          automaticAction: 'NONE',
        },
        {
          skuId: 302,
          skuCode: 'SKU-TEST-302',
          packageVersionId: 312,
          packageName: '',
          specName: '',
          standardName: '',
          salePriceFen: 20000,
          skuStatus: 'INACTIVE',
          activeInstitutionCount: 1,
          salesQuantity: 9,
          paidOrderCount: 2,
          paidLineCount: 2,
          lastSoldAt: '2026-10-21T01:00:00Z',
          lowFrequency: false,
          qualityStatus: 'ATTENTION',
          issueCodes: ['MISSING_STANDARD_NAME'],
          automaticAction: 'NONE',
        },
      ],
      total: 2,
      page: 1,
      size: 100,
      drillDown: [],
      drillDownType: null,
      drillDownLimit: 20,
    })
    http.defaults.adapter = adapter

    const result = await getSkuQualityReport({
      from: '2026-07-29',
      to: '2026-10-26',
      timeZone: 'Asia/Shanghai',
      sourceSystem: 'ALL',
      page: 1,
      size: 100,
      lowFrequencyThreshold: 3,
    })

    expect(calls).toHaveLength(1)
    expect(calls[0]?.method).toBe('get')
    expect(calls[0]?.url).toBe('/reports/sku-quality')
    expect(calls[0]?.params).toEqual({
      from: '2026-07-29',
      to: '2026-10-26',
      timeZone: 'Asia/Shanghai',
      sourceSystem: 'ALL',
      page: 1,
      size: 100,
      lowFrequencyThreshold: 3,
    })
    expect(result.items[0]).toMatchObject({ sales90Days: 2, issues: ['LOW_FREQUENCY'], automaticAction: 'NONE' })
    expect(result.summary).toEqual({
      totalSkuCount: 2,
      namingIssueCount: 1,
      lowFrequencySkuCount: 1,
      lowFrequencyRatio: 0.5,
    })
    expect(result.checks).toEqual([
      expect.objectContaining({ checkCode: 'LOW_FREQUENCY', issueCount: 1 }),
      expect.objectContaining({ checkCode: 'MISSING_STANDARD_NAME', issueCount: 1 }),
    ])
  })
})
