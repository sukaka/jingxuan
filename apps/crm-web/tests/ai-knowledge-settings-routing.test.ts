import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

describe('Dify 知识配置路由及权限入口', () => {
  it('注册独立系统管理页面并声明权限', () => {
    const route = routes.find((item) => item.path === '/settings/ai/knowledge')
    expect(route?.name).toBe('ai-knowledge-settings')
    expect(route?.meta).toMatchObject({ title: '知识库配置', section: '系统管理', permission: 'ai:knowledge:config' })
    expect(route?.component).toBeTypeOf('function')
  })

  it('只有具备知识配置权限的管理员菜单显示入口', () => {
    const withoutPermission = visibleMenuItems(() => false, ['ADMIN'], 'EMPLOYEE')
    const withPermission = visibleMenuItems((code) => code === 'ai:knowledge:config', ['ADMIN'], 'EMPLOYEE')
    const manager = visibleMenuItems((code) => code === 'ai:knowledge:config', ['MANAGER'], 'EMPLOYEE')

    expect(flatten(withoutPermission).map((item) => item.path)).not.toContain('/settings/ai/knowledge')
    expect(flatten(withPermission).map((item) => item.path)).toContain('/settings/ai/knowledge')
    expect(flatten(manager).map((item) => item.path)).not.toContain('/settings/ai/knowledge')
  })
})
