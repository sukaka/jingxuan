<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { claimRiskCase, closeRiskCase, getRiskCase, listRiskCases, resolveRiskCase } from '../api/risk'
import type { RiskCase, RiskCaseDetail } from '../api/risk'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatCard from '../components/prototype/StatCard.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

type RiskAction = 'CLAIM' | 'RESOLVE' | 'CLOSE'

const auth = useAuthStore()
const canRead = computed(() => auth.hasPermission('risk:read'))
const canWrite = computed(() => auth.hasPermission('risk:write'))
const canOpenYouzanSync = computed(() => canRead.value && auth.hasPermission('order:read'))
const rows = ref<RiskCase[]>([])
const total = ref(0)
const loading = ref(false)
const loaded = ref(false)
const errorMessage = ref('')
const query = reactive({ keyword: '', status: '', severity: '', riskType: '', page: 1, size: 20 })
let listSequence = 0
let destroyed = false

const highOnPage = computed(() => rows.value.filter((item) => item.severity === 'HIGH' && item.status !== 'CLOSED').length)
const openOnPage = computed(() => rows.value.filter((item) => item.status === 'OPEN').length)
const closedOnPage = computed(() => rows.value.filter((item) => item.status === 'CLOSED').length)

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  }
  return error instanceof Error ? error.message : fallback
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hour12: false, timeZone: 'Asia/Shanghai',
  }).format(date)
}

function statusLabel(status: string): string {
  return ({ OPEN: '待处理', ASSIGNED: '处理中', RESOLVED: '已处理', CLOSED: '已关闭' } as Record<string, string>)[status] ?? status
}

function statusTone(status: string): string {
  return ({ OPEN: 'warning', ASSIGNED: 'info', RESOLVED: 'success', CLOSED: 'dark' } as Record<string, string>)[status] ?? 'dark'
}

