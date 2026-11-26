<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerIdentityItem } from '../../api/customer'
import { updateCustomerTags } from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import CustomerTagManageDialog from './CustomerTagManageDialog.vue'
import StatusPill from './StatusPill.vue'
import { useDictionaries } from './shared'

const props = withDefaults(defineProps<{
  customerId: number
  tagCodes: string[]
  identities: CustomerIdentityItem[]
  readonly: boolean
  section?: 'all' | 'tags' | 'identities'
}>(), {
  section: 'all',
})
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const dicts = useDictionaries()
const editingTags = ref<string[]>([])
const saving = ref(false)
const manageVisible = ref(false)

const showTags = computed(() => props.section === 'all' || props.section === 'tags')
const showIdentities = computed(() => props.section === 'all' || props.section === 'identities')
const canWriteTags = computed(() => auth.hasPermission('customer:write') && !props.readonly)
const canManageTagCatalog = computed(() => auth.hasPermission('customer:tag:manage'))
const tagSelectOptions = computed(() => dicts.optionsWithHistoryList('customerTags', editingTags.value))

const IDENTITY_TYPE_LABELS: Record<string, string> = {
  WECOM: '企业微信',
  PHONE: '手机',
  YOUZAN: '有赞',
  LEGACY: '历史系统',
}
const IDENTITY_STATUS_LABELS: Record<string, string> = {
  LINKED: '已关联',
  已关联: '已关联',
  PENDING_VERIFY: '待核验',
}
const SOURCE_APP_LABELS: Record<string, string> = {
  WECOM: '企业微信',
  YOUZAN: '有赞',
  PHONE: '手机',
  LEGACY: '历史系统',
}

function mappedLabel(value: string, labels: Record<string, string>, fallback: string): string {
  return labels[value] ?? fallback
}

watch(
  () => props.tagCodes,
  (codes) => {
    editingTags.value = [...codes]
  },
  { immediate: true },
)

watch(
  () => props.customerId,
  () => {
    if (showTags.value) void dicts.load('customerTags')
  },
)

onMounted(() => {
  if (showTags.value) void dicts.load('customerTags')
})

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '未知错误'
}

async function onTagCatalogChanged() {
  await dicts.load('customerTags')
  emit('changed')
}

async function saveTags() {
  try {
    await ElMessageBox.confirm(
      '保存后将以当前已选标签为准，变更将记入操作日志。确认保存？',
      '保存标签',
      { type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  saving.value = true
  try {
    await updateCustomerTags(props.customerId, editingTags.value)
    ElMessage.success('标签已保存')
    emit('changed')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <template v-if="showTags">
      <div class="section-head">
        <span class="section-title">客户标签</span>
        <el-button
          v-if="canManageTagCatalog"
          text
          type="primary"
          size="small"
          data-testid="panel-manage-tags-btn"
          @click="manageVisible = true"
        >
          管理标签内容
        </el-button>
      </div>
      <el-select
        v-model="editingTags"
        multiple
        filterable
        clearable
        :disabled="!canWriteTags"
        placeholder="请选择客户标签"
        data-testid="detail-customer-tag-select"
        class="tag-select"
      >
        <el-option
          v-for="option in tagSelectOptions"
          :key="option.code"
          :label="option.label"
          :value="option.code"
          :disabled="option.disabled"
        />
      </el-select>
      <div v-if="canWriteTags" class="tag-footer">
        <el-button type="primary" size="small" :loading="saving" data-testid="detail-save-tags-btn" @click="saveTags">
          保存标签
        </el-button>
        <span v-if="!canManageTagCatalog" class="tag-hint">
          标签内容由管家主管统一维护，当前仅可选择已有标签
        </span>
      </div>
      <CustomerTagManageDialog
        v-if="canManageTagCatalog"
        v-model="manageVisible"
        @changed="onTagCatalogChanged"
      />
    </template>

    <template v-if="showIdentities">
      <div class="section-head">
        <span class="section-title">关联身份（已脱敏展示）</span>
      </div>
      <app-table :data="identities" size="small">
        <el-table-column label="身份类型" width="120">
          <template #default="{ row }">{{ mappedLabel(row.identityType, IDENTITY_TYPE_LABELS, '其他身份') }}</template>
        </el-table-column>
        <el-table-column label="来源应用" width="140">
          <template #default="{ row }">{{ mappedLabel(row.sourceApp, SOURCE_APP_LABELS, '外部应用') }}</template>
        </el-table-column>
        <el-table-column prop="externalIdMasked" label="外部账号（脱敏）" min-width="180" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusPill :text="mappedLabel(row.status, IDENTITY_STATUS_LABELS, '未知状态')" :tone="row.status === 'LINKED' ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <template #empty>
          <span>暂无关联身份；接入企微或有赞后将自动关联</span>
        </template>
      </app-table>
    </template>
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

.tag-select {
  width: 100%;
}

.tag-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.tag-hint {
  color: #7c9288;
  font-size: 12px;
}
</style>
