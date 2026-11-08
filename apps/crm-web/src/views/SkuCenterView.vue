<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Institution, ProductPackage, Sku, SkuQualityReport } from '../api/catalog'
import {
  activateSku,
  disableSku,
  getPackageVersionSnapshot,
  getSkuQualityReport,
  listInstitutions,
  listPackages,
  listSkuInstitutions,
  listSkus,
} from '../api/catalog'
import { useAuthStore } from '../stores/auth'
import { fenToYuan } from '../utils/money'
import SkuFormDrawer from './catalog/SkuFormDrawer.vue'
import StatusPill from './customer/StatusPill.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { catalogErrorText, selectionModeLabel, skuStatusLabel } from './catalog/catalogUi'
import './customer/customer-ui.css'
import './catalog/catalog.css'

const route = useRoute()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('product:write'))
const skus = ref<Sku[]>([])
const packages = ref<ProductPackage[]>([])
const institutions = ref<Institution[]>([])
const institutionNames = ref<Record<number, string[]>>({})
const categoryNames = ref<Record<number, string>>({})
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const qualityReport = ref<SkuQualityReport | null>(null)
const qualityLoading = ref(false)
const qualityError = ref('')
const qualityDetailsVisible = ref(false)
const query = reactive({ keyword: '', status: '', packageId: undefined as number | undefined, page: 1, size: 20 })
const QUALITY_TIME_ZONE = 'Asia/Shanghai'

function dateInQualityZone(date: Date): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: QUALITY_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

function defaultQualityQuery() {
  const to = dateInQualityZone(new Date())
  const from = new Date(`${to}T00:00:00Z`)
  from.setUTCDate(from.getUTCDate() - 89)
  return {
    from: from.toISOString().slice(0, 10),
    to,
    timeZone: QUALITY_TIME_ZONE,
    sourceSystem: 'ALL',
    page: 1,
    size: 100,
    lowFrequencyThreshold: 3,
  }
}

const QUALITY_LABELS: Record<string, string> = {
  NORMAL: '正常',
  ATTENTION: '需复核',
  LOW_FREQUENCY: '低频 SKU',
  MISSING_SKU_CODE: 'SKU 编码缺失',
  MISSING_STANDARD_NAME: '标准名称或规格缺失',
  MISSING_ACTIVE_INSTITUTION: '未配置启用机构',
  DUPLICATE_SPEC: '同名不同规格未拆 SKU',
  CAMPAIGN_AS_PRODUCT: '活动名替代产品标准名',
  NAMING_ISSUE: '命名异常',
}

function qualityFor(skuId: number) {
  return qualityReport.value?.items.find((item) => item.skuId === skuId)
}

function qualityText(skuId: number): string {
  const item = qualityFor(skuId)
  if (!item) return qualityError.value ? '暂不可用' : '报表未覆盖'
  const issues = item.issues.length ? item.issues : [item.qualityStatus]
  return issues.map((issue) => QUALITY_LABELS[issue] ?? '其他质量问题').join('、')
}

function qualityTone(skuId: number): 'success' | 'warning' | 'danger' | 'dark' {
  const item = qualityFor(skuId)
  if (!item) return 'dark'
  if (item.qualityStatus === 'NORMAL' && item.issues.length === 0) return 'success'
  if (item.qualityStatus === 'LOW_FREQUENCY' || item.issues.every((issue) => issue === 'LOW_FREQUENCY')) return 'warning'
  return 'danger'
}

