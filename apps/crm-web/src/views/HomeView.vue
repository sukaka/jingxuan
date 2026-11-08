<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listCustomers } from '../api/customer'
import { listFollowups } from '../api/followup'
import type { FollowupItem } from '../api/followup'
import { listTasks } from '../api/task'
import type { TaskItem } from '../api/task'
import { getRedemption, listAppointments, listRedemptions } from '../api/fulfillment'
import type { Appointment } from '../api/fulfillment'
import { getPerformanceReport } from '../api/report'
import type { PerformanceTotals } from '../api/report'
import { getEntitlementSummary } from '../api/trading'
import type { EntitlementSummary } from '../api/trading'
import { useAuthStore } from '../stores/auth'
import { useReferenceNames } from './references'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import ShellIcon from '../components/shell/ShellIcon.vue'

const auth = useAuthStore()
const isManager = computed(() => auth.hasRole('MANAGER') && !auth.hasRole('ADMIN'))
const isInstitution = computed(() => auth.user?.userType === 'INSTITUTION')
const displayName = computed(() => auth.user?.displayName || auth.user?.username || '欢迎回来')
const customersTotal = ref(0)
const tasks = ref<TaskItem[]>([])
const taskTotal = ref(0)
const followups = ref<FollowupItem[]>([])
const followupTotal = ref(0)
const appointments = ref<Appointment[]>([])
const appointmentTotal = ref(0)
const redemptionCount = ref<number | null>(null)
const monthlyPerformance = ref<PerformanceTotals | null>(null)
const remainingServices = ref<EntitlementSummary | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const today = new Date().toISOString().slice(0, 10)
const pendingAppointments = computed(() => appointments.value.filter((item) => item.status === 'PENDING_INSTITUTION'))
const serviceDoneAppointments = computed(() => appointments.value.filter((item) => item.status === 'SERVICE_DONE'))
const todayAppointments = computed(() => appointments.value.filter((item) => item.scheduledAt?.slice(0, 10) === today && item.status !== 'CANCELED'))
const { loadCustomerNames, customerLabel } = useReferenceNames()
function priorityLabel(value: string) { return ({ URGENT: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' } as Record<string, string>)[value] || '未知优先级' }
const APPOINTMENT_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  CONFIRMED: ['机构已确认', 'success'],
  CHECKED_IN: ['客户已到院', 'info'],
  SERVICE_DONE: ['服务完成', 'success'],
  REDEEMED: ['已完成', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  CANCELED: ['已取消', 'dark'],
  NO_SHOW: ['未到院', 'danger'],
}
function appointmentPill(status: string) { return APPOINTMENT_STATUS[status] || ['未知状态', 'dark'] }
const managerBuckets = computed(() => [
  { title: '今日待办', value: taskTotal.value, note: '待处理任务', icon: 'clipboard', path: '/tasks' },
  { title: '待确认预约', value: appointmentTotal.value, note: '待机构确认', icon: 'calendar', path: '/appointments' },
  { title: '我的客户', value: customersTotal.value, note: '当前数据范围', icon: 'users', path: '/customers' },
  { title: '跟进流水', value: followupTotal.value, note: '独立追加记录', icon: 'chat', path: '/followups' },
])
const todayLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const monthParts = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
}).formatToParts(new Date())
const currentYear = Number(monthParts.find((part) => part.type === 'year')?.value)
const currentMonth = Number(monthParts.find((part) => part.type === 'month')?.value)
const monthFrom = `${currentYear}-${String(currentMonth).padStart(2, '0')}-01`
const monthTo = new Date(Date.UTC(currentYear, currentMonth, 0)).toISOString().slice(0, 10)
const money = (fen: number) => `¥${(fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const monthlyPerformanceText = computed(() => monthlyPerformance.value === null ? '—' : money(monthlyPerformance.value.netPaidFen))
const monthlyPerformanceNote = computed(() => monthlyPerformance.value === null ? '需要报表查看权限'
  : `支付 ${money(monthlyPerformance.value.paidFen)} · 退款 ${money(monthlyPerformance.value.refundFen)}`)
const remainingServicesText = computed(() => remainingServices.value === null ? '—' : `${remainingServices.value.availableCount} 次`)
const remainingServicesNote = computed(() => remainingServices.value === null ? '需要权益查看权限'
  : `${remainingServices.value.customerCount} 位客户的有效可用权益`)
const adminNavigation = [
  { title: '客户管理', description: '客户档案与归属', icon: 'users', path: '/customers' },
  { title: '订单售卖', description: '订单、支付与权益', icon: 'bag', path: '/orders' },
  { title: '预约履约', description: '预约与到院服务', icon: 'calendar', path: '/appointments' },
  { title: '每日跟进', description: '沟通记录与回访', icon: 'chat', path: '/followups' },
  { title: '合作机构', description: '机构资料与审批', icon: 'hospital', path: '/institutions' },
  { title: '归属日志', description: '客户转交与历史', icon: 'clipboard', path: '/customers/ownership-logs' },
]

async function loadDashboard() {
  loading.value = true
  errorMessage.value = ''
  try {
    const requests: Promise<void>[] = []
    if (auth.hasPermission('customer:read')) requests.push(listCustomers({ page: 1, size: 5 }).then((result) => { customersTotal.value = result.total }))
    if (auth.hasPermission('task:read')) requests.push(listTasks({ page: 1, size: 6, status: 'OPEN' }).then((result) => { tasks.value = result.items; taskTotal.value = result.total }))
    if (auth.hasPermission('followup:read')) requests.push(listFollowups({ page: 1, size: 5 }).then((result) => { followups.value = result.items; followupTotal.value = result.total }))
    if (auth.hasPermission('appointment:read')) requests.push(listAppointments({ page: 1, size: 100 }).then((result) => { appointments.value = result.items; appointmentTotal.value = result.total; void loadCustomerNames(result.items.map((item) => item.customerId)) }))
    if (isManager.value && auth.hasPermission('report:read')) requests.push(getPerformanceReport({
      from: monthFrom, to: monthTo, timeZone: 'Asia/Shanghai', performanceRole: 'MANAGER', page: 1, size: 1,
    }).then((result) => { monthlyPerformance.value = result.totals }))
    if (isManager.value && auth.hasPermission('entitlement:read')) requests.push(getEntitlementSummary()
      .then((result) => { remainingServices.value = result }))
    if (isInstitution.value && auth.hasPermission('redemption:read')) requests.push(listRedemptions({ page: 1, size: 100, status: 'POSTED' }).then(async (result) => {
      const details = await Promise.all(result.items.map((item) => getRedemption(item.id)))
      redemptionCount.value = details.reduce((sum, detail) => sum + detail.lines.reduce((lineSum, line) => lineSum + line.count, 0), 0)
    }))
    await Promise.all(requests)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '工作台数据加载失败'
  } finally {
    loading.value = false
  }
}
function date(value: string | null) { return value ? value.slice(0, 10) : '待设置' }
function time(value: string | null) { return value ? value.slice(0, 16).replace('T', ' ') : '待设置' }
onMounted(() => { void loadDashboard() })
</script>

<template>
  <div class="proto-page home-page">
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
    <template v-if="isInstitution">
      <PageHeader title="合作机构工作台" :description="`${displayName} · 只处理本机构预约、到院服务与核销确认`" />
      <div class="proto-banner"><div><span class="proto-pill success"><ShellIcon name="bell" :size="14" />机构待处理提醒</span><p><strong>{{ pendingAppointments.length }} 个新预约待机构确认，{{ serviceDoneAppointments.length }} 项服务待核销确认。</strong></p><p>客户完成服务后由本机构授权操作员确认核销，历史事实不可覆盖。</p></div><RouterLink to="/appointments" class="el-button el-button--primary">立即处理</RouterLink></div>
      <div class="proto-stat-grid">
        <StatCard label="待确认预约" :value="`${pendingAppointments.length} 项`" sub="管家已经选择本机构" icon="calendar" />
        <StatCard label="今日接待" :value="`${todayAppointments.length} 位`" sub="仅本机构预约" icon="users" />
        <StatCard label="待核销确认" :value="`${serviceDoneAppointments.length} 项`" sub="确认后才扣减客户权益" icon="check" />
        <StatCard label="累计正式核销" :value="redemptionCount === null ? '加载中' : `${redemptionCount} 次`" sub="本机构历史服务流水" icon="activity" />
      </div>
      <div class="proto-two-col">
        <PageCard title="待处理预约" subtitle="机构需要确认是否接诊 / 排期">
          <template #actions><RouterLink to="/appointments" class="el-button">全部预约</RouterLink></template>
          <div class="proto-table-wrap"><app-table :data="appointments.filter(item => !['REDEEMED','CANCELED','REJECTED'].includes(item.status)).slice(0,6)"><el-table-column label="预约单" prop="appointmentNo" min-width="150" /><el-table-column label="客户" min-width="100"><template #default="{row}">{{ customerLabel(row.customerId) }}</template></el-table-column><el-table-column label="预约日期" min-width="160"><template #default="{row}">{{ time(row.scheduledAt) }}</template></el-table-column><el-table-column label="状态" width="130"><template #default="{row}"><span class="proto-pill" :class="appointmentPill(row.status)[1]">{{ appointmentPill(row.status)[0] }}</span></template></el-table-column></app-table></div>
        </PageCard>
        <PageCard title="机构消息中心" subtitle="由管家预约和核销申请自动形成"><div class="proto-card-body institution-events"><div v-for="item in pendingAppointments.slice(0,5)" :key="item.id" class="institution-event"><ShellIcon name="calendar" :size="17" /><div><strong>新预约待确认 · {{ customerLabel(item.customerId) }}</strong><p>{{ time(item.scheduledAt) }} · {{ item.appointmentNo }}</p></div></div><div v-if="!pendingAppointments.length" class="proto-empty">当前没有机构待处理提醒</div></div></PageCard>
      </div>
    </template>

    <template v-else-if="isManager">
      <div class="manager-welcome"><section class="manager-hero"><span class="hero-ai"><ShellIcon name="spark" :size="14" />AI 今日工作建议</span><h1>早上好，{{ displayName }}</h1><p>今天优先处理 {{ taskTotal }} 项待办。系统只显示你负责的 {{ customersTotal }} 位客户，重要业务变更仍需人工确认。</p><div class="hero-actions"><RouterLink to="/tasks" class="el-button el-button--primary">开始处理今日待办</RouterLink><RouterLink to="/followups" class="el-button">记录客户沟通</RouterLink></div></section><section class="manager-kpi"><div><small>我的客户</small><h3>{{ customersTotal }} 位</h3><p>只统计本人负责客户</p></div><div><small>今日待办</small><h3>{{ taskTotal }} 项</h3><p>到期和逾期任务</p></div><div data-testid="manager-monthly-performance"><small>本月业绩（净支付）</small><h3>{{ monthlyPerformanceText }}</h3><p>{{ monthlyPerformanceNote }}</p></div><div data-testid="manager-remaining-services"><small>剩余服务</small><h3>{{ remainingServicesText }}</h3><p>{{ remainingServicesNote }}</p></div></section></div>
      <div class="manager-task-grid"><RouterLink v-for="bucket in managerBuckets" :key="bucket.title" :to="bucket.path" class="work-bucket"><div><span class="bucket-icon"><ShellIcon :name="bucket.icon" :size="18" /></span><ShellIcon name="arrow-right" :size="14" /></div><h3>{{ bucket.value }}</h3><strong>{{ bucket.title }}</strong><p>{{ bucket.note }}</p></RouterLink></div>
      <div class="proto-two-col"><PageCard title="今日优先处理" subtitle="按真实待办和到期时间处理"><div class="proto-table-wrap"><app-table :data="tasks"><el-table-column label="任务" prop="title" min-width="220" /><el-table-column label="优先级" width="90"><template #default="{row}">{{ priorityLabel(row.priority) }}</template></el-table-column><el-table-column label="截止日期" width="120"><template #default="{row}">{{ date(row.dueAt) }}</template></el-table-column></app-table></div></PageCard><PageCard title="需要关注" subtitle="最近真实客户跟进"><div class="proto-card-body"><div v-for="item in followups" :key="item.id" class="attention-row"><strong>{{ item.customerName || `客户 #${item.customerId}` }}</strong><p>{{ item.summary }}</p><small>{{ date(item.contactAt) }}</small></div></div></PageCard></div>
    </template>

    <template v-else>
      <PageHeader title="运营工作台" description="聚焦今日待办与日常业务处理">
        <template #actions><RouterLink to="/integrations/wecom/monitor" class="el-button"><ShellIcon name="chat" :size="15" />会话监管</RouterLink></template>
      </PageHeader>

      <section class="admin-hero" aria-labelledby="admin-hero-title">
        <div class="admin-hero-main">
          <span class="admin-hero-kicker"><ShellIcon name="clipboard" :size="15" />今日工作</span>
          <h2 id="admin-hero-title"><strong>{{ taskTotal }}</strong><span>项待办</span></h2>
          <p>优先处理到期与高优任务，及时跟进客户。</p>
          <div class="admin-hero-actions">
            <RouterLink to="/tasks" class="admin-hero-primary">处理待办<ShellIcon name="arrow-right" :size="15" /></RouterLink>
            <RouterLink to="/customers" class="admin-hero-secondary">查看客户</RouterLink>
          </div>
        </div>
        <div class="admin-hero-date">
          <ShellIcon name="calendar" :size="20" />
          <span>今天</span>
          <strong>{{ todayLabel }}</strong>
          <small>从待办开始安排工作</small>
        </div>
      </section>

      <div class="admin-workspace">
        <PageCard title="团队待办" subtitle="按到期时间与优先级处理" class="admin-task-card">
          <template #actions><RouterLink to="/tasks" class="admin-card-link">全部待办<ShellIcon name="arrow-right" :size="14" /></RouterLink></template>
          <div class="proto-table-wrap admin-task-table">
            <app-table :data="tasks">
              <el-table-column label="待办事项" min-width="240">
                <template #default="{ row }">
                  <div class="admin-task-title">{{ row.title }}</div>
                  <div class="admin-task-description">{{ row.description || '暂无补充说明' }}</div>
                </template>
              </el-table-column>
              <el-table-column label="截止日期" width="130">
                <template #default="{ row }"><span :class="{ 'is-unscheduled': !row.dueAt }">{{ date(row.dueAt) }}</span></template>
              </el-table-column>
              <el-table-column label="优先级" width="100">
                <template #default="{ row }"><span class="admin-priority" :class="`is-${row.priority.toLowerCase()}`">{{ priorityLabel(row.priority) }}</span></template>
              </el-table-column>
              <template #empty>
                <div class="admin-task-empty"><ShellIcon name="check" :size="22" /><strong>暂无待办任务</strong><span>当前没有需要处理的任务</span></div>
              </template>
            </app-table>
          </div>
        </PageCard>

        <PageCard title="快捷入口" subtitle="常用业务功能" class="admin-nav-card">
          <nav class="admin-nav-list" aria-label="常用业务功能">
            <RouterLink v-for="entry in adminNavigation" :key="entry.path" :to="entry.path" class="admin-nav-item">
              <span class="admin-nav-icon"><ShellIcon :name="entry.icon" :size="17" /></span>
              <span class="admin-nav-copy"><strong>{{ entry.title }}</strong><small>{{ entry.description }}</small></span>
              <ShellIcon name="arrow-right" class="admin-nav-arrow" :size="14" />
            </RouterLink>
          </nav>
        </PageCard>
      </div>
    </template>
    <footer class="proto-footer">鲸选美学 CRM · 业务数据以服务端权限与实际流水为准</footer>
  </div>
</template>

<style scoped>
.manager-welcome{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(290px,.65fr);gap:18px;margin-bottom:18px}.manager-hero{padding:27px 30px;border-radius:17px;color:#fff;background:linear-gradient(125deg,#103444,#0c5d61)}.manager-hero h1{margin:18px 0 9px;font-size:27px}.manager-hero p{color:#b5d2d5;line-height:1.8}.hero-ai{display:inline-flex;align-items:center;gap:6px;padding:5px 9px;border-radius:6px;color:#c9fff3;background:rgb(255 255 255 / 12%);font-size:11px;font-weight:700}.hero-actions{display:flex;gap:9px;margin-top:22px;flex-wrap:wrap}.hero-actions .el-button{color:#fff;background:rgb(255 255 255 / 8%);border-color:rgb(255 255 255 / 20%)}.hero-actions .el-button--primary{background:#31bca9;border-color:#31bca9}.manager-kpi{display:grid;grid-template-columns:1fr 1fr;gap:12px}.manager-kpi>div{padding:17px;border:1px solid #e9eff2;border-radius:11px;background:#fff}.manager-kpi small{color:#889ba8}.manager-kpi h3{margin:11px 0 4px;font-size:22px}.manager-kpi p{margin:0;color:#9aabb4;font-size:10px}.manager-task-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:13px;margin:18px 0}.work-bucket{padding:17px;border:1px solid #e8eef2;border-radius:13px;color:inherit;background:#fff;text-decoration:none}.work-bucket>div{display:flex;align-items:center;justify-content:space-between}.bucket-icon{display:grid;width:34px;height:34px;place-items:center;border-radius:10px;color:#11998e;background:#ecf8f6}.work-bucket h3{margin:15px 0 3px;font-size:22px}.work-bucket strong{font-size:12px}.work-bucket p{margin:5px 0 0;color:#92a4af;font-size:10px}.attention-row{padding:13px 0;border-bottom:1px solid #edf1f3}.attention-row:last-child{border:0}.attention-row strong{font-size:12px}.attention-row p{margin:5px 0;color:#8ca0ac;font-size:11px;line-height:1.6}.attention-row small{color:#a3b1bc;font-size:10px}.institution-events{padding-top:12px}.institution-event{display:flex;gap:12px;padding:14px 0;border-bottom:1px solid #edf1f3;color:#209990}.institution-event strong{color:#345166;font-size:12px}.institution-event p{margin:5px 0;color:#8ca0ac;font-size:11px}
.admin-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(250px, 0.65fr);
  margin-bottom: 22px;
  overflow: hidden;
  border: 1px solid #163e4d;
  border-radius: 16px;
  color: #f3faf9;
  background: #103c43;
  box-shadow: 0 12px 28px rgba(16, 52, 68, 0.1);
}

.admin-hero-main {
  padding: 28px 30px 26px;
}

.admin-hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #92d7cc;
  font-size: 12px;
  font-weight: 650;
}

