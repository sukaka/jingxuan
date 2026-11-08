<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerOwnerOption, OwnershipHistoryItem, OwnershipTransferItem } from '../../api/customer'
import {
  approveOwnershipTransfer,
  createOwnershipTransfer,
  listCustomerOwnerOptions,
  listOwnershipHistory,
  listOwnershipTransfers,
  rejectOwnershipTransfer,
} from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import StatusPill from './StatusPill.vue'
import { formatDateTime } from './shared'

const props = defineProps<{ customerId: number }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const canTransfer = auth.hasPermission('customer:transfer')

const transfers = ref<OwnershipTransferItem[]>([])
const history = ref<OwnershipHistoryItem[]>([])
const loading = ref(false)
const errorMessage = ref('')

const TRANSFER_STATUS: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'info' | 'dark' }> = {
  PENDING: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已审批', tone: 'info' },
  EFFECTIVE: { label: '已生效', tone: 'success' },
  REJECTED: { label: '已拒绝', tone: 'danger' },
  CANCELED: { label: '已取消', tone: 'dark' },
}

function transferStatusLabel(status: string) {
  return TRANSFER_STATUS[status] ?? { label: '未知状态', tone: 'dark' as const }
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '客户不存在或不在你的数据范围内'
    if (error.status === 409) return '申请状态已变化,请刷新后重试'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [transferList, historyList] = await Promise.all([
      listOwnershipTransfers(props.customerId),
      listOwnershipHistory(props.customerId),
    ])
    transfers.value = transferList
    history.value = historyList
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

// ---- 发起转交 ----
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const ownerOptions = ref<CustomerOwnerOption[]>([])
const ownerOptionsError = ref('')
const createForm = reactive({ toOwnerId: undefined as number | undefined, reason: '' })

function ownerOptionLabel(option: CustomerOwnerOption): string {
  return option.teamName ? `${option.displayName}(${option.teamName})` : option.displayName
}

async function openCreateDialog() {
  createForm.toOwnerId = undefined
  createForm.reason = ''
  createDialogVisible.value = true
  ownerOptionsError.value = ''
  try {
    ownerOptions.value = await listCustomerOwnerOptions()
  } catch (error) {
    // 无权限或接口失败:明确提示且不伪造任何选项
    ownerOptions.value = []
    ownerOptionsError.value = errorText(error)
  }
}

async function submitCreate() {
  if (!createForm.toOwnerId) {
    ElMessage.warning('请选择新管家')
    return
  }
  if (!createForm.reason.trim()) {
    ElMessage.warning('请填写转交原因(将写入归属日志与审计)')
    return
  }
  createSubmitting.value = true
  try {
    await createOwnershipTransfer(props.customerId, {
      newOwnerId: createForm.toOwnerId,
      reason: createForm.reason.trim(),
    })
    ElMessage.success('转交申请已提交,审批生效后归属才会变更')
    createDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    createSubmitting.value = false
  }
}

// ---- 审批 ----
async function handleApprove(item: OwnershipTransferItem) {
  try {
    await ElMessageBox.confirm(
      '批准后客户归属(管家与团队)立即变更并追加不可改的归属日志。确认批准?',
      '批准转交',
      { type: 'warning', confirmButtonText: '批准', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await approveOwnershipTransfer(item.id, item.version)
    ElMessage.success('已批准并生效')
    emit('changed')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

const rejectDialogVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectTarget = ref<OwnershipTransferItem | null>(null)
const rejectReason = ref('')

function openRejectDialog(item: OwnershipTransferItem) {
  rejectTarget.value = item
  rejectReason.value = ''
  rejectDialogVisible.value = true
}

async function submitReject() {
  if (!rejectTarget.value) return
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝原因')
    return
  }
  rejectSubmitting.value = true
  try {
    await rejectOwnershipTransfer(rejectTarget.value.id, rejectTarget.value.version, rejectReason.value.trim())
    ElMessage.success('已拒绝该转交申请')
    rejectDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    rejectSubmitting.value = false
  }
}

function ownerLabel(id: number | null | undefined, name: string | null | undefined): string {
  return name ?? (id != null ? `#${id}` : '-')
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      :title="`加载失败:${errorMessage}`"
      class="crm-table-alert"
    />

    <div class="section-head">
      <span class="section-title">转交申请</span>
      <el-button v-if="canTransfer" type="primary" size="small" @click="openCreateDialog">
        发起转交
      </el-button>
    </div>
    <app-table :data="transfers" size="small">
      <el-table-column prop="id" label="申请号" width="80" />
      <el-table-column label="原管家" width="110">
        <template #default="{ row }">{{ ownerLabel(row.fromOwnerId, row.fromOwnerName) }}</template>
      </el-table-column>
      <el-table-column label="新管家" width="110">
        <template #default="{ row }">{{ ownerLabel(row.toOwnerId, row.toOwnerName) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusPill :text="transferStatusLabel(row.status).label" :tone="transferStatusLabel(row.status).tone" />
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="申请人" width="100">
        <template #default="{ row }">{{ row.requestedByName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="申请时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column v-if="canTransfer" label="操作" min-width="230">
        <template #default="{ row }">
          <div v-if="row.status === 'PENDING'" class="ownership-actions">
            <el-button size="small" type="primary" @click="handleApprove(row)">批准</el-button>
            <el-button size="small" type="danger" @click="openRejectDialog(row)">拒绝</el-button>
          </div>
          <span v-else class="crm-muted">已处理</span>
        </template>
      </el-table-column>
      <template #empty>
        <span>暂无转交申请</span>
      </template>
    </app-table>

    <div class="section-head">
      <span class="section-title">归属历史(已生效,不可修改)</span>
    </div>
    <app-table :data="history" size="small">
      <el-table-column label="原管家" width="100">
        <template #default="{ row }">{{ ownerLabel(row.oldOwnerId, row.oldOwnerName) }}</template>
      </el-table-column>
      <el-table-column label="新管家" width="100">
        <template #default="{ row }">{{ ownerLabel(row.newOwnerId, row.newOwnerName) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="100">
        <template #default="{ row }">{{ row.changedByName ?? `#${row.changedBy}` }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="生效时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.effectiveAt) }}</template>
      </el-table-column>
      <template #empty>
        <span>暂无归属变更记录</span>
      </template>
    </app-table>

    <el-dialog v-model="createDialogVisible" title="发起客户转交" width="460px">
      <el-alert
        type="info"
        :closable="false"
        title="同组主管可转交本组客户,跨组转交仅管理员;批准后归属才变更,历史日志不可覆盖"
        class="crm-table-alert"
      />
      <el-alert
        v-if="ownerOptionsError"
        type="error"
        :closable="false"
        :title="`管家选项加载失败:${ownerOptionsError}`"
        class="crm-table-alert"
      />
      <el-form label-width="90px">
        <el-form-item label="新管家" required>
          <el-select
            v-model="createForm.toOwnerId"
            filterable
            :disabled="ownerOptionsError !== ''"
            :placeholder="ownerOptionsError ? '选项不可用' : '按姓名搜索'"
            class="full-width"
          >
            <el-option
              v-for="option in ownerOptions"
              :key="option.id"
              :label="ownerOptionLabel(option)"
              :value="option.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="转交原因" required>
          <el-input v-model="createForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectDialogVisible" title="拒绝转交申请" width="460px">
      <el-form label-width="90px">
        <el-form-item label="拒绝原因" required>
          <el-input v-model="rejectReason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" @click="submitReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 18px 0 10px;
}

.section-head:first-child {
  margin-top: 0;
}

.section-title {
  font-weight: 700;
  font-size: 14px;
}

.ownership-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.ownership-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.full-width {
  width: 100%;
}
</style>
