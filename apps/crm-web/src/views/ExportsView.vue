<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createExportJob,
  getExportJobDownload,
  listExportAccessLogs,
  listExportJobs,
} from '../api/exporting'
import type { ExportAccessLog, ExportJob, ExportType } from '../api/exporting'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatCard from '../components/prototype/StatCard.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

interface DatasetDefinition {
  type: ExportType
  title: string
  description: string
  fields: { code: string; label: string }[]
  defaults: string[]
}

const datasets: DatasetDefinition[] = [
  {
    type: 'CUSTOMER', title: '客户中心', description: '客户档案、归属与分层字段',
    fields: [
      { code: 'customerNo', label: '客户编号' }, { code: 'displayName', label: '客户称呼' },
      { code: 'nickname', label: '客户昵称' }, { code: 'remarkName', label: '备注名' },
      { code: 'mobile', label: '手机号（脱敏）' }, { code: 'cityCode', label: '城市' },
      { code: 'sourceCode', label: '客户来源' }, { code: 'channelCode', label: '渠道' },
      { code: 'customerStatus', label: '客户状态' }, { code: 'tierCode', label: '客户分层' },
      { code: 'intentLevel', label: '意向级别' }, { code: 'spendingLevel', label: '消费能力' },
      { code: 'birthday', label: '生日' }, { code: 'points', label: '积分' },
      { code: 'attentionProjects', label: '关注项目' }, { code: 'blockerNote', label: '卡点情况' },
      { code: 'remark', label: '备注' }, { code: 'ownerStaffId', label: '归属管家 ID' },
      { code: 'ownerName', label: '归属管家' }, { code: 'teamId', label: '团队 ID' },
      { code: 'firstAddedAt', label: '首次添加时间' }, { code: 'firstOrderAt', label: '首次下单时间' },
      { code: 'lastFollowupAt', label: '最后跟进时间' }, { code: 'createdAt', label: '创建时间' },
    ],
    defaults: ['customerNo', 'displayName', 'mobile', 'customerStatus', 'ownerStaffId', 'teamId', 'createdAt'],
  },
  {
    type: 'ORDER', title: '订单售卖', description: '订单类型、活动批次与整数分金额',
    fields: [
      { code: 'orderNo', label: '订单编号' }, { code: 'customerNo', label: '客户编号' },
      { code: 'customerName', label: '客户称呼' }, { code: 'soldByUserId', label: '成交管家 ID' },
      { code: 'ownerAtSaleId', label: '销售时归属管家 ID' }, { code: 'externalOrderNo', label: '外部订单号' },
      { code: 'sourceSystem', label: '订单来源' }, { code: 'orderTypeCode', label: '订单类型' },
      { code: 'campaignId', label: '活动 ID' }, { code: 'campaignBatchId', label: '批次 ID' },
      { code: 'status', label: '订单状态' }, { code: 'receiverType', label: '收款方类型' },
      { code: 'listAmountFen', label: '原价（分）' }, { code: 'discountAmountFen', label: '优惠（分）' },
      { code: 'payableFen', label: '应付（分）' }, { code: 'paidFen', label: '实付（分）' },
      { code: 'refundedFen', label: '已退款（分）' }, { code: 'orderedAt', label: '下单时间' },
      { code: 'paidAt', label: '支付完成时间' }, { code: 'createdAt', label: '创建时间' },
    ],
    defaults: ['orderNo', 'customerNo', 'status', 'payableFen', 'paidFen', 'refundedFen', 'orderedAt'],
  },
  {
    type: 'REDEMPTION', title: '核销记录', description: '机构、预约与正式核销状态',
    fields: [
      { code: 'redemptionId', label: '核销 ID' }, { code: 'appointmentId', label: '预约 ID' },
      { code: 'customerNo', label: '客户编号' }, { code: 'customerName', label: '客户称呼' },
      { code: 'institutionId', label: '机构 ID' }, { code: 'ownerStaffId', label: '归属管家 ID' },
      { code: 'teamId', label: '团队 ID' }, { code: 'serviceAt', label: '服务时间' },
      { code: 'status', label: '核销状态' }, { code: 'settlementStatus', label: '结算状态' },
      { code: 'postedBy', label: '确认操作员 ID' }, { code: 'postedAt', label: '确认时间' },
      { code: 'createdAt', label: '创建时间' },
    ],
    defaults: ['redemptionId', 'customerNo', 'institutionId', 'serviceAt', 'status', 'postedAt'],
  },
  {
    type: 'FOLLOWUP', title: '每日跟进', description: '沟通记录、归属与下次跟进',
    fields: [
      { code: 'followupId', label: '跟进 ID' }, { code: 'customerNo', label: '客户编号' },
      { code: 'customerName', label: '客户称呼' }, { code: 'ownerStaffId', label: '管家 ID' },
      { code: 'ownerName', label: '管家' }, { code: 'channelCode', label: '沟通方式' },
      { code: 'contactAt', label: '沟通时间' }, { code: 'summary', label: '沟通摘要' },
      { code: 'intentProject', label: '意向项目' }, { code: 'estimatedAmountFen', label: '预计金额（分）' },
      { code: 'nextFollowupAt', label: '下次跟进时间' }, { code: 'blockerNote', label: '卡点' },
      { code: 'createdBy', label: '登记人 ID' }, { code: 'createdAt', label: '创建时间' },
    ],
    defaults: ['followupId', 'customerNo', 'ownerStaffId', 'channelCode', 'contactAt', 'nextFollowupAt'],
  },
]

