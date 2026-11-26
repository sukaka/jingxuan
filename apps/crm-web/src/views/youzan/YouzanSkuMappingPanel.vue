<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Sku } from '../../api/catalog'
import { listSkus } from '../../api/catalog'
import type { YouzanSkuCandidate, YouzanSkuMapping } from '../../api/youzan'
import { saveYouzanSkuMapping } from '../../api/youzan'
import { fenToYuan } from '../../utils/money'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'

const props = withDefaults(defineProps<{
  mappings: YouzanSkuMapping[]
  candidates?: YouzanSkuCandidate[]
  candidatesLoading?: boolean
  candidatesError?: string
  shopId: string
  canWrite: boolean
  loading: boolean
  suggestedExternalId?: string
  autoOpenRequest?: { externalSkuId: string; nonce: number } | null
  batchRetryingSku?: string | null
}>(), {
  candidates: () => [],
  candidatesLoading: false,
  candidatesError: '',
  suggestedExternalId: '',
  autoOpenRequest: null,
  batchRetryingSku: null,
})

const emit = defineEmits<{
  saved: [externalSkuId: string]
  batchRetry: [externalSkuId?: string]
  autoOpenConsumed: [nonce: number]
  reloadCandidates: []
}>()

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const editingStatus = ref<string | null>(null)
const externalSkuId = ref('')
const prefilledExternalSkuId = ref('')
const crmSkuId = ref<number | undefined>()
const version = ref<number | undefined>()
const skus = ref<Sku[]>([])
const selectedSku = ref<Sku | null>(null)
const searching = ref(false)
const hasMore = ref(false)
const searchError = ref('')
const saving = ref(false)
const idempotencyKey = ref('')
let searchSequence = 0
let lastHandledNonce = 0

const isMockShop = computed(() => props.shopId.trim().startsWith('MOCK-'))
const unmappedCandidates = computed(() => props.candidates.filter((item) => !item.mapped))
const readyRetryCandidates = computed(() => props.candidates.filter((item) => item.mapped && item.pendingIssueCount > 0))
const candidateBySkuId = computed(() => {
  const map = new Map<string, YouzanSkuCandidate>()
  for (const item of props.candidates) map.set(item.externalSkuId, item)
  return map
})
const readyRetryBySkuId = computed(() => {
  const map = new Map<string, YouzanSkuCandidate>()
  for (const item of readyRetryCandidates.value) map.set(item.externalSkuId, item)
  return map
})
const selectedCandidate = computed(() => {
  const id = externalSkuId.value.trim()
  return id ? (candidateBySkuId.value.get(id) ?? null) : null
})
const showShopMismatchWarning = computed(() => {
  const id = externalSkuId.value.trim()
  if (!id || !prefilledExternalSkuId.value || id !== prefilledExternalSkuId.value) return false
  return !props.candidatesLoading && !props.candidatesError && !selectedCandidate.value
})
const selectedUnmappedOption = computed(() => {
  const id = externalSkuId.value.trim()
  return unmappedCandidates.value.some((item) => item.externalSkuId === id) ? id : undefined
})

const skuOptions = computed(() => {
  const options = new Map<number, Sku>()
  if (selectedSku.value) options.set(selectedSku.value.id, selectedSku.value)
  for (const item of skus.value) options.set(item.id, item)
  return Array.from(options.values())
})

function candidateHeadline(item: YouzanSkuCandidate): string {
  const parts = [item.itemTitle, item.skuSpec].filter((part): part is string => Boolean(part && part.trim()))
  return parts.length > 0 ? parts.join('｜') : '原始报文未返回商品标题'
}

function candidateOptionLabel(item: YouzanSkuCandidate): string {
  const parts: string[] = []
  if (item.itemTitle) parts.push(item.itemTitle)
  if (item.skuSpec) parts.push(item.skuSpec)
  if (item.priceFen != null) parts.push(`¥${fenToYuan(item.priceFen)}`)
  const prefix = parts.length > 0 ? `${parts.join('｜')} — ` : ''
  return `${prefix}${item.externalSkuId}（${item.pendingIssueCount} 条异常）`
}

function findExistingMapping(skuId: string): YouzanSkuMapping | undefined {
  const target = skuId.trim()
  if (!target) return undefined
  return props.mappings.find((item) => item.externalSkuId === target)
}

