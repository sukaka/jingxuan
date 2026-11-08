import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

describe('知识资料路由及权限入口', () => {
  it('注册知识资料管理页面，读取与发布任一权限均可进入', () => {
    const route = routes.find((item) => item.path === '/knowledge/documents')
    expect(route?.name).toBe('knowledge-documents')
    expect(route?.meta).toMatchObject({
      title: '知识资料', section: '系统管理', permission: ['ai:knowledge:read', 'ai:knowledge:publish'],
    })
    expect(route?.component).toBeTypeOf('function')
  })

  it('具备知识读取或发布权限的管理员菜单显示入口，普通管家不显示', () => {
    const withoutPermission = visibleMenuItems(() => false, ['ADMIN'], 'EMPLOYEE')
    const withReadPermission = visibleMenuItems((code) => code === 'ai:knowledge:read', ['ADMIN'], 'EMPLOYEE')
    const withPublishPermission = visibleMenuItems((code) => code === 'ai:knowledge:publish', ['ADMIN'], 'EMPLOYEE')
    const manager = visibleMenuItems((code) => code === 'ai:knowledge:publish', ['MANAGER'], 'EMPLOYEE')

    expect(flatten(withoutPermission).map((item) => item.path)).not.toContain('/knowledge/documents')
    expect(flatten(withReadPermission).map((item) => item.path)).toContain('/knowledge/documents')
    expect(flatten(withPublishPermission).map((item) => item.path)).toContain('/knowledge/documents')
    expect(flatten(manager).map((item) => item.path)).not.toContain('/knowledge/documents')
  })
})
