<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { MergeCaseItem } from '../api/customer'
import {
  confirmMergeCase,
  createMergeCase,
  listMergeCandidates,
  rejectMergeCase,
  requestMergeReversal,
  reviewMergeCase,
  reviewMergeReversal,
  scanMergeCandidates,
} from '../api/customer'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import StatusPill from './customer/StatusPill.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import './customer/customer-ui.css'

const auth = useAuthStore()
const canMerge = computed(() => auth.hasPermission('customer:merge'))
const canReverse = computed(() => auth.hasPermission('customer:merge_reverse'))
const summary = reactive({ pending: 0, processed: 0 })
const showRules = ref(false)
const query = reactive({ status: '', page: 1, size: 20 })
const items = ref<MergeCaseItem[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const STATUS_META: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'info' | 'dark' }> = {
  SUGGESTED: { label: '待审核', tone: 'warning' },
  REVIEWING: { label: '审核中', tone: 'info' },
  CONFIRMED: { label: '已合并', tone: 'success' },
  REJECTED: { label: '已拒绝', tone: 'dark' },
  REVERSED: { label: '已更正', tone: 'info' },
}

function statusMeta(status: string) {
  return STATUS_META[status] ?? { label: '未知状态', tone: 'dark' as const }
}

const REVERSAL_LABELS: Record<string, string> = {
  REQUESTED: '待更正审批',
  MANUAL_REVIEW: '待人工处理',
  EXECUTED: '更正已执行',
  REJECTED: '更正已驳回',
  ERROR: '更正执行失败',
}

function reversalLabel(item: MergeCaseItem): string {
  return REVERSAL_LABELS[item.reversalStatus ?? ''] ?? ''
}

const MATCH_LABELS: Record<string, string> = {
  MOBILE_HASH: '手机号一致',
  IDENTITY: '身份标识一致',
  MANUAL: '人工登记',
}

function evidenceReasons(evidence: unknown): string[] {
  if (!evidence || typeof evidence !== 'object') return []
  const matched = (evidence as { matchedOn?: unknown }).matchedOn
  if (!Array.isArray(matched)) return []
  return matched.map((code) => MATCH_LABELS[String(code)] ?? '其他依据')
}

