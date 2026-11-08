import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

describe('企业微信会话与合规归档路由及菜单权限', () => {
  it.each([
    { path: '/integrations/wecom', name: 'wecom-workspace', title: '企业微信 · AI' },
    { path: '/integrations/wecom/archive', name: 'wecom-archive-admin', title: '归档管理' },
  ])('$path 使用独立真实页面和 wecom:read', ({ path, name, title }) => {
    const route = routes.find((item) => item.path === path)
    expect(route?.name).toBe(name)
    expect(route?.meta).toMatchObject({ title, section: '客户管理', permission: 'wecom:read' })
    expect(route?.component).toBeTypeOf('function')
  })

  it('wecom:monitor 不替代 wecom:read，菜单保留真实会话与监管入口', () => {
    const monitorOnly = visibleMenuItems((code) => code === 'wecom:monitor')
    const readOnly = visibleMenuItems((code) => code === 'wecom:read')
    expect(flatten(monitorOnly).map((item) => item.path)).toEqual(['/'])
    expect(flatten(readOnly).map((item) => item.path)).toEqual([
      '/',
      '/integrations/wecom',
      '/integrations/wecom/monitor',
    ])
    expect(flatten(readOnly).map((item) => item.path)).not.toContain('/integrations/wecom/archive')
  })

  it('机构主体即使错误配置了读取权限也不显示企微入口', () => {
    const items = visibleMenuItems((code) => code === 'wecom:read', ['INSTITUTION_OPERATOR'], 'INSTITUTION')
    expect(flatten(items).map((item) => item.path)).toEqual(['/'])
  })
})
