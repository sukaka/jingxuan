<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { AppointmentDetail, EntitlementOption, RedemptionDetail } from '../../api/fulfillment'
import { createRedemption, getAppointmentOptions } from '../../api/fulfillment'
import { tradingErrorText } from '../trading/tradingUi'
import { useReferenceNames } from '../references'
import { createFulfillmentIdempotencyKey } from './fulfillmentUi'

const props = defineProps<{
  modelValue: boolean
  detail: AppointmentDetail | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [detail: RedemptionDetail]
}>()

interface RedemptionLine {
  entitlementId: number | undefined
  count: number | undefined
}

const form = reactive({
  serviceAt: null as Date | null,
  evidenceRef: '',
  voucherFileId: undefined as number | undefined,
  items: [{ entitlementId: undefined, count: undefined }] as RedemptionLine[],
})
const options = ref<EntitlementOption[]>([])
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const idempotencyKey = ref('')
const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
const appointment = computed(() => props.detail?.appointment ?? null)
const { loadCustomerNames, customerLabel, institutionLabel } = useReferenceNames()

watch(appointment, (value) => {
  if (value) void loadCustomerNames([value.customerId])
}, { immediate: true })

async function loadOptions() {
  const current = appointment.value
  if (!current || current.status !== 'SERVICE_DONE' || !props.detail) return
  loading.value = true
  loadError.value = ''
  try {
    const result = await getAppointmentOptions({ appointmentId: current.id })
    const allowedIds = new Set(props.detail.items.map((item) => item.entitlementId))
    options.value = result.entitlements.filter((item) => allowedIds.has(item.id) && item.institutionIds.includes(current.institutionId))
  } catch (error) {
    loadError.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

function addItem() {
  form.items.push({ entitlementId: undefined, count: undefined })
}

function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

function selectedOption(id: number | undefined): EntitlementOption | undefined {
  return options.value.find((item) => item.id === id)
}

function optionLabel(option: EntitlementOption): string {
  return `${option.projectName || `项目 #${option.projectId}`}｜批次 #${option.id}｜当前可用 ${option.availableCount} 次`
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, {
      serviceAt: null,
      evidenceRef: '',
      voucherFileId: undefined,
      items: [{ entitlementId: undefined, count: undefined }],
    })
    options.value = []
    loadError.value = ''
    idempotencyKey.value = createFulfillmentIdempotencyKey('redemption.post')
    void loadOptions()
  },
)

