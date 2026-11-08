<script setup lang="ts">
import { computed, watch } from 'vue'
import type { AppointmentDetail } from '../../api/fulfillment'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { useReferenceNames } from '../references'
import {
  appointmentStatusLabel,
  appointmentStatusTone,
  redemptionAmountLabel,
  redemptionCount,
  redemptionStatusLabel,
} from './fulfillmentUi'

type AppointmentAction = 'confirm' | 'reject' | 'cancel' | 'check-in' | 'service-done' | 'redeem'

const props = defineProps<{
  modelValue: boolean
  detail: AppointmentDetail | null
  canWrite: boolean
  canConfirm: boolean
  canPost: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  action: [action: AppointmentAction]
}>()

const { loadCustomerNames, customerLabel, institutionLabel, projectLabel } = useReferenceNames()

const appointment = computed(() => props.detail?.appointment ?? null)
const plannedCount = computed(() => props.detail?.items.reduce((total, item) => total + item.plannedCount, 0) ?? 0)
const postedCount = computed(() => props.detail?.items.reduce((total, item) => total + item.postedCount, 0) ?? 0)

watch(appointment, (value) => {
  if (value) void loadCustomerNames([value.customerId])
}, { immediate: true })

function transitionTime(status: string): string | null {
  const matches = props.detail?.history.filter((item) => item.toStatus === status) ?? []
  return matches.length ? matches[matches.length - 1]!.createdAt : null
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="appointment ? `预约 ${appointment.appointmentNo}` : '预约详情'"
    size="min(960px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="detail && appointment">
      <div class="fulfillment-detail-head">
        <div>
          <h3 class="fulfillment-detail-title">{{ customerLabel(appointment.customerId) }}</h3>
          <div class="crm-muted">{{ institutionLabel(appointment.institutionId) }} · 归属管家 #{{ appointment.ownerStaffId }}</div>
        </div>
        <StatusPill :text="appointmentStatusLabel(appointment.status)" :tone="appointmentStatusTone(appointment.status)" />
      </div>
      <div class="fulfillment-summary">
        <div class="fulfillment-mini-card"><small>计划服务项目</small><strong>{{ detail.items.length }} 项 / {{ plannedCount }} 次</strong></div>
        <div class="fulfillment-mini-card"><small>机构正式核销</small><strong>{{ postedCount }} 次</strong></div>
        <div class="fulfillment-mini-card"><small>当前状态</small><strong>{{ appointmentStatusLabel(appointment.status) }}</strong></div>
      </div>
      <el-descriptions :column="3" border class="trading-descriptions">
        <el-descriptions-item label="计划到院">{{ formatDateTime(appointment.scheduledAt) }}</el-descriptions-item>
        <el-descriptions-item label="接诊确认">{{ formatDateTime(transitionTime('CONFIRMED')) }}</el-descriptions-item>
        <el-descriptions-item label="实际到院">{{ formatDateTime(transitionTime('CHECKED_IN')) }}</el-descriptions-item>
        <el-descriptions-item label="服务完成">{{ formatDateTime(transitionTime('SERVICE_DONE')) }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(appointment.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="乐观锁版本">{{ appointment.version }}</el-descriptions-item>
        <el-descriptions-item label="预约备注" :span="3">{{ appointment.remark || '-' }}</el-descriptions-item>
      </el-descriptions>

      <h4 class="trading-section-title">逐项目预约计划</h4>
      <app-table :data="detail.items" size="small">
        <el-table-column label="真实项目" min-width="140"><template #default="{ row }">{{ projectLabel(row.projectId) }}</template></el-table-column>
        <el-table-column label="权益批次" width="120"><template #default="{ row }">#{{ row.entitlementId }}</template></el-table-column>
        <el-table-column prop="plannedCount" label="计划次数" width="100" align="right" />
        <el-table-column prop="postedCount" label="已核销次数" width="110" align="right" />
      </app-table>

      <h4 class="trading-section-title">正式核销记录（原记录只读）</h4>
      <div class="fulfillment-readonly">正式核销事实不被冲正申请或审核覆盖；补偿通过独立权益流水呈现。金额规则未确认时仅显示“待核实”，不按 0 元结算。</div>
      <app-table :data="detail.redemptions" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="redemption-lines">
              <div v-for="line in row.lines" :key="line.id" class="redemption-line">
                {{ projectLabel(line.projectId) }} · 权益批次 #{{ line.entitlementId }} · 预约项目 #{{ line.appointmentItemId }} · {{ line.count }} 次
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="核销 ID" width="90"><template #default="{ row }">#{{ row.redemption.id }}</template></el-table-column>
        <el-table-column label="实际服务" width="155"><template #default="{ row }">{{ formatDateTime(row.redemption.serviceAt) }}</template></el-table-column>
        <el-table-column label="次数" width="75" align="right"><template #default="{ row }">{{ redemptionCount(row) }}</template></el-table-column>
        <el-table-column label="状态" width="125"><template #default="{ row }">{{ redemptionStatusLabel(row.redemption.status) }}</template></el-table-column>
        <el-table-column label="金额（元）" width="100" align="right"><template #default><span class="fulfillment-pending-amount">{{ redemptionAmountLabel() }}</span></template></el-table-column>
        <template #empty><el-empty description="尚无正式核销记录" /></template>
      </app-table>

      <h4 class="trading-section-title">预约状态历史（只追加）</h4>
      <el-timeline>
        <el-timeline-item v-for="item in detail.history" :key="item.id" :timestamp="formatDateTime(item.createdAt)">
          <strong>{{ appointmentStatusLabel(item.fromStatus) }} → {{ appointmentStatusLabel(item.toStatus) }}</strong>
          <div class="crm-muted">{{ item.actorUserId === null ? '-' : `用户 #${item.actorUserId}` }}<span v-if="item.reason"> · {{ item.reason }}</span></div>
        </el-timeline-item>
      </el-timeline>
    </template>
    <template #footer>
      <div class="fulfillment-actions">
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        <template v-if="appointment && canWrite && (appointment.status === 'PENDING_INSTITUTION' || appointment.status === 'CONFIRMED')">
          <el-button data-testid="appointment-cancel" @click="emit('action', 'cancel')">取消预约</el-button>
        </template>
        <template v-if="appointment && canConfirm">
          <el-button v-if="appointment.status === 'PENDING_INSTITUTION'" type="danger" plain data-testid="appointment-reject" @click="emit('action', 'reject')">拒绝接诊</el-button>
          <el-button v-if="appointment.status === 'PENDING_INSTITUTION'" type="primary" data-testid="appointment-confirm" @click="emit('action', 'confirm')">确认接诊</el-button>
          <el-button v-if="appointment.status === 'CONFIRMED'" type="primary" data-testid="appointment-check-in" @click="emit('action', 'check-in')">确认实际到院</el-button>
          <el-button v-if="appointment.status === 'CHECKED_IN'" type="primary" data-testid="appointment-service-done" @click="emit('action', 'service-done')">确认服务完成</el-button>
        </template>
        <el-button v-if="appointment && canPost && appointment.status === 'SERVICE_DONE'" type="primary" data-testid="appointment-final-redemption" @click="emit('action', 'redeem')">
          选择项目并正式核销
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.redemption-lines { display: grid; gap: 7px; padding: 8px 18px; }
.redemption-line { color: #607d87; font-size: 12px; line-height: 1.6; }
</style>
