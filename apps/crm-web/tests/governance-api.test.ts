import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { http } from '../src/api/http'
import {
  createMaterial,
  createMaterialRevision,
  getMaterial,
  getMaterialRevisionDownload,
  reviewMaterialRevision,
  submitMaterialRevision,
} from '../src/api/material'
import { createExportJob, getExportJobDownload, listExportAccessLogs, listExportJobs } from '../src/api/exporting'
import { claimRiskCase, closeRiskCase, listRiskCases, resolveRiskCase } from '../src/api/risk'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

interface CapturedCall {
  method: string
  url: string
  params: Record<string, unknown>
  data: unknown
  idempotencyKey: unknown
}

function captureAdapter(responseData: unknown = {}) {
  const calls: CapturedCall[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: (config.method ?? 'get').toLowerCase(),
      url: config.url ?? '',
      params: (config.params ?? {}) as Record<string, unknown>,
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      idempotencyKey: config.headers?.get('Idempotency-Key'),
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'request-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('P2 素材 API 契约', () => {
  it('首版与后续版本使用真实 fileId，追加版本和审核携带对应 version', async () => {
    const { adapter, calls } = captureAdapter()
    http.defaults.adapter = adapter

    await createMaterial({ title: '测试素材', categoryCode: 'CASE', tags: ['测试'], fileId: 700, changeNote: '首个真实版本' }, 'material-create')
    await createMaterialRevision(11, {
      version: 3, title: '测试素材第二版', categoryCode: 'CASE', fileId: 701, changeNote: '替换真实扫描文件', tags: [],
    }, 'revision-create')
    await submitMaterialRevision(11, 101, 0, 'revision-submit')
    await reviewMaterialRevision(11, 101, { version: 1, decision: 'REJECTED', reviewNote: '需要补充授权依据' }, 'revision-review')
    await getMaterialRevisionDownload(11, 102)

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['post', '/materials'],
      ['post', '/materials/11/revisions'],
      ['post', '/materials/11/revisions/101/submit'],
      ['post', '/materials/11/revisions/101/review'],
      ['get', '/materials/11/revisions/102/download'],
    ])
    expect(calls.map((call) => call.idempotencyKey)).toEqual([
      'material-create', 'revision-create', 'revision-submit', 'revision-review', undefined,
    ])
    expect(calls[0]?.data).toEqual({ title: '测试素材', categoryCode: 'CASE', tags: ['测试'], fileId: 700, changeNote: '首个真实版本' })
    expect(calls[1]?.data).toMatchObject({ version: 3, fileId: 701 })
    expect(calls[2]?.data).toEqual({ version: 0 })
    expect(calls[3]?.data).toEqual({ version: 1, decision: 'REJECTED', reviewNote: '需要补充授权依据' })
  })

  it('将后端扁平 MaterialResponse 转为页面详情且保留全部版本', async () => {
    const revision = { id: 101, materialId: 11, revisionNo: 1, status: 'DRAFT', fileId: 700, version: 0 }
    const { adapter } = captureAdapter({
      id: 11, materialNo: 'MT-TEST', title: '测试素材', categoryCode: 'CASE', institutionId: null,
      projectId: null, cityCode: null, summary: null, tags: [], currentRevisionId: null,
      reviewStatus: 'DRAFT', createdBy: 1, createdAt: '2026-10-25T01:00:00Z',
      updatedAt: '2026-10-25T01:00:00Z', version: 0, revisions: [revision],
    })
    http.defaults.adapter = adapter

    const result = await getMaterial(11)

    expect(result.material.id).toBe(11)
    expect(result.material).not.toHaveProperty('revisions')
    expect(result.revisions).toEqual([revision])
  })
})

describe('P2 导出 API 契约', () => {
  it('只提交白名单字段、from/to 和理由，客户端不传 tenant 或放大 scope', async () => {
    const { adapter, calls } = captureAdapter()
    http.defaults.adapter = adapter

    await createExportJob({
      exportType: 'CUSTOMER',
      filters: { from: '2026-09-30T16:00:00.000Z', to: '2026-10-26T15:59:59.999Z' },
      columns: ['customerNo', 'displayName', 'mobile'],
      reason: '核对本人客户的授权分层字段',
    }, 'export-fixed-key')

    expect(calls[0]).toMatchObject({ method: 'post', url: '/exports', idempotencyKey: 'export-fixed-key' })
    expect(calls[0]?.data).toEqual({
      exportType: 'CUSTOMER',
      filters: { from: '2026-09-30T16:00:00.000Z', to: '2026-10-26T15:59:59.999Z' },
      columns: ['customerNo', 'displayName', 'mobile'],
      reason: '核对本人客户的授权分层字段',
    })
    expect(calls[0]?.data).not.toHaveProperty('tenantId')
    expect(calls[0]?.data).not.toHaveProperty('scope')
  })

  it('任务、下载和审计使用独立服务端端点', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await listExportJobs({ page: 1, size: 20, exportType: 'ORDER', status: 'RUNNING' })
    await getExportJobDownload(91)
    await listExportAccessLogs({ page: 1, size: 20, jobId: 91 })

    expect(calls.map((call) => call.url)).toEqual(['/exports', '/exports/91/download', '/exports/access-logs'])
    expect(calls[0]?.params).toEqual({ page: 1, size: 20, exportType: 'ORDER', status: 'RUNNING' })
    expect(calls[2]?.params).toEqual({ page: 1, size: 20, jobId: 91 })
  })
})

describe('P2 风控 API 契约', () => {
  it('认领、处理、关闭均携带真实版本和理由，不提供删除或本地状态替换', async () => {
    const { adapter, calls } = captureAdapter()
    http.defaults.adapter = adapter

    await listRiskCases({ page: 1, size: 20, keyword: 'RISK-TEST', status: 'OPEN', severity: 'HIGH' })
    await claimRiskCase(31, { version: 0, reason: '认领核对同步异常' }, 'risk-claim')
    await resolveRiskCase(31, { version: 1, resolution: '已依据原始流水完成更正' }, 'risk-resolve')
    await closeRiskCase(31, { version: 2, reason: '复核历史与结果一致' }, 'risk-close')

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['get', '/risks'],
      ['post', '/risks/31/claim'],
      ['post', '/risks/31/resolve'],
      ['post', '/risks/31/close'],
    ])
    expect(calls[1]?.data).toEqual({ version: 0, reason: '认领核对同步异常' })
    expect(calls[2]?.data).toEqual({ version: 1, resolution: '已依据原始流水完成更正' })
    expect(calls[3]?.data).toEqual({ version: 2, reason: '复核历史与结果一致' })
    expect(calls.slice(1).map((call) => call.idempotencyKey)).toEqual(['risk-claim', 'risk-resolve', 'risk-close'])
  })
})