async function save() {
  const current = appointment.value
  if (!current || current.status !== 'SERVICE_DONE') {
    ElMessage.warning('只有服务完成且尚未正式核销的预约可执行此操作，请刷新状态')
    return
  }
  if (!form.items.length || form.items.some((item) => !item.entitlementId || !Number.isInteger(item.count) || (item.count ?? 0) <= 0)) {
    ElMessage.warning('请逐项目选择真实权益批次并填写正整数核销次数')
    return
  }
  const ids = form.items.map((item) => item.entitlementId)
  if (new Set(ids).size !== ids.length) {
    ElMessage.warning('同一权益批次不能重复提交')
    return
  }
  for (const item of form.items) {
    const option = selectedOption(item.entitlementId)
    if (!option || (item.count ?? 0) > option.availableCount) {
      ElMessage.warning('核销次数超过当前可用次数，请刷新权益后重试')
      return
    }
  }
  if (!(form.serviceAt instanceof Date) || Number.isNaN(form.serviceAt.getTime())) {
    ElMessage.warning('请选择实际服务时间')
    return
  }
  if (!form.evidenceRef.trim()) {
    ElMessage.warning('请填写真实履约凭证引用')
    return
  }

  const summary = form.items.map((item) => {
    const option = selectedOption(item.entitlementId)
    return `${option?.projectName || `项目 #${option?.projectId}`} ${item.count} 次`
  }).join('、')
  saving.value = true
  try {
    await ElMessageBox.confirm(
      `请再次核对：${summary}；实际服务时间 ${form.serviceAt.toLocaleString()}（${timeZone}）；凭证 ${form.evidenceRef.trim()}。确认后将由机构授权操作员形成正式核销记录与权益扣减流水，原记录不可直接修改。`,
      '二次确认正式核销',
      { type: 'warning', confirmButtonText: '确认正式核销', cancelButtonText: '返回核对' },
    )
    const saved = await createRedemption(current.id, {
      appointmentVersion: current.version,
      serviceAt: form.serviceAt.toISOString(),
      evidenceRef: form.evidenceRef.trim(),
      voucherFileId: form.voucherFileId,
      lines: form.items.map((item) => ({
        entitlementId: item.entitlementId!,
        count: item.count!,
      })),
    }, idempotencyKey.value)
    ElMessage.success('机构正式核销已记录，请核对最新权益余额')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="机构正式核销"
    size="min(860px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert type="warning" :closable="false" title="仅服务完成后，由机构授权操作员按真实项目、批次、次数和凭证确认；管家与前台不能在此执行最终核销。" class="redemption-tip" />
    <template v-if="appointment">
      <div class="fulfillment-readonly">预约 {{ appointment.appointmentNo }} · {{ customerLabel(appointment.customerId) }} · {{ institutionLabel(appointment.institutionId) }}</div>
      <el-alert v-if="loadError" type="error" :closable="false" class="redemption-tip">
        <template #title>权益批次加载失败：{{ loadError }} <el-button text size="small" data-testid="redemption-options-retry" @click="loadOptions">重试</el-button></template>
      </el-alert>
      <el-form v-loading="loading" label-position="top" @submit.prevent>
        <div class="fulfillment-batch-head">
          <strong>实际服务项目与次数</strong>
          <el-button size="small" :disabled="!options.length" @click="addItem">添加项目</el-button>
        </div>
        <div v-for="(item, index) in form.items" :key="index" class="fulfillment-batch-line">
          <div>
            <el-select v-model="item.entitlementId" filterable class="full-width" placeholder="选择本预约的真实项目权益批次" data-testid="redemption-entitlement-select">
              <el-option v-for="option in options" :key="option.id" :label="optionLabel(option)" :value="option.id" :disabled="option.availableCount <= 0" />
            </el-select>
            <div v-if="selectedOption(item.entitlementId)" class="fulfillment-batch-meta">当前可用 {{ selectedOption(item.entitlementId)?.availableCount }} 次，最终余额以后端事务校验为准</div>
          </div>
          <el-input-number v-model="item.count" :min="1" :precision="0" :controls="false" placeholder="实际次数" class="count-input" data-testid="redemption-count" />
          <el-button text type="danger" :disabled="form.items.length === 1" @click="removeItem(index)">移除</el-button>
        </div>
        <el-empty v-if="!loading && options.length === 0 && !loadError" description="本预约没有可正式核销的有效权益批次" />
        <el-form-item :label="`实际服务时间（${timeZone}）`" required>
          <el-date-picker v-model="form.serviceAt" type="datetime" :clearable="false" class="full-width" data-testid="redemption-service-at" />
        </el-form-item>
        <el-form-item label="履约凭证引用" required>
          <el-input v-model="form.evidenceRef" maxlength="192" show-word-limit placeholder="填写真实签收或履约核验记录编号" data-testid="redemption-evidence" />
        </el-form-item>
        <el-form-item label="已上传凭证文件 ID（可选）">
          <el-input-number v-model="form.voucherFileId" :min="1" :precision="0" :controls="false" class="full-width" placeholder="仅填写系统中已存在的文件 ID" />
        </el-form-item>
      </el-form>
    </template>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!appointment || appointment.status !== 'SERVICE_DONE'" data-testid="redemption-submit" @click="save">二次确认并正式核销</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.redemption-tip { margin-bottom: 16px; }
.count-input { width: 130px; }
@media (max-width: 480px) {
  .count-input { width: 100%; }
}
</style>