.admin-hero h2 {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 16px 0 10px;
}

.admin-hero h2 strong {
  font-size: 44px;
  font-weight: 760;
  line-height: 1;
}

.admin-hero h2 span {
  font-size: 18px;
  font-weight: 550;
}

.admin-hero-main > p {
  margin: 0;
  color: #b5d2d5;
  font-size: 13px;
  line-height: 1.7;
}

.admin-hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}

.admin-hero-primary,
.admin-hero-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 9px;
  font-size: 12px;
  font-weight: 650;
  text-decoration: none;
  transition: background-color 160ms ease, border-color 160ms ease;
}

.admin-hero-primary {
  color: #0b3840;
  background: #46c7b2;
}

.admin-hero-primary:hover {
  background: #66d8c3;
}

.admin-hero-secondary {
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: #e3f3f1;
  background: rgba(255, 255, 255, 0.04);
}

.admin-hero-secondary:hover {
  border-color: rgba(255, 255, 255, 0.36);
  background: rgba(255, 255, 255, 0.08);
}

.admin-hero-date {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 26px 28px;
  border-left: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.035);
}

.admin-hero-date > .shell-icon {
  margin-bottom: 18px;
  color: #7dd8c8;
}

.admin-hero-date > span {
  color: #9dc6c7;
  font-size: 11px;
}

