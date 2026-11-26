<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveOwnershipTransfer,
  listCustomerOwnerOptions,
  listCustomerOwnershipLogs,
  listScopedOwnershipTransfers,
  rejectOwnershipTransfer,
} from '../api/customer'
import type {
  CustomerOwnerOption,
  CustomerOwnershipLogItem,
  ScopedOwnershipTransferItem,
} from '../api/customer'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { formatDateTime } from './customer/shared'

const auth = useAuthStore()
const canTransfer = computed(() => auth.hasPermission('customer:transfer'))
const activeTab = ref<'history' | 'transfers'>('history')
const ownerOptions = ref<CustomerOwnerOption[]>([])

const historyQuery = reactive({
  keyword: '',
  ownerId: undefined as number | undefined,
  page: 1,
  size: 20,
})
const historyRows = ref<CustomerOwnershipLogItem[]>([])
const historyTotal = ref(0)
const historyLoading = ref(false)
const historyError = ref('')

const transferQuery = reactive({
  keyword: '',
  status: '',
  ownerId: undefined as number | undefined,
  page: 1,
  size: 20,
})
const transferRows = ref<ScopedOwnershipTransferItem[]>([])
const transferTotal = ref(0)
const transferLoading = ref(false)
const transferError = ref('')
const transfersLoaded = ref(false)

const rejectDialogVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectTarget = ref<ScopedOwnershipTransferItem | null>(null)
const rejectReason = ref('')

const STATUS_MAP: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'info' | 'dark' }> = {
  PENDING: { label: '待审批', tone: 'warning' },
  APPROVED: { label: '已审批', tone: 'info' },
  EFFECTIVE: { label: '已生效', tone: 'success' },
  REJECTED: { label: '已拒绝', tone: 'danger' },
  CANCELED: { label: '已取消', tone: 'dark' },
}

const MODE_MAP: Record<string, { label: string; tone: 'success' | 'info' | 'dark' }> = {
  DIRECT_ASSIGN: { label: '主管直接分配', tone: 'success' },
  BATCH_ASSIGN: { label: '主管批量分配', tone: 'success' },
  TRANSFER_APPROVAL: { label: '转交审批', tone: 'info' },
}

function statusMeta(status: string) {
  return STATUS_MAP[status] ?? { label: '未知状态', tone: 'dark' as const }
}

function modeMeta(mode: string) {
  return MODE_MAP[mode] ?? { label: '归属变更', tone: 'dark' as const }
}

function ownerOptionLabel(option: CustomerOwnerOption): string {
  return option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName
}

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : fallback
}

async function loadHistory(page = historyQuery.page) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await listCustomerOwnershipLogs({
      page,
      size: historyQuery.size,
      keyword: historyQuery.keyword || undefined,
      ownerId: historyQuery.ownerId,
    })
    historyRows.value = result.items
    historyTotal.value = result.total
    historyQuery.page = result.page
    historyQuery.size = result.size
  } catch (error) {
    historyError.value = errorText(error, '归属记录加载失败')
  } finally {
    historyLoading.value = false
  }
}

async function loadTransfers(page = transferQuery.page) {
  transferLoading.value = true
  transferError.value = ''
  try {
    const result = await listScopedOwnershipTransfers({
      page,
      size: transferQuery.size,
      status: transferQuery.status || undefined,
      ownerId: transferQuery.ownerId,
      keyword: transferQuery.keyword || undefined,
    })
    transferRows.value = result.items
    transferTotal.value = result.total
    transferQuery.page = result.page
    transferQuery.size = result.size
    transfersLoaded.value = true
  } catch (error) {
    transferError.value = errorText(error, '转交审批单加载失败')
  } finally {
    transferLoading.value = false
  }
}

async function handleApprove(item: ScopedOwnershipTransferItem) {
  try {
    await ElMessageBox.confirm(
      `批准后客户「${item.customerName}」归属将立即变更为 ${item.toOwnerName || `#${item.toOwnerId}`}，并追加不可修改归属日志。确认批准？`,
      '批准转交',
      { type: 'warning', confirmButtonText: '批准', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await approveOwnershipTransfer(item.id, item.version)
    ElMessage.success('已批准并生效')
    await Promise.all([loadTransfers(), loadHistory()])
  } catch (error) {
    ElMessage.error(errorText(error, '批准失败'))
  }
}

function openReject(item: ScopedOwnershipTransferItem) {
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
    await loadTransfers()
  } catch (error) {
    ElMessage.error(errorText(error, '拒绝失败'))
  } finally {
    rejectSubmitting.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'transfers' && !transfersLoaded.value) {
    void loadTransfers(1)
  }
})

onMounted(() => {
  void loadHistory(1)
  void listCustomerOwnerOptions().then((result) => { ownerOptions.value = result }).catch(() => { ownerOptions.value = [] })
})
</script>

