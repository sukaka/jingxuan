<script setup lang="ts">
import type { InstitutionChangeRequest } from '../../api/institution'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { changeStatusLabel, changeStatusTone, changeTypeLabel, changeValueLabel, fieldLabel, requiresBothSteps, reviewStatusLabel, workflowStepLabel } from './institutionUi'
import { useReferenceNames } from '../references'

const { institutionLabel } = useReferenceNames()

const props = defineProps<{
  requests: InstitutionChangeRequest[]
  loading: boolean
  canMarket: boolean
  canFinance: boolean
  canChange: boolean
  currentUserId: number
}>()
const emit = defineEmits<{
  review: [request: InstitutionChangeRequest, step: 'MARKET' | 'FINANCE', decision: 'APPROVE' | 'REJECT']
  revalidate: [request: InstitutionChangeRequest]
}>()

function canReview(request: InstitutionChangeRequest, step: 'MARKET' | 'FINANCE'): boolean {
  if (request.status !== 'PENDING_REVIEW' || request.currentStep !== step || request.requestedBy === props.currentUserId) return false
  if (step === 'MARKET' && !props.canMarket) return false
  if (step === 'FINANCE' && !props.canFinance) return false
  if (step === 'FINANCE' && requiresBothSteps(request)) {
    const marketReviewer = request.steps.find((item) => item.stepCode === 'MARKET' && item.status === 'APPROVED')?.reviewerUserId
    if (marketReviewer === props.currentUserId) return false
  }
  return true
}
</script>

<template>
  <app-table v-loading="loading" :data="requests" row-key="id">
    <el-table-column label="申请 / 机构" width="140">
      <template #default="{ row }"><strong>#{{ row.id }}</strong><div class="trading-cell-sub">{{ institutionLabel(row.institutionId) }} · {{ formatDateTime(row.requestedAt) }}</div></template>
    </el-table-column>
    <el-table-column label="类型 / 字段" min-width="160">
      <template #default="{ row }">{{ changeTypeLabel(row.changeType) }}<div class="trading-cell-sub">{{ fieldLabel(row.fieldCode) }}</div></template>
    </el-table-column>
    <el-table-column label="原掩码值 → 申请掩码值" min-width="220">
      <template #default="{ row }">
        <div class="crm-muted">{{ changeValueLabel(row.fieldCode, row.beforeMasked) }}</div>
        <strong class="after-value">→ {{ changeValueLabel(row.fieldCode, row.afterMasked) }}</strong>
        <div class="trading-cell-sub">{{ row.reason }}</div>
      </template>
    </el-table-column>
    <el-table-column label="审核步骤" min-width="205">
      <template #default="{ row }">
        <div v-for="step in row.steps" :key="step.id" class="step-line">
          {{ workflowStepLabel(step.stepCode) }} · {{ reviewStatusLabel(step.status) }}<span v-if="step.reviewerUserId"> · 用户 #{{ step.reviewerUserId }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="当前状态" width="145">
      <template #default="{ row }">
        <StatusPill :text="changeStatusLabel(row.status)" :tone="changeStatusTone(row.status)" />
        <div class="trading-cell-sub">{{ workflowStepLabel(row.currentStep) }} · 版本 {{ row.version }}</div>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="250" fixed="right">
      <template #default="{ row }">
        <el-alert v-if="row.status === 'CONFLICT_REVALIDATE'" type="warning" :closable="false" title="基准版本冲突" class="conflict-alert">
          <template #default>目标版本已变化，禁止覆盖。可重新复核。</template>
        </el-alert>
        <el-button v-if="row.status === 'CONFLICT_REVALIDATE' && canChange" text size="small" type="primary" data-testid="change-revalidate" @click="emit('revalidate', row)">重新复核</el-button>
        <template v-if="canReview(row, 'MARKET')">
          <el-button text size="small" type="primary" data-testid="market-approve" @click="emit('review', row, 'MARKET', 'APPROVE')">市场通过</el-button>
          <el-button text size="small" type="danger" @click="emit('review', row, 'MARKET', 'REJECT')">市场驳回</el-button>
        </template>
        <template v-if="canReview(row, 'FINANCE')">
          <el-button text size="small" type="primary" data-testid="finance-approve" @click="emit('review', row, 'FINANCE', 'APPROVE')">财务通过</el-button>
          <el-button text size="small" type="danger" @click="emit('review', row, 'FINANCE', 'REJECT')">财务驳回</el-button>
        </template>
      </template>
    </el-table-column>
    <template #empty><el-empty description="暂无变更申请" /></template>
  </app-table>
</template>

<style scoped>
.after-value { color: #0f948b; }
.step-line { margin-bottom: 5px; color: #6f8794; font-size: 11px; line-height: 1.5; }
.conflict-alert { margin-bottom: 6px; padding: 5px 8px; }
</style>
