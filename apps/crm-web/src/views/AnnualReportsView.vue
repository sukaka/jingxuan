<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchGenerateAnnualReports,
  createAnnualReport,
  disableAnnualReport,
  getAnnualReportSummary,
  getYouzanAnnualSnapshotSummary,
  listAnnualReportSessions,
  listAnnualReports,
  listYouzanAnnualProfiles,
  syncYouzanAnnualSnapshots,
} from '../api/annualReport'
import type {
  AnnualGenderHint,
  AnnualReportItem,
  AnnualReportSession,
  AnnualReportSummary,
  AnnualToneCode,
  YouzanAnnualProfileItem,
  YouzanAnnualSnapshotSummary,
} from '../api/annualReport'
import { listCustomers } from '../api/customer'
import type { CustomerListItem } from '../api/customer'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import { shanghaiDateTimeMinute } from '../utils/date'
import { fenToYuan } from '../utils/money'
import { createRequestId } from '../utils/requestId'

const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('customer:write'))
const canManageYouzan = computed(() => auth.hasPermission('integration:youzan:manage'))

const currentYear = new Date().getFullYear()
const yearOptions = [currentYear, currentYear - 1, currentYear - 2]

const filters = reactive<{
  reportYear: number
  status: '' | 'ACTIVE' | 'DISABLED'
  page: number
  size: number
}>({
  reportYear: currentYear,
  status: '',
  page: 1,
  size: 20,
})

const loading = ref(false)
const items = ref<AnnualReportItem[]>([])
const total = ref(0)
const summary = ref<AnnualReportSummary | null>(null)

// 单客生成弹窗
const createDialogOpen = ref(false)
const creating = ref(false)
const customerOptions = ref<CustomerListItem[]>([])
const customerLoading = ref(false)
const createForm = reactive<{
  customerId: number | undefined
  reportYear: number
  toneCode: AnnualToneCode
  genderHint: AnnualGenderHint
  customButlerNote: string
  disablePreviousActive: boolean
}>({
  customerId: undefined,
  reportYear: currentYear,
  toneCode: 'AUTO',
  genderHint: 'UNKNOWN',
  customButlerNote: '',
  disablePreviousActive: true,
})

// 批量生成弹窗
const batchDialogOpen = ref(false)
const batchSubmitting = ref(false)
const batchForm = reactive<{
  customerIds: number[]
  reportYear: number
  toneCode: AnnualToneCode
  disablePreviousActive: boolean
}>({
  customerIds: [],
  reportYear: currentYear,
  toneCode: 'AUTO',
  disablePreviousActive: true,
})

// 访问会话明细抽屉
const sessionsDrawerOpen = ref(false)
const sessionsLoading = ref(false)
const activeReport = ref<AnnualReportItem | null>(null)
const sessions = ref<AnnualReportSession[]>([])

// V60: 有赞历史订单只读快照与统一免密入口
const yzSummary = ref<YouzanAnnualSnapshotSummary | null>(null)
const yzSyncDialogOpen = ref(false)
const yzSyncing = ref(false)
const yzSyncForm = reactive({
  shopId: '',
  startDate: `${currentYear}-01-01`,
  endDate: `${currentYear}-12-31`,
  enrichBuyerProfile: true,
})
const yzProfilesDrawerOpen = ref(false)
const yzProfilesLoading = ref(false)
const yzProfiles = ref<YouzanAnnualProfileItem[]>([])
const yzProfilesTotal = ref(0)
const yzProfilesKeyword = ref('')
const yzProfilesPage = ref(1)

const TONE_LABELS: Record<string, string> = {
  AUTO: '自动推断（按画像）',
  POETIC: '诗意松弛风',
  CLINICAL: '清醒专业风',
  MINIMAL: '极简高效风',
}

const openRateText = computed(() => {
  if (!summary.value || summary.value.totalReports <= 0) {
    return '0%'
  }
  return `${Math.round((summary.value.openedReports / summary.value.totalReports) * 100)}%`
})

const completionRateText = computed(() => {
  if (!summary.value || summary.value.openedReports <= 0) {
    return '0%'
  }
  return `${Math.round((summary.value.completedReports / summary.value.openedReports) * 100)}%`
})

function formatDurationMs(ms: number): string {
  const sec = Math.max(0, Math.round(ms / 1000))
  if (sec < 60) {
    return `${sec} 秒`
  }
  const min = Math.floor(sec / 60)
  const rem = sec % 60
  return `${min} 分 ${rem} 秒`
}

