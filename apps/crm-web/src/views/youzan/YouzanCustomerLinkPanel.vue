<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerListItem } from '../../api/customer'
import { listCustomers } from '../../api/customer'
import type { YouzanCustomerLink } from '../../api/youzan'
import { saveYouzanCustomerLink } from '../../api/youzan'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'
import { useReferenceNames } from '../references'

const { loadCustomerNames, customerLabel: crmCustomerLabel } = useReferenceNames()

const props = defineProps<{
  links: YouzanCustomerLink[]
  shopId: string
  canWrite: boolean
  loading: boolean
}>()
const emit = defineEmits<{ saved: [] }>()

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const externalCustomerId = ref('')
const customerId = ref<number | undefined>()
const version = ref<number | undefined>()
const reason = ref('')
const customers = ref<CustomerListItem[]>([])
const selectedCustomer = ref<CustomerListItem | null>(null)
const searching = ref(false)
const hasMore = ref(false)
const searchError = ref('')
const saving = ref(false)
const idempotencyKey = ref('')
let searchSequence = 0

watch(() => props.links, (links) => {
  void loadCustomerNames(links.map((link) => link.customerId))
}, { immediate: true })

const customerOptions = computed(() => {
  const options = new Map<number, CustomerListItem>()
  if (selectedCustomer.value) options.set(selectedCustomer.value.id, selectedCustomer.value)
  for (const item of customers.value) options.set(item.id, item)
  return Array.from(options.values())
})

function openCreate() {
  editingId.value = null
  externalCustomerId.value = ''
  customerId.value = undefined
  version.value = undefined
  selectedCustomer.value = null
  reason.value = ''
  idempotencyKey.value = createYouzanKey('youzan.customer.link')
  formVisible.value = true
  void searchCustomers('')
}

function openEdit(link: YouzanCustomerLink) {
  editingId.value = link.id
  externalCustomerId.value = link.externalCustomerId
  customerId.value = link.customerId
  version.value = link.version
  reason.value = link.reason ?? ''
  idempotencyKey.value = createYouzanKey('youzan.customer.link')
  formVisible.value = true
  void searchCustomers('')
}

function selectCustomer(id: number | undefined) {
  selectedCustomer.value = customerOptions.value.find((item) => item.id === id) ?? null
  customerId.value = id
}

async function searchCustomers(keyword: string) {
  const sequence = ++searchSequence
  searching.value = true
  searchError.value = ''
  try {
    const result = await listCustomers({ page: 1, size: 20, keyword: keyword.trim() || undefined })
    if (sequence !== searchSequence) return
    customers.value = result.items
    hasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === searchSequence) searchError.value = tradingErrorText(error)
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

function customerLabel(customer: CustomerListItem): string {
  return `${customer.displayName || customer.nickname || `客户 #${customer.id}`}（${customer.customerNo}）`
}

async function save() {
  if (!externalCustomerId.value.trim()) {
    ElMessage.warning('请填写有赞外部客户标识，不能使用昵称或手机号代替')
    return
  }
  if (!customerId.value) {
    ElMessage.warning('请选择当前数据范围内的 CRM 客户')
    return
  }
  if (!reason.value.trim()) {
    ElMessage.warning('必须填写人工匹配理由与证据')
    return
  }
  saving.value = true
  try {
    await saveYouzanCustomerLink({
      shopId: props.shopId,
      externalCustomerId: externalCustomerId.value.trim(),
      customerId: customerId.value,
      version: version.value,
      reason: reason.value.trim(),
    }, idempotencyKey.value)
    ElMessage.success('人工客户链接已保存；后续订单仍需通过 SKU 与支付校验')
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
      <div><h3>人工客户链接</h3><p>只链接当前可见 CRM 客户并记录人工理由；昵称、手机号或外部客户标识都不能自动决定客户 ID。</p></div>
      <el-button v-if="canWrite" size="small" data-testid="customer-link-create" @click="openCreate">新增人工链接</el-button>
    </div>
    <app-table v-loading="loading" :data="links" size="small">
      <el-table-column prop="externalCustomerId" label="外部客户标识" min-width="170" />
      <el-table-column label="CRM 客户" width="115"><template #default="{ row }">{{ crmCustomerLabel(row.customerId) }}</template></el-table-column>
      <el-table-column label="链接方式" width="100"><template #default="{ row }">{{ row.linkType === 'STRONG' ? '强确认' : row.linkType === 'MANUAL' ? '人工确认' : row.linkType }}</template></el-table-column>
      <el-table-column label="证据等级" width="95"><template #default="{ row }">{{ ({ HIGH: '高', MEDIUM: '中', LOW: '低' } as Record<string, string>)[row.confidence] || row.confidence }}</template></el-table-column>
      <el-table-column prop="reason" label="人工理由" min-width="190" show-overflow-tooltip />
      <el-table-column v-if="canWrite" label="操作" width="100" fixed="right"><template #default="{ row }"><el-button text size="small" @click="openEdit(row)">编辑</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无人工客户链接；未确认客户不会生成订单或权益" /></template>
    </app-table>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑人工客户链接' : '新增人工客户链接'" width="min(580px, 94vw)">
      <el-alert type="warning" :closable="false" title="这里只保存人工确认关系，不按昵称或手机号自动匹配，也不会据此伪造支付凭证。" class="dialog-tip" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="有赞外部客户标识" required><el-input v-model="externalCustomerId" maxlength="128" data-testid="customer-link-external" /></el-form-item>
        <el-form-item label="CRM 客户（当前数据范围）" required>
          <el-select :model-value="customerId" filterable remote :remote-method="searchCustomers" :loading="searching" class="full-width" data-testid="customer-link-crm" @change="selectCustomer">
            <el-option v-for="item in customerOptions" :key="item.id" :label="customerLabel(item)" :value="item.id" />
          </el-select>
          <div v-if="searchError" class="youzan-error-text">{{ searchError }} <el-button text size="small" @click="searchCustomers('')">重试</el-button></div>
          <div v-else-if="hasMore" class="youzan-warning-text">结果较多，请继续输入客户编号或名称缩小范围。</div>
        </el-form-item>
        <el-form-item label="人工匹配理由与证据" required><el-input v-model="reason" type="textarea" :rows="3" maxlength="1000" data-testid="customer-link-reason" placeholder="说明人工核对了哪些订单、外部身份与 CRM 档案依据" /></el-form-item>
        <div v-if="editingId" class="youzan-retry-note">乐观锁版本：{{ version }}</div>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="customer-link-save" @click="save">保存人工链接</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.full-width { width: 100%; }
.dialog-tip { margin-bottom: 14px; }
</style>
