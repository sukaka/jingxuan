import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  customerStatusTone,
  customerTierTone,
  materialCategoryTone,
  orderStatusTone,
  orderTypeTone,
  priorityTone,
  roleTone,
  userStatusTone,
  knowledgeDocumentTone,
  knowledgeSyncTone,
} from '../src/utils/tagTone'

describe('列表标签语义配色', () => {
  it('订单类型和状态使用不同颜色，未知值保持中性', () => {
    expect(orderTypeTone('REGULAR')).toBe('teal')
    expect(orderTypeTone('FLASH')).toBe('orange')
    expect(orderTypeTone('PROMOTION')).toBe('purple')
    expect(orderTypeTone('UNKNOWN')).toBe('dark')
    expect(orderStatusTone('PAID')).toBe('success')
    expect(orderStatusTone('PENDING_PAYMENT')).toBe('warning')
    expect(orderStatusTone('PARTIALLY_REDEEMED')).toBe('info')
    expect(orderStatusTone('CANCELED')).toBe('dark')
  })

  it('素材、客户、角色和优先级复用同一语义映射', () => {
    expect(materialCategoryTone('GUIDE')).toBe('info')
    expect(materialCategoryTone('CASE')).toBe('purple')
    expect(materialCategoryTone('INSTITUTION')).toBe('teal')
    expect(materialCategoryTone('DOCTOR')).toBe('orange')
    expect(materialCategoryTone('DEVICE')).toBe('pink')
    expect(materialCategoryTone('PRODUCT')).toBe('success')
    expect(customerTierTone('S')).toBe('purple')
    expect(customerTierTone('A')).toBe('info')
    expect(customerTierTone('B')).toBe('teal')
    expect(customerTierTone('C')).toBe('dark')
    expect(customerStatusTone('ACTIVE')).toBe('success')
    expect(customerStatusTone('PENDING_FOLLOWUP')).toBe('warning')
    expect(roleTone('ADMIN')).toBe('purple')
    expect(roleTone('MANAGER')).toBe('teal')
    expect(roleTone('MANAGER_LEAD')).toBe('info')
    expect(roleTone('FINANCE')).toBe('warning')
    expect(userStatusTone('ACTIVE')).toBe('success')
    expect(userStatusTone('DISABLED')).toBe('dark')
    expect(priorityTone('URGENT')).toBe('danger')
    expect(priorityTone('HIGH')).toBe('warning')
    expect(priorityTone('MEDIUM')).toBe('info')
    expect(priorityTone('LOW')).toBe('dark')
  })

  it('知识资料和同步任务按待处理、进行中、成功、失败区分颜色', () => {
    expect(knowledgeDocumentTone('WAITING')).toBe('warning')
    expect(knowledgeDocumentTone('SYNCING')).toBe('info')
    expect(knowledgeDocumentTone('ACTIVE')).toBe('success')
    expect(knowledgeDocumentTone('FAILED')).toBe('danger')
    expect(knowledgeDocumentTone('DISABLED')).toBe('dark')
    expect(knowledgeSyncTone('PENDING')).toBe('warning')
    expect(knowledgeSyncTone('INDEXING')).toBe('info')
    expect(knowledgeSyncTone('RETRY')).toBe('warning')
    expect(knowledgeSyncTone('SUCCEEDED')).toBe('success')
    expect(knowledgeSyncTone('FAILED')).toBe('danger')
    expect(knowledgeSyncTone(null)).toBe('dark')
  })

  it('共享样式包含新增语义色且不只使用 info 蓝色', () => {
    const css = readFileSync('src/prototype.css', 'utf8')
    for (const tone of ['purple', 'teal', 'orange', 'pink']) {
      expect(css).toContain(`.proto-pill.${tone}`)
    }
  })
})
