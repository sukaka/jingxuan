import { describe, expect, it } from 'vitest'
import { AI_INTENT_LABELS, aiIntentLabel } from '../src/utils/aiIntent'

describe('AI 意图完整类型展示', () => {
  it('覆盖文档类型与既有类型，不直接展示英文枚举', () => {
    expect(Object.keys(AI_INTENT_LABELS)).toHaveLength(13)
    for (const type of ['PACKAGE_QUERY', 'VISIT_INTENT', 'AFTERCARE_FEEDBACK', 'FOLLOWUP_REQUIRED', 'RISK_EVENT']) {
      expect(aiIntentLabel(type)).not.toBe(type)
      expect(aiIntentLabel(type)).not.toContain('未知')
    }
    expect(aiIntentLabel('RISK_EVENT')).toContain('人工复核')
    expect(aiIntentLabel('SERVICE_COMPLETION_CLAIM')).toContain('待核实')
    expect(aiIntentLabel('NOT_IN_CONTRACT')).toContain('人工复核')
  })
})