function resolvePublicHref(row: AnnualReportItem): string {
  if (row.link && /^https?:\/\//i.test(row.link)) {
    return row.link
  }
  return `${window.location.origin}/h5/annual-reports/${row.shareKey}`
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const [pageRes, summaryRes, yzRes] = await Promise.all([
      listAnnualReports({
        reportYear: filters.reportYear,
        status: filters.status || undefined,
        page: filters.page,
        size: filters.size,
      }),
      getAnnualReportSummary(filters.reportYear),
      getYouzanAnnualSnapshotSummary().catch(() => null),
    ])
    items.value = pageRes.items
    total.value = pageRes.total
    summary.value = summaryRes
    yzSummary.value = yzRes
  } catch (err) {
    ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '加载年终报告数据失败')
  } finally {
    loading.value = false
  }
}

const unifiedEntryHref = computed(() => {
  const base = yzSummary.value?.unifiedEntryUrl && /^https?:\/\//i.test(yzSummary.value.unifiedEntryUrl)
    ? yzSummary.value.unifiedEntryUrl
    : `${window.location.origin}/h5/annual-reports/entry`
  return `${base}?year=${filters.reportYear}`
})

async function copyUnifiedEntryUrl(): Promise<void> {
  try {
    await navigator.clipboard.writeText(unifiedEntryHref.value)
    ElMessage.success('已复制统一免密入口链接（适用于公众号/朋友圈/有赞微商城，转发不泄密）')
  } catch {
    ElMessage.info(`请手动复制链接：${unifiedEntryHref.value}`)
  }
}

function openYzSyncDialog(): void {
  yzSyncForm.startDate = `${filters.reportYear}-01-01`
  yzSyncForm.endDate = `${filters.reportYear}-12-31`
  yzSyncDialogOpen.value = true
}

async function handleYzSync(): Promise<void> {
  if (!yzSyncForm.shopId.trim()) {
    ElMessage.warning('请填写有赞店铺 ID（kdt_id）')
    return
  }
  yzSyncing.value = true
  try {
    const res = await syncYouzanAnnualSnapshots(
      {
        shopId: yzSyncForm.shopId.trim(),
        startDate: yzSyncForm.startDate,
        endDate: yzSyncForm.endDate,
        enrichBuyerProfile: yzSyncForm.enrichBuyerProfile,
      },
      createRequestId(),
    )
    const warnSuffix = res.warnings.length ? `（提示：${res.warnings[0]}）` : ''
    ElMessage.success(
      `拉取完成：只读实付订单 ${res.paidOrdersSynced} 笔、买家 ${res.buyersSynced} 位、含微信 OpenID ${res.openIdBoundCount} 位${warnSuffix}`,
    )
    yzSyncDialogOpen.value = false
    await loadData()
  } catch (err) {
    ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '拉取有赞历史快照失败')
  } finally {
    yzSyncing.value = false
  }
}

async function openYzProfilesDrawer(): Promise<void> {
  yzProfilesDrawerOpen.value = true
  yzProfilesPage.value = 1
  await loadYzProfiles()
}

async function loadYzProfiles(): Promise<void> {
  yzProfilesLoading.value = true
  try {
    const res = await listYouzanAnnualProfiles({
      keyword: yzProfilesKeyword.value.trim() || undefined,
      page: yzProfilesPage.value,
      size: 20,
    })
    yzProfiles.value = res.items
    yzProfilesTotal.value = res.total
  } catch (err) {
    yzProfiles.value = []
    ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '读取有赞买家镜像失败')
  } finally {
    yzProfilesLoading.value = false
  }
}

async function searchCustomers(keyword = ''): Promise<void> {
  customerLoading.value = true
  try {
    const res = await listCustomers({ page: 1, size: 50, keyword: keyword.trim() || undefined })
    customerOptions.value = res.items
  } catch {
    customerOptions.value = []
  } finally {
    customerLoading.value = false
  }
}

function openCreateDialog(): void {
  createForm.customerId = undefined
  createForm.reportYear = filters.reportYear
  createForm.toneCode = 'AUTO'
  createForm.genderHint = 'UNKNOWN'
  createForm.customButlerNote = ''
  createForm.disablePreviousActive = true
  createDialogOpen.value = true
  void searchCustomers('')
}