.admin-hero-date > strong {
  margin: 8px 0;
  color: #f3faf9;
  font-size: 17px;
  line-height: 1.6;
}

.admin-hero-date > small {
  color: #8eb6bd;
  font-size: 11px;
  line-height: 1.6;
}

.admin-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 0.8fr);
  gap: 20px;
  align-items: start;
}

.admin-task-card,
.admin-nav-card {
  min-width: 0;
  margin-top: 0;
}

.admin-card-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #0c968a;
  font-size: 12px;
  font-weight: 650;
  text-decoration: none;
}

.admin-card-link:hover {
  color: #08766f;
}

.admin-task-table :deep(.el-table__cell) {
  padding: 14px 0;
}

.admin-task-title {
  color: #294456;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.55;
}

.admin-task-description {
  margin-top: 4px;
  color: #91a4b1;
  font-size: 11px;
  line-height: 1.5;
}

.is-unscheduled {
  color: #9aabb4;
}

.admin-priority {
  display: inline-flex;
  padding: 4px 8px;
  border-radius: 6px;
  color: #627b88;
  background: #edf2f5;
  font-size: 11px;
  font-weight: 700;
}

.admin-priority.is-urgent {
  color: #c95667;
  background: #ffedf0;
}

.admin-priority.is-high {
  color: #ad752e;
  background: #fff4df;
}

