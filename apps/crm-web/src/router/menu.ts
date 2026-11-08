export interface MenuItem {
  path: string
  title: string
  managerTitle?: string
  permission?: string | string[]
  excludedUserTypes?: string[]
  icon: string
  badgeKey?: string
  disabled?: boolean
}

export interface MenuGroup {
  key: string
  title: string
  icon: string
  children: MenuItem[]
}

export type MenuEntry = MenuItem | MenuGroup

export function isMenuGroup(entry: MenuEntry): entry is MenuGroup {
  return 'children' in entry
}

const item = (path: string, title: string, icon: string, permission?: string | string[], extra: Partial<MenuItem> = {}): MenuItem =>
  ({ path, title, icon, permission, ...extra })
const group = (key: string, title: string, icon: string, children: MenuItem[]): MenuGroup => ({ key, title, icon, children })

const ADMIN_MENU: MenuEntry[] = [
  group('home', '首页总览', 'grid', [item('/', '工作台', 'grid')]),
  group('customer', '客户管理', 'users', [
    item('/customers', '客户中心', 'users', 'customer:read'),
    item('/customers/merge', '客户查重合并', 'users', 'customer:merge'),
    item('/followups', '每日跟进台账', 'chat', 'followup:read'),
    item('/tasks', '回访任务', 'clipboard', 'task:read', { badgeKey: 'tasks' }),
    item('/integrations/wecom', '企业微信 · AI', 'spark', 'wecom:read'),
    item('/integrations/wecom/monitor', '会话监管', 'chat', 'wecom:read'),
    item('/customers/ownership-logs', '客户归属日志', 'users', 'customer:read'),
  ]),
  group('trading', '交易履约', 'bag', [
    item('/entitlements', '会员权益', 'heart', 'entitlement:read'),
    item('/orders', '订单售卖', 'bag', 'order:read'),
    item('/integrations/youzan', '有赞订单同步', 'refresh', 'order:read'),
    item('/risks', '异常订单 / 风控台账', 'shield', 'risk:read'),
    item('/appointments', '预约管理', 'calendar', 'appointment:read', { badgeKey: 'appointments' }),
    item('/redemptions', '核销记录', 'check', 'redemption:read'),
    item('/refunds', '退款与对账', 'wallet', 'refund:read'),
    item('/settlements', '机构结算', 'wallet', 'settlement:read'),
  ]),
  group('catalog', '产品运营', 'file', [
    item('/products', '产品与套餐', 'file', 'product:read'),
    item('/skus', 'SKU标准化与质量', 'activity', 'product:read'),
    item('/materials', '素材库', 'file', 'material:read'),
    item('/campaigns', '活动 / 大促分析', 'chart', 'campaign:read'),
  ]),
  group('institution', '机构合作', 'hospital', [
    item('/institutions', '机构中心', 'hospital', 'institution:read'),
    item('/institutions/change-approvals', '资料变更审批', 'shield', 'institution:read', { badgeKey: 'changes' }),
    item('/institutions/renewals', '合同续签管理', 'clipboard', 'institution:read'),
    item('/institutions/merchant', '机构产品库 / 一页纸', 'file', 'institution:read'),
    item('/channels', '合作渠道', 'megaphone', 'channel:read'),
  ]),
  group('analytics', '数据经营', 'chart', [
    item('/operations/dashboard', '运营数据大屏', 'chart', 'report:read'),
    item('/operations', '运营数据中心', 'chart', 'report:read'),
    item('/performance', '管家 / 商务业绩', 'chart', 'report:read'),
    item('/exports', '数据导出中心', 'download', 'export:read'),
  ]),
  group('system', '系统配置', 'settings', [
    item('/dictionaries', '基础字典 / 列表配置', 'settings', 'dictionary:read'),
    item('/users', '用户管理', 'users', 'user:read'),
    item('/roles', '角色权限', 'shield', 'user:read'),
    item('/settings/ai', '大模型配置', 'spark', 'ai:config'),
    item('/settings/ai/knowledge', '知识库配置', 'file', 'ai:knowledge:config'),
    item('/knowledge/documents', '知识资料', 'clipboard', ['ai:knowledge:read', 'ai:knowledge:publish']),
    item('/audit-logs', '审计日志', 'activity', 'audit:read'),
  ]),
]

const MANAGER_MENU: MenuEntry[] = [
  group('work', '我的工作', 'grid', [
    item('/', '我的工作台', 'grid'),
    item('/tasks', '今日待办', 'clipboard', 'task:read', { badgeKey: 'tasks' }),
    item('/followups', '客户跟进', 'chat', 'followup:read'),
  ]),
  group('customers', '客户管理', 'users', [
    item('/customers', '我的客户', 'users', 'customer:read'),
    item('/integrations/wecom', '企微 AI 助手', 'spark', 'wecom:read'),
  ]),
  group('service', '服务履约', 'calendar', [
    item('/appointments', '预约管理', 'calendar', 'appointment:read', { badgeKey: 'appointments' }),
    item('/redemptions', '核销记录', 'check', 'redemption:read'),
    item('/orders', '我的订单', 'bag', 'order:read'),
    item('/refunds', '退款申请', 'wallet', 'refund:read'),
  ]),
  group('catalog', '产品运营', 'file', [item('/materials', '素材库', 'file', 'material:read')]),
  group('performance', '数据业绩', 'chart', [item('/performance', '我的业绩', 'chart', 'report:read')]),
]

const INSTITUTION_MENU: MenuEntry[] = [
  group('institution-work', '机构工作', 'grid', [
    item('/', '机构工作台', 'grid'),
    item('/institutions', '机构资料', 'hospital', 'institution:read'),
    item('/institutions/change-approvals', '资料变更申请', 'clipboard', 'institution:read'),
  ]),
  group('institution-service', '客户履约', 'calendar', [
    item('/appointments', '预约接待', 'calendar', 'appointment:read', { badgeKey: 'appointments' }),
    item('/redemptions', '核销确认', 'check', 'redemption:read'),
    item('/redemptions?view=records', '服务记录', 'file', 'redemption:read'),
  ]),
  group('settlement', '财务结算', 'wallet', [item('/settlements', '对账概览', 'wallet', 'settlement:read')]),
]

export function visibleMenuItems(
  hasPermission: (code: string) => boolean,
  roles: readonly string[] = [],
  userType?: string,
): MenuEntry[] {
  const entries = userType === 'INSTITUTION'
    ? INSTITUTION_MENU
    : roles.includes('MANAGER') && !roles.includes('ADMIN') ? MANAGER_MENU : ADMIN_MENU
  const allowed = (entry: MenuItem) => {
    if (!entry.permission) return !entry.disabled
    return Array.isArray(entry.permission)
      ? entry.permission.some(hasPermission)
      : hasPermission(entry.permission)
  }
  return entries.flatMap((entry) => {
    if (!isMenuGroup(entry)) return allowed(entry) ? [entry] : []
    const children = entry.children.filter(allowed).filter((child) => !(userType && child.excludedUserTypes?.includes(userType)))
    return children.length ? [{ ...entry, children } as MenuEntry] : []
  })
}