function severityLabel(severity: string): string {
  return ({ HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' } as Record<string, string>)[severity] ?? severity
}

function severityTone(severity: string): string {
  return ({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' } as Record<string, string>)[severity] ?? 'dark'
}

function riskTypeLabel(type: string): string {
  return ({ MAPPING: '商品映射', PAYMENT: '支付异常', ENTITLEMENT: '权益异常', SECURITY: '安全异常' } as Record<string, string>)[type] ?? type
}

function sourceLabel(type: string): string {
  return ({ YOUZAN_ORDER: '有赞订单', ORDER: '订单', REDEMPTION: '核销', REFUND: '退款', SECURITY: '安全' } as Record<string, string>)[type] ?? type
}

function customerLabel(item: RiskCase): string {
  if (!item.customerId || item.customerId <= 0) return '未关联 / 待核实'
  return item.customerName?.trim() || item.customerNo || `客户 #${item.customerId}`
}

function customerSecondary(item: RiskCase): string {
  if (!item.customerId || item.customerId <= 0) return '来源记录尚未形成可靠客户关联'
  const details: string[] = []
  if (item.customerName?.trim() && item.customerNo) details.push(item.customerNo)
  if (item.orderNo) details.push(`订单 ${item.orderNo}`)
  else if (item.orderId && item.orderId > 0) details.push(`订单 #${item.orderId}`)
  return details.join(' · ') || '已关联真实客户主档'
}

function historyActionLabel(action: string): string {
  return ({ OPEN: '风险登记', CREATE: '风险登记', CLAIM: '认领处理', RESOLVE: '处理完成', CLOSE: '关闭风险' } as Record<string, string>)[action] ?? action
}

async function load(page = query.page) {
  if (!canRead.value) return
  const sequence = ++listSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listRiskCases({
      page,
      size: query.size,
      keyword: query.keyword,
      status: query.status,
      severity: query.severity,
      riskType: query.riskType,
    })
    if (destroyed || sequence !== listSequence) return
    rows.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
    loaded.value = true
  } catch (error) {
    if (destroyed || sequence !== listSequence) return
    rows.value = []
    total.value = 0
    loaded.value = true
    errorMessage.value = errorText(error, '风控台账加载失败，请重试')
  } finally {
    if (sequence === listSequence) loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { keyword: '', status: '', severity: '', riskType: '', page: 1 })
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<RiskCaseDetail | null>(null)
const actionType = ref<RiskAction | null>(null)
const actionReason = ref('')
const actionSubmitting = ref(false)
const actionError = ref('')
let detailSequence = 0
let actionKey = ''

const actionTitle = computed(() => ({ CLAIM: '认领风险', RESOLVE: '填写处理结论', CLOSE: '关闭风险' } as const)[actionType.value ?? 'CLAIM'])
const actionHint = computed(() => {
  if (actionType.value === 'CLAIM') return '认领后状态只能由服务端转为处理中；请说明认领与核对依据。'
  if (actionType.value === 'RESOLVE') return '处理结论会写入风险事实与追加历史，不会覆盖原证据。'
  return '关闭前请复核处理结论；关闭后仍保留完整台账与历史。'
})

watch(actionReason, () => { if (!actionSubmitting.value) actionKey = crypto.randomUUID() })
watch(detailVisible, (visible) => { if (!visible) detailSequence++ })

async function loadDetail(id: number) {
  const sequence = ++detailSequence
  detailLoading.value = true
  detailError.value = ''
  try {
    const result = await getRiskCase(id)
    if (destroyed || sequence !== detailSequence) return
    detail.value = result
  } catch (error) {
    if (destroyed || sequence !== detailSequence) return
    detailError.value = errorText(error, '风险详情加载失败，请重试')
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}

function showDetail(item: RiskCase, action: RiskAction | null) {
  detailVisible.value = true
  detail.value = null
  detailError.value = ''
  actionType.value = action
  actionReason.value = ''
  actionError.value = ''
  actionKey = crypto.randomUUID()
  void loadDetail(item.id)
}

function openHistory(item: RiskCase) {
  showDetail(item, null)
}

function openAction(item: RiskCase, action: RiskAction) {
  showDetail(item, action)
}

async function submitAction() {
  const target = detail.value?.riskCase
  const action = actionType.value
  if (!target || !action || actionSubmitting.value) return
  if (!actionReason.value.trim()) {
    actionError.value = action === 'RESOLVE' ? '请填写实际处理结论与核对依据' : '请填写理由，空理由不能提交'
    return
  }
  actionSubmitting.value = true
  actionError.value = ''
  const reason = actionReason.value.trim()
  try {
    if (action === 'CLAIM') {
      await claimRiskCase(target.id, { version: target.version, reason }, actionKey)
    } else if (action === 'RESOLVE') {
      await resolveRiskCase(target.id, { version: target.version, resolution: reason }, actionKey)
    } else {
      await closeRiskCase(target.id, { version: target.version, reason }, actionKey)
    }
    if (destroyed) return
    actionType.value = null
    ElMessage.success('服务端已受理，正在重新读取台账与追加历史')
    await Promise.all([load(query.page), loadDetail(target.id)])
  } catch (error) {
    if (!destroyed) actionError.value = errorText(error, '操作失败，请核对版本后重试')
  } finally {
    actionSubmitting.value = false
  }
}

onMounted(() => { if (canRead.value) void load(1) })
onBeforeUnmount(() => {
  destroyed = true
  listSequence++
  detailSequence++
})
</script>

<template>
  <div class="proto-page risks-page">
    <PageHeader title="异常订单 / 风控台账" description="集中承接同步、订单、映射、退款、权益与核销异常；认领、处理和关闭必须留理由并保留追加历史">
      <template #actions>
        <RouterLink v-if="canOpenYouzanSync" to="/integrations/youzan" class="el-button" data-testid="risk-youzan-sync">有赞同步</RouterLink>
      </template>
    </PageHeader>

    <PageCard v-if="!canRead">
      <div class="proto-empty risk-permission-empty" data-testid="risk-permission-empty">
        <strong>没有风控台账查看权限</strong>
        <span>页面不会请求风险证据或历史；请联系管理员按职责授权。</span>
      </div>
    </PageCard>

    <template v-else>
      <div class="proto-stat-grid">
        <StatCard label="异常总数" :value="`${total} 条`" sub="当前筛选结果 · 服务端总数" icon="shield" />
        <StatCard label="未关闭高风险" :value="`${highOnPage} 条`" sub="当前页 · 优先核对处理" icon="warning" />
        <StatCard label="待处理" :value="`${openOnPage} 条`" sub="当前页 · 尚未认领" icon="clock" />
        <StatCard label="已关闭" :value="`${closedOnPage} 条`" sub="当前页 · 历史仍然保留" icon="check" />
      </div>

      <PageCard>
        <div class="proto-toolbar risk-toolbar">
          <div class="proto-toolbar-left">
            <el-input v-model="query.keyword" clearable placeholder="风险单 / 来源 / 异常类型 / 责任人" class="risk-keyword" @keyup.enter="load(1)" />
            <el-select v-model="query.status" clearable placeholder="全部状态" class="risk-filter">
              <el-option label="待处理" value="OPEN" /><el-option label="处理中" value="ASSIGNED" />
              <el-option label="已处理" value="RESOLVED" /><el-option label="已关闭" value="CLOSED" />
            </el-select>
            <el-select v-model="query.severity" clearable placeholder="全部级别" class="risk-filter">
              <el-option label="高风险" value="HIGH" /><el-option label="中风险" value="MEDIUM" /><el-option label="低风险" value="LOW" />
            </el-select>
            <el-button type="primary" :loading="loading" @click="load(1)">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
          </div>
          <span class="proto-note">风险不能删除或本地改状态</span>
        </div>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" class="risk-alert">
          <el-button text @click="load()">重试</el-button>
        </el-alert>
        <div class="proto-table-wrap">
          <app-table v-loading="loading" :data="rows">
            <el-table-column label="风险单" min-width="155" fixed="left">
              <template #default="{ row }"><strong>{{ row.riskCaseNo }}</strong><div class="proto-subcell">{{ formatDateTime(row.createdAt) }}</div></template>
            </el-table-column>
            <el-table-column label="来源 / 引用" min-width="155">
              <template #default="{ row }"><span class="proto-pill info">{{ sourceLabel(row.sourceType) }}</span><div class="proto-subcell source-ref">{{ row.sourceRef }}</div></template>
            </el-table-column>
            <el-table-column label="客户" min-width="185">
              <template #default="{ row }">
                <div class="risk-customer" :data-testid="`risk-customer-${row.id}`">
                  <strong>{{ customerLabel(row) }}</strong>
                  <div class="proto-subcell">{{ customerSecondary(row) }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="异常类型" min-width="125"><template #default="{ row }">{{ riskTypeLabel(row.riskType) }}</template></el-table-column>
            <el-table-column label="级别" width="95"><template #default="{ row }"><span class="proto-pill" :class="severityTone(row.severity)">{{ severityLabel(row.severity) }}</span></template></el-table-column>
            <el-table-column label="发现内容（服务端脱敏摘要）" min-width="260">
              <template #default="{ row }"><div class="risk-evidence">{{ row.evidence || '未提供风险证据摘要' }}</div></template>
            </el-table-column>
            <el-table-column label="责任人" width="115"><template #default="{ row }">{{ row.ownerUserId ? `用户 #${row.ownerUserId}` : '未认领' }}</template></el-table-column>
            <el-table-column label="状态" width="100"><template #default="{ row }"><span class="proto-pill" :class="statusTone(row.status)">{{ statusLabel(row.status) }}</span></template></el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="{ row }">
                <div class="risk-actions">
                  <el-button text :data-testid="`risk-history-${row.id}`" @click="openHistory(row)">历史</el-button>
                  <el-button v-if="canWrite && row.status === 'OPEN'" text :data-testid="`risk-claim-${row.id}`" @click="openAction(row, 'CLAIM')">认领处理</el-button>
                  <el-button v-if="canWrite && row.status === 'ASSIGNED'" text :data-testid="`risk-resolve-${row.id}`" @click="openAction(row, 'RESOLVE')">处理完成</el-button>
                  <el-button v-if="canWrite && row.status === 'RESOLVED'" text :data-testid="`risk-close-${row.id}`" @click="openAction(row, 'CLOSE')">关闭问题</el-button>
                </div>
              </template>
            </el-table-column>
            <template #empty><span>{{ loaded && !errorMessage ? '当前条件下没有风险记录' : '正在读取风控台账' }}</span></template>
          </app-table>
        </div>
        <el-pagination
          v-if="total > query.size"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load(1)"
        />
      </PageCard>
    </template>

    <el-drawer
      v-model="detailVisible"
      size="min(760px,100vw)"
      :title="detail ? `${detail.riskCase.riskCaseNo} · 风险详情` : '风险详情'"
      :close-on-click-modal="!actionSubmitting"
      :close-on-press-escape="!actionSubmitting"
      :show-close="!actionSubmitting"
    >
      <div v-loading="detailLoading" class="risk-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" class="detail-alert" />
        <template v-if="detail">
          <section class="risk-fact">
            <div class="risk-fact-head">
              <div><span class="proto-pill" :class="severityTone(detail.riskCase.severity)">{{ severityLabel(detail.riskCase.severity) }}</span><h2>{{ riskTypeLabel(detail.riskCase.riskType) }}</h2></div>
              <span class="proto-pill" :class="statusTone(detail.riskCase.status)">{{ statusLabel(detail.riskCase.status) }}</span>
            </div>
            <dl>
              <div><dt>来源</dt><dd>{{ sourceLabel(detail.riskCase.sourceType) }} · {{ detail.riskCase.sourceRef }}</dd></div>
              <div><dt>责任人</dt><dd>{{ detail.riskCase.ownerUserId ? `用户 #${detail.riskCase.ownerUserId}` : '未认领' }}</dd></div>
              <div><dt>乐观锁版本</dt><dd>{{ detail.riskCase.version }}</dd></div>
              <div><dt>处理时间</dt><dd>{{ formatDateTime(detail.riskCase.resolvedAt) }}</dd></div>
            </dl>
            <div class="fact-block"><strong>风险证据（脱敏）</strong><p>{{ detail.riskCase.evidence || '—' }}</p></div>
            <div class="fact-block"><strong>处理结论</strong><p>{{ detail.riskCase.resolution || '尚未填写处理结论' }}</p></div>
          </section>

          <section v-if="actionType && canWrite" class="risk-action-panel">
            <div><h3>{{ actionTitle }}</h3><p>{{ actionHint }}</p></div>
            <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" data-testid="risk-action-error" />
            <el-input
              v-model="actionReason"
              type="textarea"
              :rows="4"
              maxlength="2000"
              show-word-limit
              :placeholder="actionType === 'RESOLVE' ? '必填：实际处理结论、核对事实与依据' : '必填：操作理由与核对依据'"
              data-testid="risk-action-reason"
            />
            <div class="action-footer">
              <span>提交版本 {{ detail.riskCase.version }} · 失败不会变更当前页面状态</span>
              <el-button type="primary" :loading="actionSubmitting" data-testid="risk-action-submit" @click="submitAction">提交{{ actionTitle }}</el-button>
            </div>
          </section>

          <section class="risk-history">
            <div class="history-heading"><h3>追加历史</h3><span>{{ detail.history.length }} 条服务端记录</span></div>
            <ol>
              <li v-for="entry in detail.history" :key="entry.id" class="risk-history-entry">
                <span class="history-dot" />
                <div class="history-body">
                  <div><strong>{{ historyActionLabel(entry.action) }}</strong><time>{{ formatDateTime(entry.createdAt) }}</time></div>
                  <p>{{ entry.fromStatus ? `${statusLabel(entry.fromStatus)} → ` : '' }}{{ statusLabel(entry.toStatus) }} · {{ entry.actorUserId ? `用户 #${entry.actorUserId}` : '系统记录' }}</p>
                  <blockquote v-if="entry.note">{{ entry.note }}</blockquote>
                </div>
              </li>
            </ol>
            <div v-if="!detail.history.length" class="proto-empty">服务端尚未返回历史记录</div>
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.risk-keyword { width: 310px; }
.risk-filter { width: 135px; }
.risk-alert { margin: 15px 20px 0; width: auto; }
.source-ref { max-width: 170px; overflow-wrap: anywhere; }
.risk-evidence { overflow-wrap: anywhere; line-height: 1.75; white-space: pre-wrap; }
.risk-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.risk-actions .el-button + .el-button { margin-left: 0; }
.risk-permission-empty { display: grid; gap: 8px; }
.risk-detail { min-height: 240px; }
.detail-alert { margin-bottom: 16px; }
.risk-fact { padding-bottom: 22px; border-bottom: 1px solid #e7edf0; }
.risk-fact-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; }
.risk-fact-head h2 { margin: 12px 0 0; font-size: 20px; }
.risk-fact dl { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 11px; margin: 20px 0; }
.risk-fact dl > div { padding: 12px; border-radius: 9px; background: #f6f9fa; }
.risk-fact dt { margin-bottom: 6px; color: #91a4ae; font-size: 10px; }
.risk-fact dd { margin: 0; overflow-wrap: anywhere; color: #4c6875; font-size: 12px; }
.fact-block { margin-top: 14px; }
.fact-block strong { color: #536e7a; font-size: 11px; }
.fact-block p { margin: 7px 0 0; overflow-wrap: anywhere; color: #314f5e; font-size: 12px; line-height: 1.85; white-space: pre-wrap; }
.risk-action-panel { display: grid; gap: 14px; margin: 20px 0; padding: 18px; border: 1px solid #dcebe7; border-radius: 11px; background: #f4faf8; }
.risk-action-panel h3 { margin: 0 0 6px; font-size: 14px; }
.risk-action-panel p { margin: 0; color: #6e8983; font-size: 11px; line-height: 1.75; }
.action-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.action-footer > span { color: #829991; font-size: 10px; line-height: 1.6; }
.risk-history { margin-top: 24px; }
.history-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.history-heading h3 { margin: 0; font-size: 15px; }
.history-heading span { color: #96a8b1; font-size: 10px; }
.risk-history ol { margin: 18px 0 0; padding: 0; list-style: none; }
.risk-history-entry { position: relative; display: grid; grid-template-columns: 14px minmax(0,1fr); gap: 11px; padding-bottom: 22px; }
.risk-history-entry:not(:last-child)::before { position: absolute; top: 13px; bottom: 0; left: 5px; width: 1px; background: #dfe9e7; content: ''; }
.history-dot { position: relative; z-index: 1; width: 11px; height: 11px; margin-top: 3px; border: 3px solid #d8eeea; border-radius: 50%; background: #218f83; }
.history-body > div { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.history-body strong { font-size: 12px; }
.history-body time { color: #96a8b1; font-size: 10px; }
.history-body p { margin: 6px 0 0; color: #7c929c; font-size: 11px; }
.history-body blockquote { margin: 10px 0 0; padding: 10px 12px; overflow-wrap: anywhere; border-left: 2px solid #c7dfda; color: #526f77; background: #f7faf9; font-size: 11px; line-height: 1.75; white-space: pre-wrap; }
@media (max-width: 760px) {
  .risk-keyword, .risk-filter { width: 100%; }
  .risk-toolbar .proto-toolbar-left > * { flex: 1 1 135px; }
  .risk-fact dl { grid-template-columns: 1fr; }
  .action-footer { align-items: stretch; flex-direction: column; }
  .history-body > div { align-items: flex-start; flex-direction: column; gap: 4px; }
}
</style>
