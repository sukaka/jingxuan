import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createInstitutionChange,
  createInstitutionContract,
  createInstitutionProfile,
  createInstitutionQualification,
  getInstitutionProfile,
  listInstitutionChanges,
  listInstitutionContracts,
  listInstitutionProfiles,
  listInstitutionQualifications,
  requestContractActivation,
  requestInstitutionActivation,
  requestQualificationReview,
  revalidateInstitutionChange,
  reviewInstitutionChangeFinance,
  reviewInstitutionChangeMarket,
  updateInstitutionProfile,
} from '../src/api/institution'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

function capture(response: unknown = {}) {
  const calls: { method: string; url: string; data: unknown; params: unknown; key: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params,
      key: config.headers?.get('Idempotency-Key'),
    })
    return { status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data: response, requestId: 'institution-test', timestamp: '' } }
  }
  return { adapter, calls }
}

describe('P0-5 机构与审批 API 契约', () => {
  it('机构分页、创建、普通更新与激活均按真实 DTO 和幂等头', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await listInstitutionProfiles({ page: 2, size: 20, keyword: '测试', status: 'PENDING', cityCode: 'BJ' })
    await getInstitutionProfile(91)
    await createInstitutionProfile({ institutionNo: 'INST-TEST', fullName: '测试机构', settlementType: 'MONTHLY', onlineRate: '0.182500', depositFen: 3000000 }, 'profile-create-key')
    await updateInstitutionProfile(91, { version: 2, shortName: '新简称', contactPhone: '13800000000' }, 'profile-update-key')
    await requestInstitutionActivation(91, { baseVersion: 2, reason: '合作资料齐全' }, 'profile-activation-key')

    expect(calls.map((call) => call.url)).toEqual(['/institution-profiles', '/institution-profiles/91', '/institution-profiles', '/institution-profiles/91', '/institution-profiles/91/activation-requests'])
    expect(calls[0]?.params).toEqual({ page: 2, size: 20, keyword: '测试', status: 'PENDING', cityCode: 'BJ' })
    expect(calls[2]?.method).toBe('post')
    expect(calls[2]?.data).toMatchObject({ onlineRate: '0.182500', depositFen: 3000000 })
    expect(calls[2]?.key).toBe('profile-create-key')
    expect(calls[3]?.method).toBe('patch')
    expect(calls[3]?.data).toEqual({ version: 2, shortName: '新简称', contactPhone: '13800000000' })
    expect(calls[4]?.data).toEqual({ baseVersion: 2, reason: '合作资料齐全' })
  })

  it('敏感申请、市场/财务审核和冲突复核使用独立端点', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createInstitutionChange({ institutionId: 91, fieldCode: 'onlineRate', newValue: 0.1825, baseVersion: 2, reason: '费率调整' }, 'change-create-key')
    await listInstitutionChanges({ page: 1, size: 20, institutionId: 91, status: 'PENDING_REVIEW' })
    await reviewInstitutionChangeMarket(801, { version: 1, decision: 'APPROVE', comment: '市场通过' }, 'market-key')
    await reviewInstitutionChangeFinance(801, { version: 2, decision: 'APPROVE', comment: '财务通过' }, 'finance-key')
    await revalidateInstitutionChange(801, { version: 2, baseVersion: 4, reason: '重新核对版本' }, 'revalidate-key')

    expect(calls.map((call) => call.url)).toEqual(['/institution-change-requests', '/institution-change-requests', '/institution-change-requests/801/market-review', '/institution-change-requests/801/finance-review', '/institution-change-requests/801/revalidate'])
    expect(calls[0]?.data).toMatchObject({ fieldCode: 'onlineRate', newValue: 0.1825, baseVersion: 2 })
    expect(calls[2]?.key).toBe('market-key')
    expect(calls[3]?.key).toBe('finance-key')
    expect(calls[4]?.data).toEqual({ version: 2, baseVersion: 4, reason: '重新核对版本' })
  })

  it('合同版本、资质文件和各自审核流均调用真实文档端点', async () => {
    const { adapter, calls } = capture()
    http.defaults.adapter = adapter

    await createInstitutionContract({ institutionId: 91, previousContractId: 70, contractNo: 'C-TEST', title: '测试合同', contractType: 'FRAMEWORK', startDate: '2027-01-01', fileId: 701, amountFen: 100000 }, 'contract-create-key')
    await requestContractActivation(701, { baseVersion: 0, reason: '激活新版本' }, 'contract-activation-key')
    await createInstitutionQualification({ institutionId: 91, qualificationType: 'MEDICAL_LICENSE', title: '医疗资质', fileId: 702 }, 'qualification-create-key')
    await requestQualificationReview(801, { baseVersion: 0, reason: '资质复核' }, 'qualification-review-key')
    await listInstitutionContracts({ institutionId: 91, page: 1, size: 20 })
    await listInstitutionQualifications({ institutionId: 91, page: 1, size: 20 })

    expect(calls.map((call) => call.url)).toEqual(['/institution-contracts', '/institution-contracts/701/activation-requests', '/institution-qualifications', '/institution-qualifications/801/review-requests', '/institution-contracts', '/institution-qualifications'])
    expect(calls[0]?.data).toMatchObject({ previousContractId: 70, fileId: 701, amountFen: 100000 })
    expect(calls[1]?.key).toBe('contract-activation-key')
    expect(calls[2]?.data).toMatchObject({ fileId: 702 })
    expect(calls[3]?.key).toBe('qualification-review-key')
  })
})
