<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  CustomerPrivacyAccessLogItem,
  CustomerPrivacyFieldCode,
  CustomerPrivacyRequestItem,
} from '../api/customerPrivacy'
import {
  DURATION_OPTIONS,
  accessModeLabel,
  approveCustomerPrivacyRequest,
  approverSourceLabel,
  cancelCustomerPrivacyRequest,
  durationLabel,
  listCustomerPrivacyAccessLogs,
  listCustomerPrivacyRequests,
  privacyFieldLabel,
  rejectCustomerPrivacyRequest,
  revokeCustomerPrivacyRequest,
} from '../api/customerPrivacy'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatusPill from './customer/StatusPill.vue'
import { formatDateTime } from './customer/shared'

const route = useRoute()
const auth = useAuthStore()

const canApprove = computed(() => auth.hasPermission('customer:privacy:approve'))
const canRequest = computed(() => auth.hasPermission('customer:privacy:request'))
const canAudit = computed(() => auth.hasPermission('customer:privacy:audit'))

const defaultTab = () => {
  const qTab = typeof route?.query?.tab === 'string' ? route.query.tab : ''
  if (qTab === 'my' && canRequest.value) return 'my'
  if (qTab === 'logs' && canAudit.value) return 'logs'
  if (canApprove.value) return 'pending'
  if (canRequest.value) return 'my'
  return 'logs'
}
const activeTab = ref<'pending' | 'my' | 'logs'>(defaultTab())

// ---- 审批管理 ----
const approvalQuery = reactive({
  view: 'APPROVALS' as 'PENDING_APPROVAL' | 'APPROVALS',
  status: 'PENDING',
  keyword: '',
  page: 1,
  size: 20,
})
const approvalRows = ref<CustomerPrivacyRequestItem[]>([])
const approvalTotal = ref(0)
const approvalLoading = ref(false)
const approvalError = ref('')

// ---- 我的申请 ----
const myQuery = reactive({
  status: '',
  keyword: '',
  page: 1,
  size: 20,
})
const myRows = ref<CustomerPrivacyRequestItem[]>([])
const myTotal = ref(0)
const myLoading = ref(false)
const myError = ref('')

// ---- 隐私访问日志 ----
const logQuery = reactive({
  customerId: undefined as number | undefined,
  actorUserId: undefined as number | undefined,
  accessMode: '',
  page: 1,
  size: 20,
})
const logRows = ref<CustomerPrivacyAccessLogItem[]>([])
const logTotal = ref(0)
const logLoading = ref(false)
const logError = ref('')

// ---- 批准弹窗 ----
const approveDialogVisible = ref(false)
const approveSubmitting = ref(false)
const approveTarget = ref<CustomerPrivacyRequestItem | null>(null)
const approveForm = reactive({
  approvedFields: [] as CustomerPrivacyFieldCode[],
  durationMinutes: 30,
  reviewReason: '',
})

// ---- 驳回弹窗 ----
const rejectDialogVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectTarget = ref<CustomerPrivacyRequestItem | null>(null)
const rejectReason = ref('')

// ---- 撤销弹窗 ----
const revokeDialogVisible = ref(false)
const revokeSubmitting = ref(false)
const revokeTarget = ref<CustomerPrivacyRequestItem | null>(null)
const revokeReason = ref('')

