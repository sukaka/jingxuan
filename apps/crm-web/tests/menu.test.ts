import { describe, expect, it } from 'vitest'
import { isMenuGroup, visibleMenuItems } from '../src/router/menu'
import type { MenuEntry, MenuItem } from '../src/router/menu'

function hasPermissionFrom(codes: string[]): (code: string) => boolean {
  const owned = new Set(codes)
  return (code) => owned.has(code)
}

function flatten(items: MenuEntry[]): MenuItem[] {
  return items.flatMap((item) => isMenuGroup(item) ? item.children : [item])
}

function enabledPaths(items: MenuEntry[]): string[] {
  return flatten(items).filter((item) => !item.disabled).map((item) => item.path)
}

function futureTitles(items: MenuEntry[]): string[] {
  return flatten(items).filter((item) => item.disabled).map((item) => item.title)
}

describe('分角色权限菜单', () => {
  it('管理员无业务权限时只保留工作台，不展示后续栏目', () => {
    const items = visibleMenuItems(hasPermissionFrom([]), ['ADMIN'], 'EMPLOYEE')

    expect(enabledPaths(items)).toEqual(['/', '/notifications'])
    expect(flatten(items).map((item) => item.path)).toEqual(['/', '/notifications'])
    expect(futureTitles(items)).toEqual([])
  })

  it('管理员按 user:read 显示用户与角色，按字典和审计权限显示系统入口', () => {
    const userItems = visibleMenuItems(hasPermissionFrom(['user:read']), ['ADMIN'], 'EMPLOYEE')
    expect(enabledPaths(userItems)).toEqual(['/', '/notifications', '/users', '/roles'])
    expect(userItems.some((item) => isMenuGroup(item) && item.key === 'system')).toBe(true)

    const auditItems = visibleMenuItems(
      hasPermissionFrom(['dictionary:read', 'audit:read']),
      ['ADMIN'],
      'EMPLOYEE',
    )
    expect(enabledPaths(auditItems)).toEqual(['/', '/notifications', '/dictionaries', '/audit-logs'])
  })

  it('普通管家使用独立导航和角色文案，不显示无权限后续栏目', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(['customer:read', 'followup:read', 'task:read']),
      ['MANAGER'],
      'EMPLOYEE',
    )

    expect(enabledPaths(items)).toEqual([
      '/',
      '/notifications',
      '/tasks',
      '/followups',
      '/followups/monthly-dispatch',
      '/customers',
      '/customers/segments',
      '/customers/tier-reviews',
      '/customers/batch-tag-jobs',
      '/customers/annual-reports',
    ])
    expect(flatten(items).filter((item) => !item.disabled).map((item) => item.title)).toEqual([
      '我的工作台',
      '通知中心',
      '今日待办',
      '客户跟进',
      '月度派单',
      '我的客户',
      '客户分组',
      '等级复核',
      '批量标签',
      '年终盘点 H5',
    ])
    expect(futureTitles(items)).toEqual([])
    expect(enabledPaths(items)).not.toContain('/users')
    expect(enabledPaths(items)).not.toContain('/audit-logs')
  })

  it('机构用户使用机构导航，不按员工角色或管理员菜单展示', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(['institution:read', 'appointment:read', 'redemption:read']),
      ['INSTITUTION_OPERATOR'],
      'INSTITUTION',
    )

    expect(enabledPaths(items)).toEqual([
      '/',
      '/notifications',
      '/institutions',
      '/institutions/change-approvals',
      '/appointments',
      '/redemptions',
      '/redemptions?view=records',
    ])
    expect(flatten(items).filter((item) => !item.disabled).map((item) => item.title)).toEqual([
      '机构工作台',
      '通知中心',
      '机构资料',
      '资料变更申请',
      '预约接待',
      '核销确认',
      '服务记录',
    ])
    expect(futureTitles(items)).toEqual([])
  })

  it('没有 customer:merge 时只过滤合并入口，不影响同组其他入口', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(['customer:read', 'followup:read', 'task:read']),
      ['ADMIN'],
      'EMPLOYEE',
    )
    expect(enabledPaths(items)).not.toContain('/customers/merge')
    expect(enabledPaths(items)).toContain('/customers')
    expect(enabledPaths(items)).toContain('/followups')
    expect(enabledPaths(items)).toContain('/tasks')
  })
})

