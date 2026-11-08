<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Appointment, AppointmentDetail, RedemptionDetail } from '../api/fulfillment'
import {
  cancelAppointment,
  checkInAppointment,
  completeAppointmentService,
  confirmAppointment,
  getAppointment,
  listAppointments,
  rejectAppointment,
} from '../api/fulfillment'
import { useAuthStore } from '../stores/auth'
import AppointmentCreateDrawer from './fulfillment/AppointmentCreateDrawer.vue'
import AppointmentDetailDrawer from './fulfillment/AppointmentDetailDrawer.vue'
import AppointmentPrototypeTabs from './fulfillment/AppointmentPrototypeTabs.vue'
import RedemptionCreateDrawer from './fulfillment/RedemptionCreateDrawer.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { appointmentStatusLabel, createFulfillmentIdempotencyKey } from './fulfillment/fulfillmentUi'
import { tradingErrorText } from './trading/tradingUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './fulfillment/fulfillment.css'

type AppointmentAction = 'confirm' | 'reject' | 'cancel' | 'check-in' | 'service-done' | 'redeem'

const STATUS_TABS = [
  { label: '全部预约', value: '' },
  { label: '待接诊', value: 'PENDING_INSTITUTION' },
  { label: '已接诊 · 待到院', value: 'CONFIRMED' },
  { label: '已到院', value: 'CHECKED_IN' },
  { label: '服务完成 · 待核销', value: 'SERVICE_DONE' },
  { label: '历史 · 已完成', value: 'REDEEMED' },
  { label: '历史 · 已取消', value: 'CANCELED' },
  { label: '历史 · 已拒绝', value: 'REJECTED' },
  { label: '历史 · 未到院', value: 'NO_SHOW' },
]

const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('appointment:write'))
const canCancel = computed(() => canWrite.value && auth.user?.userType !== 'INSTITUTION')
const canConfirm = computed(() => auth.hasPermission('appointment:confirm'))
const canPostPermission = computed(() => auth.hasPermission('redemption:post'))
const appointments = ref<Appointment[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({
  page: 1,
  size: 20,
  status: '',
  customerId: undefined as number | undefined,
  institutionId: undefined as number | undefined,
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAppointments({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      customerId: query.customerId,
      institutionId: query.institutionId,
    })
    appointments.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    appointments.value = []
    total.value = 0
    errorMessage.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void load()
}

function reset() {
  query.customerId = undefined
  query.institutionId = undefined
  query.page = 1
  void load()
}

function changeStatus(value: string | number) {
  query.status = String(value)
  query.page = 1
  void load()
}

const createVisible = ref(false)
const detailVisible = ref(false)
const detail = ref<AppointmentDetail | null>(null)
const detailLoadingId = ref<number | null>(null)
const redemptionVisible = ref(false)
const canPostCurrent = computed(() => {
  const institutionId = detail.value?.appointment.institutionId
  return institutionId !== undefined && canPostPermission.value && auth.hasInstitutionRole('OPERATOR', institutionId)
})
let transitionAttempt: { signature: string; key: string } | null = null

async function openDetail(id: number) {
  detailLoadingId.value = id
  try {
    detail.value = await getAppointment(id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

function handleCreated(saved: AppointmentDetail) {
  detail.value = saved
  detailVisible.value = true
  void load()
}

async function requestReason(action: 'reject' | 'cancel'): Promise<string | null> {
  const isReject = action === 'reject'
  try {
    const result = await ElMessageBox.prompt(
      isReject ? '请填写拒绝接诊原因，原因将追加到状态历史。' : '请填写取消预约原因，原预约与历史不会被删除。',
      isReject ? '拒绝接诊' : '取消预约',
      {
        type: 'warning',
        confirmButtonText: isReject ? '确认拒绝' : '确认取消',
        cancelButtonText: '返回',
        inputType: 'textarea',
        inputValidator: (value) => {
          const reason = value.trim()
          if (!reason) return '请填写原因'
          return reason.length <= 1000 || '原因不能超过 1000 字'
        },
      },
    )
    return result.value.trim()
  } catch {
    return null
  }
}

async function performAction(action: AppointmentAction) {
  const current = detail.value?.appointment
  if (!current) return
  if (action === 'redeem') {
    if (canPostCurrent.value && current.status === 'SERVICE_DONE') redemptionVisible.value = true
    return
  }

  let reason: string | undefined
  if (action === 'reject' || action === 'cancel') {
    reason = await requestReason(action) ?? undefined
    if (!reason) return
  } else {
    const confirmation = action === 'confirm'
      ? '请确认机构已实际同意接诊。'
      : action === 'check-in'
        ? '请确认客户已实际到院，不能依据聊天记录代替到院事实。'
        : '请确认本次真实服务已完成，完成后才可由机构授权操作员正式核销。'
    try {
      await ElMessageBox.confirm(confirmation, '确认履约状态', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '返回' })
    } catch {
      return
    }
    reason = action === 'confirm' ? '机构人工确认接诊' : action === 'check-in' ? '机构人工确认实际到院' : '机构人工确认服务完成'
  }

  const signature = `${current.id}:${action}:${current.version}:${reason ?? ''}`
  if (transitionAttempt?.signature !== signature) {
    transitionAttempt = { signature, key: createFulfillmentIdempotencyKey('appointment.transition') }
  }
  const key = transitionAttempt.key
  try {
    const body = { version: current.version, reason }
    const saved = action === 'confirm'
      ? await confirmAppointment(current.id, body, key)
      : action === 'reject'
        ? await rejectAppointment(current.id, body, key)
        : action === 'cancel'
          ? await cancelAppointment(current.id, body, key)
          : action === 'check-in'
            ? await checkInAppointment(current.id, body, key)
            : await completeAppointmentService(current.id, body, key)
    transitionAttempt = null
    detail.value = saved
    await load()
    ElMessage.success(`预约已更新为${appointmentStatusLabel(saved.appointment.status)}`)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  }
}

async function handleRedemptionSaved(saved: RedemptionDetail) {
  redemptionVisible.value = false
  try {
    detail.value = await getAppointment(saved.redemption.appointmentId)
    await load()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="预约与机构履约" description="未完成预约可修改；核销事实仍然不能随意覆盖">
      <template #actions><el-button v-if="canWrite" @click="createVisible = true">新建预约</el-button><el-button disabled title="独立核销申请接口待后续开放">提交核销申请 · 待接口</el-button></template>
    </PageHeader>
    <PageCard>
      <div class="proto-toolbar"><div class="proto-toolbar-left"><el-input-number v-model="query.customerId" :min="1" :controls="false" placeholder="客户 ID" /><el-input-number v-model="query.institutionId" :min="1" :controls="false" placeholder="机构 ID" /><el-select v-model="query.status" placeholder="全部预约状态" clearable @change="changeStatus"><el-option v-for="tab in STATUS_TABS" :key="tab.value" :label="tab.label" :value="tab.value" /></el-select><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div><span class="proto-note">只有机构授权操作员能形成正式「机构已确认」状态</span></div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
      <AppointmentPrototypeTabs :appointments="appointments" :loading="loading" @detail="openDetail" />
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()" />
    </PageCard>
    <AppointmentCreateDrawer v-if="canWrite" v-model="createVisible" @saved="handleCreated" />
    <AppointmentDetailDrawer v-model="detailVisible" :detail="detail" :can-write="canCancel" :can-confirm="canConfirm" :can-post="canPostCurrent" @action="performAction" />
    <RedemptionCreateDrawer v-if="canPostCurrent" v-model="redemptionVisible" :detail="detail" @saved="handleRedemptionSaved" />
  </div>
</template>
