<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerOptionItem } from '../../api/customer'
import {
  createCustomerChannel,
  createCustomerSource,
  disableCustomerChannel,
  disableCustomerSource,
  enableCustomerChannel,
  enableCustomerSource,
  listCustomerChannels,
  listCustomerSources,
  updateCustomerChannel,
  updateCustomerSource,
} from '../../api/customer'
import { ApiError } from '../../types/api'

type CategoryKey = 'sources' | 'channels'

const props = withDefaults(defineProps<{
  modelValue: boolean
  initialTab?: CategoryKey
}>(), {
  initialTab: 'sources',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  changed: []
}>()

const activeTab = ref<CategoryKey>('sources')
const itemsByCategory = reactive<Record<CategoryKey, CustomerOptionItem[]>>({
  sources: [],
  channels: [],
})
const loading = ref(false)
const errorMessage = ref('')
const keyword = ref('')
const statusFilter = ref<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL')

const editorVisible = ref(false)
const editingItem = ref<CustomerOptionItem | null>(null)
const saving = ref(false)
const togglingId = ref<number | null>(null)
const form = reactive({
  optionLabel: '',
  sortNo: 0,
  version: 0,
})

const categoryMeta = computed(() => (activeTab.value === 'sources'
  ? {
      noun: '客户来源',
      shortNoun: '来源',
      placeholder: '例如：达人引流、自有内容、老客转介绍、线下活动',
    }
  : {
      noun: '客户渠道',
      shortNoun: '渠道',
      placeholder: '例如：小红书、视频号、抖音、微博、大众点评',
    }))

const currentItems = computed(() => itemsByCategory[activeTab.value] ?? [])

const filteredItems = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  return currentItems.value.filter((item) => {
    const matchesKeyword = !query
      || item.optionLabel.toLocaleLowerCase().includes(query)
      || item.optionCode.toLocaleLowerCase().includes(query)
    const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
    return matchesKeyword && matchesStatus
  })
})

const activeCount = computed(() => currentItems.value.filter((item) => item.status === 'ACTIVE').length)

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function nextSortNo(): number {
  const maxSort = currentItems.value.reduce((acc, item) => Math.max(acc, item.sortNo ?? 0), 0)
  return maxSort + 1
}

async function loadCategory(category: CategoryKey) {
  loading.value = true
  errorMessage.value = ''
  try {
    itemsByCategory[category] = category === 'sources'
      ? await listCustomerSources()
      : await listCustomerChannels()
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      activeTab.value = props.initialTab || 'sources'
      keyword.value = ''
      statusFilter.value = 'ALL'
      void loadCategory(activeTab.value)
    }
  },
  { immediate: true },
)

watch(activeTab, (tab) => {
  if (!props.modelValue) return
  keyword.value = ''
  statusFilter.value = 'ALL'
  void loadCategory(tab)
})

function openCreate() {
  editingItem.value = null
  form.optionLabel = ''
  form.sortNo = nextSortNo()
  form.version = 0
  editorVisible.value = true
}

function openEdit(item: CustomerOptionItem) {
  editingItem.value = item
  form.optionLabel = item.optionLabel
  form.sortNo = item.sortNo
  form.version = item.version
  editorVisible.value = true
}

