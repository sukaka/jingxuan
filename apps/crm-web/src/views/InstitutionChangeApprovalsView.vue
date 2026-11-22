<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getInstitutionProfile,
  listInstitutionChanges,
  revalidateInstitutionChange,
  reviewInstitutionChangeFinance,
  reviewInstitutionChangeMarket,
} from '../api/institution'
import type { InstitutionChangeRequest } from '../api/institution'
import { useAuthStore } from '../stores/auth'
import ChangeRequestPanel from './institution/ChangeRequestPanel.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { changeStatusLabel, changeValueLabel, createInstitutionKey, fieldLabel, workflowStepLabel } from './institution/institutionUi'
import { useReferenceNames } from './references'
import { formatDateTime } from './customer/shared'

const { institutionLabel } = useReferenceNames()

const auth = useAuthStore(), requests = ref<InstitutionChangeRequest[]>([]), loading = ref(false)
const errorMessage = ref('')
const canMarket = computed(() => auth.hasPermission('institution:approve_market')), canFinance = computed(() => auth.hasPermission('institution:approve_finance')), canChange = computed(() => auth.hasPermission('institution:change_request'))
const pending = computed(() => requests.value.filter((item) => item.status === 'PENDING_REVIEW').length)
const market = computed(() => requests.value.filter((item) => item.currentStep === 'MARKET' && item.status === 'PENDING_REVIEW').length)
const finance = computed(() => requests.value.filter((item) => item.currentStep === 'FINANCE' && item.status === 'PENDING_REVIEW').length)
const approved = computed(() => requests.value.filter((item) => item.status === 'APPROVED').length)

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    requests.value = (await listInstitutionChanges({ page: 1, size: 100 })).items
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载变更申请失败'
    ElMessage.error(errorMessage.value)
  } finally {
    loading.value = false
  }
}

async function review(row: InstitutionChangeRequest, step: 'MARKET'|'FINANCE', decision: 'APPROVE'|'REJECT') {
  try {
    const result = await ElMessageBox.prompt('请填写审核意见（将写入审计）', `${step === 'MARKET' ? '市场' : '财务'}审核`, { inputType: 'textarea', inputValidator: (value) => !!value.trim() || '请填写审核意见' })
    const body = { version: row.version, decision, comment: result.value.trim() }
    if (step === 'MARKET') await reviewInstitutionChangeMarket(row.id, body, createInstitutionKey('market-review'))
    else await reviewInstitutionChangeFinance(row.id, body, createInstitutionKey('finance-review'))
    ElMessage.success('审核已记录'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '审核失败') }
}

let revalidateAttempt: { signature: string; key: string } | null = null

async function revalidate(row: InstitutionChangeRequest) {
  try {
    const latest = await getInstitutionProfile(row.institutionId)
    const result = await ElMessageBox.prompt(
      `将以机构当前版本 ${latest.version} 重新复核，不覆盖较新正式值。请填写复核原因。`,
      '基准版本冲突复核',
      {
        confirmButtonText: '提交重新复核',
        cancelButtonText: '返回',
        inputType: 'textarea',
        inputValidator: (value) => value.trim() ? true : '请填写复核原因',
      },
    )
    const reason = result.value.trim()
    const signature = `${row.id}:${row.version}:${latest.version}:${reason}`
    if (revalidateAttempt?.signature !== signature) {
      revalidateAttempt = { signature, key: createInstitutionKey('revalidate') }
    }
    const saved = await revalidateInstitutionChange(
      row.id,
      { version: row.version, baseVersion: latest.version, reason },
      revalidateAttempt.key,
    )
    revalidateAttempt = null
    ElMessage.success(`已重新提交复核：${changeStatusLabel(saved.status)} / ${workflowStepLabel(saved.currentStep)}`)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '重新复核失败，请核对最新基准版本后重试')
  }
}

onMounted(load)
</script>

<template>
  <div class="proto-page">
    <PageHeader title="机构资料变更审批" description="普通资料与敏感变更分离，市场与财务按顺序独立审核，版本冲突必须复核" />
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" style="margin-bottom:16px" />
    <div class="proto-stat-grid">
      <StatCard label="待审核" :value="`${pending} 项`" sub="等待处理" icon="clipboard" />
      <StatCard label="待市场" :value="`${market} 项`" sub="市场先行审核" icon="users" />
      <StatCard label="待财务" :value="`${finance} 项`" sub="市场通过后处理" icon="wallet" />
      <StatCard label="已生效" :value="`${approved} 项`" sub="保留完整审计" icon="check" />
    </div>
    <PageCard title="资料变更审批队列" subtitle="申请人与审核人必须不同，双审字段不得由同一人完成">
      <ChangeRequestPanel :requests="requests" :loading="loading" :can-market="canMarket" :can-finance="canFinance" :can-change="canChange" :current-user-id="auth.user?.userId ?? -1" @review="review" @revalidate="revalidate" />
    </PageCard>
    <PageCard title="资料变更审计日志" subtitle="仅展示真实申请与审批结果" style="margin-top:18px">
      <div class="proto-card-body">
        <app-table :data="requests.filter(item => item.status==='APPROVED')">
          <el-table-column label="生效时间" min-width="160"><template #default="{row}">{{ formatDateTime(row.completedAt) }}</template></el-table-column>
          <el-table-column label="机构" min-width="120"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
          <el-table-column label="字段" min-width="120"><template #default="{row}">{{ fieldLabel(row.fieldCode) }}</template></el-table-column>
          <el-table-column label="原值" min-width="130"><template #default="{row}">{{ changeValueLabel(row.fieldCode, row.beforeMasked) }}</template></el-table-column>
          <el-table-column label="新值" min-width="130"><template #default="{row}">{{ changeValueLabel(row.fieldCode, row.afterMasked) }}</template></el-table-column>
          <el-table-column label="申请人" width="95"><template #default="{row}">用户 #{{ row.requestedBy }}</template></el-table-column>
          <el-table-column label="结果" width="95"><template #default><span class="proto-pill success">已生效</span></template></el-table-column>
          <template #empty>暂无已生效变更</template>
        </app-table>
      </div>
    </PageCard>
  </div>
</template>
