import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

describe('AI 配置路由及 ai:config 权限入口', () => {
  it('注册系统管理页面并同时声明路由权限', () => {
    const route = routes.find((item) => item.path === '/settings/ai')
    expect(route?.name).toBe('ai-settings')
    expect(route?.meta).toMatchObject({ title: '大模型配置', section: '系统管理', permission: 'ai:config' })
    expect(route?.component).toBeTypeOf('function')
  })

  it('只有具备 ai:config 的管理员菜单显示入口', () => {
    const withoutPermission = visibleMenuItems(() => false, ['ADMIN'], 'EMPLOYEE')
    const withPermission = visibleMenuItems((code) => code === 'ai:config', ['ADMIN'], 'EMPLOYEE')
    const manager = visibleMenuItems((code) => code === 'ai:config', ['MANAGER'], 'EMPLOYEE')

    expect(flatten(withoutPermission).map((item) => item.path)).not.toContain('/settings/ai')
    expect(flatten(withPermission).map((item) => item.path)).toContain('/settings/ai')
    expect(flatten(manager).map((item) => item.path)).not.toContain('/settings/ai')
  })
})