describe('P2 导航增量接入', () => {
  const p2ReadPermissions = [
    'report:read',
    'campaign:read',
    'material:read',
    'export:read',
    'risk:read',
    'channel:read',
  ]
  const p2Paths = ['/operations', '/performance', '/campaigns', '/materials', '/exports', '/risks', '/channels']

  function childrenOf(items: MenuEntry[], key: string): MenuItem[] {
    const entry = items.find((item) => isMenuGroup(item) && item.key === key)
    if (!entry || !isMenuGroup(entry)) throw new Error(`缺少菜单组: ${key}`)
    return entry.children
  }

  it('管理员七个真实入口落在原型的四个中文分组中', () => {
    const items = visibleMenuItems(hasPermissionFrom(p2ReadPermissions), ['ADMIN'], 'EMPLOYEE')

    expect(childrenOf(items, 'trading').map((item) => item.path)).toContain('/risks')
    expect(childrenOf(items, 'catalog').map((item) => item.path)).toEqual(expect.arrayContaining(['/materials', '/campaigns']))
    expect(childrenOf(items, 'institution').map((item) => item.path)).toContain('/channels')
    expect(childrenOf(items, 'analytics').map((item) => item.path)).toEqual(
      expect.arrayContaining(['/operations', '/performance', '/exports']),
    )
    expect(flatten(items).filter((item) => p2Paths.includes(item.path))).toHaveLength(7)
    expect(futureTitles(items)).toEqual([])
  })

  it('写权限不代替各模块的读入口权限', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(['campaign:write', 'material:write', 'channel:write', 'risk:write', 'export:create']),
      ['ADMIN'],
      'EMPLOYEE',
    )

    expect(enabledPaths(items)).toEqual(['/', '/notifications'])
  })

  it('管家显示素材库与我的业绩，但不显示导出菜单，即使账号带有导出权限', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(['material:read', 'report:read', 'export:read', 'export:create']),
      ['MANAGER'],
      'EMPLOYEE',
    )

    expect(enabledPaths(items)).toEqual(['/', '/notifications', '/materials', '/performance', '/operations/wecom-analytics'])
    expect(flatten(items).map((item) => item.title)).toEqual(expect.arrayContaining(['素材库', '我的业绩']))
    expect(enabledPaths(items)).not.toContain('/exports')
    expect(flatten(items).map((item) => item.title)).not.toContain('数据导出中心')
  })

  it('管家素材与业绩分别按 material:read 和 report:read 过滤', () => {
    const materialOnly = visibleMenuItems(hasPermissionFrom(['material:read']), ['MANAGER'], 'EMPLOYEE')
    const reportOnly = visibleMenuItems(hasPermissionFrom(['report:read']), ['MANAGER'], 'EMPLOYEE')

    expect(enabledPaths(materialOnly)).toEqual(['/', '/notifications', '/materials'])
    expect(enabledPaths(reportOnly)).toEqual(['/', '/notifications', '/performance', '/operations/wecom-analytics'])
  })

  it('机构账号不继承 CRM 全局页面入口', () => {
    const items = visibleMenuItems(
      hasPermissionFrom([...p2ReadPermissions, 'institution:read', 'appointment:read', 'redemption:read']),
      ['ADMIN'],
      'INSTITUTION',
    )

    expect(enabledPaths(items)).not.toEqual(expect.arrayContaining(p2Paths))
    expect(childrenOf(items, 'institution-work').map((item) => item.path)).toContain('/institutions')
    expect(childrenOf(items, 'institution-service').map((item) => item.path)).toContain('/redemptions')
  })
})