function openBatchDialog(): void {
  batchForm.customerIds = []
  batchForm.reportYear = filters.reportYear
  batchForm.toneCode = 'AUTO'
  batchForm.disablePreviousActive = true
  batchDialogOpen.value = true
  void searchCustomers('')
}

async function handleCreate(): Promise<void> {
  if (!createForm.customerId) {
    ElMessage.warning('请先选择一位客户')
    return
  }
  creating.value = true
  try {
    const created = await createAnnualReport(
      {
        customerId: createForm.customerId,
        reportYear: createForm.reportYear,
        toneCode: createForm.toneCode,
        genderHint: createForm.genderHint,
        customButlerNote: createForm.customButlerNote.trim() || undefined,
        disablePreviousActive: createForm.disablePreviousActive,
      },
      createRequestId(),
    )
    ElMessage.success(`已为 ${created.displayName} 生成 ${created.reportYear} 年终 H5 链接`)
    createDialogOpen.value = false
    await loadData()
  } catch (err) {
    ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '生成年度报告失败')
  } finally {
    creating.value = false
  }
}

async function handleBatchGenerate(): Promise<void> {
  if (!batchForm.customerIds.length) {
    ElMessage.warning('请至少选择一位客户')
    return
  }
  batchSubmitting.value = true
  try {
    const res = await batchGenerateAnnualReports(
      {
        customerIds: batchForm.customerIds,
        reportYear: batchForm.reportYear,
        toneCode: batchForm.toneCode,
        disablePreviousActive: batchForm.disablePreviousActive,
      },
      createRequestId(),
    )
    ElMessage.success(`批量生成完成：成功 ${res.createdCount} 份，跳过 ${res.skippedCount} 份`)
    batchDialogOpen.value = false
    await loadData()
  } catch (err) {
    ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '批量生成失败')
  } finally {
    batchSubmitting.value = false
  }
}

async function copyShareLink(row: AnnualReportItem): Promise<void> {
  const url = resolvePublicHref(row)
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('已复制客户专属 H5 分享链接')
  } catch {
    ElMessage.info(`请手动复制链接：${url}`)
  }
}

function previewH5(row: AnnualReportItem): void {
  window.open(resolvePublicHref(row), '_blank', 'noopener,noreferrer')
}

async function openSessions(row: AnnualReportItem): Promise<void> {
  activeReport.value = row
  sessionsDrawerOpen.value = true
  sessionsLoading.value = true
  try {
    sessions.value = await listAnnualReportSessions(row.id, 50)
  } catch (err) {
    sessions.value = []
    ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '读取访问明细失败')
  } finally {
    sessionsLoading.value = false
  }
}

async function handleDisable(row: AnnualReportItem): Promise<void> {
  try {
    const promptRes = await ElMessageBox.prompt('停用后该公开 H5 链接将立即失效（历史访问统计保留），请输入停用理由：', '停用年度报告链接', {
      confirmButtonText: '确认停用',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：已生成新版快照或客户要求撤回',
      inputValidator: (v) => (v && v.trim().length > 0 ? true : '停用理由不能为空'),
    })
    await disableAnnualReport(row.id, { version: row.version, reason: promptRes.value.trim() }, createRequestId())
    ElMessage.success('已停用该年度报告链接')
    await loadData()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') {
      ElMessage.error(err instanceof ApiError || err instanceof Error ? err.message : '停用失败')
    }
  }
}

onMounted(() => {
  void loadData()
})
</script>

