<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { AuditLogItem } from '../api/audit'
import { listAuditLogs } from '../api/audit'
import { ApiError } from '../types/api'

const query = reactive({
  actorId: undefined as number | undefined,
  actionCode: '',
  targetType: '',
  page: 1,
  size: 20,
})
const logs = ref<AuditLogItem[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString()
}

function formatSnapshot(value: unknown): string {
  if (value === null || value === undefined) return '-'
  // 后端 JSON 列可能以字符串形式返回,直接展示原文;对象则格式化
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAuditLogs({
      page,
      size: query.size,
      actorId: query.actorId ?? null,
      actionCode: query.actionCode,
      targetType: query.targetType,
    })
    logs.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    logs.value = []
    total.value = 0
    errorMessage.value =
      error instanceof ApiError ? error.message : '加载失败,请稍后重试'
  } finally {
    loading.value = false
  }
}

function search() {
  void load(1)
}

function resetQuery() {
  query.actorId = undefined
  query.actionCode = ''
  query.targetType = ''
  void load(1)
}

onMounted(() => load())
</script>

<template>
  <div>
    <h2 class="page-title">审计日志</h2>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input-number
          v-model="query.actorId"
          :min="1"
          controls-position="right"
          placeholder="行为人 ID"
          class="filter-number"
        />
        <el-input
          v-model="query.actionCode"
          placeholder="动作编码,如 auth.login"
          class="filter-input"
          clearable
          @keyup.enter="search"
        />
        <el-input
          v-model="query.targetType"
          placeholder="资源类型,如 sys_user"
          class="filter-input"
          clearable
          @keyup.enter="search"
        />
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        :title="`加载失败:${errorMessage}`"
        class="table-alert"
      />

      <app-table v-else v-loading="loading" :data="logs">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="snapshot">
              <div>
                <div class="snapshot-title">操作前(脱敏快照)</div>
                <pre class="snapshot-json">{{ formatSnapshot(row.beforeJson) }}</pre>
              </div>
              <div>
                <div class="snapshot-title">操作后(脱敏快照)</div>
                <pre class="snapshot-json">{{ formatSnapshot(row.afterJson) }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="actorUserId" label="行为人" width="90">
          <template #default="{ row }">{{ row.actorUserId ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="actionCode" label="动作" min-width="140" />
        <el-table-column prop="targetType" label="资源类型" width="120" />
        <el-table-column prop="targetId" label="资源 ID" width="100" />
        <el-table-column prop="clientIp" label="客户端 IP" width="130">
          <template #default="{ row }">{{ row.clientIp ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="traceId" label="链路追踪 ID" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.traceId ?? '-' }}</template>
        </el-table-column>
        <template #empty>
          <span>暂无审计日志</span>
        </template>
      </app-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="(page: number) => load(page)"
        @size-change="(size: number) => { query.size = size; void load(1) }"
      />
    </el-card>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-number {
  width: 150px;
}

.filter-input {
  width: 220px;
}

.table-alert {
  margin-bottom: 4px;
}

.snapshot {
  display: flex;
  gap: 24px;
  padding: 8px 16px;
}

.snapshot-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.snapshot-json {
  margin: 0;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  max-width: 480px;
  overflow: auto;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
