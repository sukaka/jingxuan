<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Institution } from '../../api/catalog'
import { listInstitutions } from '../../api/catalog'
import type { CustomerListItem } from '../../api/customer'
import { listCustomers } from '../../api/customer'
import type { AppointmentDetail, EntitlementOption } from '../../api/fulfillment'
import { createAppointment, getAppointmentOptions } from '../../api/fulfillment'
import { tradingErrorText } from '../trading/tradingUi'
import { createFulfillmentIdempotencyKey } from './fulfillmentUi'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: AppointmentDetail]
}>()

interface BatchLine {
  entitlementId: number | undefined
  plannedCount: number | undefined
}

const OPTION_PAGE_SIZE = 20
const form = reactive({
  customerId: undefined as number | undefined,
  institutionId: undefined as number | undefined,
  scheduledAt: null as Date | null,
  notes: '',
  items: [{ entitlementId: undefined, plannedCount: undefined }] as BatchLine[],
})
const customers = ref<CustomerListItem[]>([])
const institutions = ref<Institution[]>([])
const selectedCustomer = ref<CustomerListItem | null>(null)
const selectedInstitution = ref<Institution | null>(null)
const customersLoading = ref(false)
const institutionsLoading = ref(false)
const customerHasMore = ref(false)
const institutionHasMore = ref(false)
const customerLoadError = ref('')
const institutionLoadError = ref('')
const customerKeyword = ref('')
const institutionKeyword = ref('')
const entitlementOptions = ref<EntitlementOption[]>([])
const optionsLoading = ref(false)
const optionsError = ref('')
const saving = ref(false)
const idempotencyKey = ref('')
const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
let customerSequence = 0
let institutionSequence = 0
let optionsSequence = 0

function mergeOptions<T extends { id: number }>(results: T[], selected: T | null): T[] {
  const options = new Map<number, T>()
  if (selected) options.set(selected.id, selected)
  for (const item of results) options.set(item.id, item)
  return Array.from(options.values())
}

const customerOptions = computed(() => mergeOptions(customers.value, selectedCustomer.value))
const institutionOptions = computed(() => mergeOptions(institutions.value, selectedInstitution.value))

async function searchCustomers(keyword: string) {
  customerKeyword.value = keyword
  const sequence = ++customerSequence
  customersLoading.value = true
  customerLoadError.value = ''
  try {
    const result = await listCustomers({ page: 1, size: OPTION_PAGE_SIZE, keyword: keyword.trim() || undefined })
    if (sequence !== customerSequence) return
    customers.value = result.items
    customerHasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === customerSequence) customerLoadError.value = tradingErrorText(error)
  } finally {
    if (sequence === customerSequence) customersLoading.value = false
  }
}

async function searchInstitutions(keyword: string) {
  institutionKeyword.value = keyword
  const sequence = ++institutionSequence
  institutionsLoading.value = true
  institutionLoadError.value = ''
  try {
    const result = await listInstitutions({ page: 1, size: OPTION_PAGE_SIZE, keyword: keyword.trim() || undefined, status: 'ACTIVE' })
    if (sequence !== institutionSequence) return
    institutions.value = result.items
    institutionHasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === institutionSequence) institutionLoadError.value = tradingErrorText(error)
  } finally {
    if (sequence === institutionSequence) institutionsLoading.value = false
  }
}

async function loadEntitlementOptions() {
  if (!form.customerId || !form.institutionId) return
  const customerId = form.customerId
  const institutionId = form.institutionId
  const sequence = ++optionsSequence
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const result = await getAppointmentOptions(customerId)
    if (sequence !== optionsSequence) return
    entitlementOptions.value = result.entitlements.filter((item) => item.institutionIds.includes(institutionId))
  } catch (error) {
    if (sequence === optionsSequence) optionsError.value = tradingErrorText(error)
  } finally {
    if (sequence === optionsSequence) optionsLoading.value = false
  }
}