<template>
  <div class="proto-page">
    <PageHeader
      title="客户归属日志"
      description="统一留存主管直接分配、批量分配与转交审批的全部前后归属、操作人、原因和生效时间，历史不可覆盖"
    />
    <PageCard>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="已生效归属记录" name="history">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-input
                v-model="historyQuery.keyword"
                placeholder="客户姓名 / 昵称 / 编号"
                clearable
                style="width:240px"
                data-testid="ownership-history-keyword"
                @keyup.enter="loadHistory(1)"
              />
              <el-select
                v-if="ownerOptions.length > 0"
                v-model="historyQuery.ownerId"
                placeholder="按涉及管家筛选"
                clearable
                filterable
                style="width:185px"
                data-testid="ownership-history-owner"
                @change="loadHistory(1)"
              >
                <el-option v-for="option in ownerOptions" :key="option.id" :label="ownerOptionLabel(option)" :value="option.id" />
              </el-select>
              <el-button type="primary" :loading="historyLoading" @click="loadHistory(1)">查询</el-button>
            </div>
          </div>
          <el-alert v-if="historyError" type="error" :title="historyError" :closable="false" />
          <div class="proto-table-wrap">
            <app-table v-loading="historyLoading" :data="historyRows">
              <el-table-column label="客户" min-width="150">
                <template #default="{ row }">
                  <strong>{{ row.customerName }}</strong>
                  <div class="proto-subcell">{{ row.customerNo }}</div>
                </template>
              </el-table-column>
              <el-table-column label="原管家" min-width="105">
                <template #default="{ row }">{{ row.oldOwnerName || `#${row.oldOwnerId}` }}</template>
              </el-table-column>
              <el-table-column label="新管家" min-width="105">
                <template #default="{ row }">{{ row.newOwnerName || `#${row.newOwnerId}` }}</template>
              </el-table-column>
              <el-table-column label="变更方式" width="130">
                <template #default="{ row }">
                  <span class="proto-pill" :class="modeMeta(row.changeMode).tone">{{ modeMeta(row.changeMode).label }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作人" min-width="105">
                <template #default="{ row }">{{ row.changedByName || `#${row.changedBy}` }}</template>
              </el-table-column>
              <el-table-column prop="reason" label="变更原因" min-width="190" show-overflow-tooltip>
                <template #default="{ row }">{{ row.reason || '—' }}</template>
              </el-table-column>
              <el-table-column label="生效时间" min-width="160">
                <template #default="{ row }">{{ formatDateTime(row.effectiveAt || row.createdAt) }}</template>
              </el-table-column>
              <template #empty>暂无已生效归属变更记录</template>
            </app-table>
          </div>
          <el-pagination
            v-model:current-page="historyQuery.page"
            v-model:page-size="historyQuery.size"
            :total="historyTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadHistory"
            @size-change="loadHistory(1)"
          />
        </el-tab-pane>

        <el-tab-pane label="转交审批单" name="transfers">
          <div class="proto-toolbar">
            <div class="proto-toolbar-left">
              <el-input
                v-model="transferQuery.keyword"
                placeholder="客户姓名 / 昵称 / 编号"
                clearable
                style="width:240px"
                @keyup.enter="loadTransfers(1)"
              />
              <el-select
                v-model="transferQuery.status"
                placeholder="全部状态"
                clearable
                style="width:135px"
                @change="loadTransfers(1)"
              >
                <el-option label="待审批" value="PENDING" />
                <el-option label="已生效" value="EFFECTIVE" />
                <el-option label="已拒绝" value="REJECTED" />
              </el-select>
              <el-select
                v-if="ownerOptions.length > 0"
                v-model="transferQuery.ownerId"
                placeholder="按涉及管家筛选"
                clearable
                filterable
                style="width:185px"
                @change="loadTransfers(1)"
              >
                <el-option v-for="option in ownerOptions" :key="option.id" :label="ownerOptionLabel(option)" :value="option.id" />
              </el-select>
              <el-button type="primary" :loading="transferLoading" @click="loadTransfers(1)">查询</el-button>
            </div>
          </div>
          <el-alert v-if="transferError" type="error" :title="transferError" :closable="false" />
          <div class="proto-table-wrap">
            <app-table v-loading="transferLoading" :data="transferRows">
              <el-table-column label="客户" min-width="150">
                <template #default="{ row }">
                  <strong>{{ row.customerName }}</strong>
                  <div class="proto-subcell">{{ row.customerNo }}</div>
                </template>
              </el-table-column>
              <el-table-column label="原管家" min-width="105">
                <template #default="{ row }">{{ row.fromOwnerName || `#${row.fromOwnerId}` }}</template>
              </el-table-column>
              <el-table-column label="新管家" min-width="105">
                <template #default="{ row }">{{ row.toOwnerName || `#${row.toOwnerId}` }}</template>
              </el-table-column>
              <el-table-column label="申请人" min-width="105">
                <template #default="{ row }">{{ row.requestedByName || '—' }}</template>
              </el-table-column>
              <el-table-column label="审核人" min-width="105">
                <template #default="{ row }">{{ row.reviewedByName || '待审核' }}</template>
              </el-table-column>
              <el-table-column prop="reason" label="转交原因" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <span class="proto-pill" :class="statusMeta(row.status).tone">{{ statusMeta(row.status).label }}</span>
                </template>
              </el-table-column>
              <el-table-column label="生效时间" min-width="160">
                <template #default="{ row }">{{ row.effectiveAt ? formatDateTime(row.effectiveAt) : '尚未生效' }}</template>
              </el-table-column>
              <el-table-column v-if="canTransfer" label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <template v-if="row.status === 'PENDING'">
                    <el-button size="small" type="primary" text @click="handleApprove(row)">批准</el-button>
                    <el-button size="small" type="danger" text @click="openReject(row)">拒绝</el-button>
                  </template>
                  <span v-else class="crm-muted">已处理</span>
                </template>
              </el-table-column>
              <template #empty>暂无转交审批记录</template>
            </app-table>
          </div>
          <el-pagination
            v-model:current-page="transferQuery.page"
            v-model:page-size="transferQuery.size"
            :total="transferTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadTransfers"
            @size-change="loadTransfers(1)"
          />
        </el-tab-pane>
      </el-tabs>
    </PageCard>
    <footer class="proto-footer">归属变更仅追加不可修改日志，不改写历史订单成交与核销快照</footer>

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