function openCreate(prefill = '') {
  const normalized = (prefill || props.suggestedExternalId || '').trim()
  editingId.value = null
  editingStatus.value = null
  externalSkuId.value = normalized
  prefilledExternalSkuId.value = normalized
  crmSkuId.value = undefined
  version.value = undefined
  selectedSku.value = null
  idempotencyKey.value = createYouzanKey('youzan.sku.mapping')
  formVisible.value = true
  void searchSkus('')
}

function openEdit(mapping: YouzanSkuMapping, fromPrefill = false) {
  editingId.value = mapping.id
  editingStatus.value = mapping.status
  externalSkuId.value = mapping.externalSkuId
  prefilledExternalSkuId.value = fromPrefill ? mapping.externalSkuId : ''
  crmSkuId.value = mapping.crmSkuId
  version.value = mapping.version
  selectedSku.value = null
  idempotencyKey.value = createYouzanKey('youzan.sku.mapping')
  formVisible.value = true
  void searchSkus('')
}

function openForExternalSku(skuId: string) {
  const target = skuId.trim()
  const existing = findExistingMapping(target)
  if (existing) {
    openEdit(existing, true)
  } else {
    openCreate(target)
  }
}

function handleCandidateSelect(value: string | undefined) {
  const target = typeof value === 'string' ? value.trim() : ''
  prefilledExternalSkuId.value = ''
  if (!target) {
    externalSkuId.value = ''
    editingId.value = null
    editingStatus.value = null
    version.value = undefined
    return
  }
  const existing = findExistingMapping(target)
  if (existing) {
    editingId.value = existing.id
    editingStatus.value = existing.status
    externalSkuId.value = existing.externalSkuId
    crmSkuId.value = existing.crmSkuId
    version.value = existing.version
    selectedSku.value = null
    return
  }
  editingId.value = null
  editingStatus.value = null
  version.value = undefined
  externalSkuId.value = target
}

watch(
  () => props.autoOpenRequest,
  (request) => {
    if (!request || !props.canWrite || request.nonce <= lastHandledNonce) return
    const target = request.externalSkuId.trim()
    if (!target) return
    lastHandledNonce = request.nonce
    openForExternalSku(target)
    emit('autoOpenConsumed', request.nonce)
  },
  { immediate: true },
)

function selectSku(id: number | undefined) {
  selectedSku.value = skuOptions.value.find((item) => item.id === id) ?? null
  crmSkuId.value = id
}

