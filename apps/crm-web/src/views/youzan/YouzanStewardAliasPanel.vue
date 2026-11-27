<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { YouzanStewardAlias, YouzanStewardOption } from '../../api/youzan'
import { deleteYouzanStewardAlias, saveYouzanStewardAlias } from '../../api/youzan'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'

const props = defineProps<{
  aliases: YouzanStewardAlias[]
  stewardOptions: YouzanStewardOption[]
  loading: boolean
  canWrite: boolean
  suggestedAliasName?: string
}>()

const emit = defineEmits<{
  saved: []
}>()

const dialogVisible = ref(false)
const saving = ref(false)
const deletingId = ref<number | null>(null)
const editing = ref<YouzanStewardAlias | null>(null)
let saveAttempt: { signature: string; key: string } | null = null

const form = reactive<{
  aliasName: string
  stewardUserId: number | undefined
  status: string
  remark: string
  backfillPending: boolean
}>({
  aliasName: '',
  stewardUserId: undefined,
  status: 'ACTIVE',
  remark: '',
  backfillPending: true,
})

watch(
  () => props.suggestedAliasName,
  (next) => {
    if (next && next.trim() && props.canWrite) {
      openCreate(next.trim())
    }
  },
)

function optionLabel(option: YouzanStewardOption): string {
  return option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName
}

function openCreate(prefillAlias = '') {
  editing.value = null
  form.aliasName = prefillAlias
  form.stewardUserId = undefined
  form.status = 'ACTIVE'
  form.remark = ''
  form.backfillPending = true
  saveAttempt = null
  dialogVisible.value = true
}

function openEdit(row: YouzanStewardAlias) {
  editing.value = row
  form.aliasName = row.aliasName
  form.stewardUserId = row.stewardUserId
  form.status = row.status
  form.remark = row.remark ?? ''
  form.backfillPending = true
  saveAttempt = null
  dialogVisible.value = true
}

async function submit() {
  if (saving.value) return
  const aliasName = form.aliasName.trim()
  if (!aliasName) {
    ElMessage.warning('请填写有赞备注中的账号名（如：紫紫）')
    return
  }
  if (form.stewardUserId == null) {
    ElMessage.warning('请选择对应的在职管家')
    return
  }
  const remark = form.remark.trim()
  const signature = `${aliasName}:${form.stewardUserId}:${form.status}:${remark}:${form.backfillPending}:${editing.value?.version ?? ''}`
  if (saveAttempt?.signature !== signature) {
    saveAttempt = { signature, key: createYouzanKey('youzan.steward.alias') }
  }
  const key = saveAttempt.key
  saving.value = true
  try {
    const res = await saveYouzanStewardAlias(
      {
        aliasName,
        stewardUserId: form.stewardUserId,
        status: form.status,
        ...(remark ? { remark } : {}),
        ...(editing.value ? { version: editing.value.version } : {}),
        backfillPending: form.backfillPending,
      },
      key,
    )
    saveAttempt = null
    dialogVisible.value = false
    const backfillTip = res.backfilledOrderCount > 0
      ? `，并已自动回填 ${res.backfilledOrderCount} 笔历史待分配订单`
      : ''
    ElMessage.success(`账号别名「${res.alias.aliasName}」已保存${backfillTip}`)
    emit('saved')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: YouzanStewardAlias) {
  if (deletingId.value != null) return
  try {
    await ElMessageBox.confirm(
      `确认删除别名映射「${row.aliasName} → ${row.stewardName || `#${row.stewardUserId}`}」？已分配的历史订单不会被撤销。`,
      '删除账号别名映射',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await deleteYouzanStewardAlias(row.id)
    ElMessage.success(`已删除别名「${row.aliasName}」`)
    emit('saved')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    deletingId.value = null
  }
}
</script>

<template>
  <div class="steward-alias-panel" data-testid="youzan-steward-alias-panel">
    <div class="crm-toolbar alias-toolbar">
      <span class="proto-note">
        拉取有赞订单时，系统自动解析商家备注（trade_memo）中「账号：」后的人名：优先按此处启用中的别名映射分配，未命中时再按在职管家姓名精确匹配。
      </span>
      <el-button
        v-if="canWrite"
        type="primary"
        data-testid="steward-alias-create"
        @click="openCreate()"
      >
        ＋ 新增账号映射
      </el-button>
    </div>

    <app-table v-loading="loading" :data="aliases" row-key="id">
      <el-table-column prop="aliasName" label="有赞备注账号名" min-width="150">
        <template #default="{ row }">
          <strong>{{ row.aliasName }}</strong>
        </template>
      </el-table-column>
      <el-table-column label="映射 CRM 管家" min-width="170">
        <template #default="{ row }">
          {{ row.stewardName || `管家 #${row.stewardUserId}` }}
          <div v-if="row.teamName" class="proto-subcell">{{ row.teamName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusPill :text="row.status === 'ACTIVE' ? '启用' : '停用'" :tone="row.status === 'ACTIVE' ? 'success' : 'info'" />
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="说明" min-width="180">
        <template #default="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="160">
        <template #default="{ row }">{{ row.updatedAt ? formatDateTime(row.updatedAt) : '—' }}</template>
      </el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" data-testid="steward-alias-edit" @click="openEdit(row)">编辑</el-button>
          <el-button
            text
            type="danger"
            :loading="deletingId === row.id"
            data-testid="steward-alias-delete"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>暂未配置有赞备注账号别名映射</template>
    </app-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑账号别名映射' : '新增账号别名映射'"
      width="480px"
    >
      <el-form label-position="top">
        <el-form-item label="有赞备注「账号：」后的人名" required>
          <el-input
            v-model="form.aliasName"
            :disabled="Boolean(editing)"
            maxlength="64"
            placeholder="例如：紫紫"
            data-testid="steward-alias-name"
          />
        </el-form-item>
        <el-form-item label="对应 CRM 管家" required>
          <el-select
            v-model="form.stewardUserId"
            filterable
            placeholder="请选择在职管家或管家主管"
            style="width: 100%"
            data-testid="steward-alias-steward"
          >
            <el-option
              v-for="item in stewardOptions"
              :key="item.id"
              :label="optionLabel(item)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="映射状态">
          <el-radio-group v-model="form.status" data-testid="steward-alias-status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="INACTIVE">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="配置说明（选填）">
          <el-input v-model="form.remark" maxlength="255" placeholder="如：花名/微信号简称说明" data-testid="steward-alias-remark" />
        </el-form-item>
        <el-form-item v-if="form.status === 'ACTIVE'">
          <el-checkbox v-model="form.backfillPending" data-testid="steward-alias-backfill">
            保存后自动回填同账号的历史待分配订单（不影响已人工指派的订单）
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="steward-alias-save" @click="submit">
          保存映射
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.alias-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
</style>