const roleBaselines = [
  { role: '运营管理员', scope: '全局', mobileRule: '按权限显示', copyRule: '允许', exportRule: '允许（需授权）', exportScope: '授权数据集' },
  { role: '管家主管', scope: '本组客户', mobileRule: '可配置', copyRule: '可配置', exportRule: '需授权', exportScope: '本组范围' },
  { role: '普通管家', scope: '本人客户', mobileRule: '业务所需', copyRule: '默认禁止批量复制', exportRule: '默认禁止', exportScope: '—' },
  { role: '商务', scope: '机构 / 渠道', mobileRule: '不开放客户全量', copyRule: '限制', exportRule: '限制', exportScope: '机构 / 渠道' },
  { role: '财务', scope: '订单 / 退款 / 结算', mobileRule: '按需脱敏', copyRule: '限制', exportRule: '允许授权', exportScope: '财务字段' },
  { role: '合作机构', scope: '本机构履约', mobileRule: '脱敏', copyRule: '禁止', exportRule: '禁止', exportScope: '—' },
]

const auth = useAuthStore()
const canRead = computed(() => auth.hasPermission('export:read'))
const canCreate = computed(() => auth.hasPermission('export:create'))
const canAudit = canRead

const jobs = ref<ExportJob[]>([])
const jobsTotal = ref(0)
const jobsLoading = ref(false)
const jobsLoaded = ref(false)
const jobsError = ref('')
const jobQuery = reactive({ page: 1, size: 20, exportType: '' as ExportType | '', status: '' })
let jobsSequence = 0
let pollTimer: ReturnType<typeof setTimeout> | undefined
let destroyed = false

const logs = ref<ExportAccessLog[]>([])
const logsTotal = ref(0)
const logsLoading = ref(false)
const logsError = ref('')
const logQuery = reactive({ page: 1, size: 20 })
let logsSequence = 0

const runningOnPage = computed(() => jobs.value.filter((item) => ['PENDING', 'RUNNING'].includes(item.status)).length)
const selectedDataset = computed(() => datasets.find((item) => item.type === form.exportType) ?? datasets[0]!)
const jobById = computed(() => new Map(jobs.value.map((item) => [item.id, item])))

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

function datasetLabel(type: ExportType): string {
  return datasets.find((item) => item.type === type)?.title ?? type
}

function jobStatusLabel(status: string): string {
  return ({ PENDING: '等待执行', RUNNING: '正在生成', SUCCEEDED: '已完成', FAILED: '执行失败' } as Record<string, string>)[status] ?? status
}

function jobStatusTone(status: string): string {
  return ({ PENDING: 'warning', RUNNING: 'info', SUCCEEDED: 'success', FAILED: 'danger' } as Record<string, string>)[status] ?? 'dark'
}

function formatScope(scope: Record<string, unknown>): string {
  try { return JSON.stringify(scope) } catch { return '范围详情无法展示' }
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
  if (destroyed || !canRead.value || !jobs.value.some((item) => ['PENDING', 'RUNNING'].includes(item.status))) return
  pollTimer = setTimeout(() => { void loadJobs(jobQuery.page, true) }, 4000)
}