const STATUS_META: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'info' | 'dark' }> = {
  PENDING: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已批准（生效中）', tone: 'success' },
  EXPIRED: { label: '已到期', tone: 'dark' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  CANCELED: { label: '已撤回', tone: 'dark' },
  REVOKED: { label: '已撤销', tone: 'danger' },
}

function statusMeta(status: string) {
  return STATUS_META[status] ?? { label: status || '未知', tone: 'dark' as const }
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function formatMaskedSnapshot(snapshot: Record<string, string> | null | undefined): string {
  if (!snapshot) return '—'
  const entries = Object.entries(snapshot)
  if (!entries.length) return '—'
  return entries.map(([key, val]) => `${privacyFieldLabel(key)}：${val}`).join('；')
}

async function loadApprovals(page = approvalQuery.page) {
  if (!canApprove.value) return
  approvalLoading.value = true
  approvalError.value = ''
  try {
    const res = await listCustomerPrivacyRequests({
      page,
      size: approvalQuery.size,
      view: approvalQuery.status === 'PENDING' ? 'PENDING_APPROVAL' : 'APPROVALS',
      status: approvalQuery.status || undefined,
      keyword: approvalQuery.keyword.trim() || undefined,
    })
    approvalRows.value = res.items
    approvalTotal.value = res.total
    approvalQuery.page = res.page
    approvalQuery.size = res.size
  } catch (error) {
    approvalError.value = errorText(error)
  } finally {
    approvalLoading.value = false
  }
}

async function loadMyRequests(page = myQuery.page) {
  if (!canRequest.value) return
  myLoading.value = true
  myError.value = ''
  try {
    const res = await listCustomerPrivacyRequests({
      page,
      size: myQuery.size,
      view: 'MY',
      status: myQuery.status || undefined,
      keyword: myQuery.keyword.trim() || undefined,
    })
    myRows.value = res.items
    myTotal.value = res.total
    myQuery.page = res.page
    myQuery.size = res.size
  } catch (error) {
    myError.value = errorText(error)
  } finally {
    myLoading.value = false
  }
}

async function loadAccessLogs(page = logQuery.page) {
  if (!canAudit.value) return
  logLoading.value = true
  logError.value = ''
  try {
    const res = await listCustomerPrivacyAccessLogs({
      page,
      size: logQuery.size,
      customerId: logQuery.customerId,
      actorUserId: logQuery.actorUserId,
      accessMode: logQuery.accessMode || undefined,
    })
    logRows.value = res.items
    logTotal.value = res.total
    logQuery.page = res.page
    logQuery.size = res.size
  } catch (error) {
    logError.value = errorText(error)
  } finally {
    logLoading.value = false
  }
}

function openApprove(row: CustomerPrivacyRequestItem) {
  approveTarget.value = row
  approveForm.approvedFields = [...row.requestedFields]
  approveForm.durationMinutes = row.requestedDurationMinutes || 30
  approveForm.reviewReason = ''
  approveDialogVisible.value = true
}

async function submitApprove() {
  if (!approveTarget.value || approveSubmitting.value) return
  if (approveForm.approvedFields.length === 0) {
    ElMessage.warning('请至少勾选一项批准查看的字段')
    return
  }
  approveSubmitting.value = true
  try {
    await approveCustomerPrivacyRequest(approveTarget.value.id, {
      approvedFields: [...approveForm.approvedFields],
      durationMinutes: approveForm.durationMinutes,
      reviewReason: approveForm.reviewReason.trim() || undefined,
      version: approveTarget.value.version,
    })
    ElMessage.success('已批准该隐私查看申请并生成限时授权')
    approveDialogVisible.value = false
    await loadApprovals()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    approveSubmitting.value = false
  }
}

function openReject(row: CustomerPrivacyRequestItem) {
  rejectTarget.value = row
  rejectReason.value = ''
  rejectDialogVisible.value = true
}

async function submitReject() {
  if (!rejectTarget.value || rejectSubmitting.value) return
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  rejectSubmitting.value = true
  try {
    await rejectCustomerPrivacyRequest(rejectTarget.value.id, {
      reviewReason: rejectReason.value.trim(),
      version: rejectTarget.value.version,
    })
    ElMessage.success('已驳回该隐私查看申请')
    rejectDialogVisible.value = false
    await loadApprovals()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    rejectSubmitting.value = false
  }
}

function openRevoke(row: CustomerPrivacyRequestItem) {
  revokeTarget.value = row
  revokeReason.value = ''
  revokeDialogVisible.value = true
}

async function submitRevoke() {
  if (!revokeTarget.value || revokeSubmitting.value) return
  if (!revokeReason.value.trim()) {
    ElMessage.warning('请填写撤销授权原因')
    return
  }
  revokeSubmitting.value = true
  try {
    await revokeCustomerPrivacyRequest(revokeTarget.value.id, {
      revokeReason: revokeReason.value.trim(),
      version: revokeTarget.value.version,
    })
    ElMessage.success('已立即撤销该隐私查看授权')
    revokeDialogVisible.value = false
    await loadApprovals()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    revokeSubmitting.value = false
  }
}

async function handleCancelMy(row: CustomerPrivacyRequestItem) {
  try {
    await ElMessageBox.confirm(`确认撤回申请单 ${row.requestNo} 吗？`, '撤回申请', {
      type: 'warning',
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await cancelCustomerPrivacyRequest(row.id, row.version)
    ElMessage.success('已撤回申请')
    await loadMyRequests()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

watch(activeTab, (tab) => {
  if (tab === 'pending') void loadApprovals(1)
  else if (tab === 'my') void loadMyRequests(1)
  else if (tab === 'logs') void loadAccessLogs(1)
})

onMounted(() => {
  if (activeTab.value === 'pending') void loadApprovals(1)
  else if (activeTab.value === 'my') void loadMyRequests(1)
  else void loadAccessLogs(1)
})
</script>

<template>
  <div class="proto-page" data-testid="privacy-approvals-page">
    <PageHeader
      title="客户隐私查看审批与审计"
      description="所有用户默认不可见客户手机号、身份证号、邮箱、微信号明文；非管理员须经上级审批限时查看，管理员按钮查看同步落不可篡改日志"
    />

    <PageCard>
      <el-tabs v-model="activeTab">
        <el-tab-pane v-if="canApprove" label="隐私审批管理" name="pending">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-input
                v-model="approvalQuery.keyword"
                placeholder="单号 / 客户姓名 / 客户编号 / 申请人"
                clearable
                style="width: 260px"
                @keyup.enter="loadApprovals(1)"
              />
              <el-select
                v-model="approvalQuery.status"
                placeholder="全部状态"
                clearable
                style="width: 150px"
                @change="loadApprovals(1)"
              >
                <el-option label="待审批" value="PENDING" />
                <el-option label="已批准" value="APPROVED" />
                <el-option label="已驳回" value="REJECTED" />
                <el-option label="已撤销" value="REVOKED" />
                <el-option label="已撤回" value="CANCELED" />
              </el-select>
              <el-button type="primary" :loading="approvalLoading" @click="loadApprovals(1)">查询</el-button>
            </div>
          </div>

          <el-alert v-if="approvalError" type="error" :title="approvalError" :closable="false" class="crm-table-alert" />
          <div class="proto-table-wrap">
            <app-table v-loading="approvalLoading" :data="approvalRows">
              <el-table-column prop="requestNo" label="申请单号" width="145" />
              <el-table-column label="客户" min-width="150">
                <template #default="{ row }">
                  <strong>{{ row.customerName }}</strong>
                  <div class="proto-subcell">{{ row.customerNo }}</div>
                </template>
              </el-table-column>
              <el-table-column label="申请人" width="110">
                <template #default="{ row }">{{ row.applicantName || `#${row.applicantUserId}` }}</template>
              </el-table-column>
              <el-table-column label="申请字段" min-width="160">
                <template #default="{ row }">
                  <span v-for="code in row.requestedFields" :key="code" class="proto-pill info field-pill">
                    {{ privacyFieldLabel(code) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="申请原因" min-width="190" show-overflow-tooltip />
              <el-table-column label="审批上级" min-width="135">
                <template #default="{ row }">
                  {{ row.assignedApproverName || `#${row.assignedApproverId}` }}
                  <div class="proto-subcell">{{ approverSourceLabel(row.approverSource) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="有效期 / 查看次数" min-width="150">
                <template #default="{ row }">
                  <div>{{ durationLabel(row.approvedDurationMinutes ?? row.requestedDurationMinutes) }}</div>
                  <div v-if="row.grantExpiresAt" class="proto-subcell">
                    至 {{ formatDateTime(row.grantExpiresAt) }} · 已查 {{ row.revealCount }} 次
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <StatusPill :text="statusMeta(row.effectiveStatus).label" :tone="statusMeta(row.effectiveStatus).tone" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="160" fixed="right">
                <template #default="{ row }">
                  <template v-if="row.status === 'PENDING'">
                    <el-button type="primary" text size="small" data-testid="privacy-approve-btn" @click="openApprove(row)">批准</el-button>
                    <el-button type="danger" text size="small" data-testid="privacy-reject-btn" @click="openReject(row)">驳回</el-button>
                  </template>
                  <template v-else-if="row.effectiveStatus === 'APPROVED'">
                    <el-button type="danger" text size="small" data-testid="privacy-revoke-btn" @click="openRevoke(row)">撤销授权</el-button>
                  </template>
                  <span v-else class="crm-muted">已结束</span>
                </template>
              </el-table-column>
              <template #empty>暂无隐私查看审批单</template>
            </app-table>
          </div>
          <el-pagination
            v-model:current-page="approvalQuery.page"
            v-model:page-size="approvalQuery.size"
            :total="approvalTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadApprovals"
            @size-change="loadApprovals(1)"
          />
        </el-tab-pane>

        <el-tab-pane v-if="canRequest" label="我的隐私申请" name="my">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-input
                v-model="myQuery.keyword"
                placeholder="单号 / 客户姓名 / 客户编号"
                clearable
                style="width: 240px"
                @keyup.enter="loadMyRequests(1)"
              />
              <el-select
                v-model="myQuery.status"
                placeholder="全部状态"
                clearable
                style="width: 140px"
                @change="loadMyRequests(1)"
              >
                <el-option label="待审批" value="PENDING" />
                <el-option label="已批准" value="APPROVED" />
                <el-option label="已驳回" value="REJECTED" />
                <el-option label="已撤销" value="REVOKED" />
                <el-option label="已撤回" value="CANCELED" />
              </el-select>
              <el-button type="primary" :loading="myLoading" @click="loadMyRequests(1)">查询</el-button>
            </div>
          </div>

          <el-alert v-if="myError" type="error" :title="myError" :closable="false" class="crm-table-alert" />
          <div class="proto-table-wrap">
            <app-table v-loading="myLoading" :data="myRows">
              <el-table-column prop="requestNo" label="申请单号" width="145" />
              <el-table-column label="客户" min-width="150">
                <template #default="{ row }">
                  <strong>{{ row.customerName }}</strong>
                  <div class="proto-subcell">{{ row.customerNo }}</div>
                </template>
              </el-table-column>
              <el-table-column label="申请 / 获批字段" min-width="170">
                <template #default="{ row }">
                  <span
                    v-for="code in (row.approvedFields.length ? row.approvedFields : row.requestedFields)"
                    :key="code"
                    class="proto-pill success field-pill"
                  >
                    {{ privacyFieldLabel(code) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="审批上级" min-width="130">
                <template #default="{ row }">
                  {{ row.assignedApproverName || `#${row.assignedApproverId}` }}
                  <div class="proto-subcell">{{ approverSourceLabel(row.approverSource) }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="申请原因" min-width="170" show-overflow-tooltip />
              <el-table-column label="审批结果 / 有效期" min-width="180">
                <template #default="{ row }">
                  <StatusPill :text="statusMeta(row.effectiveStatus).label" :tone="statusMeta(row.effectiveStatus).tone" />
                  <div v-if="row.grantExpiresAt" class="proto-subcell">
                    至 {{ formatDateTime(row.grantExpiresAt) }}（已查 {{ row.revealCount }} 次）
                  </div>
                  <div v-else-if="row.reviewReason" class="proto-subcell">{{ row.reviewReason }}</div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'PENDING'" text type="danger" size="small" @click="handleCancelMy(row)">
                    撤回
                  </el-button>
                  <span v-else class="crm-muted">—</span>
                </template>
              </el-table-column>
              <template #empty>暂无我的隐私查看申请</template>
            </app-table>
          </div>
          <el-pagination
            v-model:current-page="myQuery.page"
            v-model:page-size="myQuery.size"
            :total="myTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadMyRequests"
            @size-change="loadMyRequests(1)"
          />
        </el-tab-pane>

        <el-tab-pane v-if="canAudit" label="隐私访问审计日志" name="logs">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-input-number
                v-model="logQuery.customerId"
                :min="1"
                controls-position="right"
                placeholder="客户 ID"
                style="width: 140px"
              />
              <el-input-number
                v-model="logQuery.actorUserId"
                :min="1"
                controls-position="right"
                placeholder="查看人 ID"
                style="width: 140px"
              />
              <el-select
                v-model="logQuery.accessMode"
                placeholder="全部查看方式"
                clearable
                style="width: 170px"
                @change="loadAccessLogs(1)"
              >
                <el-option label="管理员按钮揭盲" value="ADMIN_DIRECT_REVEAL" />
                <el-option label="主管审批后查看" value="APPROVED_GRANT_REVEAL" />
              </el-select>
              <el-button type="primary" :loading="logLoading" @click="loadAccessLogs(1)">查询</el-button>
            </div>
            <span class="proto-note">隐私访问日志由数据库触发器保护，仅可追加、禁止修改与删除</span>
          </div>

          <el-alert v-if="logError" type="error" :title="logError" :closable="false" class="crm-table-alert" />
          <div class="proto-table-wrap">
            <app-table v-loading="logLoading" :data="logRows">
              <el-table-column label="查看时间" width="165">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="查看人" min-width="135">
                <template #default="{ row }">
                  <strong>{{ row.actorDisplayName || row.actorUsername || `#${row.actorUserId}` }}</strong>
                  <div class="proto-subcell">{{ row.actorRoleSnapshot }}</div>
                </template>
              </el-table-column>
              <el-table-column label="查看方式" width="140">
                <template #default="{ row }">
                  <StatusPill
                    :text="accessModeLabel(row.accessMode)"
                    :tone="row.accessMode === 'ADMIN_DIRECT_REVEAL' ? 'warning' : 'info'"
                  />
                </template>
              </el-table-column>
              <el-table-column label="目标客户" min-width="150">
                <template #default="{ row }">
                  <strong>{{ row.customerName }}</strong>
                  <div class="proto-subcell">{{ row.customerNo }}</div>
                </template>
              </el-table-column>
              <el-table-column label="查看字段（脱敏快照）" min-width="220">
                <template #default="{ row }">
                  <div>
                    <span v-for="code in row.revealedFields" :key="code" class="proto-pill dark field-pill">
                      {{ privacyFieldLabel(code) }}
                    </span>
                  </div>
                  <div class="proto-subcell">{{ formatMaskedSnapshot(row.maskedValuesSnapshot) }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="查看原因" min-width="180" show-overflow-tooltip />
              <el-table-column label="关联申请单" width="140">
                <template #default="{ row }">{{ row.requestNo || '管理员直查' }}</template>
              </el-table-column>
              <el-table-column label="IP / 追踪号" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <div>{{ row.clientIp || '-' }}</div>
                  <div class="proto-subcell">{{ row.traceId || '-' }}</div>
                </template>
              </el-table-column>
              <template #empty>暂无客户隐私揭盲访问日志</template>
            </app-table>
          </div>
          <el-pagination
            v-model:current-page="logQuery.page"
            v-model:page-size="logQuery.size"
            :total="logTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadAccessLogs"
            @size-change="loadAccessLogs(1)"
          />
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <!-- 批准弹窗 -->
    <el-dialog v-model="approveDialogVisible" title="批准隐私查看申请" width="480px">
      <template v-if="approveTarget">
        <p class="dialog-meta">
          申请人：<strong>{{ approveTarget.applicantName }}</strong> · 客户：<strong>{{ approveTarget.customerName }}（{{ approveTarget.customerNo }}）</strong>
        </p>
        <p class="dialog-meta">申请原因：{{ approveTarget.reason }}</p>
        <el-form label-position="top">
          <el-form-item label="批准查看字段（可收窄范围）" required>
            <el-checkbox-group v-model="approveForm.approvedFields">
              <el-checkbox v-for="code in approveTarget.requestedFields" :key="code" :value="code">
                {{ privacyFieldLabel(code) }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="授权有效时长" required>
            <el-select v-model="approveForm.durationMinutes" style="width: 100%">
              <el-option v-for="opt in DURATION_OPTIONS" :key="opt.minutes" :label="opt.label" :value="opt.minutes" />
            </el-select>
          </el-form-item>
          <el-form-item label="审批意见（选填）">
            <el-input v-model="approveForm.reviewReason" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="approveSubmitting" data-testid="confirm-approve-btn" @click="submitApprove">
          确认批准
        </el-button>
      </template>
    </el-dialog>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectDialogVisible" title="驳回隐私查看申请" width="460px">
      <el-form label-position="top">
        <el-form-item label="驳回原因（必填）" required>
          <el-input
            v-model="rejectReason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            data-testid="reject-reason-input"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" data-testid="confirm-reject-btn" @click="submitReject">
          确认驳回
        </el-button>
      </template>
    </el-dialog>

    <!-- 撤销授权弹窗 -->
    <el-dialog v-model="revokeDialogVisible" title="提前撤销隐私查看授权" width="460px">
      <el-form label-position="top">
        <el-form-item label="撤销原因（必填）" required>
          <el-input
            v-model="revokeReason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            data-testid="revoke-reason-input"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="revokeDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="revokeSubmitting" data-testid="confirm-revoke-btn" @click="submitRevoke">
          确认撤销
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.field-pill {
  margin-right: 4px;
  margin-bottom: 2px;
}

.dialog-meta {
  margin: 0 0 10px;
  font-size: 13px;
  color: #46606d;
}
</style>
