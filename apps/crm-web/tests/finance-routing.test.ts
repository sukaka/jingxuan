import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

function permissions(codes: string[]): (code: string) => boolean {
  const owned = new Set(codes)
  return (code) => owned.has(code)
}

describe('P1-4 退款与结算导航权限', () => {
  it.each([
    ['/refunds', 'refunds', 'refund:read'],
    ['/settlements', 'settlements', 'settlement:read'],
  ])('%s 使用独立读取权限和真实页面组件', (path, name, permission) => {
    const route = routes.find((item) => item.path === path)
    expect(route?.name).toBe(name)
    expect(route?.meta).toMatchObject({ permission })
    expect(route?.component).toBeTypeOf('function')
  })

  it('管理员、管家和机构看到各自真实入口，写权限不代替读权限', () => {
    expect(flatten(visibleMenuItems(permissions(['refund:read']), ['ADMIN'], 'EMPLOYEE')).map((item) => item.path)).toContain('/refunds')
    expect(flatten(visibleMenuItems(permissions(['settlement:read']), ['ADMIN'], 'EMPLOYEE')).map((item) => item.path)).toContain('/settlements')
    expect(flatten(visibleMenuItems(permissions(['refund:read']), ['MANAGER'], 'EMPLOYEE')).map((item) => item.path)).toContain('/refunds')
    expect(flatten(visibleMenuItems(permissions(['settlement:read']), ['INSTITUTION_FINANCE'], 'INSTITUTION')).map((item) => item.path)).toContain('/settlements')
    expect(flatten(visibleMenuItems(permissions(['refund:write', 'settlement:write']), ['ADMIN'], 'EMPLOYEE')).map((item) => item.path)).toEqual(['/'])
  })
})
