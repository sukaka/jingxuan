<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import type { FollowupItem } from '../../api/followup'
import { listFollowups } from '../../api/followup'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import FollowupFormDialog from './FollowupFormDialog.vue'
import { fenToYuan, formatDateTime, useDictionaries } from './shared'

const props = defineProps<{
  /** 客户详情内嵌时固定客户,隐藏客户筛选;否则显示客户 ID 筛选(跟进台账页) */
  fixedCustomerId?: number | null
}>()
const emit = defineEmits<{
  saved: []
}>()

const auth = useAuthStore()
const dicts = useDictionaries()

const query = reactive({
  customerId: undefined as number | undefined,
  page: 1,
  size: 20,
})
const items = ref<FollowupItem[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '客户不存在或不在你的数据范围内'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listFollowups({
      page,
      size: query.size,
      customerId: props.fixedCustomerId ?? query.customerId,
    })
    items.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    items.value = []
    total.value = 0
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function search() {
  void load(1)
}

watch(
  () => props.fixedCustomerId,
  (id) => {
    if (id != null) void load(1)
  },
)

const dialogVisible = ref(false)
const editingFollowup = ref<FollowupItem | null>(null)

function openCreate() {
  editingFollowup.value = null
  dialogVisible.value = true
}

function openEdit(row: FollowupItem) {
  editingFollowup.value = row
  dialogVisible.value = true
}

function handleSaved() {
  emit('saved')
  void load()
}

onMounted(() => {
  void dicts.load('communicationChannels')
  void load()
})

defineExpose({ reload: () => load() })
</script>

<template>
  <div>
    <div class="followup-toolbar">
      <div class="followup-filter">
        <el-input-number
          v-if="fixedCustomerId == null"
          v-model="query.customerId"
          :min="1"
          controls-position="right"
          placeholder="客户 ID"
          class="filter-number"
          @change="search"
        />
      </div>
      <div class="followup-actions">
        <el-button class="followup-search-button" :loading="loading" @click="search">
          <svg v-if="!loading" class="followup-button-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <circle cx="8.5" cy="8.5" r="5.25" stroke="currentColor" stroke-width="1.7" />
            <path d="M12.5 12.5L16 16" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          </svg>
          <span>查询</span>
        </el-button>
        <el-button v-if="auth.hasPermission('followup:write')" type="primary" class="followup-create-button" @click="openCreate">
          <svg class="followup-button-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M10 4V16M4 10H16" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span>登记跟进</span>
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      :title="`加载失败:${errorMessage}`"
      class="crm-table-alert"
    />

    <app-table v-else v-loading="loading" :data="items">
      <el-table-column v-if="fixedCustomerId == null" label="客户" width="110">
        <template #default="{ row }">{{ row.customerName ?? `#${row.customerId}` }}</template>
      </el-table-column>
      <el-table-column label="沟通时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.contactAt) }}</template>
      </el-table-column>
      <el-table-column label="方式" width="100">
        <template #default="{ row }">{{ dicts.label('communicationChannels', row.channelCode) }}</template>
      </el-table-column>
      <el-table-column prop="summary" label="沟通内容" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.summary ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="意向项目" width="120">
        <template #default="{ row }">{{ row.intentProject ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="预计金额(元)" width="110" align="right">
        <template #default="{ row }">{{ fenToYuan(row.estimatedAmountFen) }}</template>
      </el-table-column>
      <el-table-column label="下次跟进" width="160">
        <template #default="{ row }">{{ formatDateTime(row.nextFollowupAt) }}</template>
      </el-table-column>
      <el-table-column label="卡点" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.blockerNote ?? '-' }}</template>
      </el-table-column>
      <el-table-column
        v-if="auth.hasPermission('followup:write')"
        label="操作"
        width="90"
      >
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span>暂无跟进记录{{ auth.hasPermission('followup:write') ? ',点击「登记跟进」记录今天与客户的沟通' : '' }}</span>
      </template>
    </app-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      class="crm-pager"
      @current-change="(page: number) => load(page)"
      @size-change="(size: number) => { query.size = size; void load(1) }"
    />

    <FollowupFormDialog
      v-model="dialogVisible"
      :followup="editingFollowup"
      :fixed-customer-id="fixedCustomerId ?? null"
      @saved="handleSaved"
    />
  </div>
</template>

<style scoped>
.followup-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 16px;
  margin-bottom: 16px;
}

.followup-filter {
  display: flex;
  flex: 1 1 180px;
  align-items: center;
  min-width: 0;
}

.followup-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.followup-actions .el-button {
  min-height: 36px;
  margin-left: 0;
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease, box-shadow 160ms ease;
}

.followup-actions .followup-search-button {
  color: #526b78;
  background: #fff;
  border-color: #d8e4e8;
  box-shadow: none;
}

.followup-actions .followup-search-button:hover {
  color: #08766f;
  background: #f0f8f6;
  border-color: #82c2b8;
}

.followup-actions .followup-search-button:active {
  color: #08766f;
  background: #e7f4f1;
  border-color: #65afa3;
}

.followup-actions .followup-create-button {
  color: #fff;
  background: #0f948b;
  border-color: #0f948b;
  box-shadow: 0 3px 8px rgba(15, 148, 139, 0.14);
}

.followup-actions .followup-create-button:hover {
  color: #fff;
  background: #08766f;
  border-color: #08766f;
  box-shadow: 0 4px 10px rgba(15, 148, 139, 0.18);
}

.followup-actions .followup-create-button:active {
  color: #fff;
  background: #066b64;
  border-color: #066b64;
}

.followup-button-icon {
  width: 16px;
  height: 16px;
  margin-right: 6px;
  vertical-align: -3px;
}

.filter-number {
  width: 150px;
}

@media (max-width: 560px) {
  .followup-filter:empty {
    display: none;
  }

  .followup-actions {
    width: 100%;
    justify-content: flex-end;
  }

  .filter-number {
    width: 100%;
  }
}
</style>
