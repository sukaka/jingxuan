import { describe, expect, it } from 'vitest'
import { routes } from '../src/router/index'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'

describe('AI 聊天托管导航', () => {
  it('路由和菜单复用 AI 读权限，机构账号无入口', () => {
    const route = routes.find((item) => item.path === '/ai/chat-delegation')
    expect(route?.meta).toMatchObject({ title: 'AI 聊天托管', permission: 'ai:read' })

    const admin = visibleMenuItems((code) => code === 'ai:read', ['ADMIN'], 'EMPLOYEE')
    const manager = visibleMenuItems((code) => code === 'ai:read', ['MANAGER'], 'EMPLOYEE')
    const institution = visibleMenuItems(() => true, ['INSTITUTION_OPERATOR'], 'INSTITUTION')
    expect(admin.some((entry) => isMenuGroup(entry) && entry.children.some((item) => item.path === '/ai/chat-delegation'))).toBe(true)
    expect(manager.some((entry) => isMenuGroup(entry) && entry.children.some((item) => item.path === '/ai/chat-delegation'))).toBe(true)
    expect(institution.some((entry) => isMenuGroup(entry) && entry.children.some((item) => item.path === '/ai/chat-delegation'))).toBe(false)
  })
})