function evidenceMasked(evidence: unknown): string {
  if (!evidence || typeof evidence !== 'object') return ''
  const e = evidence as { mobileMasked?: unknown; externalIdMasked?: unknown }
  return String(e.mobileMasked ?? e.externalIdMasked ?? '')
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return '该候选状态已变化,请刷新后重试'
    if (error.status === 404) return '候选或客户不在你的数据范围内'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listMergeCandidates({ page, size: query.size, status: query.status })
    items.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    items.value = []
    total.value = 0
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function search() {
  void load(1)
}

const scanning = ref(false)
const scanMessage = ref('')

async function handleScan() {
  scanning.value = true
  scanMessage.value = ''
  try {
    const result = await scanMergeCandidates()
    scanMessage.value = result.created > 0 ? `新增 ${result.created} 组候选，请逐组人工审核` : '未发现新的重复客户候选'
    await load(1)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    scanning.value = false
  }
}

// ---- 人工登记候选 ----
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createForm = reactive({
  primaryCustomerId: undefined as number | undefined,
  secondaryCustomerId: undefined as number | undefined,
})

function openCreateDialog() {
  createForm.primaryCustomerId = undefined
  createForm.secondaryCustomerId = undefined
  createDialogVisible.value = true
}

async function submitCreate() {
  if (!createForm.primaryCustomerId || !createForm.secondaryCustomerId) {
    ElMessage.warning('请填写主客户与被合并客户 ID')
    return
  }
  if (createForm.primaryCustomerId === createForm.secondaryCustomerId) {
    ElMessage.warning('主客户与被合并客户不能相同')
    return
  }
  createSubmitting.value = true
  try {
    await createMergeCase({
      primaryCustomerId: createForm.primaryCustomerId,
      secondaryCustomerId: createForm.secondaryCustomerId,
    })
    ElMessage.success('候选已登记(待审核),未经确认不会迁移任何数据')
    createDialogVisible.value = false
    await load(1)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    createSubmitting.value = false
  }
}

// ---- 审核动作 ----
async function handleReview(item: MergeCaseItem) {
  try {
    await reviewMergeCase(item.id, item.version)
    ElMessage.success('已进入审核中')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

const confirmDialogVisible = ref(false)
const confirmSubmitting = ref(false)
const confirmTarget = ref<MergeCaseItem | null>(null)
const confirmReason = ref('')

function openConfirmDialog(item: MergeCaseItem) {
  confirmTarget.value = item
  confirmReason.value = ''
  confirmDialogVisible.value = true
}

async function submitConfirm() {
  if (!confirmTarget.value) return
  if (!confirmReason.value.trim()) {
    ElMessage.warning('请填写合并理由(将写入审计)')
    return
  }
  confirmSubmitting.value = true
  try {
    await confirmMergeCase(confirmTarget.value.id, confirmTarget.value.version, confirmReason.value.trim())
    ElMessage.success('合并已确认:身份/标签/跟进/任务/积分已迁移至主客户,被合并客户已归档')
    confirmDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    confirmSubmitting.value = false
  }
}

const rejectDialogVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectTarget = ref<MergeCaseItem | null>(null)
const rejectReason = ref('')

function openRejectDialog(item: MergeCaseItem) {
  rejectTarget.value = item
  rejectReason.value = ''
  rejectDialogVisible.value = true
}

async function submitReject() {
  if (!rejectTarget.value) return
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝原因')
    return
  }
  rejectSubmitting.value = true
  try {
    await rejectMergeCase(rejectTarget.value.id, rejectTarget.value.version, rejectReason.value.trim())
    ElMessage.success('已标记为「不是同一人」')
    rejectDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    rejectSubmitting.value = false
  }
}

// ---- 错误合并更正：申请与审批分离，审批人使用更正单版本 ----
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
  if (action === 'request' ? !canMerge.value : !canReverse.value) {
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
    ElMessage.warning('请填写理由或处理说明（将写入审计）')
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
      ElMessage[ saved.status === 'MANUAL_REVIEW' ? 'warning' : 'success' ](
        saved.status === 'MANUAL_REVIEW' ? '从客户已恢复，其他关联数据需人工逐项迁移确认' : '已按合并前快照执行更正',
      )
    } else {
      ElMessage.success(reversalAction.value === 'reject' ? '更正申请已驳回，客户数据未改变' : '操作已完成')
    }
    reversalDialogVisible.value = false
    await load()
    void loadSummary()
  } catch (error) {
    ElMessage.error(`${errorText(error)}；重试将沿用同一幂等键`)
  } finally {
    reversalSubmitting.value = false
  }
}

function scoreOf(evidence: unknown) {
  if (evidence && typeof evidence === 'object' && 'score' in evidence) {
    const score = (evidence as { score?: unknown }).score
    return typeof score === 'number' ? score : null
  }
  return null
}
async function loadSummary() {
  const [suggested, reviewing, confirmed, rejected] = await Promise.all([
    listMergeCandidates({ status: 'SUGGESTED', page: 1, size: 1 }),
    listMergeCandidates({ status: 'REVIEWING', page: 1, size: 1 }),
    listMergeCandidates({ status: 'CONFIRMED', page: 1, size: 1 }),
    listMergeCandidates({ status: 'REJECTED', page: 1, size: 1 }),
  ])
  summary.pending = suggested.total + reviewing.total
  summary.processed = confirmed.total + rejected.total
}
onMounted(() => { void load(); void loadSummary() })
</script>

