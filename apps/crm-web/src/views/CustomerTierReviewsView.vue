<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerTierReview } from '../api/customerTier'
import { listCustomerTierReviews, reviewCustomerTierDowngrade } from '../api/customerTier'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const items = ref<CustomerTierReview[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('PENDING_REVIEW')
const loading = ref(false)
const errorMessage = ref('')
const dialogVisible = ref(false)
const submitting = ref(false)
const actionForm = reactive<{ review: CustomerTierReview | null; action: 'approve' | 'reject'; reason: string }>({
  review: null, action: 'approve', reason: '',
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCustomerTierReviews({ status: status.value, page: page.value, size: size.value })
    items.value = result.items
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '复核列表加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function open(review: CustomerTierReview, action: 'approve' | 'reject') {
  actionForm.review = review
  actionForm.action = action
  actionForm.reason = ''
  dialogVisible.value = true
}

async function submit() {
  if (!actionForm.review || !actionForm.reason.trim()) {
    ElMessage.warning('请填写复核理由')
    return
  }
  submitting.value = true
  try {
    await reviewCustomerTierDowngrade(actionForm.review.id, actionForm.action, {
      version: actionForm.review.version,
      reason: actionForm.reason.trim(),
    }, crypto.randomUUID())
    ElMessage.success(actionForm.action === 'approve' ? '降级已批准并追加历史' : '已驳回，客户等级保持不变')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '复核提交失败')
  } finally {
    submitting.value = false
  }
}

function statusLabel(value: string): string {
  return { PENDING_REVIEW: '待复核', APPROVED: '已批准', REJECTED: '已驳回', STALE: '已失效' }[value] ?? value
}
</script>

<template>
  <div class="tier-reviews">
    <header class="page-heading">
      <div><h1>客户降级复核</h1><p>自动降级只生成待复核单；规则、指标快照或客户版本变化后旧单不可审批。</p></div>
      <el-select v-model="status" data-testid="tier-review-status" @change="page = 1; load()">
        <el-option label="待复核" value="PENDING_REVIEW" />
        <el-option label="已批准" value="APPROVED" />
        <el-option label="已驳回" value="REJECTED" />
        <el-option label="已失效" value="STALE" />
        <el-option label="全部" value="" />
      </el-select>
    </header>
    <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" />
    <el-table v-loading="loading" :data="items">
      <el-table-column prop="id" label="复核单" width="90" />
      <el-table-column prop="customerId" label="客户 ID" width="100" />
      <el-table-column label="拟变更" width="110"><template #default="{ row }">{{ row.fromTierCode }} → {{ row.toTierCode }}</template></el-table-column>
      <el-table-column prop="ruleId" label="规则版本" width="100" />
      <el-table-column prop="snapshotId" label="指标快照" width="110" />
      <el-table-column prop="baseCustomerVersion" label="客户版本" width="100" />
      <el-table-column label="状态" width="100"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
      <el-table-column prop="reviewReason" label="复核理由" min-width="150" />
      <el-table-column v-if="auth.hasPermission('customer:write')" label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING_REVIEW'">
            <el-button link type="primary" :data-testid="`tier-review-approve-${row.id}`" @click="open(row, 'approve')">批准</el-button>
            <el-button link type="danger" :data-testid="`tier-review-reject-${row.id}`" @click="open(row, 'reject')">驳回</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty><el-empty description="当前范围没有复核单" /></template>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="load"
      @size-change="page = 1; load()"
    />

    <el-dialog v-model="dialogVisible" :title="actionForm.action === 'approve' ? '批准降级' : '驳回降级'" width="min(520px, 92vw)">
      <el-alert
        v-if="actionForm.review"
        type="warning"
        :closable="false"
        :title="`复核单 #${actionForm.review.id}，版本 ${actionForm.review.version}；批准只改变当前等级并追加历史，驳回不改等级`"
      />
      <el-input
        v-model="actionForm.reason"
        type="textarea"
        :rows="4"
        maxlength="1000"
        placeholder="必填复核理由"
        data-testid="tier-review-reason"
        class="review-reason"
      />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" data-testid="tier-review-submit" @click="submit">提交复核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tier-reviews { display: grid; gap: 16px; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-heading h1 { margin: 0 0 6px; font-size: 22px; }
.page-heading p { margin: 0; color: #667085; }
.review-reason { margin-top: 16px; }
</style>
