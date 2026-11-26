<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  CustomerStewardRelationItem,
  MergeCaseItem,
  MultiStewardCustomerGroupItem,
  MultiStewardSummary,
} from '../api/customer'
import {
  getMultiStewardSummary,
  listMergeCandidates,
  listMultiStewardCustomers,
  requestMergeReversal,
  reviewMergeReversal,
} from '../api/customer'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import CustomerDetailDrawer from './customer/CustomerDetailDrawer.vue'
import StatusPill from './customer/StatusPill.vue'
import { formatDateTime } from './customer/shared'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import './customer/customer-ui.css'

const auth = useAuthStore()
const canMerge = computed(() => auth.hasPermission('customer:merge'))
const canReverse = computed(() => auth.hasPermission('customer:merge_reverse'))

const activeTab = ref<'multi-stewards' | 'history-reversals'>('multi-stewards')
const showRules = ref(false)

// ---- 多管家排查（主视图，只读，无需合并） ----
const summary = reactive<MultiStewardSummary>({
  multiStewardCustomerCount: 0,
  crossTeamCustomerCount: 0,
  maxStewardCount: 0,
})
const query = reactive({
  keyword: '',
  minStewards: 2,
  crossTeamOnly: false,
  page: 1,
  size: 20,
})
const groups = ref<MultiStewardCustomerGroupItem[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const detailVisible = ref(false)
const detailCustomerId = ref<number | null>(null)

const MATCH_LABELS: Record<string, string> = {
  MOBILE_HASH: '手机号一致',
  IDENTITY: '外部身份一致',
  WECOM_CONTACT: '企微多管家添加',
}

const RELATION_LABELS: Record<string, string> = {
  CUSTOMER_OWNER: '客户归属',
  WECOM_FOLLOW: '企微好友',
}

function matchReasonLabel(code: string): string {
  return MATCH_LABELS[code] ?? '关联识别'
}

function relationLabel(code: string): string {
  return RELATION_LABELS[code] ?? code
}

function stewardDisplayLabel(steward: CustomerStewardRelationItem): string {
  return steward.teamName ? `${steward.staffName}（${steward.teamName}）` : steward.staffName
}

function groupCustomerTitle(row: MultiStewardCustomerGroupItem): string {
  return row.displayName || row.nickname || row.primaryCustomerNo || '企微未建档顾客'
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return '该记录状态已变更，请刷新后重试'
    if (error.status === 404) return '记录或客户不在您的权限范围内'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function loadSummary() {
  if (!canMerge.value) return
  try {
    const result = await getMultiStewardSummary()
    summary.multiStewardCustomerCount = result.multiStewardCustomerCount
    summary.crossTeamCustomerCount = result.crossTeamCustomerCount
    summary.maxStewardCount = result.maxStewardCount
  } catch {
    // 汇总失败时不覆盖列表错误提示
  }
}

async function loadGroups(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listMultiStewardCustomers({
      page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      minStewards: query.minStewards,
      crossTeamOnly: query.crossTeamOnly,
    })
    groups.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    groups.value = []
    total.value = 0
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function searchGroups() {
  void loadGroups(1)
  void loadSummary()
}

function openCustomerDetail(customerId: number) {
  detailCustomerId.value = customerId
  detailVisible.value = true
}

// ---- 历史已合并记录更正（仅管理员 customer:merge_reverse 可见，不再开放新合并操作） ----
const historyQuery = reactive({ status: 'CONFIRMED', page: 1, size: 20 })
const historyItems = ref<MergeCaseItem[]>([])
const historyTotal = ref(0)
const historyLoading = ref(false)
const historyError = ref('')
const historyLoaded = ref(false)

const STATUS_META: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'info' | 'dark' }> = {
  CONFIRMED: { label: '历史已合并', tone: 'success' },
  REVERSED: { label: '已更正', tone: 'info' },
  REJECTED: { label: '已拒绝', tone: 'dark' },
}

const REVERSAL_LABELS: Record<string, string> = {
  REQUESTED: '待更正审批',
  MANUAL_REVIEW: '待人工处理',
  EXECUTED: '更正已执行',
  REJECTED: '更正已驳回',
  ERROR: '更正执行失败',
}

function statusMeta(status: string) {
  return STATUS_META[status] ?? { label: status, tone: 'dark' as const }
}

function reversalLabel(item: MergeCaseItem): string {
  return REVERSAL_LABELS[item.reversalStatus ?? ''] ?? ''
}

async function loadHistory(page = historyQuery.page) {
  if (!canReverse.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await listMergeCandidates({
      page,
      size: historyQuery.size,
      status: historyQuery.status || undefined,
    })
    historyItems.value = result.items.filter((item) => item.status === 'CONFIRMED' || item.status === 'REVERSED')
    historyTotal.value = result.total
    historyQuery.page = result.page
    historyQuery.size = result.size
    historyLoaded.value = true
  } catch (error) {
    historyItems.value = []
    historyTotal.value = 0
    historyError.value = errorText(error)
  } finally {
    historyLoading.value = false
  }
}

type ReversalAction = 'request' | 'approve' | 'complete' | 'reject'
const reversalDialogVisible = ref(false)
const reversalSubmitting = ref(false)
const reversalTarget = ref<MergeCaseItem | null>(null)
const reversalAction = ref<ReversalAction>('request')
const reversalReason = ref('')
let reversalKey = ''
const REVERSAL_ACTION_LABELS: Record<ReversalAction, string> = {
  request: '申请更正',
  approve: '审批并执行',
  complete: '人工完成',
  reject: '驳回更正',
}

function openReversalDialog(item: MergeCaseItem, action: ReversalAction) {
  if (!canReverse.value) {
    ElMessage.warning('当前账号没有此操作权限')
    return
  }
  if (action !== 'request' && (item.reversalId == null || item.reversalVersion == null)) {
    ElMessage.warning('更正单信息不完整，请刷新后重试')
    return
  }
  reversalTarget.value = item
  reversalAction.value = action
  reversalReason.value = ''
  reversalKey = crypto.randomUUID()
  reversalDialogVisible.value = true
}

async function submitReversal() {
  const item = reversalTarget.value
  const reason = reversalReason.value.trim()
  if (!item) return
  if (!reason) {
    ElMessage.warning('请填写理由或处理说明（将记入操作日志）')
    return
  }
  if (reason.length > 1000) {
    ElMessage.warning('理由或处理说明不能超过 1000 字')
    return
  }
  reversalSubmitting.value = true
  try {
    const saved = reversalAction.value === 'request'
      ? await requestMergeReversal(item.id, reason, reversalKey)
      : await reviewMergeReversal(item.reversalId!, reversalAction.value, {
        reason,
        version: item.reversalVersion!,
      }, reversalKey)
    if (reversalAction.value === 'approve') {
      ElMessage[saved.status === 'MANUAL_REVIEW' ? 'warning' : 'success'](
        saved.status === 'MANUAL_REVIEW' ? '被合并客户已恢复，其他关联数据需人工逐项迁移确认' : '已按合并前记录执行更正',
      )
    } else {
      ElMessage.success(reversalAction.value === 'reject' ? '更正申请已驳回，客户数据未改变' : '操作已完成')
    }
    reversalDialogVisible.value = false
    await loadHistory()
  } catch (error) {
    ElMessage.error(`${errorText(error)}，请核对后重试`)
  } finally {
    reversalSubmitting.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'history-reversals' && canReverse.value && !historyLoaded.value) {
    void loadHistory(1)
  }
})

onMounted(() => {
  void loadGroups(1)
  void loadSummary()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader
      title="客户唯一性 · 多管家排查"
      description="查看同一顾客添加了哪几位管家及所属团队；保留各管家独立跟进，无需合并客户档案"
    >
      <template #actions>
        <el-button @click="showRules = true">识别规则说明</el-button>
        <el-button type="primary" :loading="loading" data-testid="multi-steward-refresh" @click="searchGroups">
          刷新排查
        </el-button>
      </template>
    </PageHeader>

    <div class="proto-stat-grid three">
      <StatCard
        label="多管家客户"
        :value="`${summary.multiStewardCustomerCount} 位`"
        sub="同一顾客添加 2 位及以上管家"
        icon="users"
      />
      <StatCard
        label="跨团队重复"
        :value="`${summary.crossTeamCustomerCount} 位`"
        sub="跨组仅展示管家与团队汇总"
        icon="heart"
      />
      <StatCard
        label="单客最高管家数"
        :value="`${summary.maxStewardCount} 位`"
        sub="无需合并，各自独立跟进"
        icon="check"
      />
    </div>

    <el-tabs v-if="canReverse" v-model="activeTab" class="uniqueness-tabs" data-testid="uniqueness-admin-tabs">
      <el-tab-pane label="多管家重复排查" name="multi-stewards" />
      <el-tab-pane label="历史合并更正（管理员）" name="history-reversals" />
    </el-tabs>

    <PageCard v-if="!canReverse || activeTab === 'multi-stewards'">
      <div class="proto-toolbar">
        <div class="proto-toolbar-left">
          <el-input
            v-model="query.keyword"
            placeholder="客户姓名 / 手机 / 编号 / 管家姓名"
            clearable
            style="width: 260px"
            data-testid="multi-steward-keyword"
            @keyup.enter="loadGroups(1)"
          />
          <el-select
            v-model="query.minStewards"
            style="width: 145px"
            data-testid="multi-steward-min-select"
            @change="loadGroups(1)"
          >
            <el-option label="2 位及以上管家" :value="2" />
            <el-option label="3 位及以上管家" :value="3" />
            <el-option label="4 位及以上管家" :value="4" />
          </el-select>
          <el-checkbox
            v-model="query.crossTeamOnly"
            data-testid="multi-steward-cross-team-only"
            @change="loadGroups(1)"
          >
            仅看跨团队重复
          </el-checkbox>
          <el-button type="primary" :loading="loading" data-testid="multi-steward-search-btn" @click="loadGroups(1)">
            查询
          </el-button>
        </div>
        <span class="proto-note">跨团队客户仅展示管家姓名、团队与总数汇总，不泄露跨组敏感详情</span>
      </div>

      <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" class="crm-table-alert" />

      <div class="proto-table-wrap">
        <app-table v-loading="loading" :data="groups" data-testid="multi-steward-table">
          <el-table-column label="顾客" min-width="180">
            <template #default="{ row }">
              <strong>{{ groupCustomerTitle(row) }}</strong>
              <div class="proto-subcell">
                <span v-if="row.primaryCustomerNo">{{ row.primaryCustomerNo }}</span>
                <span v-if="row.primaryCustomerNo && row.mobileMasked"> · </span>
                <span v-if="row.mobileMasked">{{ row.mobileMasked }}</span>
                <span v-if="!row.primaryCustomerNo && !row.mobileMasked">{{ row.evidenceMasked || '—' }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="已加管家数" width="145">
            <template #default="{ row }">
              <StatusPill :text="`已加 ${row.stewardCount} 位管家`" :tone="row.stewardCount >= 3 ? 'danger' : 'warning'" />
              <div v-if="row.crossTeamStewardCount > 0" class="proto-subcell">
                含跨组 {{ row.crossTeamStewardCount }} 位
              </div>
            </template>
          </el-table-column>

          <el-table-column label="已添加管家明细" min-width="310">
            <template #default="{ row }">
              <div class="steward-chip-list">
                <div
                  v-for="steward in row.stewards"
                  :key="steward.staffUserId"
                  class="steward-chip"
                  :class="{ 'is-cross-team': !steward.inMyScope }"
                  :data-testid="`steward-chip-${steward.staffUserId}`"
                >
                  <span class="steward-chip-name">{{ stewardDisplayLabel(steward) }}</span>
                  <span
                    v-for="rel in steward.relationTypes"
                    :key="rel"
                    class="steward-rel-tag"
                  >
                    {{ relationLabel(rel) }}
                  </span>
                  <el-button
                    v-if="steward.inMyScope && steward.customerId"
                    text
                    type="primary"
                    size="small"
                    :data-testid="`steward-customer-btn-${steward.customerId}`"
                    @click="openCustomerDetail(steward.customerId)"
                  >
                    {{ steward.customerNo || '查看档案' }}
                  </el-button>
                  <span v-else-if="!steward.inMyScope" class="steward-cross-tag" data-testid="cross-team-summary-tag">
                    跨组仅汇总
                  </span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="识别依据" min-width="170">
            <template #default="{ row }">
              <div class="reason-pills">
                <StatusPill
                  v-for="reason in row.matchReasons"
                  :key="reason"
                  :text="matchReasonLabel(reason)"
                  tone="info"
                />
              </div>
              <div v-if="row.evidenceMasked" class="proto-subcell">{{ row.evidenceMasked }}</div>
            </template>
          </el-table-column>

          <el-table-column label="最近添加时间" width="165">
            <template #default="{ row }">{{ formatDateTime(row.latestAddedAt) }}</template>
          </el-table-column>

          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.primaryCustomerId"
                text
                type="primary"
                :data-testid="`group-open-detail-${row.primaryCustomerId}`"
                @click="openCustomerDetail(row.primaryCustomerId)"
              >
                客户详情
              </el-button>
              <span v-else class="proto-muted">企微未建档</span>
            </template>
          </el-table-column>

          <template #empty>当前数据范围内暂无添加多位管家的顾客</template>
        </app-table>
      </div>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadGroups"
        @size-change="query.size = $event; loadGroups(1)"
      />
    </PageCard>

    <PageCard v-else-if="canReverse && activeTab === 'history-reversals'">
      <div class="proto-toolbar">
        <div class="proto-toolbar-left">
          <el-select v-model="historyQuery.status" style="width: 150px" @change="loadHistory(1)">
            <el-option label="历史已合并" value="CONFIRMED" />
            <el-option label="已更正" value="REVERSED" />
            <el-option label="全部历史记录" value="" />
          </el-select>
          <el-button type="primary" :loading="historyLoading" @click="loadHistory(1)">查询</el-button>
        </div>
        <span class="proto-note">当前业务不再需要合并客户；本页仅保留管理员对历史已合并记录的更正查看与处理</span>
      </div>
      <el-alert v-if="historyError" type="error" :closable="false" :title="historyError" class="crm-table-alert" />
      <div class="proto-table-wrap">
        <app-table v-loading="historyLoading" :data="historyItems">
          <el-table-column prop="id" label="记录" width="80" />
          <el-table-column label="保留档案" min-width="170">
            <template #default="{ row }">
              {{ row.primarySummary }}
              <div class="proto-subcell">保留 #{{ row.primaryCustomerId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="原归档档案" min-width="170">
            <template #default="{ row }">
              {{ row.secondarySummary }}
              <div class="proto-subcell">
                {{ row.status === 'REVERSED' || row.reversalStatus === 'MANUAL_REVIEW' || row.reversalStatus === 'EXECUTED' ? '已恢复' : '归档' }}
                #{{ row.secondaryCustomerId }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <StatusPill :text="statusMeta(row.status).label" :tone="statusMeta(row.status).tone" />
              <div v-if="reversalLabel(row)" class="proto-subcell" :data-testid="`reversal-status-${row.id}`">
                {{ reversalLabel(row) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'CONFIRMED' && !row.reversalStatus"
                text
                type="warning"
                :data-testid="`reversal-request-${row.id}`"
                @click="openReversalDialog(row, 'request')"
              >
                申请更正
              </el-button>
              <template v-else-if="row.reversalStatus === 'REQUESTED'">
                <el-button
                  text
                  type="warning"
                  :data-testid="`reversal-approve-${row.id}`"
                  @click="openReversalDialog(row, 'approve')"
                >
                  审批并执行
                </el-button>
                <el-button
                  text
                  type="danger"
                  :data-testid="`reversal-reject-${row.id}`"
                  @click="openReversalDialog(row, 'reject')"
                >
                  驳回
                </el-button>
              </template>
              <el-button
                v-else-if="row.reversalStatus === 'MANUAL_REVIEW'"
                text
                type="warning"
                :data-testid="`reversal-complete-${row.id}`"
                @click="openReversalDialog(row, 'complete')"
              >
                人工完成
              </el-button>
              <span v-else class="proto-muted">已处理</span>
            </template>
          </el-table-column>
          <template #empty>暂无历史已合并记录</template>
          <template #append>
            <template v-for="row in historyItems" :key="`warning-${row.id}`">
              <el-alert
                v-if="row.reversalWarning"
                type="warning"
                :closable="false"
                :title="`记录 #${row.id}：${row.reversalWarning}`"
                class="crm-table-alert"
                :data-testid="`reversal-warning-${row.id}`"
              />
            </template>
          </template>
        </app-table>
      </div>
      <el-pagination
        v-model:current-page="historyQuery.page"
        v-model:page-size="historyQuery.size"
        :total="historyTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadHistory"
        @size-change="historyQuery.size = $event; loadHistory(1)"
      />
    </PageCard>

    <div class="proto-callout" style="margin-top: 18px">
      客户唯一 ID 独立于手机号与昵称；本视图仅用于管家主管掌握同一顾客添加了哪几位管家，不执行客户档案合并，也不突破跨团队敏感数据隔离。
    </div>

    <el-dialog v-model="showRules" title="多管家识别规则说明" width="540px">
      <p>系统基于以下三种客观关联自动汇总同一顾客添加的多位管家，无需执行合并：</p>
      <ul class="rule-list">
        <li><strong>手机号一致</strong>：使用加密手机号不可逆盲索引比对，界面仅展示脱敏号段。</li>
        <li><strong>外部身份一致</strong>：同一有赞/企微/历史外部身份映射到多个客户档案。</li>
        <li><strong>企微多管家添加</strong>：企微中同一外部联系人（或同一 UnionID / 同一客户）存在多位活跃跟进管家。</li>
      </ul>
      <p class="crm-muted">
        跨团队隔离：管家主管仅能查看本组客户关联的重复情况；跨团队管家仅展示姓名、所属团队与总数汇总，不可越权查看跨组客户详情、跟进或订单。
      </p>
    </el-dialog>

    <el-dialog v-model="reversalDialogVisible" :title="REVERSAL_ACTION_LABELS[reversalAction]" width="560px">
      <el-alert
        v-if="reversalTarget"
        type="warning"
        :closable="false"
        :title="reversalAction === 'request'
          ? `申请对历史合并记录 #${reversalTarget.id} 进行更正；申请不会立即修改客户，须由管理员审批执行。`
          : reversalAction === 'approve'
            ? '合并时留有迁出记录的，系统将自动迁回对应数据；无历史迁出记录的仅恢复被合并客户档案，其余关联数据须人工逐项处理。'
            : reversalAction === 'complete'
              ? '仅在已逐项人工核对并迁移旧合并关联数据后完成；完成后不会自动恢复合并时去重删除的身份或标签。'
              : '驳回只关闭本次更正申请，不改变原合并结果或客户数据。'"
        class="crm-table-alert"
      />
      <el-form label-width="110px">
        <el-form-item :label="reversalAction === 'complete' ? '人工处理说明' : '理由'" required>
          <el-input
            v-model="reversalReason"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            :placeholder="reversalAction === 'complete' ? '说明已核对的关联数据处理结果' : '请填写原因，提交后不可修改历史记录'"
            data-testid="reversal-reason"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reversalDialogVisible = false">取消</el-button>
        <el-button
          :type="reversalAction === 'reject' ? 'danger' : 'primary'"
          :loading="reversalSubmitting"
          data-testid="reversal-submit"
          @click="submitReversal"
        >
          {{ REVERSAL_ACTION_LABELS[reversalAction] }}
        </el-button>
      </template>
    </el-dialog>

    <CustomerDetailDrawer
      v-model="detailVisible"
      :customer-id="detailCustomerId"
      @changed="searchGroups"
    />
  </div>
</template>

<style scoped>
.uniqueness-tabs {
  margin-bottom: 12px;
}

.steward-chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.steward-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 9px;
  border-radius: 8px;
  border: 1px solid #d9e8e3;
  background: #f5faf8;
  font-size: 12px;
  color: #21453d;
}

.steward-chip.is-cross-team {
  border-color: #eadcc4;
  background: #fdf8ef;
  color: #6b4e1e;
}

.steward-chip-name {
  font-weight: 600;
}

.steward-rel-tag {
  padding: 1px 6px;
  border-radius: 999px;
  background: #e3f1ed;
  color: #1f5c52;
  font-size: 11px;
}

.steward-chip.is-cross-team .steward-rel-tag {
  background: #f5e7cf;
  color: #7c581a;
}

.steward-cross-tag {
  font-size: 11px;
  color: #9a6c20;
}

.reason-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.rule-list {
  margin: 10px 0 12px;
  padding-left: 18px;
  line-height: 1.8;
}
</style>
