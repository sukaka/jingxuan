<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerListItem } from '../../api/customer'
import type { Sku } from '../../api/catalog'
import type { OrderFulfillmentView } from '../../api/trading'
import { createOrder } from '../../api/trading'
import { yuanToFen } from '../../utils/money'
import { tradingErrorText } from './tradingUi'

const props = withDefaults(defineProps<{
  modelValue: boolean
  customers: CustomerListItem[]
  skus: Sku[]
  customersLoading?: boolean
  skusLoading?: boolean
  customerHasMore?: boolean
  skuHasMore?: boolean
  customerLoadError?: string
  skuLoadError?: string
}>(), {
  customersLoading: false,
  skusLoading: false,
  customerHasMore: false,
  skuHasMore: false,
  customerLoadError: '',
  skuLoadError: '',
})
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [view: OrderFulfillmentView]
  'search-customers': [keyword: string]
  'search-skus': [keyword: string]
}>()

const form = reactive({
  customerId: undefined as number | undefined,
  receiverType: 'PLATFORM',
  orderTypeCode: 'REGULAR',
  campaignId: undefined as number | undefined,
  campaignBatchId: undefined as number | undefined,
  payableYuan: 0,
  validTo: '',
  items: [{ skuId: undefined as number | undefined, quantity: 1 }],
})
const saving = ref(false)
const customerKeyword = ref('')
const skuKeyword = ref('')
const selectedCustomer = ref<CustomerListItem | null>(null)
const selectedSkus = ref<Record<number, Sku>>({})

function mergeOptions<T extends { id: number }>(results: T[], selected: T[]): T[] {
  const options = new Map<number, T>()
  for (const item of selected) options.set(item.id, item)
  for (const item of results) options.set(item.id, item)
  return Array.from(options.values())
}

const customerOptions = computed(() =>
  mergeOptions(props.customers, selectedCustomer.value ? [selectedCustomer.value] : []),
)
const skuOptions = computed(() => mergeOptions(props.skus, Object.values(selectedSkus.value)))

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, {
      customerId: undefined,
      receiverType: 'PLATFORM',
      orderTypeCode: 'REGULAR',
      campaignId: undefined,
      campaignBatchId: undefined,
      payableYuan: 0,
      validTo: '',
      items: [{ skuId: undefined, quantity: 1 }],
    })
    customerKeyword.value = ''
    skuKeyword.value = ''
    selectedCustomer.value = null
    selectedSkus.value = {}
  },
)

watch(
  [() => form.items.map((item) => [item.skuId, item.quantity]), skuOptions],
  () => {
    const payableFen = form.items.reduce((total, item) => {
      const sku = skuOptions.value.find((candidate) => candidate.id === item.skuId)
      return total + (sku ? sku.salePriceFen * item.quantity : 0)
    }, 0)
    form.payableYuan = payableFen / 100
  },
  { deep: true },
)

function addItem() {
  form.items.push({ skuId: undefined, quantity: 1 })
}

function pruneSelectedSkus() {
  const next: Record<number, Sku> = {}
  for (const item of form.items) {
    if (item.skuId === undefined) continue
    const sku = selectedSkus.value[item.skuId]
    if (sku) next[item.skuId] = sku
  }
  selectedSkus.value = next
}

function removeItem(index: number) {
  if (form.items.length > 1) {
    form.items.splice(index, 1)
    pruneSelectedSkus()
  }
}

function selectCustomer(value: number | undefined) {
  selectedCustomer.value = value === undefined
    ? null
    : customerOptions.value.find((item) => item.id === value) ?? null
}

function selectSku(value: number | undefined) {
  if (value !== undefined) {
    const sku = skuOptions.value.find((item) => item.id === value)
    if (sku) selectedSkus.value = { ...selectedSkus.value, [sku.id]: sku }
  }
  pruneSelectedSkus()
}

function searchCustomers(keyword: string) {
  customerKeyword.value = keyword
  emit('search-customers', keyword)
}

function searchSkus(keyword: string) {
  skuKeyword.value = keyword
  emit('search-skus', keyword)
}

function skuLabel(sku: Sku) {
  return `${sku.packageName ?? sku.skuCode}｜${sku.specName}｜${(sku.salePriceFen / 100).toFixed(2)}元`
}