<template>
  <section class="annual-admin-page" data-testid="annual-reports-view">
    <header class="page-header">
      <div>
        <h1 class="page-title">年终购买轨迹 H5 与访问统计</h1>
        <p class="page-subtitle">
          从 CRM 真实订单、权益与核销事实冻结客户年度私享灯箱快照，实时统计谁看过、停留时长、已看页数及管家预约意向。
        </p>
      </div>
      <div class="header-actions">
        <el-select v-model="filters.reportYear" style="width: 118px" @change="filters.page = 1; loadData()">
          <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年度`" :value="y" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 120px" @change="filters.page = 1; loadData()">
          <el-option label="生效中" value="ACTIVE" />
          <el-option label="已停用" value="DISABLED" />
        </el-select>
        <el-button @click="loadData">刷新</el-button>
        <el-button v-if="canWrite" @click="openBatchDialog">批量生成链接</el-button>
        <el-button v-if="canWrite" type="primary" data-testid="btn-open-create-annual" @click="openCreateDialog">
          生成客户年终 H5
        </el-button>
      </div>
    </header>

    <!-- 汇总指标卡片 -->
    <div v-if="summary" class="summary-grid">
      <el-card shadow="never" class="metric-card">
        <div class="metric-label">已生成报告 / 生效中</div>
        <div class="metric-value">{{ summary.totalReports }} <small>/ {{ summary.activeReports }}</small></div>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <div class="metric-label">客户打开人数 / 打开率</div>
        <div class="metric-value">{{ summary.openedReports }} <small>{{ openRateText }}</small></div>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <div class="metric-label">完读 6 幕 / 完读率</div>
        <div class="metric-value">{{ summary.completedReports }} <small>{{ completionRateText }}</small></div>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <div class="metric-label">单次平均前台停留</div>
        <div class="metric-value">{{ formatDurationMs(summary.avgVisibleDurationMs) }}</div>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <div class="metric-label">生成海报 / 点击预约管家</div>
        <div class="metric-value">{{ summary.posterReports }} <small>/ {{ summary.bookingIntentReports }}</small></div>
      </el-card>
    </div>

    <!-- 有赞历史订单只读快照 & 统一免密入口卡片 -->
    <el-card v-if="yzSummary" shadow="never" class="youzan-portal-card" data-testid="youzan-annual-portal-card">
      <template #header>
        <div class="card-head-row">
          <div>
            <strong>有赞历史订单只读快照与微信免密统一入口</strong>
            <el-tag size="small" :type="yzSummary.wechatMpConfigured ? 'success' : 'info'" style="margin-left: 8px">
              服务号静默授权：{{ yzSummary.wechatMpConfigured ? '已启用' : '待配置' }}
            </el-tag>
            <el-tag size="small" :type="yzSummary.smsEnabled ? 'success' : 'info'" style="margin-left: 6px">
              手机号验证码兜底：{{ yzSummary.smsEnabled ? '已启用' : '默认关闭' }}
            </el-tag>
          </div>
          <div class="portal-actions">
            <el-button size="small" @click="copyUnifiedEntryUrl">复制朋友圈/公众号统一入口</el-button>
            <el-button size="small" @click="openYzProfilesDrawer">
              有赞买家镜像 ({{ yzSummary.profileCount }})
            </el-button>
            <el-button v-if="canManageYouzan" size="small" type="primary" plain @click="openYzSyncDialog">
              拉取有赞历史快照
            </el-button>
          </div>
        </div>
      </template>
      <div class="portal-stats-row">
        <span>统一免密链接：<code>{{ unifiedEntryHref }}</code></span>
        <span>已拉有赞买家：<b>{{ yzSummary.profileCount }}</b>（含 OpenID {{ yzSummary.openIdBoundCount }} / UnionID {{ yzSummary.unionIdBoundCount }}）</span>
        <span>有赞只读实付订单：<b>{{ yzSummary.paidOrderCount }}</b> 笔（¥{{ fenToYuan(yzSummary.totalPaymentFen) }}）</span>
        <span>统一入口访问会话：<b>{{ yzSummary.unifiedVisitSessions }}</b> 次</span>
      </div>
      <div class="portal-tip">
        仅作年终 H5 只读轨迹展示，绝不改动 CRM 正式订单与权益发放；客户转发统一入口至朋友圈时，朋友打开自动识别各自身份，不泄露原主人账单。
      </div>
    </el-card>

    <!-- 6 幕逐页留存漏斗 -->
    <el-card v-if="summary" shadow="never" class="funnel-card">
      <template #header>
        <div class="card-head-row">
          <span>{{ filters.reportYear }} 年度 H5 逐幕到达漏斗（当前数据范围）</span>
          <small>累计访问会话 {{ summary.totalSessions }} 次</small>
        </div>
      </template>
      <div class="funnel-bars">
        <div v-for="step in summary.sceneFunnel" :key="step.sceneIndex" class="funnel-row">
          <span class="funnel-label">{{ step.label }}</span>
          <div class="funnel-track">
            <div
              class="funnel-fill"
              :style="{
                width: `${summary.openedReports > 0 ? Math.round((step.reachedReports / summary.openedReports) * 100) : 0}%`,
              }"
            />
          </div>
          <span class="funnel-count">{{ step.reachedReports }} 人</span>
        </div>
      </div>
    </el-card>

    <!-- 客户报告与访问追踪表格 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="items" stripe border>
        <el-table-column label="客户称呼" min-width="140">
          <template #default="{ row }">
            <div class="cell-main">{{ row.displayName }}</div>
            <div class="cell-sub">{{ row.tierLabel }} · 管家 {{ row.butlerName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="文案语调" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ TONE_LABELS[row.toneCode] ?? row.toneCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订单 / 净消费" width="150">
          <template #default="{ row }">
            <div>{{ row.orderCount }} 笔</div>
            <div class="cell-sub">¥{{ fenToYuan(row.netSpendFen) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="核销 / 余次" width="110">
          <template #default="{ row }">
            {{ row.redeemCount }} / {{ row.remainingEntitlementCount }} 次
          </template>
        </el-table-column>
        <el-table-column label="访问统计（次 / 时长 / 最深页）" min-width="190">
          <template #default="{ row }">
            <div v-if="row.visitCount > 0">
              <strong>{{ row.visitCount }} 次</strong> · {{ formatDurationMs(row.totalVisibleDurationMs) }}
              <div class="cell-sub">最深第 {{ row.maxSceneReached }} 幕（已看 {{ row.maxViewedSceneCount }}/6 页）</div>
            </div>
            <span v-else class="cell-sub">尚未打开</span>
          </template>
        </el-table-column>
        <el-table-column label="转化行为" width="145">
          <template #default="{ row }">
            <el-tag v-if="row.clickedBookButler" size="small" type="danger" style="margin-right: 4px">已点预约</el-tag>
            <el-tag v-if="row.generatedPoster" size="small" type="success">已出海报</el-tag>
            <span v-if="!row.clickedBookButler && !row.generatedPoster" class="cell-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '生效中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="copyShareLink(row)">
              复制链接
            </el-button>
            <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="previewH5(row)">
              预览
            </el-button>
            <el-button link type="primary" @click="openSessions(row)">访问明细</el-button>
            <el-button v-if="canWrite && row.status === 'ACTIVE'" link type="danger" @click="handleDisable(row)">
              停用
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager-row">
        <el-pagination
          v-model:current-page="filters.page"
          v-model:page-size="filters.size"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="loadData"
        />
      </div>
    </el-card>

    <!-- 单客生成弹窗 -->
    <el-dialog v-model="createDialogOpen" title="生成客户年终购买轨迹 H5" width="520px">
      <el-form label-width="110px">
        <el-form-item label="选择客户" required>
          <el-select
            v-model="createForm.customerId"
            filterable
            remote
            reserve-keyword
            placeholder="搜索客户姓名、昵称或编号"
            :remote-method="searchCustomers"
            :loading="customerLoading"
            style="width: 100%"
          >
            <el-option
              v-for="c in customerOptions"
              :key="c.id"
              :label="`${c.displayName || c.nickname || c.customerNo} (${c.customerNo})`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点年度">
          <el-select v-model="createForm.reportYear" style="width: 100%">
            <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
        </el-form-item>
        <el-form-item label="文案语调">
          <el-select v-model="createForm.toneCode" style="width: 100%">
            <el-option label="自动推断（按客户画像/标签）" value="AUTO" />
            <el-option label="诗意松弛风（老客/仪式感）" value="POETIC" />
            <el-option label="清醒专业风（成分功课派）" value="CLINICAL" />
            <el-option label="极简高效风（男士/效率派）" value="MINIMAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="称呼偏好">
          <el-radio-group v-model="createForm.genderHint">
            <el-radio value="UNKNOWN">默认称呼</el-radio>
            <el-radio value="FEMALE">女士 / 她</el-radio>
            <el-radio value="MALE">先生 / 他</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="自定义寄语">
          <el-input
            v-model="createForm.customButlerNote"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="可选：留空则由文案引擎按语调自动生成；严禁填写绝对化或疗效承诺词"
          />
        </el-form-item>
        <el-form-item label="旧链接处理">
          <el-switch v-model="createForm.disablePreviousActive" active-text="生成新版时自动停用同年度旧链接" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">生成并冻结快照</el-button>
      </template>
    </el-dialog>

    <!-- 批量生成弹窗 -->
    <el-dialog v-model="batchDialogOpen" title="批量生成客户年终 H5 链接" width="540px">
      <el-form label-width="110px">
        <el-form-item label="选择客户" required>
          <el-select
            v-model="batchForm.customerIds"
            multiple
            filterable
            remote
            reserve-keyword
            placeholder="可多选当前范围可见客户（单批上限 200 人）"
            :remote-method="searchCustomers"
            :loading="customerLoading"
            style="width: 100%"
          >
            <el-option
              v-for="c in customerOptions"
              :key="c.id"
              :label="`${c.displayName || c.nickname || c.customerNo} (${c.customerNo})`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点年度">
          <el-select v-model="batchForm.reportYear" style="width: 100%">
            <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
        </el-form-item>
        <el-form-item label="文案策略">
          <el-select v-model="batchForm.toneCode" style="width: 100%">
            <el-option label="自动匹配（推荐：按每位客户画像差异化输出）" value="AUTO" />
            <el-option label="统一诗意松弛风" value="POETIC" />
            <el-option label="统一清醒专业风" value="CLINICAL" />
            <el-option label="统一极简高效风" value="MINIMAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="旧链接处理">
          <el-switch v-model="batchForm.disablePreviousActive" active-text="自动停用同年度旧链接" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="handleBatchGenerate">开始批量生成</el-button>
      </template>
    </el-dialog>

    <!-- 拉取有赞历史订单只读快照弹窗 -->
    <el-dialog v-model="yzSyncDialogOpen" title="拉取有赞历史订单只读快照" width="500px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 14px"
        title="本操作仅拉取有赞历史订单商品标题与买家身份（盲索引）至只读快照表供年终 H5 展示，绝不创建 CRM 正式订单或发放权益。"
      />
      <el-form label-width="112px">
        <el-form-item label="有赞店铺 ID" required>
          <el-input v-model="yzSyncForm.shopId" placeholder="请输入有赞店铺 kdt_id（需先在「有赞订单同步」完成店铺授权）" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-input v-model="yzSyncForm.startDate" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-input v-model="yzSyncForm.endDate" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="补全买家信息">
          <el-switch
            v-model="yzSyncForm.enrichBuyerProfile"
            active-text="同步姓名、性别及微信 OpenID/UnionID（自动按 ≤89 天切窗）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="yzSyncDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="yzSyncing" @click="handleYzSync">开始拉取只读快照</el-button>
      </template>
    </el-dialog>

    <!-- 有赞买家只读镜像抽屉 -->
    <el-drawer v-model="yzProfilesDrawerOpen" title="有赞买家年度只读镜像（已脱敏）" size="680px">
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <el-input
          v-model="yzProfilesKeyword"
          clearable
          placeholder="按称呼或脱敏手机号搜索"
          @keyup.enter="yzProfilesPage = 1; loadYzProfiles()"
        />
        <el-button type="primary" @click="yzProfilesPage = 1; loadYzProfiles()">查询</el-button>
      </div>
      <el-table v-loading="yzProfilesLoading" :data="yzProfiles" border stripe size="small">
        <el-table-column label="买家称呼" min-width="120">
          <template #default="{ row }">
            <div class="cell-main">{{ row.displayName }}</div>
            <div class="cell-sub">{{ row.mobileMasked || '未留手机' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="免密识别能力" min-width="150">
          <template #default="{ row }">
            <el-tag v-if="row.hasWechatOpenId" size="small" type="success" style="margin-right: 4px">OpenID</el-tag>
            <el-tag v-if="row.hasWechatUnionId" size="small" type="success" style="margin-right: 4px">UnionID</el-tag>
            <el-tag v-if="row.customerId" size="small" type="info">已关 CRM #{{ row.customerId }}</el-tag>
            <span v-if="!row.hasWechatOpenId && !row.hasWechatUnionId" class="cell-sub">待手机号/管家链接</span>
          </template>
        </el-table-column>
        <el-table-column label="有赞实付单 / 消费" min-width="130">
          <template #default="{ row }">
            <div>{{ row.orderCount }} 笔</div>
            <div class="cell-sub">¥{{ fenToYuan(row.totalPaymentFen) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="最近同步" min-width="135">
          <template #default="{ row }">
            {{ shanghaiDateTimeMinute(row.syncedAt) }}
          </template>
        </el-table-column>
      </el-table>
      <div class="pager-row">
        <el-pagination
          v-model:current-page="yzProfilesPage"
          :page-size="20"
          :total="yzProfilesTotal"
          layout="total, prev, pager, next"
          @current-change="loadYzProfiles"
        />
      </div>
    </el-drawer>

    <!-- 访问会话明细抽屉 -->
    <el-drawer
      v-model="sessionsDrawerOpen"
      :title="activeReport ? `${activeReport.displayName} · 访问会话明细` : '访问会话明细'"
      size="520px"
    >
      <div v-loading="sessionsLoading">
        <el-empty v-if="!sessions.length" description="客户暂未打开该 H5 链接" />
        <div v-else class="session-cards">
          <el-card v-for="s in sessions" :key="s.id" shadow="never" class="session-card">
            <div class="session-head">
              <strong>{{ shanghaiDateTimeMinute(s.firstOpenAt) }}</strong>
              <el-tag size="small" effect="plain">{{ s.clientDevice }}</el-tag>
            </div>
            <div class="session-stats">
              <span>有效停留：<b>{{ formatDurationMs(s.visibleDurationMs) }}</b></span>
              <span>已看页数：<b>{{ s.viewedSceneCount }} / 6 页</b></span>
              <span>最深到达：<b>第 {{ s.maxSceneReached }} 幕</b></span>
            </div>
            <div class="session-scene-pills">
              <span v-for="(ms, idx) in s.sceneDurationsMs" :key="idx" class="scene-pill">
                第{{ idx }}幕 {{ Math.round(ms / 1000) }}s
              </span>
            </div>
            <div class="session-flags">
              <el-tag v-if="s.ignitedLamp" size="small" type="info">已点亮灯箱</el-tag>
              <el-tag v-if="s.openedOrderCount > 0" size="small" type="info">查看订单 {{ s.openedOrderCount }} 次</el-tag>
              <el-tag v-if="s.toggledPrivacy" size="small" type="warning">切换隐私</el-tag>
              <el-tag v-if="s.generatedPoster" size="small" type="success">生成海报</el-tag>
              <el-tag v-if="s.clickedBookButler" size="small" type="danger">
                已点击预约{{ s.bookingTaskId ? ` (待办 #${s.bookingTaskId})` : '' }}
              </el-tag>
            </div>
          </el-card>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.annual-admin-page { display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-start; gap: 12px; }
.page-title { margin: 0; font-size: 20px; }
.page-subtitle { margin: 6px 0 0; font-size: 13px; color: var(--app-muted); }
.header-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.metric-label { font-size: 12px; color: var(--app-muted); margin-bottom: 6px; }
.metric-value { font-size: 22px; font-weight: 700; color: var(--app-ink); }
.metric-value small { font-size: 13px; font-weight: 500; color: var(--app-primary); margin-left: 4px; }
.card-head-row { display: flex; justify-content: space-between; align-items: center; }
.funnel-bars { display: flex; flex-direction: column; gap: 10px; }
.funnel-row { display: grid; grid-template-columns: 140px 1fr 70px; align-items: center; gap: 12px; font-size: 13px; }
.funnel-track { height: 10px; border-radius: 999px; background: #edf3f6; overflow: hidden; }
.funnel-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #0f948b, #4ec9ba); transition: width 0.4s ease; }
.funnel-count { text-align: right; font-variant-numeric: tabular-nums; color: var(--app-ink); }
.cell-main { font-weight: 600; color: var(--app-ink); }
.cell-sub { font-size: 12px; color: var(--app-muted); }
.pager-row { margin-top: 16px; display: flex; justify-content: flex-end; }
.session-cards { display: flex; flex-direction: column; gap: 12px; }
.session-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.session-stats { display: flex; flex-wrap: wrap; gap: 14px; font-size: 13px; margin-bottom: 8px; }
.session-scene-pills { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.scene-pill { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: #eef6f5; color: #0c756e; }
.session-flags { display: flex; flex-wrap: wrap; gap: 6px; }
.portal-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.portal-stats-row { display: flex; flex-wrap: wrap; gap: 16px; font-size: 13px; color: var(--app-ink); margin-bottom: 8px; }
.portal-stats-row code { padding: 2px 6px; border-radius: 4px; background: #f2f6f8; font-size: 12px; }
.portal-tip { font-size: 12px; color: var(--app-muted); }
</style>
