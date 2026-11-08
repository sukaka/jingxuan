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

describe('P0-4 履约路由与菜单权限', () => {
  it.each([
    ['/appointments', 'appointments', 'appointment:read'],
    ['/redemptions', 'redemptions', 'redemption:read'],
  ])('%s 使用真实组件和独立读取权限', (path, name, permission) => {
    const route = routes.find((item) => item.path === path)
    expect(route?.name).toBe(name)
    expect(route?.meta).toMatchObject({ section: '机构履约', permission })
    expect(route?.component).toBeTypeOf('function')
  })

  it('预约权限不隐式开放核销，操作权限不代替读取权限', () => {
    const appointmentOnly = visibleMenuItems(permissions(['appointment:read', 'redemption:post']), ['FRONTDESK'])
    const redemptionOnly = visibleMenuItems(permissions(['redemption:read', 'redemption:approve']))
    expect(flatten(appointmentOnly).map((item) => item.path)).toEqual(['/', '/appointments'])
    expect(flatten(redemptionOnly).map((item) => item.path)).toEqual(['/', '/redemptions'])
  })

  it('管家看到服务履约中的预约管理入口', () => {
    const items = visibleMenuItems(permissions(['appointment:read']), ['MANAGER'])
    expect(flatten(items).map((item) => item.title)).toContain('预约管理')
  })
})
