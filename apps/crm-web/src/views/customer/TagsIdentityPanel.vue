<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerIdentityItem } from '../../api/customer'
import { updateCustomerTags } from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import StatusPill from './StatusPill.vue'
import { customerTagLabel } from './shared'

const props = defineProps<{
  customerId: number
  tagCodes: string[]
  identities: CustomerIdentityItem[]
  readonly: boolean
}>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const editingTags = ref<string[]>([])
const saving = ref(false)

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

function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '未知错误'
}

async function saveTags() {
  try {
    await ElMessageBox.confirm(
      '标签为全量替换:保存后以当前选择为准,差异将写入审计。确认保存?',
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
    <div class="section-head">
      <span class="section-title">客户标签</span>
    </div>
    <el-select
      v-model="editingTags"
      multiple
      filterable
      allow-create
      default-first-option
      :disabled="readonly || !auth.hasPermission('customer:write')"
      placeholder="输入标签编码后回车添加(标签字典化后此处切换为选项)"
      class="tag-select"
    >
      <el-option v-for="code in editingTags" :key="code" :label="customerTagLabel(code)" :value="code" />
    </el-select>
    <div v-if="auth.hasPermission('customer:write') && !readonly" class="tag-actions">
      <el-button type="primary" size="small" :loading="saving" @click="saveTags">保存标签</el-button>
    </div>

    <div class="section-head">
      <span class="section-title">来源身份(外部标识脱敏)</span>
    </div>
    <app-table :data="identities" size="small">
      <el-table-column label="身份类型" width="120">
        <template #default="{ row }">{{ mappedLabel(row.identityType, IDENTITY_TYPE_LABELS, '其他身份') }}</template>
      </el-table-column>
      <el-table-column label="来源应用" width="140">
        <template #default="{ row }">{{ mappedLabel(row.sourceApp, SOURCE_APP_LABELS, '外部应用') }}</template>
      </el-table-column>
      <el-table-column prop="externalIdMasked" label="外部标识(脱敏)" min-width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <StatusPill :text="mappedLabel(row.status, IDENTITY_STATUS_LABELS, '未知状态')" :tone="row.status === 'LINKED' ? 'success' : 'warning'" />
        </template>
      </el-table-column>
      <template #empty>
        <span>暂无来源身份;企微/有赞对接上线后由集成自动登记</span>
      </template>
    </app-table>
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

.tag-actions {
  margin-top: 10px;
}
</style>
