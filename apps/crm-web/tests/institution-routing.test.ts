import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

describe('P0-5 机构路由与菜单', () => {
  it('/institutions 使用真实页面和 institution:read', () => {
    const route = routes.find((item) => item.path === '/institutions')
    expect(route?.name).toBe('institutions')
    expect(route?.meta).toMatchObject({ section: '机构合作', permission: 'institution:read' })
    expect(route?.component).toBeTypeOf('function')
  })

  it('读取权限控制菜单，写和审核权限不替代读取', () => {
    const has = (code: string) => code === 'institution:write'
    expect(flatten(visibleMenuItems(has)).map((item) => item.path)).toEqual(['/'])
    const approve = (code: string) => ['institution:approve_market', 'institution:approve_finance'].includes(code)
    expect(flatten(visibleMenuItems(approve)).map((item) => item.path)).toEqual(['/'])
    const read = (code: string) => code === 'institution:read'
    expect(flatten(visibleMenuItems(read)).map((item) => item.path)).toEqual([
      '/',
      '/institutions',
      '/institutions/change-approvals',
      '/institutions/renewals',
      '/institutions/merchant',
    ])
  })
})