async function saveEditor() {
  const trimmedLabel = form.optionLabel.trim()
  if (!trimmedLabel) {
    ElMessage.warning(`请填写${categoryMeta.value.noun}名称`)
    return
  }
  saving.value = true
  try {
    const isSource = activeTab.value === 'sources'
    if (editingItem.value) {
      const payload = { optionLabel: trimmedLabel, sortNo: form.sortNo, version: form.version }
      if (isSource) await updateCustomerSource(editingItem.value.id, payload)
      else await updateCustomerChannel(editingItem.value.id, payload)
      ElMessage.success(`${categoryMeta.value.noun}已修改`)
    } else {
      const payload = { optionLabel: trimmedLabel, sortNo: form.sortNo }
      if (isSource) await createCustomerSource(payload)
      else await createCustomerChannel(payload)
      ElMessage.success(`${categoryMeta.value.noun}已新增`)
    }
    editorVisible.value = false
    await loadCategory(activeTab.value)
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function disableOption(item: CustomerOptionItem) {
  try {
    await ElMessageBox.confirm(
      `停用${categoryMeta.value.noun}「${item.optionLabel}」后，普通管家在建档与编辑时将不可再新选；历史客户保留原记录不受影响。确认停用？`,
      `停用${categoryMeta.value.noun}`,
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  togglingId.value = item.id
  try {
    if (activeTab.value === 'sources') await disableCustomerSource(item.id)
    else await disableCustomerChannel(item.id)
    ElMessage.success(`${categoryMeta.value.noun}已停用`)
    await loadCategory(activeTab.value)
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    togglingId.value = null
  }
}

async function enableOption(item: CustomerOptionItem) {
  togglingId.value = item.id
  try {
    if (activeTab.value === 'sources') await enableCustomerSource(item.id)
    else await enableCustomerChannel(item.id)
    ElMessage.success(`${categoryMeta.value.noun}已重新启用`)
    await loadCategory(activeTab.value)
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    togglingId.value = null
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="客户来源与渠道管理"
    width="min(740px, 94vw)"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="客户来源与客户渠道由管家主管及系统管理员统一维护；普通管家在客户建档与编辑时仅支持下拉选择已启用项。"
      class="option-manage-tip"
    />
    <el-tabs v-model="activeTab" data-testid="source-channel-tabs">
      <el-tab-pane label="客户来源" name="sources" />
      <el-tab-pane label="客户渠道" name="channels" />
    </el-tabs>

    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      :title="`加载失败：${errorMessage}`"
      class="option-manage-tip"
    />

    <div class="option-manage-toolbar">
      <div class="option-manage-filters">
        <el-input
          v-model="keyword"
          clearable
          :placeholder="`搜索${categoryMeta.noun}名称`"
          data-testid="source-channel-search"
          style="width: 220px"
        />
        <el-select
          v-model="statusFilter"
          data-testid="source-channel-status-filter"
          style="width: 120px"
        >
          <el-option label="全部状态" value="ALL" />
          <el-option label="仅启用" value="ACTIVE" />
          <el-option label="仅停用" value="INACTIVE" />
        </el-select>
        <span class="option-manage-count">启用 {{ activeCount }} / 共 {{ currentItems.length }} 项</span>
      </div>
      <el-button type="primary" data-testid="source-channel-create-btn" @click="openCreate">
        ＋ 新增{{ categoryMeta.shortNoun }}
      </el-button>
    </div>

    <el-table v-loading="loading" :data="filteredItems" size="small" max-height="380">
      <el-table-column :label="`${categoryMeta.noun}名称`" min-width="180">
        <template #default="{ row }">
          <strong>{{ row.optionLabel }}</strong>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span
            class="proto-pill"
            :class="row.status === 'ACTIVE' ? 'success' : row.status === 'ARCHIVED' ? 'dark' : 'warning'"
          >
            {{ row.status === 'ACTIVE' ? '启用' : row.status === 'ARCHIVED' ? '已归档' : '停用' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status !== 'ARCHIVED'"
            text
            :data-testid="`source-channel-edit-${row.id}`"
            @click="openEdit(row)"
          >
            修改
          </el-button>
          <el-button
            v-if="row.status === 'ACTIVE'"
            text
            type="warning"
            :loading="togglingId === row.id"
            :data-testid="`source-channel-disable-${row.id}`"
            @click="disableOption(row)"
          >
            停用
          </el-button>
          <el-button
            v-else-if="row.status === 'INACTIVE'"
            text
            type="primary"
            :loading="togglingId === row.id"
            :data-testid="`source-channel-enable-${row.id}`"
            @click="enableOption(row)"
          >
            启用
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span>暂无匹配的{{ categoryMeta.noun }}，点击右上角「新增{{ categoryMeta.shortNoun }}」创建</span>
      </template>
    </el-table>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>

    <el-dialog
      v-model="editorVisible"
      :title="editingItem ? `修改${categoryMeta.noun}` : `新增${categoryMeta.noun}`"
      width="420px"
      append-to-body
    >
      <el-form label-position="top">
        <el-form-item :label="`${categoryMeta.noun}名称`" required>
          <el-input
            v-model="form.optionLabel"
            maxlength="64"
            show-word-limit
            :placeholder="categoryMeta.placeholder"
            data-testid="source-channel-label-input"
            @keyup.enter="saveEditor"
          />
        </el-form-item>
        <el-form-item label="展示排序（数字越小越靠前）">
          <el-input-number v-model="form.sortNo" :min="0" :max="999999" data-testid="source-channel-sort-input" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          data-testid="source-channel-save-btn"
          @click="saveEditor"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.option-manage-tip {
  margin-bottom: 12px;
}

.option-manage-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.option-manage-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.option-manage-count {
  color: #7c9288;
  font-size: 12px;
}
</style>