async function loadJobs(page = jobQuery.page, silent = false) {
  if (!canRead.value) return
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
  const sequence = ++jobsSequence
  if (!silent) jobsLoading.value = true
  jobsError.value = ''
  try {
    const result = await listExportJobs({ page, size: jobQuery.size, exportType: jobQuery.exportType, status: jobQuery.status })
    if (destroyed || sequence !== jobsSequence) return
    jobs.value = result.items
    jobsTotal.value = result.total
    jobQuery.page = result.page
    jobQuery.size = result.size
    jobsLoaded.value = true
    schedulePoll()
  } catch (error) {
    if (destroyed || sequence !== jobsSequence) return
    if (!silent) {
      jobs.value = []
      jobsTotal.value = 0
    }
    jobsLoaded.value = true
    jobsError.value = errorText(error, '导出任务加载失败，请重试')
  } finally {
    if (sequence === jobsSequence) jobsLoading.value = false
  }
}

async function loadLogs(page = logQuery.page) {
  if (!canAudit.value) return
  const sequence = ++logsSequence
  logsLoading.value = true
  logsError.value = ''
  try {
    const result = await listExportAccessLogs({ page, size: logQuery.size })
    if (destroyed || sequence !== logsSequence) return
    logs.value = result.items
    logsTotal.value = result.total
    logQuery.page = result.page
    logQuery.size = result.size
  } catch (error) {
    if (destroyed || sequence !== logsSequence) return
    logs.value = []
    logsTotal.value = 0
    logsError.value = errorText(error, '导出审计加载失败，请重试')
  } finally {
    if (sequence === logsSequence) logsLoading.value = false
  }
}

const requestVisible = ref(false)
const submitting = ref(false)
const requestError = ref('')
const form = reactive({
  exportType: 'CUSTOMER' as ExportType,
  columns: [] as string[],
  reason: '',
  dateRange: [] as string[],
})
let requestKey = ''

watch(form, () => { if (!submitting.value) requestKey = crypto.randomUUID() }, { deep: true })

function selectDataset(type: ExportType) {
  if (!canCreate.value) return
  const dataset = datasets.find((item) => item.type === type)!
  form.exportType = type
  form.columns = [...dataset.defaults]
  form.reason = ''
  form.dateRange = []
  requestError.value = ''
  requestKey = crypto.randomUUID()
  requestVisible.value = true
}

async function submitExport() {
  if (!canCreate.value) return
  if (!form.columns.length || form.reason.trim().length < 2) {
    requestError.value = '请至少选择一个导出字段，并填写 2 至 500 字的具体业务理由'
    return
  }
  const filters: { from?: string; to?: string } = {}
  if (form.dateRange.length === 2) {
    const from = new Date(`${form.dateRange[0]}T00:00:00+08:00`)
    const to = new Date(`${form.dateRange[1]}T23:59:59.999+08:00`)
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
      requestError.value = '导出日期无效，请重新选择'
      return
    }
    filters.from = from.toISOString()
    filters.to = to.toISOString()
  }
  submitting.value = true
  requestError.value = ''
  try {
    await createExportJob({
      exportType: form.exportType,
      filters,
      columns: [...form.columns],
      reason: form.reason.trim(),
    }, requestKey)
    if (destroyed) return
    requestVisible.value = false
    ElMessage.success('导出任务已提交，文件将由服务端异步生成')
    if (canRead.value) await loadJobs(1)
    if (canAudit.value) await loadLogs(1)
  } catch (error) {
    if (!destroyed) requestError.value = `${errorText(error, '导出任务提交失败')}；未修改表单时重试会沿用同一幂等键，也可先刷新任务列表核对`
  } finally {
    submitting.value = false
  }
}

function canDownload(item: ExportJob): boolean {
  if (item.status !== 'SUCCEEDED' || !item.fileId || !item.expiresAt) return false
  const expires = new Date(item.expiresAt).getTime()
  return Number.isFinite(expires) && expires > Date.now()
}

const downloadingId = ref<number | null>(null)

function safeOpen(url: string) {
  const target = new URL(url, window.location.origin)
  if (target.protocol !== 'https:' && target.protocol !== 'http:') throw new Error('服务端返回了不安全的下载地址')
  window.open(target.toString(), '_blank', 'noopener,noreferrer')
}