async function save() {
  if (!form.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  if (form.items.some((item) => !item.skuId || !Number.isInteger(item.quantity) || item.quantity <= 0)) {
    ElMessage.warning('请完整选择 SKU 并填写正整数购买份数')
    return
  }
  if ((form.campaignId === undefined) !== (form.campaignBatchId === undefined)) {
    ElMessage.warning('活动 ID 与批次 ID 需同时填写，或同时留空')
    return
  }
  const payableFen = yuanToFen(form.payableYuan)
  if (!Number.isFinite(form.payableYuan) || !Number.isSafeInteger(payableFen) || payableFen <= 0) {
    ElMessage.warning('应付金额必须大于 0，且最多保留两位小数')
    return
  }
  saving.value = true
  try {
    const saved = await createOrder({
      customerId: form.customerId,
      receiverType: form.receiverType,
      orderTypeCode: form.orderTypeCode,
      campaignId: form.campaignId,
      campaignBatchId: form.campaignBatchId,
      payableFen,
      validTo: form.validTo || undefined,
      items: form.items.map((item) => ({ skuId: item.skuId!, quantity: item.quantity })),
    })
    ElMessage.success(`订单 ${saved.order.order.orderNo} 已保存为草稿，未发放权益`)
    emit('update:modelValue', false)
    emit('saved', saved)
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="新建订单草稿"
    size="min(780px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="草稿不会发放权益。先提交待支付，再由有权限的人核验支付凭证后确认"
      class="drawer-tip"
    />
    <el-form label-position="top">
      <div class="trading-form-grid">
        <el-form-item label="所属客户" required>
          <el-select
            v-model="form.customerId"
            filterable
            remote
            :remote-method="searchCustomers"
            :loading="customersLoading"
            class="full-width"
            placeholder="输入关键词搜索真实客户"
            data-testid="order-customer-select"
            @change="selectCustomer"
          >
            <el-option v-for="item in customerOptions" :key="item.id" :label="`${item.displayName || item.nickname || `客户 #${item.id}`}（${item.customerNo}）`" :value="item.id" />
          </el-select>
          <div v-if="customerLoadError" class="option-feedback is-error">
            <span>客户搜索失败：{{ customerLoadError }}</span>
            <el-button text size="small" data-testid="customer-search-retry" @click="searchCustomers(customerKeyword)">重试</el-button>
          </div>
          <div v-else-if="customerHasMore" class="option-feedback">客户匹配结果较多，请继续输入更具体的关键词缩小范围。</div>
        </el-form-item>
        <el-form-item label="订单类型" required>
          <el-select v-model="form.orderTypeCode" class="full-width">
            <el-option label="普通订单" value="REGULAR" />
            <el-option label="快闪订单" value="FLASH" />
            <el-option label="大促活动" value="PROMOTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="收款方" required>
          <el-select v-model="form.receiverType" class="full-width">
            <el-option label="平台收款" value="PLATFORM" />
            <el-option label="机构收款" value="INSTITUTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="应付金额（元）" required>
          <el-input-number v-model="form.payableYuan" :min="0.01" :precision="2" :step="100" class="full-width" />
        </el-form-item>
        <el-form-item label="活动 ID / 批次 ID（可选，需成对）">
          <div class="campaign-inputs">
            <el-input-number v-model="form.campaignId" :min="1" :precision="0" :controls="false" placeholder="活动 ID" class="campaign-input" />
            <el-input-number v-model="form.campaignBatchId" :min="1" :precision="0" :controls="false" placeholder="批次 ID" class="campaign-input" />
          </div>
        </el-form-item>
        <el-form-item label="权益到期日（可选）">
          <el-date-picker v-model="form.validTo" type="date" value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
      </div>

      <div class="section-head">
        <strong>订单行（成交时快照）</strong>
        <el-button size="small" @click="addItem">添加 SKU</el-button>
      </div>
      <div v-for="(item, index) in form.items" :key="index" class="order-line">
        <el-select
          v-model="item.skuId"
          filterable
          remote
          :remote-method="searchSkus"
          :loading="skusLoading"
          class="sku-select"
          placeholder="输入 SKU / 规格 / 套餐关键词"
          data-testid="order-sku-select"
          @change="selectSku"
        >
          <el-option v-for="sku in skuOptions" :key="sku.id" :label="skuLabel(sku)" :value="sku.id" />
        </el-select>
        <el-input-number v-model="item.quantity" :min="1" :max="1000" class="quantity-input" />
        <el-button text type="danger" :disabled="form.items.length === 1" @click="removeItem(index)">移除</el-button>
      </div>
      <div v-if="skuLoadError" class="option-feedback is-error">
        <span>上架 SKU 搜索失败：{{ skuLoadError }}</span>
        <el-button text size="small" data-testid="sku-search-retry" @click="searchSkus(skuKeyword)">重试</el-button>
      </div>
      <div v-else-if="skuHasMore" class="option-feedback">SKU 匹配结果较多，请继续输入更具体的关键词缩小范围。</div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="order-save-draft" @click="save">保存订单草稿</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.drawer-tip { margin-bottom: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: center; margin: 16px 0 10px; }
.order-line { display: grid; grid-template-columns: minmax(0, 1fr) 130px 54px; gap: 10px; align-items: center; margin-bottom: 12px; }
.sku-select { width: 100%; }
.quantity-input { width: 130px; }
.campaign-inputs { display: flex; gap: 8px; }
.campaign-input { width: 110px; }
.option-feedback { display: flex; align-items: center; gap: 8px; margin-top: 6px; color: #8a7a56; font-size: 11px; line-height: 1.6; }
.option-feedback.is-error { color: #c95667; }
.option-feedback .el-button { flex: none; }
</style>