function resetBatches() {
  form.items = [{ entitlementId: undefined, plannedCount: undefined }]
  entitlementOptions.value = []
  optionsError.value = ''
  if (form.customerId && form.institutionId) void loadEntitlementOptions()
}

function selectCustomer(value: number | undefined) {
  selectedCustomer.value = value === undefined ? null : customerOptions.value.find((item) => item.id === value) ?? null
  resetBatches()
}

function selectInstitution(value: number | undefined) {
  selectedInstitution.value = value === undefined ? null : institutionOptions.value.find((item) => item.id === value) ?? null
  resetBatches()
}

function addItem() {
  form.items.push({ entitlementId: undefined, plannedCount: undefined })
}

function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

function optionLabel(option: EntitlementOption): string {
  return `${option.projectName || `项目 #${option.projectId}`}｜批次 #${option.id}｜可用 ${option.availableCount} 次`
}

function selectedOption(id: number | undefined): EntitlementOption | undefined {
  return entitlementOptions.value.find((item) => item.id === id)
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, {
      customerId: undefined,
      institutionId: undefined,
      scheduledAt: null,
      notes: '',
      items: [{ entitlementId: undefined, plannedCount: undefined }],
    })
    selectedCustomer.value = null
    selectedInstitution.value = null
    customers.value = []
    institutions.value = []
    entitlementOptions.value = []
    customerLoadError.value = ''
    institutionLoadError.value = ''
    optionsError.value = ''
    customerHasMore.value = false
    institutionHasMore.value = false
    customerKeyword.value = ''
    institutionKeyword.value = ''
    idempotencyKey.value = createFulfillmentIdempotencyKey('appointment.create')
    void searchCustomers('')
    void searchInstitutions('')
  },
)