describe('V30-V33 导航增量接入', () => {
  const allPermissions = [
    'customer:read',
    'customer:write',
    'followup:read',
    'complaint:read',
    'complaint:write',
    'complaint:config',
  ]
  const businessPaths = [
    '/customers/segments',
    '/followups/monthly-dispatch',
    '/customers/tier-reviews',
    '/complaints',
    '/customers/batch-tag-jobs',
  ]
  const settingsPaths = [
    '/settings/customer-tiers',
    '/settings/followup-automation',
    '/settings/complaint-automation',
  ]

  it('管理员在客户管理和系统配置看到全部真实入口，通知中心保持不变', () => {
    const items = visibleMenuItems(hasPermissionFrom(allPermissions), ['ADMIN'], 'EMPLOYEE')
    const paths = enabledPaths(items)

    expect(paths).toEqual(expect.arrayContaining([...businessPaths, ...settingsPaths, '/notifications']))
    const customerGroup = items.find((item) => isMenuGroup(item) && item.key === 'customer')
    const systemGroup = items.find((item) => isMenuGroup(item) && item.key === 'system')
    expect(customerGroup && isMenuGroup(customerGroup) ? customerGroup.children.map((item) => item.path) : [])
      .toEqual(expect.arrayContaining(businessPaths))
    expect(systemGroup && isMenuGroup(systemGroup) ? systemGroup.children.map((item) => item.path) : [])
      .toEqual(expect.arrayContaining(settingsPaths))
    expect(futureTitles(items)).toEqual([])
  })

  it('管家主管按实际权限看到业务入口和跟进提醒，不看到管理员分级规则或客诉配置', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(allPermissions.filter((code) => code !== 'complaint:config')),
      ['MANAGER_LEAD'],
      'EMPLOYEE',
    )
    const paths = enabledPaths(items)

    expect(paths).toEqual(expect.arrayContaining([...businessPaths, '/settings/followup-automation', '/notifications']))
    expect(paths).not.toContain('/settings/customer-tiers')
    expect(paths).not.toContain('/settings/complaint-automation')
  })

  it('普通管家只增加后端可访问的业务入口，不继承系统配置菜单', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(allPermissions.filter((code) => code !== 'complaint:config')),
      ['MANAGER'],
      'EMPLOYEE',
    )
    const paths = enabledPaths(items)

    expect(paths).toEqual(expect.arrayContaining([...businessPaths, '/notifications']))
    expect(paths).not.toEqual(expect.arrayContaining(settingsPaths))
  })

  it('写权限不代替分组、复核、客诉、批量标签和派单的读入口权限', () => {
    const items = visibleMenuItems(
      hasPermissionFrom(['customer:write', 'followup:write', 'complaint:write']),
      ['MANAGER'],
      'EMPLOYEE',
    )

    expect(enabledPaths(items)).not.toEqual(expect.arrayContaining(businessPaths))
  })

  it('机构账号即使带有员工权限也不显示这些员工入口', () => {
    const items = visibleMenuItems(hasPermissionFrom(allPermissions), ['INSTITUTION_OPERATOR'], 'INSTITUTION')

    expect(enabledPaths(items)).not.toEqual(expect.arrayContaining([...businessPaths, ...settingsPaths]))
    expect(enabledPaths(items)).toContain('/notifications')
  })
})


describe('V46 话术管理导航', () => {
  it('管理员按 quickreply:manage 显示话术库，普通管家不继承管理入口', () => {
    const adminItems = visibleMenuItems(hasPermissionFrom(['quickreply:manage']), ['ADMIN'], 'EMPLOYEE')
    expect(enabledPaths(adminItems)).toContain('/settings/quick-replies')

    const managerItems = visibleMenuItems(hasPermissionFrom(['quickreply:manage']), ['MANAGER'], 'EMPLOYEE')
    expect(enabledPaths(managerItems)).not.toContain('/settings/quick-replies')

    const deniedItems = visibleMenuItems(hasPermissionFrom(['quickreply:view']), ['ADMIN'], 'EMPLOYEE')
    expect(enabledPaths(deniedItems)).not.toContain('/settings/quick-replies')
  })
})
