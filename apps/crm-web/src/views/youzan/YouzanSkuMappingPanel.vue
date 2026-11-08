<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Sku } from '../../api/catalog'
import { listSkus } from '../../api/catalog'
import type { YouzanSkuMapping } from '../../api/youzan'
import { saveYouzanSkuMapping } from '../../api/youzan'
import { fenToYuan } from '../../utils/money'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'

const props = defineProps<{
  mappings: YouzanSkuMapping[]
  shopId: string
  canWrite: boolean
  loading: boolean
}>()
const emit = defineEmits<{ saved: [] }>()

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const externalSkuId = ref('')
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

const skuOptions = computed(() => {
  const options = new Map<number, Sku>()
  if (selectedSku.value) options.set(selectedSku.value.id, selectedSku.value)
  for (const item of skus.value) options.set(item.id, item)
  return Array.from(options.values())
})

function openCreate() {
  editingId.value = null
  externalSkuId.value = ''
  crmSkuId.value = undefined
  version.value = undefined
  selectedSku.value = null
  idempotencyKey.value = createYouzanKey('youzan.sku.mapping')
  formVisible.value = true
  void searchSkus('')
}

function openEdit(mapping: YouzanSkuMapping) {
  editingId.value = mapping.id
  externalSkuId.value = mapping.externalSkuId
  crmSkuId.value = mapping.crmSkuId
  version.value = mapping.version
  idempotencyKey.value = createYouzanKey('youzan.sku.mapping')
  formVisible.value = true
  void searchSkus('')
}

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
  return ({ ACTIVE: '有效', INACTIVE: '已停用' } as Record<string, string>)[status] ?? status
}

async function save() {
  if (!externalSkuId.value.trim()) {
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
      externalSkuId: externalSkuId.value.trim(),
      crmSkuId: crmSkuId.value,
      version: version.value,
    }, idempotencyKey.value)
    ElMessage.success('SKU 映射已保存；只有后续事件满足全部规则才会继续处理')
    formVisible.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section>
    <div class="youzan-section-head">
      <div><h3>SKU 映射管理</h3><p>外部 SKU 与 CRM 标准 SKU 一对一关联，“有效”仅代表映射配置可用，不代表订单已处理成功。</p></div>
      <el-button v-if="canWrite" size="small" data-testid="sku-mapping-create" @click="openCreate">新增映射</el-button>
    </div>
    <app-table v-loading="loading" :data="mappings" size="small">
      <el-table-column prop="externalSkuId" label="有赞外部 SKU" min-width="170" />
      <el-table-column label="CRM SKU" min-width="150"><template #default="{ row }">#{{ row.crmSkuId }}</template></el-table-column>
      <el-table-column label="配置状态" width="105"><template #default="{ row }">{{ statusText(row.status) }}</template></el-table-column>
      <el-table-column label="版本" width="75"><template #default="{ row }">{{ row.version }}</template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="100" fixed="right"><template #default="{ row }"><el-button text size="small" @click="openEdit(row)">编辑</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无 SKU 映射，缺失映射的订单会进入异常队列" /></template>
    </app-table>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑 SKU 映射' : '新增 SKU 映射'" width="min(560px, 94vw)">
      <el-alert type="info" :closable="false" title="只选择真实上架且权益规则完整的 SKU；保存映射不等于订单或支付已同步。" class="dialog-tip" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="有赞外部 SKU ID" required><el-input v-model="externalSkuId" maxlength="128" data-testid="sku-mapping-external" /></el-form-item>
        <el-form-item label="CRM 标准 SKU" required>
          <el-select :model-value="crmSkuId" filterable remote :remote-method="searchSkus" :loading="searching" class="full-width" data-testid="sku-mapping-crm" @change="selectSku">
            <el-option v-for="sku in skuOptions" :key="sku.id" :label="skuLabel(sku)" :value="sku.id" />
          </el-select>
          <div v-if="searchError" class="youzan-error-text">{{ searchError }} <el-button text size="small" @click="searchSkus('')">重试</el-button></div>
          <div v-else-if="hasMore" class="youzan-warning-text">结果较多，请继续输入 SKU 编码、规格或套餐关键词缩小范围。</div>
        </el-form-item>
        <div v-if="editingId" class="youzan-retry-note">乐观锁版本：{{ version }}</div>
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