async function save() {
  if (!form.customerId || !form.institutionId) {
    ElMessage.warning('请选择客户与服务机构')
    return
  }
  if (!(form.scheduledAt instanceof Date) || Number.isNaN(form.scheduledAt.getTime())) {
    ElMessage.warning('请选择计划到院时间')
    return
  }
  if (!form.items.length || form.items.some((item) => !item.entitlementId || !Number.isInteger(item.plannedCount) || (item.plannedCount ?? 0) <= 0)) {
    ElMessage.warning('请逐项目选择权益批次并填写正整数计划次数')
    return
  }
  const entitlementIds = form.items.map((item) => item.entitlementId)
  if (new Set(entitlementIds).size !== entitlementIds.length) {
    ElMessage.warning('同一权益批次不能在预约中重复选择')
    return
  }
  for (const item of form.items) {
    const option = selectedOption(item.entitlementId)
    if (!option || (item.plannedCount ?? 0) > option.availableCount) {
      ElMessage.warning('计划次数不能超过所选批次的当前可用次数，请刷新后核对')
      return
    }
  }

  saving.value = true
  try {
    const saved = await createAppointment({
      customerId: form.customerId,
      institutionId: form.institutionId,
      scheduledAt: form.scheduledAt.toISOString(),
      remark: form.notes.trim() || undefined,
      items: form.items.map((item) => ({ entitlementId: item.entitlementId!, count: item.plannedCount! })),
    }, idempotencyKey.value)
    ElMessage.success('预约已创建，待机构确认接诊；计划次数尚未扣减权益')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="新建到院预约"
    size="min(900px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert type="info" :closable="false" title="预约仅登记履约计划，不扣减权益；实际服务完成后仍须机构授权操作员正式核销。" class="create-tip" />
    <el-form label-position="top" @submit.prevent>
      <div class="fulfillment-option-grid">
        <el-form-item label="所属客户" required>
          <el-select
            v-model="form.customerId"
            filterable
            remote
            :remote-method="searchCustomers"
            :loading="customersLoading"
            class="full-width"
            placeholder="输入关键词搜索本人范围内客户"
            data-testid="appointment-customer-select"
            @change="selectCustomer"
          >
            <el-option v-for="item in customerOptions" :key="item.id" :label="`${item.displayName || item.nickname || `客户 #${item.id}`}（${item.customerNo}）`" :value="item.id" />
          </el-select>
          <div v-if="customerLoadError" class="fulfillment-option-feedback is-error">
            <span>客户搜索失败：{{ customerLoadError }}</span>
            <el-button text size="small" @click="searchCustomers(customerKeyword)">重试</el-button>
          </div>
          <div v-else-if="customerHasMore" class="fulfillment-option-feedback">客户结果较多，请继续缩小关键词。</div>
        </el-form-item>
        <el-form-item label="服务机构" required>
          <el-select
            v-model="form.institutionId"
            filterable
            remote
            :remote-method="searchInstitutions"
            :loading="institutionsLoading"
            class="full-width"
            placeholder="输入机构名称 / 编号关键词"
            data-testid="appointment-institution-select"
            @change="selectInstitution"
          >
            <el-option v-for="item in institutionOptions" :key="item.id" :label="`${item.fullName}（${item.institutionNo}）`" :value="item.id" />
          </el-select>
          <div v-if="institutionLoadError" class="fulfillment-option-feedback is-error">
            <span>机构搜索失败：{{ institutionLoadError }}</span>
            <el-button text size="small" @click="searchInstitutions(institutionKeyword)">重试</el-button>
          </div>
          <div v-else-if="institutionHasMore" class="fulfillment-option-feedback">机构结果较多，请继续缩小关键词。</div>
        </el-form-item>
        <el-form-item :label="`计划到院时间（${timeZone}）`" required>
          <el-date-picker v-model="form.scheduledAt" type="datetime" :clearable="false" class="full-width" data-testid="appointment-scheduled-at" />
        </el-form-item>
        <el-form-item label="预约备注（可选）">
          <el-input v-model="form.notes" type="textarea" :rows="2" maxlength="1000" show-word-limit placeholder="仅填写履约所需备注，不填写无关敏感信息" />
        </el-form-item>
      </div>

      <div class="fulfillment-batch-head">
        <strong>逐项目权益批次与计划次数</strong>
        <el-button size="small" :disabled="!entitlementOptions.length" @click="addItem">添加项目</el-button>
      </div>
      <el-alert v-if="optionsError" type="error" :closable="false" class="options-alert">
        <template #title>
          权益批次加载失败：{{ optionsError }}
          <el-button text size="small" data-testid="appointment-options-retry" @click="loadEntitlementOptions">重试</el-button>
        </template>
      </el-alert>
      <div v-loading="optionsLoading">
        <div v-for="(item, index) in form.items" :key="index" class="fulfillment-batch-line">
          <div>
            <el-select v-model="item.entitlementId" filterable class="full-width" placeholder="选择真实项目权益批次" data-testid="appointment-entitlement-select">
              <el-option v-for="option in entitlementOptions" :key="option.id" :label="optionLabel(option)" :value="option.id" :disabled="option.availableCount <= 0" />
            </el-select>
            <div v-if="selectedOption(item.entitlementId)" class="fulfillment-batch-meta">
              项目 #{{ selectedOption(item.entitlementId)?.projectId }} · 有效期至 {{ selectedOption(item.entitlementId)?.validTo || '未设到期日' }}
            </div>
          </div>
          <el-input-number v-model="item.plannedCount" :min="1" :precision="0" :controls="false" placeholder="计划次数" class="count-input" data-testid="appointment-planned-count" />
          <el-button text type="danger" :disabled="form.items.length === 1" @click="removeItem(index)">移除</el-button>
        </div>
        <el-empty v-if="!optionsLoading && form.customerId && form.institutionId && entitlementOptions.length === 0 && !optionsError" description="当前客户在该机构没有可预约的有效权益批次" />
      </div>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="appointment-create-submit" @click="save">创建预约</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.create-tip { margin-bottom: 18px; }
.options-alert { margin-bottom: 12px; }
.count-input { width: 130px; }
@media (max-width: 480px) {
  .count-input { width: 100%; }
}
</style>