function ratioText(): string {
  const ratio = qualityReport.value?.summary.lowFrequencyRatio
  if (!qualityReport.value || ratio === null || ratio === undefined) return '—'
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(ratio * 100)}%`
}

async function loadQuality() {
  qualityReport.value = null
  qualityError.value = ''
  if (!auth.hasPermission('report:read')) {
    qualityError.value = '当前角色无 SKU 质量报表查看权限'
    return
  }
  qualityLoading.value = true
  try {
    qualityReport.value = await getSkuQualityReport(defaultQualityQuery())
  } catch (error) {
    qualityError.value = catalogErrorText(error)
  } finally {
    qualityLoading.value = false
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listSkus(query)
    skus.value = result.items
    const institutionResults = await Promise.allSettled(result.items.map((item) => listSkuInstitutions(item.id)))
    institutionNames.value = Object.fromEntries(institutionResults.map((entry, index) => [result.items[index].id,
      entry.status === 'fulfilled' ? entry.value.map((institution) => institution.fullName) : []]))
    const snapshotResults = await Promise.allSettled(result.items.map((item) => getPackageVersionSnapshot(item.packageVersionId)))
    categoryNames.value = Object.fromEntries(snapshotResults.map((entry, index) => [result.items[index].id,
      entry.status === 'fulfilled'
        ? [...new Set(entry.value.projects.map((project) => project.categoryCode).filter(Boolean))].join('、')
        : '']))
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    errorMessage.value = catalogErrorText(error)
    skus.value = []
  } finally {
    loading.value = false
  }
}

async function reloadAll() {
  await Promise.all([load(), loadQuality()])
}

const formVisible = ref(false)
const editingSku = ref<Sku | null>(null)
const initialInstitutionIds = ref<number[]>([])
const presetPackageId = ref<number | null>(null)
const presetVersionId = ref<number | null>(null)

function openCreate() {
  editingSku.value = null
  initialInstitutionIds.value = []
  formVisible.value = true
}

async function openEdit(item: Sku) {
  try {
    const snapshot = await getPackageVersionSnapshot(item.packageVersionId)
    if (snapshot.status !== 'DRAFT') {
      ElMessage.warning('已激活版本的规格与价格不可覆盖，请回到套餐中心创建新版本')
      return
    }
    initialInstitutionIds.value = (await listSkuInstitutions(item.id)).map((item) => item.id)
    editingSku.value = item
    presetPackageId.value = item.packageId
    presetVersionId.value = item.packageVersionId
    formVisible.value = true
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

async function activate(item: Sku) {
  try {
    await ElMessageBox.confirm('上架前将复核版本、权益规则与适用机构，确认上架？', '上架 SKU', { type: 'warning' })
    await activateSku(item.id, item.version)
    await reloadAll()
    ElMessage.success('SKU 已上架')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(catalogErrorText(error))
  }
}

async function disable(item: Sku) {
  try {
    await ElMessageBox.confirm('停用后不能用于新订单，历史快照不受影响。确认停用？', '停用 SKU', { type: 'warning' })
    await disableSku(item.id, item.version)
    await reloadAll()
    ElMessage.success('SKU 已停用')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(catalogErrorText(error))
  }
}

const institutionDialog = ref(false)
const institutionRows = ref<Institution[]>([])
const institutionSkuCode = ref('')

async function showInstitutions(item: Sku) {
  try {
    institutionRows.value = await listSkuInstitutions(item.id)
    institutionSkuCode.value = item.skuCode
    institutionDialog.value = true
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

onMounted(async () => {
  const results = await Promise.allSettled([listPackages({ page: 1, size: 100 }), listInstitutions({ page: 1, size: 100 })])
  if (results[0].status === 'fulfilled') packages.value = results[0].value.items
  if (results[1].status === 'fulfilled') institutions.value = results[1].value.items
  presetPackageId.value = route.query.packageId ? Number(route.query.packageId) : null
  presetVersionId.value = route.query.versionId ? Number(route.query.versionId) : null
  await reloadAll()
  if (route.query.create === '1') openCreate()
})
</script>

<template>
  <div class="proto-page sku-quality-page">
    <PageHeader title="SKU 标准化与数据质量" description="SKU 主数据支持新增、修改、停用；已售版本继续保留成交快照">
      <template #actions><el-button v-if="canWrite" type="primary" @click="openCreate">＋ 新增SKU</el-button></template>
    </PageHeader>

    <el-alert v-if="qualityError" type="warning" :title="`质量报表加载失败：${qualityError}`" :closable="false" class="quality-alert" />
    <PageCard>
      <div class="crm-toolbar">
        <el-input v-model="query.keyword" placeholder="SKU 编码 / 规格 / 套餐" clearable class="keyword" @keyup.enter="load" />
        <el-select v-model="query.status" placeholder="状态" clearable class="filter">
          <el-option label="上架中" value="ACTIVE" />
          <el-option label="已停用" value="INACTIVE" />
        </el-select>
        <el-select v-model="query.packageId" placeholder="套餐" clearable filterable class="package-filter">
          <el-option v-for="item in packages" :key="item.id" :label="item.packageName" :value="item.id" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="query.page = 1; load()">查询</el-button>
        <el-button @click="query.keyword = ''; query.status = ''; query.packageId = undefined; query.page = 1; load()">重置</el-button>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="crm-table-alert" />
      <div class="proto-table-wrap">
        <app-table v-loading="loading" :data="skus">
          <el-table-column prop="skuCode" label="SKU编码" min-width="150" />
          <el-table-column label="标准名称" min-width="160"><template #default="{row}">{{ row.packageName }}<div class="proto-subcell">第 {{ row.versionNo }} 版 · {{ selectionModeLabel(row.selectionMode) }}</div></template></el-table-column>
          <el-table-column label="分类" width="110"><template #default="{row}">{{ categoryNames[row.id] || '—' }}</template></el-table-column>
          <el-table-column prop="specName" label="规格" min-width="100" />
          <el-table-column label="价格" width="100" align="right"><template #default="{row}">{{ fenToYuan(row.salePriceFen) }}</template></el-table-column>
          <el-table-column label="参与机构" min-width="165"><template #default="{row}"><span v-for="name in institutionNames[row.id] || []" :key="name" class="proto-pill success institution-tag">{{ name }}</span><el-button text @click="showInstitutions(row)">查看</el-button></template></el-table-column>
          <el-table-column label="90天销量" width="105" align="right"><template #default="{row}"><span :data-testid="`sku-sales-${row.id}`">{{ qualityFor(row.id)?.sales90Days ?? '—' }}</span></template></el-table-column>
          <el-table-column label="质量" min-width="145"><template #default="{row}"><span :data-testid="`sku-quality-${row.id}`"><el-button text class="quality-link" aria-label="查看质量检查与口径" @click="qualityDetailsVisible = true"><StatusPill :text="qualityText(row.id)" :tone="qualityTone(row.id)" /></el-button></span></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{row}"><StatusPill :text="skuStatusLabel(row.status)" :tone="row.status==='ACTIVE'?'success':'dark'" /></template></el-table-column>
          <el-table-column label="操作" width="155" fixed="right"><template #default="{row}"><el-button v-if="canWrite && row.status==='INACTIVE'" text @click="openEdit(row)">修改</el-button><el-button v-if="canWrite && row.status==='INACTIVE'" text @click="activate(row)">上架</el-button><el-button v-if="canWrite && row.status==='ACTIVE'" text @click="disable(row)">停用</el-button></template></el-table-column>
          <template #empty>暂无 SKU</template>
        </app-table>
      </div>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="crm-pager"
        @current-change="load"
        @size-change="query.page = 1; load()"
      />
    </PageCard>

    <el-drawer v-model="qualityDetailsVisible" title="SKU 数据质量与口径" size="min(680px,100vw)">
      <div v-loading="qualityLoading" class="quality-drawer">
        <template v-if="qualityReport">
          <dl class="quality-summary">
            <div><dt>标准 SKU</dt><dd>{{ qualityReport.summary.totalSkuCount }} 个</dd></div>
            <div><dt>命名异常（本页）</dt><dd>{{ qualityReport.summary.namingIssueCount }} 个</dd></div>
            <div><dt>90 天低频（本页）</dt><dd>{{ qualityReport.summary.lowFrequencySkuCount }} 个</dd></div>
            <div><dt>低频占比（本页）</dt><dd>{{ ratioText() }}</dd></div>
          </dl>
          <p class="quality-meta">{{ qualityReport.from }} — {{ qualityReport.to }}（{{ qualityReport.periodDays }} 天）· {{ qualityReport.timeZone }} · 来源 {{ qualityReport.sourceSystem }} · 低频阈值 ≤ {{ qualityReport.lowFrequencyThreshold }} · {{ qualityReport.metricVersion }} · 数据水位 {{ qualityReport.sourceWatermark ?? '暂无' }}</p>
          <p class="quality-rule">低频只提示，不自动下架；命名、规则与机构问题均需人工复核。销量和质量不使用原型演示值。</p>
          <div class="quality-table-wrap">
            <table class="quality-table">
              <thead><tr><th>检查规则</th><th>异常数</th><th>处理原则</th></tr></thead>
              <tbody>
                <tr v-for="check in qualityReport.checks" :key="check.checkCode" :data-testid="`quality-check-${check.checkCode}`">
                  <td><strong>{{ check.checkName }}</strong><small>{{ check.checkCode }}</small></td>
                  <td><span class="proto-pill" :class="check.issueCount ? 'warning' : 'success'">{{ check.issueCount }}</span></td>
                  <td>{{ check.handlingRule }}</td>
                </tr>
                <tr v-if="qualityReport.checks.length === 0"><td colspan="3" class="quality-empty">当前报表未返回质量检查项</td></tr>
              </tbody>
            </table>
          </div>
        </template>
        <el-empty v-else description="质量报表暂不可用，请稍后重试；SKU 主数据不受影响" />
      </div>
    </el-drawer>

    <SkuFormDrawer
      v-model="formVisible"
      :packages="packages"
      :institutions="institutions"
      :preset-package-id="presetPackageId"
      :preset-version-id="presetVersionId"
      :sku="editingSku"
      :initial-institution-ids="initialInstitutionIds"
      @saved="reloadAll"
    />

    <el-dialog v-model="institutionDialog" :title="`适用机构 · ${institutionSkuCode}`" width="520px">
      <div class="institution-dialog-list">
        <el-tag v-for="item in institutionRows" :key="item.id" effect="plain">{{ item.fullName }}（{{ item.cityCode || '未设城市' }}）</el-tag>
        <el-empty v-if="institutionRows.length === 0" description="尚未配置机构，不能上架" />
      </div>
      <template #footer><el-button @click="institutionDialog = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.keyword { width: min(300px, 100%); }
.filter { width: 130px; }
.package-filter { width: 210px; }
.institution-dialog-list { display: flex; flex-wrap: wrap; gap: 9px; }
.quality-link { padding: 0; }
.quality-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 24px; margin: 0; }
.quality-summary div { padding-bottom: 14px; border-bottom: 1px solid #edf2f4; }
.quality-summary dt { color: #849aa6; font-size: 12px; }
.quality-summary dd { margin: 7px 0 0; color: #294456; font-size: 20px; font-weight: 650; }
.quality-meta { margin: 20px 0 0 !important; color: #6b8793 !important; overflow-wrap: anywhere; font-size: 12px; line-height: 1.8; }
.quality-rule { margin: 14px 0 0; color: #587482; font-size: 12px; line-height: 1.8; }
.quality-alert { margin-bottom: 18px; }
.quality-table-wrap { padding: 18px 0 0; overflow-x: auto; }
.quality-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.quality-table th { padding: 12px 14px; color: #8ba0ad; background: #fbfcfd; font-size: 11px; text-align: left; }
.quality-table td { padding: 15px 14px; border-top: 1px solid #eef2f4; color: #516878; line-height: 1.7; }
.quality-table td strong { display: block; color: #294456; }
.quality-table td small { display: block; margin-top: 3px; color: #9aacb6; }
.quality-empty { color: #93a8b5 !important; text-align: center; }
@media (max-width: 760px) {
  .quality-summary { grid-template-columns: 1fr; }
  .quality-table-wrap { padding: 14px 0 0; }
}
</style>
