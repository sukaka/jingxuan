import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

describe('有赞同步路由与菜单权限', () => {
  it('/integrations/youzan 使用 order:read 和真实页面', () => {
    const route = routes.find((item) => item.path === '/integrations/youzan')
    expect(route?.name).toBe('youzan-orders')
    expect(route?.meta).toMatchObject({ section: '集成中心', permission: 'order:read' })
    expect(route?.component).toBeTypeOf('function')
  })

  it('campaign:write 不替代 order:read，读取权限才显示同步菜单', () => {
    const writeOnly = visibleMenuItems((code) => code === 'campaign:write')
    const readOnly = visibleMenuItems((code) => code === 'order:read')
    expect(flatten(writeOnly).map((item) => item.path)).toEqual(['/'])
    expect(flatten(readOnly).map((item) => item.path)).toEqual(['/', '/orders', '/integrations/youzan'])
  })
})
