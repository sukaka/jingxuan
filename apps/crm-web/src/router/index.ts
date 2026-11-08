import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { installAuthGuard } from './guards'

/** 路由表单独导出,便于守卫单测复用同一份 meta 配置 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/HomeView.vue'),
    meta: { title: '工作台', section: '首页总览' },
  },
  {
    path: '/customers',
    name: 'customers',
    component: () => import('../views/CustomersView.vue'),
    meta: { title: '客户中心', section: '客户管理', permission: 'customer:read' },
  },
  {
    path: '/customers/merge',
    name: 'customer-merge',
    component: () => import('../views/CustomerMergeView.vue'),
    meta: { title: '查重合并', section: '客户管理', permission: 'customer:merge' },
  },
  {
    path: '/followups',
    name: 'followups',
    component: () => import('../views/FollowupsView.vue'),
    meta: { title: '每日跟进', section: '客户管理', permission: 'followup:read' },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('../views/TasksView.vue'),
    meta: { title: '回访任务', section: '客户管理', permission: 'task:read' },
  },
  {
    path: '/products',
    name: 'products',
    component: () => import('../views/ProductsView.vue'),
    meta: { title: '产品与套餐', section: '产品运营', permission: 'product:read' },
  },
  {
    path: '/skus',
    name: 'skus',
    component: () => import('../views/SkuCenterView.vue'),
    meta: { title: 'SKU 管理', section: '产品运营', permission: 'product:read' },
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrdersView.vue'),
    meta: { title: '订单与支付', section: '交易管理', permission: 'order:read' },
  },
  {
    path: '/entitlements',
    name: 'entitlements',
    component: () => import('../views/EntitlementsView.vue'),
    meta: { title: '客户权益', section: '交易管理', permission: 'entitlement:read' },
  },
  {
    path: '/refunds',
    name: 'refunds',
    component: () => import('../views/RefundsView.vue'),
    meta: { title: '退款管理', section: '交易管理', permission: 'refund:read' },
  },
  {
    path: '/settlements',
    name: 'settlements',
    component: () => import('../views/SettlementsView.vue'),
    meta: { title: '机构结算与对账', section: '财务结算', permission: 'settlement:read' },
  },
  {
    path: '/integrations/youzan',
    name: 'youzan-orders',
    component: () => import('../views/YouzanOrdersView.vue'),
    meta: { title: '有赞订单同步', section: '集成中心', permission: 'order:read' },
  },
  {
    path: '/customers/ownership-logs',
    name: 'customer-ownership-logs',
    component: () => import('../views/CustomerOwnershipLogsView.vue'),
    meta: { title: '客户归属日志', section: '客户管理', permission: 'customer:read' },
  },
  {
    path: '/integrations/wecom',
    name: 'wecom-workspace',
    component: () => import('../views/WecomArchiveView.vue'),
    meta: { title: '企业微信 · AI', section: '客户管理', permission: 'wecom:read' },
  },
  {
    path: '/integrations/wecom/monitor',
    name: 'wecom-monitor',
    component: () => import('../views/WecomMonitorView.vue'),
    meta: { title: '会话监管', section: '客户管理', permission: 'wecom:read' },
  },
  {
    path: '/integrations/wecom/archive',
    name: 'wecom-archive-admin',
    component: () => import('../views/WecomArchiveAdminView.vue'),
    meta: { title: '归档管理', section: '客户管理', permission: 'wecom:read' },
  },
  {
    path: '/institutions/change-approvals',
    name: 'institution-change-approvals',
    component: () => import('../views/InstitutionChangeApprovalsView.vue'),
    meta: { title: '资料变更审批', section: '机构合作', permission: 'institution:read' },
  },
  {
    path: '/institutions/renewals',
    name: 'institution-renewals',
    component: () => import('../views/ContractRenewalsView.vue'),
    meta: { title: '合同续签管理', section: '机构合作', permission: 'institution:read' },
  },
  {
    path: '/institutions/merchant',
    name: 'institution-merchant',
    component: () => import('../views/MerchantLibraryView.vue'),
    meta: { title: '机构产品库 / 一页纸', section: '机构合作', permission: 'institution:read' },
  },
  {
    path: '/institutions',
    name: 'institutions',
    component: () => import('../views/InstitutionsView.vue'),
    meta: { title: '机构主档与审批', section: '机构合作', permission: 'institution:read' },
  },
  {
    path: '/appointments',
    name: 'appointments',
    component: () => import('../views/AppointmentsView.vue'),
    meta: { title: '预约与机构履约', section: '机构履约', permission: 'appointment:read' },
  },
  {
    path: '/redemptions',
    name: 'redemptions',
    component: () => import('../views/RedemptionsView.vue'),
    meta: { title: '核销记录与冲正', section: '机构履约', permission: 'redemption:read' },
  },
  {
    path: '/operations/dashboard',
    name: 'operations-dashboard',
    component: () => import('../views/dashboard/OperationsDashboardView.vue'),
    meta: { title: '运营数据大屏', section: '数据经营', permission: 'report:read', display: true },
  },
  {
    path: '/operations',
    name: 'operations',
    component: () => import('../views/OperationsView.vue'),
    meta: { title: '运营数据中心', section: '数据经营', permission: 'report:read' },
  },
  {
    path: '/performance',
    name: 'performance',
    component: () => import('../views/PerformanceView.vue'),
    meta: { title: '管家 / 商务业绩', section: '数据经营', permission: 'report:read' },
  },
  {
    path: '/campaigns',
    name: 'campaigns',
    component: () => import('../views/CampaignsView.vue'),
    meta: { title: '活动 / 大促分析', section: '产品运营', permission: 'campaign:read' },
  },
  {
    path: '/materials',
    name: 'materials',
    component: () => import('../views/MaterialsView.vue'),
    meta: { title: '素材库', section: '产品运营', permission: 'material:read' },
  },
  {
    path: '/exports',
    name: 'exports',
    component: () => import('../views/ExportsView.vue'),
    meta: { title: '数据导出中心', section: '数据经营', permission: 'export:read' },
  },
  {
    path: '/risks',
    name: 'risks',
    component: () => import('../views/RiskCasesView.vue'),
    meta: { title: '异常订单 / 风控台账', section: '交易履约', permission: 'risk:read' },
  },
  {
    path: '/channels',
    name: 'channels',
    component: () => import('../views/ChannelsView.vue'),
    meta: { title: '合作渠道', section: '机构合作', permission: 'channel:read' },
  },
  {
    path: '/users',
    name: 'users',
    component: () => import('../views/UsersView.vue'),
    meta: { title: '用户管理', section: '系统管理', permission: 'user:read' },
  },
  {
    path: '/roles',
    name: 'roles',
    component: () => import('../views/RolesView.vue'),
    meta: { title: '角色权限', section: '系统管理', permission: 'user:read' },
  },
  {
    path: '/dictionaries',
    name: 'dictionaries',
    component: () => import('../views/DictionariesView.vue'),
    meta: { title: '字典管理', section: '系统管理', permission: 'dictionary:read' },
  },
  {
    path: '/audit-logs',
    name: 'audit-logs',
    component: () => import('../views/AuditLogsView.vue'),
    meta: { title: '审计日志', section: '系统管理', permission: 'audit:read' },
  },
  {
    path: '/settings/ai',
    name: 'ai-settings',
    component: () => import('../views/AiSettingsView.vue'),
    meta: { title: '大模型配置', section: '系统管理', permission: 'ai:config' },
  },
  {
    path: '/settings/ai/knowledge',
    name: 'ai-knowledge-settings',
    component: () => import('../views/AiKnowledgeSettingsView.vue'),
    meta: { title: '知识库配置', section: '系统管理', permission: 'ai:knowledge:config' },
  },
  {
    path: '/knowledge/documents',
    name: 'knowledge-documents',
    component: () => import('../views/KnowledgeDocumentsView.vue'),
    meta: { title: '知识资料', section: '系统管理', permission: ['ai:knowledge:read', 'ai:knowledge:publish'] },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('../views/ForbiddenView.vue'),
    meta: { title: '无权访问' },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

installAuthGuard(router)

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 鲸选美学 CRM` : '鲸选美学 CRM'
})
