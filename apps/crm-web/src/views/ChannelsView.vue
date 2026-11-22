<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Channel, ChannelChangeRequestStatus, ChannelFinancialChangeRequest } from '../api/channel'
import {
  listAllChannelFinancialChangeRequests,
  listChannels,
  reviewChannelFinancialChangeRequest,
  toggleChannelStatus,
} from '../api/channel'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import StatusPill from './customer/StatusPill.vue'
import ChannelFormDialog from './channel/ChannelFormDialog.vue'
import ChannelFinanceChangeDialog from './channel/ChannelFinanceChangeDialog.vue'
import ChannelChangeRequestsPanel from './channel/ChannelChangeRequestsPanel.vue'
import { useDictionaries } from './customer/shared'
import './customer/customer-ui.css'

const auth = useAuthStore()
const dicts = useDictionaries()
const canWrite = computed(() => auth.hasPermission('channel:write'))
const canApproveFinance = computed(() => auth.hasPermission('channel:approve_finance'))
const currentUserId = computed(() => auth.user?.userId ?? 0)

const activeTab = ref<'channels' | 'financeChanges'>('channels')
const channels = ref<Channel[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({
  keyword: '',
  status: '' as '' | Channel['status'],
  channelType: '' as '' | Channel['channelType'],
  page: 1,
  size: 20,
})

const changeRequests = ref<ChannelFinancialChangeRequest[]>([])
const changeTotal = ref(0)
const changeLoading = ref(false)
const changeError = ref('')
const changeQuery = reactive({
  status: '' as '' | ChannelChangeRequestStatus,
  page: 1,
  size: 20,
})

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '合作中',
  INACTIVE: '暂停合作',
  ARCHIVED: '已归档',
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? '未知状态'
}

function statusTone(status: string): 'success' | 'warning' | 'dark' {
  if (status === 'ACTIVE' || status === '合作中') return 'success'
  if (status === 'ARCHIVED') return 'dark'
  return 'warning'
}

function channelTypeLabel(type: Channel['channelType']): string {
  return ({ BLOGGER: '博主/达人', DISTRIBUTION: '分销', REFERRAL: '老带新' } as Record<string, string>)[type] ?? '未知类型'
}

function channelTypeTone(type: Channel['channelType']): 'teal' | 'purple' | 'orange' | 'dark' {
  return ({ BLOGGER: 'teal', DISTRIBUTION: 'purple', REFERRAL: 'orange' } as const)[type] ?? 'dark'
}

function platformLabel(code: string | null): string {
  if (!code) return '未配置平台'
  return dicts.options('customerChannels').find((option) => option.code === code)?.label ?? code
}

function settlementLabel(code: string | null): string {
  if (!code) return '—'
  if (code === 'MONTHLY') return '电汇月结'
  if (code === 'TWICE_MONTHLY') return '电汇每月两次'
  return dicts.options('settlementTypes').find((option) => option.code === code)?.label ?? code
}

function maskPhone(value: string | null, configured?: boolean): string {
  if (!value) return configured ? '已配置（已脱敏）' : '—'
  if (value.includes('*')) return value
  return value.length === 11 ? `${value.slice(0, 3)}****${value.slice(-4)}` : '****'
}

function maskIdCard(value: string | null, configured?: boolean): string {
  if (!value) return configured ? '已配置（已脱敏）' : '—'
  if (value.includes('*')) return value
  return value.length >= 11 ? `${value.slice(0, 6)}${'*'.repeat(value.length - 10)}${value.slice(-4)}` : '****'
}

