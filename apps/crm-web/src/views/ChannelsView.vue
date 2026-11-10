<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Channel } from '../api/channel'
import { listChannels, updateChannel } from '../api/channel'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import StatusPill from './customer/StatusPill.vue'
import ChannelFormDialog from './channel/ChannelFormDialog.vue'
import { useDictionaries } from './customer/shared'
import './customer/customer-ui.css'

const auth = useAuthStore()
const dicts = useDictionaries()
const canWrite = computed(() => auth.hasPermission('channel:write'))
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

function maskPhone(value: string | null): string {
  if (!value) return '—'
  return value.length === 11 ? `${value.slice(0, 3)}****${value.slice(-4)}` : '****'
}

function maskIdCard(value: string | null): string {
  if (!value) return '—'
  return value.length >= 11 ? `${value.slice(0, 6)}${'*'.repeat(value.length - 10)}${value.slice(-4)}` : '****'
}

function maskBankAccount(value: string | null): string {
  if (!value) return '—'
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

const formVisible = ref(false)
const editingChannel = ref<Channel | null>(null)

function openCreate() {
  editingChannel.value = null
  formVisible.value = true
}

function openEdit(item: Channel) {
  editingChannel.value = item
  formVisible.value = true
}

async function handleSaved() {
  await load()
}

async function pause(item: Channel) {
  try {
    await ElMessageBox.confirm('暂停后不可用于新的渠道归因，历史订单快照不变。确认暂停？', '暂停合作', { type: 'warning' })
  } catch {
    return
  }
  try {
    await updateChannel(item.id, {
      name: item.name,
      channelType: item.channelType,
      platformCode: item.platformCode,
      commissionPercent: item.commissionPercent,
      businessOwnerUserId: item.businessOwnerUserId,
      realName: item.realName,
      contactPhone: item.contactPhone,
      idCardNumber: item.idCardNumber,
      settlementMethod: item.settlementMethod,
      cooperationStartDate: item.cooperationStartDate,
      cooperationEndDate: item.cooperationEndDate,
      paymentAccountName: item.paymentAccountName,
      paymentAccountNo: item.paymentAccountNo,
      paymentBankName: item.paymentBankName,
      status: 'INACTIVE',
      remarks: item.remarks,
      version: item.version,
    })
    ElMessage.success('渠道已暂停，历史归因保持不变')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
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
          <el-table-column prop="contactPhone" label="联系方式（脱敏）" min-width="150"><template #default="{ row }">{{ maskPhone(row.contactPhone) }}</template></el-table-column>
          <el-table-column prop="idCardNumber" label="身份证号（脱敏）" min-width="190"><template #default="{ row }">{{ maskIdCard(row.idCardNumber) }}</template></el-table-column>
          <el-table-column label="结算方式" min-width="120"><template #default="{ row }">{{ settlementLabel(row.settlementMethod) }}</template></el-table-column>
          <el-table-column prop="cooperationStartDate" label="合作开始时间" min-width="135"><template #default="{ row }">{{ row.cooperationStartDate || '—' }}</template></el-table-column>
          <el-table-column prop="cooperationEndDate" label="合作结束时间" min-width="135"><template #default="{ row }">{{ row.cooperationEndDate || '—' }}</template></el-table-column>
          <el-table-column prop="paymentAccountName" label="收款账户信息" min-width="150"><template #default="{ row }">{{ row.paymentAccountName || '—' }}</template></el-table-column>
          <el-table-column prop="paymentAccountNo" label="收款账号（脱敏）" min-width="190"><template #default="{ row }">{{ maskBankAccount(row.paymentAccountNo) }}</template></el-table-column>
          <el-table-column prop="paymentBankName" label="收款开户行" min-width="170"><template #default="{ row }">{{ row.paymentBankName || '—' }}</template></el-table-column>
          <el-table-column label="合作状态" width="110">
            <template #default="{ row }"><StatusPill :text="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
          </el-table-column>
          <el-table-column prop="remarks" label="备注" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remarks || '—' }}</template>
          </el-table-column>
          <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'ARCHIVED'" text @click="openEdit(row)">修改</el-button>
              <el-button v-if="row.status === 'ACTIVE'" text class="pause-action" @click="pause(row)">暂停</el-button>
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
    </PageCard>

    <ChannelFormDialog v-model="formVisible" :channel="editingChannel" @saved="handleSaved" />
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
