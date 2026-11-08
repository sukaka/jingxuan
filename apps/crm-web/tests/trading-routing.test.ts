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

describe('P0-3 订单与权益导航权限', () => {
  it.each([
    ['/orders', 'orders', 'order:read'],
    ['/entitlements', 'entitlements', 'entitlement:read'],
  ])('%s 使用独立读取权限和真实页面组件', (path, name, permission) => {
    const route = routes.find((item) => item.path === path)
    expect(route?.name).toBe(name)
    expect(route?.meta).toMatchObject({ section: '交易管理', permission })
    expect(route?.component).toBeTypeOf('function')
  })

  it('管家订单读取权限只开放我的订单，不隐式开放权益或有赞同步', () => {
    const items = visibleMenuItems(permissions(['order:read']), ['MANAGER'])
    expect(flatten(items).map((item) => item.path)).toEqual(['/', '/orders'])
  })

  it('权益读取权限只开放权益入口，支付确认权限不代替读取权限', () => {
    const entitlementOnly = visibleMenuItems(permissions(['entitlement:read']))
    const confirmOnly = visibleMenuItems(permissions(['order:confirm_payment']))
    expect(flatten(entitlementOnly).map((item) => item.path)).toEqual(['/', '/entitlements'])
    expect(flatten(confirmOnly).map((item) => item.path)).toEqual(['/'])
  })
})