function maskBankAccount(value: string | null, configured?: boolean): string {
  if (!value) return configured ? '已配置（已脱敏）' : '—'
  if (value.includes('*')) return value
  const normalized = value.replace(/\s/g, '')
  return normalized.length > 4 ? `**** **** **** ${normalized.slice(-4)}` : '****'
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return error.message || '渠道已变更或当前状态不允许操作，请刷新后重试'
    return error.message
  }
  return error instanceof Error ? error.message : '渠道操作失败'
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listChannels({
      page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      channelType: query.channelType || undefined,
    })
    channels.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    channels.value = []
    total.value = 0
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function loadChangeRequests(page = changeQuery.page) {
  changeLoading.value = true
  changeError.value = ''
  try {
    const result = await listAllChannelFinancialChangeRequests({
      page,
      size: changeQuery.size,
      status: changeQuery.status || undefined,
    })
    changeRequests.value = result.items
    changeTotal.value = result.total
    changeQuery.page = result.page
    changeQuery.size = result.size
  } catch (error) {
    changeRequests.value = []
    changeTotal.value = 0
    changeError.value = errorText(error)
  } finally {
    changeLoading.value = false
  }
}

const formVisible = ref(false)
const editingChannel = ref<Channel | null>(null)
const financeDialogVisible = ref(false)
const financeChannel = ref<Channel | null>(null)

function openCreate() {
  editingChannel.value = null
  formVisible.value = true
}

function openEdit(item: Channel) {
  editingChannel.value = item
  formVisible.value = true
}

function openFinanceChange(item: Channel) {
  financeChannel.value = item
  financeDialogVisible.value = true
}

async function handleSaved() {
  await load()
}

async function handleFinanceSubmitted() {
  await loadChangeRequests(1)
}