.admin-priority.is-medium {
  color: #397ab2;
  background: #e7f2fc;
}

.admin-priority.is-low {
  color: #657d8a;
  background: #e9eff2;
}

.admin-task-empty {
  display: flex;
  min-height: 190px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  color: #53b3a6;
}

.admin-task-empty strong {
  color: #526b78;
  font-size: 14px;
}

.admin-task-empty span {
  color: #91a4b1;
  font-size: 11px;
}

.admin-nav-list {
  padding: 6px 20px 14px;
}

.admin-nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 -4px;
  padding: 13px 10px;
  border-bottom: 1px solid #edf2f4;
  border-radius: 8px;
  color: inherit;
  text-decoration: none;
  transition: background-color 150ms ease;
}

.admin-nav-item:last-child {
  border-bottom-color: transparent;
}

.admin-nav-item:hover {
  background: #f3faf8;
}

.admin-nav-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: none;
  place-items: center;
  border-radius: 9px;
  color: #11877c;
  background: #eaf6f3;
}

.admin-nav-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.admin-nav-copy strong {
  color: #304b5c;
  font-size: 12px;
  font-weight: 650;
}

.admin-nav-copy small {
  color: #91a4b1;
  font-size: 10px;
}

.admin-nav-arrow {
  color: #9aafb8;
}

@media (max-width: 1100px) {
  .admin-workspace {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .admin-hero {
    grid-template-columns: 1fr;
  }

  .admin-hero-date {
    padding: 18px 24px;
    border-top: 1px solid rgba(255, 255, 255, 0.12);
    border-left: 0;
  }

  .admin-hero-date > .shell-icon {
    display: none;
  }
}

@media (max-width: 560px) {
  .admin-hero-main {
    padding: 24px 22px;
  }

  .admin-hero h2 strong {
    font-size: 38px;
  }

  .admin-hero-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .admin-hero-date {
    padding: 16px 22px;
  }
}

@media(max-width:1020px){.manager-welcome{grid-template-columns:1fr}.manager-task-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:560px){.manager-hero{padding:23px}.manager-task-grid{grid-template-columns:1fr 1fr}.manager-kpi{grid-template-columns:1fr 1fr}}
</style>
