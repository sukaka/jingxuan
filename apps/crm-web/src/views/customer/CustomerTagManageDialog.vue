<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerTagItem } from '../../api/customer'
import {
  createCustomerTag,
  disableCustomerTag,
  enableCustomerTag,
  listCustomerTags,
  updateCustomerTag,
} from '../../api/customer'
import { ApiError } from '../../types/api'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  changed: []
}>()

const tags = ref<CustomerTagItem[]>([])
const loading = ref(false)
const errorMessage = ref('')
const keyword = ref('')
const statusFilter = ref<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL')

const editorVisible = ref(false)
const editingTag = ref<CustomerTagItem | null>(null)
const saving = ref(false)
const togglingId = ref<number | null>(null)
const form = reactive({
  optionLabel: '',
  sortNo: 0,
  version: 0,
})

const filteredTags = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  return tags.value.filter((item) => {
    const matchesKeyword = !query
      || item.optionLabel.toLocaleLowerCase().includes(query)
      || item.optionCode.toLocaleLowerCase().includes(query)
    const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
    return matchesKeyword && matchesStatus
  })
})

const activeCount = computed(() => tags.value.filter((item) => item.status === 'ACTIVE').length)

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function nextSortNo(): number {
  const maxSort = tags.value.reduce((acc, item) => Math.max(acc, item.sortNo ?? 0), 0)
  return maxSort + 1
}

async function loadTags() {
  loading.value = true
  errorMessage.value = ''
  try {
    tags.value = await listCustomerTags()
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
      keyword.value = ''
      statusFilter.value = 'ALL'
      void loadTags()
    }
  },
  { immediate: true },
)

function openCreate() {
  editingTag.value = null
  form.optionLabel = ''
  form.sortNo = nextSortNo()
  form.version = 0
  editorVisible.value = true
}

function openEdit(item: CustomerTagItem) {
  editingTag.value = item
  form.optionLabel = item.optionLabel
  form.sortNo = item.sortNo
  form.version = item.version
  editorVisible.value = true
}

async function saveEditor() {
  const trimmedLabel = form.optionLabel.trim()
  if (!trimmedLabel) {
    ElMessage.warning('请填写标签内容')
    return
  }
  const normalizedSortNo = typeof form.sortNo === 'number' && Number.isFinite(form.sortNo) ? form.sortNo : 0
  saving.value = true
  try {
    if (editingTag.value) {
      await updateCustomerTag(editingTag.value.id, {
        optionLabel: trimmedLabel,
        sortNo: normalizedSortNo,
        version: form.version,
      })
      ElMessage.success('标签内容已修改')
    } else {
      await createCustomerTag({
        optionLabel: trimmedLabel,
        sortNo: normalizedSortNo,
      })
      ElMessage.success('标签内容已新增')
    }
    editorVisible.value = false
    await loadTags()
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function disableTag(item: CustomerTagItem) {
  try {
    await ElMessageBox.confirm(
      `停用标签「${item.optionLabel}」后，管家建档和打标时将不可再新选该标签；已有客户身上的历史标签不受影响。确认停用？`,
      '停用客户标签',
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  togglingId.value = item.id
  try {
    await disableCustomerTag(item.id)
    ElMessage.success('标签已停用')
    await loadTags()
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    togglingId.value = null
  }
}

async function enableTag(item: CustomerTagItem) {
  togglingId.value = item.id
  try {
    await enableCustomerTag(item.id)
    ElMessage.success('标签已重新启用')
    await loadTags()
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
    title="客户标签管理"
    width="min(720px, 94vw)"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="仅管家主管及系统管理员可新增、修改或启停标签内容；管家在建档与维护客户时仅能从已启用标签中选择。"
      class="tag-manage-tip"
    />
    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      :title="`加载失败：${errorMessage}`"
      class="tag-manage-tip"
    />

    <div class="tag-manage-toolbar">
      <div class="tag-manage-filters">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索标签内容"
          data-testid="customer-tag-search"
          style="width: 220px"
        />
        <el-select
          v-model="statusFilter"
          data-testid="customer-tag-status-filter"
          style="width: 120px"
        >
          <el-option label="全部状态" value="ALL" />
          <el-option label="仅启用" value="ACTIVE" />
          <el-option label="仅停用" value="INACTIVE" />
        </el-select>
        <span class="tag-manage-count">启用 {{ activeCount }} / 共 {{ tags.length }} 个</span>
      </div>
      <el-button type="primary" data-testid="customer-tag-create-btn" @click="openCreate">
        ＋ 新增标签
      </el-button>
    </div>

    <el-table v-loading="loading" :data="filteredTags" size="small" max-height="380">
      <el-table-column label="标签内容" min-width="180">
        <template #default="{ row }">
          <strong>{{ row.optionLabel }}</strong>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span
            class="proto-pill"
            :class="row.status === 'ACTIVE' ? 'success' : row.status === 'ARCHIVED' ? 'info' : 'warning'"
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
            :data-testid="`customer-tag-edit-${row.id}`"
            @click="openEdit(row)"
          >
            修改
          </el-button>
          <el-button
            v-if="row.status === 'ACTIVE'"
            text
            type="warning"
            :loading="togglingId === row.id"
            :data-testid="`customer-tag-disable-${row.id}`"
            @click="disableTag(row)"
          >
            停用
          </el-button>
          <el-button
            v-else-if="row.status === 'INACTIVE'"
            text
            type="primary"
            :loading="togglingId === row.id"
            :data-testid="`customer-tag-enable-${row.id}`"
            @click="enableTag(row)"
          >
            启用
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span>暂无匹配的客户标签，点击右上角「新增标签」创建</span>
      </template>
    </el-table>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>

    <el-dialog
      v-model="editorVisible"
      :title="editingTag ? '修改标签内容' : '新增标签内容'"
      width="420px"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="标签内容" required>
          <el-input
            v-model="form.optionLabel"
            maxlength="64"
            show-word-limit
            placeholder="例如：敏感肌、抗衰重点、热玛吉意向"
            data-testid="customer-tag-label-input"
            @keyup.enter="saveEditor"
          />
        </el-form-item>
        <el-form-item label="展示排序（数字越小越靠前）">
          <el-input-number v-model="form.sortNo" :min="0" :max="999999" data-testid="customer-tag-sort-input" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          data-testid="customer-tag-save-btn"
          @click="saveEditor"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.tag-manage-tip {
  margin-bottom: 12px;
}

.tag-manage-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.tag-manage-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tag-manage-count {
  color: #7c9288;
  font-size: 12px;
}
</style>