async function toggleStatus(item: Channel, targetStatus: 'ACTIVE' | 'INACTIVE') {
  const isPause = targetStatus === 'INACTIVE'
  try {
    await ElMessageBox.confirm(
      isPause ? '暂停后不可用于新的渠道归因，历史订单快照不变。确认暂停？' : '确认恢复该渠道的合作状态？',
      isPause ? '暂停合作' : '恢复合作',
      { type: isPause ? 'warning' : 'info' },
    )
  } catch {
    return
  }
  try {
    await toggleChannelStatus(item.id, {
      status: targetStatus,
      version: item.version,
      reason: isPause ? '运营操作暂停合作' : '运营操作恢复合作',
    })
    ElMessage.success(isPause ? '渠道已暂停，历史归因保持不变' : '渠道已恢复合作')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

let reviewAttempt: { signature: string; key: string } | null = null

async function handleReview(request: ChannelFinancialChangeRequest, decision: 'APPROVE' | 'REJECT') {
  let comment = ''
  try {
    const promptResult = await ElMessageBox.prompt(
      decision === 'APPROVE' ? '填写审批通过意见（可选）' : '请填写驳回原因',
      decision === 'APPROVE' ? '审批通过财务变更' : '驳回财务变更',
      {
        inputPlaceholder: decision === 'APPROVE' ? '核对无误，同意变更' : '请说明驳回原因',
        inputValidator: decision === 'REJECT'
          ? (value) => (value?.trim() ? true : '驳回时必须填写原因')
          : undefined,
      },
    )
    comment = promptResult.value?.trim() ?? ''
  } catch {
    return
  }
  const signature = `${request.id}:${request.version}:${decision}:${comment}`
  if (reviewAttempt?.signature !== signature) {
    reviewAttempt = { signature, key: crypto.randomUUID() }
  }
  try {
    await reviewChannelFinancialChangeRequest(
      request.id,
      { decision, version: request.version, comment: comment || undefined },
      reviewAttempt.key,
    )
    reviewAttempt = null
    ElMessage.success(decision === 'APPROVE' ? '已审批通过，渠道财务资料已更新' : '已驳回该财务变更申请')
    await Promise.all([load(), loadChangeRequests()])
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function handleTabChange(name: string | number) {
  if (name === 'financeChanges') {
    void loadChangeRequests(1)
  }
}

onMounted(() => {
  void dicts.load('customerChannels')
  void dicts.load('settlementTypes')
  void load()
})
</script>

<template>
  <div class="proto-page channels-page">
    <PageHeader title="合作渠道 / 达人" description="维护真实渠道主数据与成交时商务归因来源；历史订单保留原渠道和商务快照">
      <template #actions>
        <el-button v-if="canWrite" type="primary" data-testid="channel-create" @click="openCreate">＋ 新增渠道达人</el-button>
      </template>
    </PageHeader>

    <el-alert
      type="info"
      :closable="false"
      title="当前缺少真实归因数据：本页不展示线索量或订单量，也不按 0 计算。业绩只认真实订单的成交快照，不能从当前客户来源或管家身份推断。"
      class="attribution-alert"
    />

    <PageCard>
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane label="渠道主数据" name="channels">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-input v-model="query.keyword" clearable placeholder="渠道编号 / 名称" class="keyword" @keyup.enter="load(1)" />
              <el-select v-model="query.channelType" clearable placeholder="合作类型" class="status-filter" data-testid="channel-type-filter">
                <el-option label="博主/达人" value="BLOGGER" />
                <el-option label="分销" value="DISTRIBUTION" />
                <el-option label="老带新" value="REFERRAL" />
              </el-select>
              <el-select v-model="query.status" clearable placeholder="合作状态" class="status-filter">
                <el-option label="合作中" value="ACTIVE" />
                <el-option label="暂停合作" value="INACTIVE" />
                <el-option label="已归档" value="ARCHIVED" />
              </el-select>
              <el-button type="primary" :loading="loading" @click="load(1)">查询</el-button>
              <el-button @click="query.keyword = ''; query.status = ''; query.channelType = ''; load(1)">重置</el-button>
            </div>
            <span class="proto-note">平台选项来自客户渠道字典，停用项不可新选</span>
          </div>
          <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
          <div class="proto-table-wrap">
            <app-table v-loading="loading" :data="channels">
              <el-table-column prop="channelNo" label="渠道编号" min-width="135" fixed="left" />
              <el-table-column prop="name" label="博主姓名/昵称" min-width="160" />
              <el-table-column label="合作类型" min-width="125"><template #default="{ row }"><span class="proto-pill" :class="channelTypeTone(row.channelType)">{{ channelTypeLabel(row.channelType) }}</span></template></el-table-column>
              <el-table-column label="推广渠道" min-width="120">
                <template #default="{ row }"><span class="proto-pill info">{{ platformLabel(row.platformCode) }}</span></template>
              </el-table-column>
              <el-table-column label="分佣比例" width="110" align="right">
                <template #default="{ row }">{{ row.commissionPercent === null ? '—' : `${row.commissionPercent}%` }}</template>
              </el-table-column>
              <el-table-column label="对接商务" min-width="120">
                <template #default="{ row }">{{ row.businessOwnerName || '未分配' }}</template>
              </el-table-column>
              <el-table-column prop="realName" label="真实姓名" min-width="120"><template #default="{ row }">{{ row.realName || '—' }}</template></el-table-column>
              <el-table-column prop="contactPhone" label="联系方式（脱敏）" min-width="150"><template #default="{ row }">{{ maskPhone(row.contactPhone, row.contactPhoneConfigured) }}</template></el-table-column>
              <el-table-column prop="idCardNumber" label="身份证号（脱敏）" min-width="190"><template #default="{ row }">{{ maskIdCard(row.idCardNumber, row.idCardConfigured) }}</template></el-table-column>
              <el-table-column label="结算方式" min-width="120"><template #default="{ row }">{{ settlementLabel(row.settlementMethod) }}</template></el-table-column>
              <el-table-column prop="cooperationStartDate" label="合作开始时间" min-width="135"><template #default="{ row }">{{ row.cooperationStartDate || '—' }}</template></el-table-column>
              <el-table-column prop="cooperationEndDate" label="合作结束时间" min-width="135"><template #default="{ row }">{{ row.cooperationEndDate || '—' }}</template></el-table-column>
              <el-table-column prop="paymentAccountName" label="收款账户信息" min-width="150"><template #default="{ row }">{{ row.paymentAccountName || (row.paymentAccountConfigured ? '已配置（已脱敏）' : '—') }}</template></el-table-column>
              <el-table-column prop="paymentAccountNo" label="收款账号（脱敏）" min-width="190"><template #default="{ row }">{{ maskBankAccount(row.paymentAccountNo, row.paymentAccountConfigured) }}</template></el-table-column>
              <el-table-column prop="paymentBankName" label="收款开户行" min-width="170"><template #default="{ row }">{{ row.paymentBankName || '—' }}</template></el-table-column>
              <el-table-column label="合作状态" width="110">
                <template #default="{ row }"><StatusPill :text="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
              </el-table-column>
              <el-table-column prop="remarks" label="备注" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">{{ row.remarks || '—' }}</template>
              </el-table-column>
              <el-table-column v-if="canWrite" label="操作" width="220" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.status !== 'ARCHIVED'" text :data-testid="`channel-edit-${row.id}`" @click="openEdit(row)">修改</el-button>
                  <el-button v-if="row.status !== 'ARCHIVED'" text :data-testid="`channel-finance-change-${row.id}`" @click="openFinanceChange(row)">财务变更</el-button>
                  <el-button v-if="row.status === 'ACTIVE'" text class="pause-action" :data-testid="`channel-pause-${row.id}`" @click="toggleStatus(row, 'INACTIVE')">暂停</el-button>
                  <el-button v-else-if="row.status === 'INACTIVE'" text type="primary" :data-testid="`channel-resume-${row.id}`" @click="toggleStatus(row, 'ACTIVE')">恢复</el-button>
                </template>
              </el-table-column>
              <template #empty>暂无渠道</template>
            </app-table>
          </div>
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="load"
            @size-change="load(1)"
          />
        </el-tab-pane>

        <el-tab-pane label="财务资料变更审批" name="financeChanges">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-select v-model="changeQuery.status" clearable placeholder="全部审批状态" class="status-filter" data-testid="channel-change-status-filter">
                <el-option label="待财务审批" value="PENDING_REVIEW" />
                <el-option label="已通过" value="APPROVED" />
                <el-option label="已驳回" value="REJECTED" />
              </el-select>
              <el-button type="primary" :loading="changeLoading" @click="loadChangeRequests(1)">刷新</el-button>
            </div>
            <span class="proto-note">财务审批需具备 channel:approve_finance 权限，且不可审批本人提交的申请</span>
          </div>
          <el-alert v-if="changeError" type="error" :title="changeError" :closable="false" class="attribution-alert" />
          <ChannelChangeRequestsPanel
            :requests="changeRequests"
            :loading="changeLoading"
            :can-approve-finance="canApproveFinance"
            :current-user-id="currentUserId"
            @review="handleReview"
          />
          <el-pagination
            v-if="changeTotal > changeQuery.size"
            v-model:current-page="changeQuery.page"
            v-model:page-size="changeQuery.size"
            :total="changeTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadChangeRequests"
            @size-change="loadChangeRequests(1)"
          />
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <ChannelFormDialog
      v-model="formVisible"
      :channel="editingChannel"
      @saved="handleSaved"
      @request-finance-change="openFinanceChange"
    />
    <ChannelFinanceChangeDialog
      v-model="financeDialogVisible"
      :channel="financeChannel"
      @submitted="handleFinanceSubmitted"
    />
  </div>
</template>

<style scoped>
.attribution-alert { margin-bottom: 18px; }
.keyword { width: min(280px, 100%); }
.status-filter { width: 145px; }
.pause-action { color: var(--el-color-danger) !important; }
@media (max-width: 760px) {
  .keyword, .status-filter { width: 100%; }
}
</style>
