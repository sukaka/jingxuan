import { describe, expect, it } from 'vitest'
import type { PackageVersionSnapshot } from '../src/api/catalog'
import { selectionError, selectionTotals, toSelectionInputs } from '../src/views/trading/packageSelection'

const snapshot = {
  selectionMode: 'CHOOSE_N',
  groups: [{ id: 1, packageVersionId: 301, groupCode: 'A', groupName: '焕肤组', minSelections: 2, maxSelections: 2, sortOrder: 1, version: 0 }],
  projects: [
    { projectId: 101, projectName: '项目一', optionGroup: 'A', grantCount: 1, referencePriceFen: 80000 },
    { projectId: 102, projectName: '项目二', optionGroup: 'A', grantCount: 1, referencePriceFen: 40000 },
    { projectId: 103, projectName: '项目三', optionGroup: 'A', grantCount: 2, referencePriceFen: 30000 },
    { projectId: 104, projectName: '项目四', optionGroup: 'A', grantCount: 1, referencePriceFen: 20000 },
  ],
} as unknown as PackageVersionSnapshot

describe('套餐分组选择计算', () => {
  it('固定 4 选 2 未选够时不可提交，选够后生成稳定快照请求', () => {
    expect(selectionError(snapshot, { A: [101] })).toContain('至少选择 2 项')
    expect(selectionError(snapshot, { A: [101, 102, 103] })).toContain('最多选择 2 项')
    expect(selectionError(snapshot, { A: [102, 101] })).toBe('')
    expect(toSelectionInputs(snapshot, { A: [102, 101] })).toEqual([
      { groupCode: 'A', projectId: 101 },
      { groupCode: 'A', projectId: 102 },
    ])
  })

  it('单品参考价、套餐价与优惠额独立计算，不要求两者相等', () => {
    expect(selectionTotals(snapshot, { A: [101, 102] }, 100000)).toEqual({
      referenceAmountFen: 120000,
      packageAmountFen: 100000,
      discountAmountFen: 20000,
    })
    expect(selectionTotals(snapshot, { A: [101, 103] }, 100000, 2)).toEqual({
      referenceAmountFen: 280000,
      packageAmountFen: 200000,
      discountAmountFen: 80000,
    })
    expect(selectionTotals(snapshot, { A: [103, 104] }, 100000)).toMatchObject({
      referenceAmountFen: 80000,
      packageAmountFen: 100000,
      discountAmountFen: 0,
    })
  })

  it('拒绝同一项目重复选择', () => {
    expect(selectionError(snapshot, { A: [101, 101] })).toContain('重复')
  })
})