<template>
  <div class="proto-page">
    <PageHeader title="客户查重合并" description="系统只生成候选，必须人工确认；保留主客户ID，迁移关联数据并记录操作日志">
      <template #actions><el-button @click="showRules=true">查重规则说明</el-button><el-button v-if="canMerge" :loading="scanning" @click="handleScan">扫描重复客户</el-button><el-button v-if="canMerge" type="primary" @click="openCreateDialog">登记候选</el-button></template>
    </PageHeader>
    <div class="proto-stat-grid three"><StatCard label="待确认候选" :value="`${summary.pending} 组`" sub="必须人工审核" icon="users" /><StatCard label="高相似度" value="按证据" sub="只使用后端可信证据" icon="heart" /><StatCard label="已处理" :value="`${summary.processed} 组`" sub="合并与拒绝均留痕" icon="check" /></div>
    <PageCard>
      <div class="proto-toolbar"><div class="proto-toolbar-left"><el-select v-model="query.status" placeholder="全部状态" clearable><el-option label="待审核" value="SUGGESTED" /><el-option label="审核中" value="REVIEWING" /><el-option label="已合并" value="CONFIRMED" /><el-option label="已拒绝" value="REJECTED" /><el-option label="已更正" value="REVERSED" /></el-select><el-button type="primary" @click="search">查询</el-button></div><span class="proto-note">昵称相似不构成自动合并依据</span></div>
      <el-alert v-if="scanMessage" type="success" :closable="false" :title="scanMessage" />
      <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" />
      <div class="proto-table-wrap">
        <app-table v-loading="loading" :data="items">
          <el-table-column prop="id" label="候选" width="80" />
          <el-table-column label="CRM客户" min-width="170"><template #default="{row}">{{ row.primarySummary }}<div class="proto-subcell">保留 #{{ row.primaryCustomerId }}</div></template></el-table-column>
          <el-table-column label="另一身份" min-width="170"><template #default="{row}">{{ row.secondarySummary }}<div class="proto-subcell">{{ row.status === 'REVERSED' || row.reversalStatus === 'MANUAL_REVIEW' || row.reversalStatus === 'EXECUTED' ? '已恢复' : '归档' }} #{{ row.secondaryCustomerId }}</div></template></el-table-column>
          <el-table-column label="相似度" width="95"><template #default="{row}">{{ scoreOf(row.matchEvidence) ?? '待评估' }}</template></el-table-column>
          <el-table-column label="命中依据" min-width="190"><template #default="{row}"><template v-if="evidenceReasons(row.matchEvidence).length"><StatusPill v-for="reason in evidenceReasons(row.matchEvidence)" :key="reason" :text="reason" tone="info" /><div v-if="evidenceMasked(row.matchEvidence)" class="proto-subcell">{{ evidenceMasked(row.matchEvidence) }}</div></template><span v-else class="proto-muted">-</span></template></el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{row}">
              <StatusPill :text="statusMeta(row.status).label" :tone="statusMeta(row.status).tone" />
              <div v-if="reversalLabel(row)" class="proto-subcell" :data-testid="`reversal-status-${row.id}`">{{ reversalLabel(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{row}">
              <el-button v-if="canMerge && row.status==='SUGGESTED'" text @click="handleReview(row)">开始审核</el-button>
              <template v-else-if="canMerge && row.status==='REVIEWING'">
                <el-button text type="primary" @click="openConfirmDialog(row)">确认合并</el-button>
                <el-button text type="danger" @click="openRejectDialog(row)">不是同一人</el-button>
              </template>
              <el-button v-else-if="canMerge && row.status==='CONFIRMED' && !row.reversalStatus" text type="warning" :data-testid="`reversal-request-${row.id}`" @click="openReversalDialog(row, 'request')">申请更正</el-button>
              <template v-else-if="canReverse && row.reversalStatus==='REQUESTED'">
                <el-button text type="warning" :data-testid="`reversal-approve-${row.id}`" @click="openReversalDialog(row, 'approve')">审批并执行</el-button>
                <el-button text type="danger" :data-testid="`reversal-reject-${row.id}`" @click="openReversalDialog(row, 'reject')">驳回</el-button>
              </template>
              <el-button v-else-if="canReverse && row.reversalStatus==='MANUAL_REVIEW'" text type="warning" :data-testid="`reversal-complete-${row.id}`" @click="openReversalDialog(row, 'complete')">人工完成</el-button>
              <span v-else class="proto-muted">{{ row.reversalStatus ? '已提交更正' : '已处理' }}</span>
            </template>
          </el-table-column>
          <template #empty>暂无合并候选</template>
          <template #append>
            <template v-for="row in items" :key="`warning-${row.id}`">
              <el-alert v-if="row.reversalWarning" type="warning" :closable="false" :title="`候选 #${row.id}：${row.reversalWarning}`" class="crm-table-alert" :data-testid="`reversal-warning-${row.id}`" />
            </template>
          </template>
        </app-table>
      </div>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.size=$event;load(1)" />
    </PageCard>
    <div class="proto-callout" style="margin-top:18px">合并保留主客户 ID，迁移身份、标签、跟进、任务和积分；被合并客户归档，操作全程写入审计。</div>
    <el-dialog v-model="showRules" title="查重规则说明" width="520px"><p>候选依据同手机号哈希或同外部身份生成，仅昵称相似不构成合并依据。确认前不迁移任何数据，确认后在同一事务完成关联数据迁移。</p></el-dialog>

    <el-dialog v-model="createDialogVisible" title="登记合并候选" width="460px">
      <el-form label-width="110px">
        <el-form-item label="主客户 ID" required>
          <el-input-number v-model="createForm.primaryCustomerId" :min="1" controls-position="right" class="full-width" />
          <span class="crm-muted">合并后保留的客户</span>
        </el-form-item>
        <el-form-item label="被合并客户 ID" required>
          <el-input-number v-model="createForm.secondaryCustomerId" :min="1" controls-position="right" class="full-width" />
          <span class="crm-muted">合并后归档的客户</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="confirmDialogVisible" title="确认合并" width="520px">
      <el-alert
        v-if="confirmTarget"
        type="warning"
        :closable="false"
        :title="`将把 #${confirmTarget.secondaryCustomerId} 的关联数据迁移到 #${confirmTarget.primaryCustomerId}，并归档被合并客户。合并历史保留，错误合并需通过更正审批处理。`"
        class="crm-table-alert"
      />
      <el-form label-width="90px">
        <el-form-item label="合并理由" required>
          <el-input v-model="confirmReason" type="textarea" :rows="2" placeholder="写入审计快照" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirmSubmitting" @click="submitConfirm">确认合并</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectDialogVisible" title="拒绝合并" width="460px">
      <el-form label-width="90px">
        <el-form-item label="拒绝原因" required>
          <el-input v-model="rejectReason" type="textarea" :rows="2" placeholder="如:核实后不是同一人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" @click="submitReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reversalDialogVisible" :title="REVERSAL_ACTION_LABELS[reversalAction]" width="560px">
      <el-alert
        v-if="reversalTarget"
        type="warning"
        :closable="false"
        :title="reversalAction === 'request'
          ? `申请对候选 #${reversalTarget.id} 的合并进行更正；申请不会立即修改客户，须由有更正审批权限的管理员执行。`
          : reversalAction === 'approve'
            ? '有快照时仅迁回合并时记录的资源；历史无快照时只恢复从客户，其余数据须人工逐项处理。原合并与审计历史均保留。'
            : reversalAction === 'complete'
              ? '仅在已逐项人工核对并迁移旧合并关联数据后完成；完成不代表系统自动恢复去重删除的身份或标签。'
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
            :placeholder="reversalAction === 'complete' ? '说明已核对的身份、标签、跟进、任务、积分、订单和权益处理结果' : '请填写原因，提交后不可修改历史记录'"
            data-testid="reversal-reason"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reversalDialogVisible = false">取消</el-button>
        <el-button :type="reversalAction === 'reject' ? 'danger' : 'primary'" :loading="reversalSubmitting" data-testid="reversal-submit" @click="submitReversal">
          {{ REVERSAL_ACTION_LABELS[reversalAction] }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-select {
  width: 130px;
}

.full-width {
  width: 100%;
}
</style>