async function searchSkus(keyword: string) {
  const sequence = ++searchSequence
  searching.value = true
  searchError.value = ''
  try {
    const result = await listSkus({ page: 1, size: 20, keyword: keyword.trim() || undefined, status: 'ACTIVE' })
    if (sequence !== searchSequence) return
    skus.value = result.items
    hasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === searchSequence) searchError.value = tradingErrorText(error)
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

function skuLabel(sku: Sku): string {
  return `${sku.packageName ?? sku.skuCode}｜${sku.specName}｜${fenToYuan(sku.salePriceFen)} 元`
}

function statusText(status: string): string {
  return ({ ACTIVE: '有效', INACTIVE: '已停用' } as Record<string, string>)[status] ?? '未知状态'
}

async function save() {
  const normalizedExternalId = externalSkuId.value.trim()
  if (!normalizedExternalId) {
    ElMessage.warning('请填写有赞外部 SKU ID')
    return
  }
  if (!crmSkuId.value) {
    ElMessage.warning('请选择 CRM 标准 SKU')
    return
  }
  saving.value = true
  try {
    await saveYouzanSkuMapping({
      shopId: props.shopId,
      externalSkuId: normalizedExternalId,
      crmSkuId: crmSkuId.value,
      version: version.value,
    }, idempotencyKey.value)
    ElMessage.success('SKU 映射已保存；只有后续事件满足全部规则才会继续处理')
    formVisible.value = false
    emit('saved', normalizedExternalId)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重新提交不会重复保存`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section>
    <div v-if="readyRetryCandidates.length > 0 && canWrite" class="youzan-ready-retry-banner" data-testid="sku-ready-retry-banner">
      <div>
        <strong>已有 {{ readyRetryCandidates.length }} 个已映射外部 SKU 存在待补偿重试异常</strong>
        <p>保存映射不会自动确认订单或发放权益；确认映射无误后，可手动点击右侧按钮批量重试关联异常。</p>
      </div>
      <el-button
        type="primary"
        size="small"
        :loading="batchRetryingSku === '__ALL__'"
        :disabled="batchRetryingSku !== null && batchRetryingSku !== '__ALL__'"
        data-testid="sku-batch-retry-all"
        @click="emit('batchRetry')"
      >
        批量重试已映射异常
      </el-button>
    </div>

    <el-alert v-if="candidatesError" type="warning" :closable="false" class="dialog-tip" data-testid="sku-candidates-error">
      <template #title>
        待映射外部 SKU 候选加载失败：{{ candidatesError }}
        <el-button text size="small" type="primary" data-testid="sku-candidates-retry" @click="emit('reloadCandidates')">重试</el-button>
      </template>
    </el-alert>

    <div v-if="unmappedCandidates.length > 0" class="youzan-candidates-block" data-testid="sku-candidates-section">
      <div class="youzan-section-head">
        <div>
          <h3>待映射外部 SKU（已从异常订单自动提取，共 {{ unmappedCandidates.length }} 个）</h3>
          <p v-if="canWrite">点击「一键映射」自动带入外部 SKU ID 与有赞商品信息，无需人工手抄编码。</p>
          <p v-else>当前账号为只读权限，仅展示自动提取的待映射外部 SKU 信息；如需配置映射请联系具备映射权限的管理员。</p>
        </div>
      </div>
      <app-table v-loading="candidatesLoading" :data="unmappedCandidates" size="small">
        <el-table-column label="有赞外部 SKU ID" min-width="190">
          <template #default="{ row }">
            <span class="youzan-mono-id">{{ row.externalSkuId }}</span>
            <div v-if="row.outerSkuCode" class="trading-cell-sub">商家编码：{{ row.outerSkuCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="有赞商品 / 规格" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ candidateHeadline(row) }}
          </template>
        </el-table-column>
        <el-table-column label="参考单价" width="105" align="right">
          <template #default="{ row }">{{ row.priceFen != null ? `¥${fenToYuan(row.priceFen)}` : '-' }}</template>
        </el-table-column>
        <el-table-column label="待处理异常" min-width="145">
          <template #default="{ row }">
            <strong>{{ row.pendingIssueCount }} 条</strong>
            <div v-if="row.sampleOrderNo" class="trading-cell-sub">单号 {{ row.sampleOrderNo }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="115" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              text
              size="small"
              data-testid="sku-candidate-map"
              @click="openForExternalSku(row.externalSkuId)"
            >
              一键映射
            </el-button>
          </template>
        </el-table-column>
      </app-table>
    </div>

    <div class="youzan-section-head">
      <div><h3>SKU 映射管理</h3><p>外部 SKU 与 CRM 标准 SKU 一对一关联，“有效”仅代表映射配置可用，不代表订单已处理成功。</p></div>
      <el-button v-if="canWrite" size="small" data-testid="sku-mapping-create" @click="openCreate()">新增映射</el-button>
    </div>
    <el-alert v-if="suggestedExternalId && canWrite" type="info" :closable="false" class="dialog-tip" data-testid="sku-mapping-suggestion">
      <template #title>
        待映射有赞外部商品编码：<strong>{{ suggestedExternalId }}</strong>
        <el-button text size="small" type="primary" @click="openForExternalSku(suggestedExternalId)">使用该标识新增映射</el-button>
      </template>
    </el-alert>
    <app-table v-loading="loading" :data="mappings" size="small">
      <el-table-column prop="externalSkuId" label="有赞外部 SKU" min-width="170" />
      <el-table-column label="CRM SKU" min-width="150"><template #default="{ row }">#{{ row.crmSkuId }}</template></el-table-column>
      <el-table-column label="配置状态" width="105"><template #default="{ row }">{{ statusText(row.status) }}</template></el-table-column>
      <el-table-column label="版本" width="75"><template #default="{ row }">{{ row.version }}</template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="185" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'ACTIVE' && readyRetryBySkuId.has(row.externalSkuId)"
            text
            size="small"
            type="primary"
            :loading="batchRetryingSku === row.externalSkuId"
            :disabled="batchRetryingSku !== null && batchRetryingSku !== row.externalSkuId"
            data-testid="sku-mapping-retry"
            @click="emit('batchRetry', row.externalSkuId)"
          >
            重试异常({{ readyRetryBySkuId.get(row.externalSkuId)?.pendingIssueCount }})
          </el-button>
          <el-button text size="small" data-testid="sku-mapping-edit" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无 SKU 映射，缺失映射的订单会进入异常队列" /></template>
    </app-table>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑 SKU 映射' : '新增 SKU 映射'" width="min(580px, 94vw)">
      <el-alert type="info" :closable="false" title="只选择真实上架且权益规则完整的 SKU；保存映射不等于订单或支付已同步。" class="dialog-tip" />
      <div class="youzan-dialog-shop" data-testid="sku-mapping-target-shop">
        <span>目标店铺编号：<strong>{{ shopId || '未选择' }}</strong></span>
        <span v-if="isMockShop" class="youzan-warning-text">（当前为模拟演示店铺，配置真实店铺映射前请先切换为真实店铺编号 kdt_id）</span>
      </div>
      <el-alert
        v-if="showShopMismatchWarning"
        type="warning"
        :closable="false"
        class="dialog-tip"
        data-testid="sku-mapping-shop-mismatch-warn"
        :title="`当前店铺（${shopId}）的待映射异常中未检出该外部 SKU，请核对异常所属店铺并确认顶部店铺编号无误后再保存。`"
      />
      <el-form label-position="top" @submit.prevent>
        <el-form-item v-if="editingId === null && unmappedCandidates.length > 0" label="从待映射有赞商品选择（自动填入下方外部 SKU ID）">
          <el-select
            :model-value="selectedUnmappedOption"
            filterable
            clearable
            placeholder="选择异常订单中的有赞商品自动带出外部 SKU ID"
            class="full-width"
            data-testid="sku-mapping-candidate-select"
            @change="handleCandidateSelect"
          >
            <el-option
              v-for="item in unmappedCandidates"
              :key="item.externalSkuId"
              :label="candidateOptionLabel(item)"
              :value="item.externalSkuId"
            />
          </el-select>
        </el-form-item>
        <div v-if="selectedCandidate" class="youzan-candidate-meta" data-testid="sku-mapping-candidate-info">
          <div class="youzan-candidate-meta__title">{{ candidateHeadline(selectedCandidate) }}</div>
          <div class="youzan-candidate-meta__sub">
            <span v-if="selectedCandidate.priceFen != null">参考单价：¥{{ fenToYuan(selectedCandidate.priceFen) }}</span>
            <span v-if="selectedCandidate.outerSkuCode">商家编码：{{ selectedCandidate.outerSkuCode }}</span>
            <span>待处理异常：{{ selectedCandidate.pendingIssueCount }} 条</span>
            <span v-if="selectedCandidate.sampleOrderNo">示例单号：{{ selectedCandidate.sampleOrderNo }}</span>
          </div>
        </div>
        <el-form-item label="有赞外部 SKU ID" required><el-input v-model="externalSkuId" :disabled="editingId !== null" maxlength="128" data-testid="sku-mapping-external" /></el-form-item>
        <el-form-item label="CRM 标准 SKU" required>
          <el-select :model-value="crmSkuId" filterable remote :remote-method="searchSkus" :loading="searching" class="full-width" data-testid="sku-mapping-crm" @change="selectSku">
            <el-option v-for="sku in skuOptions" :key="sku.id" :label="skuLabel(sku)" :value="sku.id" />
          </el-select>
          <div v-if="searchError" class="youzan-error-text">{{ searchError }} <el-button text size="small" @click="searchSkus('')">重试</el-button></div>
          <div v-else-if="hasMore" class="youzan-warning-text">结果较多，请继续输入 SKU 编码、规格或套餐关键词缩小范围。</div>
        </el-form-item>
        <div v-if="editingId" class="youzan-retry-note">
          当前数据版本：{{ version }}<span v-if="editingStatus === 'INACTIVE'">（当前已停用，保存后将重新启用该映射）</span>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="sku-mapping-save" @click="save">保存映射</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.full-width { width: 100%; }
.dialog-tip { margin-bottom: 14px; }
</style>