async function download(item: ExportJob) {
  if (!canDownload(item)) return
  downloadingId.value = item.id
  jobsError.value = ''
  try {
    const result = await getExportJobDownload(item.id)
    if (destroyed) return
    safeOpen(result.downloadUrl)
    if (canAudit.value) await loadLogs(logQuery.page)
  } catch (error) {
    if (!destroyed) jobsError.value = errorText(error, '下载地址获取失败；服务端可能已拒绝过期或未 CLEAN 文件')
  } finally {
    downloadingId.value = null
  }
}

onMounted(() => {
  if (canRead.value) void loadJobs(1)
  if (canAudit.value) void loadLogs(1)
})

onBeforeUnmount(() => {
  destroyed = true
  jobsSequence++
  logsSequence++
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<template>
  <div class="proto-page exports-page">
    <PageHeader title="数据导出中心" description="按角色、数据范围、字段与理由创建服务端异步任务；浏览器不拼接或导出全量数据" />

    <PageCard v-if="!canRead && !canCreate">
      <div class="proto-empty export-permission-empty" data-testid="export-permission-empty">
        <strong>没有导出权限</strong>
        <span>当前账号不能创建任务或查看导出记录。权限、字段与数据范围需由管理员授权并由后端执行。</span>
      </div>
    </PageCard>

    <template v-else>
      <div class="proto-stat-grid">
        <StatCard label="导出任务" :value="canRead ? `${jobsTotal} 个` : '—'" sub="当前筛选结果 · 非本月承诺指标" icon="download" />
        <StatCard label="可请求数据集" value="4 类" sub="客户 / 订单 / 核销 / 跟进" icon="file" />
        <StatCard label="进行中" :value="canRead ? `${runningOnPage} 个` : '—'" sub="当前页等待执行与正在生成" icon="clock" />
        <StatCard label="审计记录" :value="canAudit ? `${logsTotal} 条` : '—'" sub="仅统计服务端返回的当前日志查询" icon="shield" />
      </div>

      <div class="export-overview">
        <PageCard title="可执行导出" subtitle="先选择数据集和字段，再提交理由；实际权限与范围由服务端在创建及执行时校验">
          <div class="export-dataset-area">
            <div class="export-dataset-grid">
              <button
                v-for="(dataset, index) in datasets"
                :key="dataset.type"
                type="button"
                class="dataset-button"
                :class="{ selected: requestVisible && form.exportType === dataset.type }"
                :disabled="!canCreate"
                :data-testid="`export-dataset-${dataset.type}`"
                @click="selectDataset(dataset.type)"
              >
                <span class="dataset-index">0{{ index + 1 }}</span>
                <strong>{{ dataset.title }}</strong>
                <small>{{ dataset.description }}</small>
                <span class="dataset-action">{{ canCreate ? '选择字段并申请' : '未授权创建' }}</span>
              </button>
            </div>
            <div v-if="!canCreate" class="export-create-empty">当前账号没有创建导出任务权限；已有记录与审计仍按各自权限展示。</div>
            <form v-if="requestVisible && canCreate" class="export-request-form" @submit.prevent="submitExport">
              <div class="request-heading">
                <div><strong>{{ selectedDataset.title }} · 字段与理由</strong><span>敏感字段仅提供明确的脱敏选项，服务端仍可进一步拒绝。</span></div>
                <el-button text :disabled="submitting" @click="requestVisible = false">收起</el-button>
              </div>
              <el-alert v-if="requestError" :title="requestError" type="error" :closable="false" class="request-error" data-testid="export-request-error" />
              <div class="field-group-label">导出字段（已选 {{ form.columns.length }} 项）</div>
              <el-checkbox-group v-model="form.columns" class="export-field-grid">
                <el-checkbox v-for="field in selectedDataset.fields" :key="field.code" :value="field.code" :data-testid="`export-field-${field.code}`">
                  <span>{{ field.label }}</span><small>{{ field.code }}</small>
                </el-checkbox>
              </el-checkbox-group>
              <div class="export-request-meta">
                <label>
                  <span>业务日期（可选，含起止日）</span>
                  <el-date-picker v-model="form.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
                </label>
                <label class="reason-field">
                  <span>导出业务理由（必填）</span>
                  <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明真实业务用途，不填写“测试”或空泛理由" data-testid="export-reason" />
                </label>
              </div>
              <div class="request-submit-row">
                <span>提交仅创建任务，不会立即下载；超时重试沿用同一幂等键，修改表单后生成新键。</span>
                <el-button native-type="submit" type="primary" :loading="submitting" data-testid="export-submit">提交导出任务</el-button>
              </div>
            </form>
          </div>
        </PageCard>

        <PageCard title="导出权限原则" subtitle="界面隐藏不等于授权，所有边界均由后端再次执行" padded>
          <ul class="permission-principles">
            <li><strong>行范围</strong><span>服务端按当前租户、负责人、团队或机构范围生成，客户端不能传入放大范围。</span></li>
            <li><strong>列范围</strong><span>只提交必要字段；手机号默认脱敏，未授权字段即使勾选也会被拒绝。</span></li>
            <li><strong>异步复核</strong><span>创建与执行阶段都校验权限，任务成功不代表文件永久可下载。</span></li>
            <li><strong>私有文件</strong><span>导出文件须为 EXPORT 分类、私有且 ClamAV CLEAN，仅通过短期签名地址下载。</span></li>
            <li><strong>只追加审计</strong><span>申请与下载结果由服务端记录，页面不提供修改或删除审计入口。</span></li>
          </ul>
        </PageCard>
      </div>

      <PageCard v-if="canRead" title="导出任务" subtitle="状态、字段、服务端范围与错误均来自任务接口">
        <div class="proto-toolbar">
          <div class="proto-toolbar-left">
            <el-select v-model="jobQuery.exportType" clearable placeholder="全部数据集" class="job-filter">
              <el-option v-for="item in datasets" :key="item.type" :label="item.title" :value="item.type" />
            </el-select>
            <el-select v-model="jobQuery.status" clearable placeholder="全部状态" class="job-filter">
              <el-option label="等待执行" value="PENDING" /><el-option label="正在生成" value="RUNNING" />
              <el-option label="已完成" value="SUCCEEDED" /><el-option label="执行失败" value="FAILED" />
            </el-select>
            <el-button type="primary" :loading="jobsLoading" @click="loadJobs(1)">查询</el-button>
            <el-button data-testid="export-reload" @click="loadJobs()">刷新</el-button>
          </div>
          <span class="proto-note">进行中任务每 4 秒查询一次，离开页面自动停止</span>
        </div>
        <el-alert v-if="jobsError" :title="jobsError" type="error" :closable="false" class="export-alert" />
        <div class="proto-table-wrap">
          <app-table v-loading="jobsLoading" :data="jobs">
            <el-table-column label="任务 / 数据集" min-width="150" fixed="left">
              <template #default="{ row }"><strong>{{ row.jobNo }}</strong><div class="proto-subcell">{{ datasetLabel(row.exportType) }} · #{{ row.id }}</div></template>
            </el-table-column>
            <el-table-column label="字段" min-width="200">
              <template #default="{ row }"><div class="column-list"><span v-for="column in row.columns" :key="column">{{ column }}</span></div></template>
            </el-table-column>
            <el-table-column label="服务端数据范围" min-width="180">
              <template #default="{ row }"><code class="scope-text">{{ formatScope(row.scope) }}</code></template>
            </el-table-column>
            <el-table-column prop="reason" label="导出理由" min-width="190" show-overflow-tooltip />
            <el-table-column label="状态" width="120">
              <template #default="{ row }"><span class="proto-pill" :class="jobStatusTone(row.status)">{{ jobStatusLabel(row.status) }}</span><div v-if="row.lastError" class="job-error">{{ row.lastError }}</div></template>
            </el-table-column>
            <el-table-column label="条数" width="85" align="right"><template #default="{ row }">{{ row.rowCount ?? '—' }}</template></el-table-column>
            <el-table-column label="创建 / 过期" min-width="180">
              <template #default="{ row }"><span>{{ formatDateTime(row.createdAt) }}</span><div class="proto-subcell">过期：{{ formatDateTime(row.expiresAt) }}</div></template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canDownload(row)" text :loading="downloadingId === row.id" :data-testid="`export-download-${row.id}`" @click="download(row)">私有下载</el-button>
                <span v-else class="proto-note">{{ row.status === 'SUCCEEDED' ? '不可下载或已过期' : '待任务完成' }}</span>
              </template>
            </el-table-column>
            <template #empty><span>{{ jobsLoaded && !jobsError ? '暂无符合条件的导出任务' : '正在读取任务状态' }}</span></template>
          </app-table>
        </div>
        <el-pagination
          v-if="jobsTotal > jobQuery.size"
          v-model:current-page="jobQuery.page"
          v-model:page-size="jobQuery.size"
          :total="jobsTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadJobs"
          @size-change="loadJobs(1)"
        />
      </PageCard>
      <PageCard v-else>
        <div class="proto-empty">没有导出任务查看权限；创建成功也不代表可查看他人任务或扩大数据范围。</div>
      </PageCard>

      <PageCard title="角色权限基线" subtitle="依据项目权限规范展示原则，不代表当前账号已获授权；实际结果以服务端为准">
        <div class="proto-table-wrap">
          <app-table :data="roleBaselines">
            <el-table-column prop="role" label="角色" width="120" />
            <el-table-column prop="scope" label="可查看范围" min-width="145" />
            <el-table-column prop="mobileRule" label="手机号显示" min-width="145" />
            <el-table-column prop="copyRule" label="复制权限" min-width="150" />
            <el-table-column prop="exportRule" label="批量导出" min-width="140" />
            <el-table-column prop="exportScope" label="导出范围" min-width="130" />
          </app-table>
        </div>
      </PageCard>

      <PageCard v-if="canAudit" title="导出审计日志" subtitle="谁申请或下载了哪个任务、结果与理由；日志只读，不展示敏感导出内容">
        <el-alert v-if="logsError" :title="logsError" type="error" :closable="false" class="export-alert" />
        <div class="proto-table-wrap">
          <app-table v-loading="logsLoading" :data="logs">
            <el-table-column label="时间" min-width="160"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="操作人" width="105"><template #default="{ row }">用户 #{{ row.actorUserId }}</template></el-table-column>
            <el-table-column label="任务 / 数据集" min-width="160">
              <template #default="{ row }"><strong>{{ row.jobId ? (jobById.get(row.jobId)?.jobNo ?? `任务 #${row.jobId}`) : '未关联任务' }}</strong><div v-if="row.jobId && jobById.get(row.jobId)" class="proto-subcell">{{ datasetLabel(jobById.get(row.jobId)!.exportType) }}</div></template>
            </el-table-column>
            <el-table-column label="动作" width="90"><template #default="{ row }">{{ row.action === 'REQUEST' ? '申请' : '下载' }}</template></el-table-column>
            <el-table-column label="结果" width="100"><template #default="{ row }"><span class="proto-pill" :class="row.result === 'ALLOWED' ? 'success' : 'danger'">{{ row.result === 'ALLOWED' ? '已允许' : '已拒绝' }}</span></template></el-table-column>
            <el-table-column prop="reason" label="理由" min-width="200"><template #default="{ row }">{{ row.reason || '—' }}</template></el-table-column>
            <el-table-column label="拒绝码" min-width="130"><template #default="{ row }">{{ row.denialCode || '—' }}</template></el-table-column>
            <template #empty><span>暂无服务端导出审计记录</span></template>
          </app-table>
        </div>
        <el-pagination
          v-if="logsTotal > logQuery.size"
          v-model:current-page="logQuery.page"
          v-model:page-size="logQuery.size"
          :total="logsTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadLogs"
          @size-change="loadLogs(1)"
        />
      </PageCard>
      <PageCard v-else>
        <div class="proto-empty export-audit-empty" data-testid="export-audit-empty">
          <strong>没有导出审计查看权限</strong>
          <span>不会请求审计接口；任务可见性也不会自动授予日志查看权。</span>
        </div>
      </PageCard>
    </template>
  </div>
</template>

<style scoped>
.export-overview { display: grid; grid-template-columns: minmax(0,1.55fr) minmax(310px,1fr); gap: 18px; margin-bottom: 18px; }
.export-overview > :deep(.proto-card + .proto-card) { margin-top: 0; }
.export-dataset-area { padding: 18px 22px 22px; }
.export-dataset-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 11px; }
.dataset-button { display: grid; min-width: 0; padding: 17px; border: 1px solid #e5ecef; border-radius: 11px; color: #2a4554; background: #fbfcfd; font: inherit; text-align: left; cursor: pointer; transition: border-color .2s ease, background-color .2s ease, transform .2s ease; }
.dataset-button:hover:not(:disabled), .dataset-button.selected { border-color: #9dcfc6; background: #f0f9f7; transform: translateY(-1px); }
.dataset-button:focus-visible { outline: 3px solid #bce3dc; outline-offset: 2px; }
.dataset-button:disabled { color: #8a9ba5; cursor: not-allowed; opacity: .72; }
.dataset-index { margin-bottom: 12px; color: #139487; font-size: 10px; font-weight: 800; letter-spacing: .12em; }
.dataset-button strong { font-size: 14px; }
.dataset-button small { min-height: 34px; margin-top: 6px; color: #80959f; font-size: 11px; line-height: 1.6; }
.dataset-action { margin-top: 12px; color: #0f8e83; font-size: 10px; font-weight: 700; }
.export-create-empty { margin-top: 14px; padding: 13px; border-radius: 9px; color: #8b734f; background: #fff8e9; font-size: 11px; line-height: 1.7; }
.export-request-form { margin-top: 20px; padding-top: 20px; border-top: 1px solid #e7edef; }
.request-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.request-heading > div { display: grid; gap: 5px; }
.request-heading strong { font-size: 14px; }
.request-heading span { color: #879aa5; font-size: 11px; line-height: 1.7; }
.request-error { margin-top: 14px; }
.field-group-label { margin: 19px 0 10px; color: #607986; font-size: 11px; font-weight: 700; }
.export-field-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 4px 14px; }
.export-field-grid :deep(.el-checkbox) { height: auto; min-height: 36px; margin-right: 0; padding: 5px 0; white-space: normal; }
.export-field-grid :deep(.el-checkbox__label) { display: flex; min-width: 0; align-items: baseline; gap: 7px; line-height: 1.5; }
.export-field-grid small { color: #9aabb5; font-size: 9px; }
.export-request-meta { display: grid; grid-template-columns: minmax(220px,.8fr) minmax(260px,1.2fr); gap: 16px; margin-top: 17px; }
.export-request-meta label { display: grid; align-content: start; gap: 8px; color: #607986; font-size: 11px; font-weight: 650; }
.export-request-meta :deep(.el-date-editor) { width: 100%; }
.request-submit-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 18px; }
.request-submit-row > span { max-width: 480px; color: #8a9ca6; font-size: 10px; line-height: 1.7; }
.permission-principles { margin: 20px 0 0; padding: 0; list-style: none; }
.permission-principles li { display: grid; grid-template-columns: 64px 1fr; gap: 12px; padding: 13px 0; border-top: 1px solid #edf2f4; font-size: 11px; line-height: 1.75; }
.permission-principles strong { color: #335645; }
.permission-principles span { color: #708892; }
.job-filter { width: 150px; }
.export-alert { margin: 15px 20px 0; width: auto; }
.column-list { display: flex; gap: 4px; flex-wrap: wrap; }
.column-list span { padding: 2px 5px; border-radius: 4px; color: #5f7783; background: #f0f4f6; font-size: 9px; }
.scope-text { display: block; max-width: 260px; overflow: hidden; color: #4e6c77; font-family: inherit; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.job-error { max-width: 160px; margin-top: 5px; color: #bd5360; font-size: 9px; line-height: 1.5; white-space: normal; }
.export-permission-empty, .export-audit-empty { display: grid; gap: 8px; }
.exports-page > :deep(.proto-card + .proto-card) { margin-top: 18px; }
@media (max-width: 1180px) {
  .export-overview { grid-template-columns: 1fr; }
  .export-request-meta { grid-template-columns: 1fr; }
}
@media (max-width: 680px) {
  .export-dataset-area { padding: 15px; }
  .export-dataset-grid, .export-field-grid { grid-template-columns: 1fr; }
  .dataset-button small { min-height: 0; }
  .request-heading, .request-submit-row { align-items: stretch; flex-direction: column; }
  .request-submit-row .el-button { width: 100%; }
  .job-filter { width: 100%; }
}
@media (prefers-reduced-motion: reduce) { .dataset-button { transition: none; } .dataset-button:hover:not(:disabled), .dataset-button.selected { transform: none; } }
</style>
