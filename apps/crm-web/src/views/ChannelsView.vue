<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Channel } from '../api/channel'
import { createChannel, listChannels, updateChannel } from '../api/channel'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import StatusPill from './customer/StatusPill.vue'
import { useDictionaries } from './customer/shared'
import './customer/customer-ui.css'

const auth = useAuthStore()
const dicts = useDictionaries()
const canWrite = computed(() => auth.hasPermission('channel:write'))
const channels = ref<Channel[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const query = reactive({ keyword: '', status: '' as '' | Channel['status'], page: 1, size: 20 })

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '合作中',
  INACTIVE: '暂停合作',
  ARCHIVED: '已归档',
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? '未知状态'
}

function statusTone(status: string): 'success' | 'warning' | 'dark' {
  if (status === 'ACTIVE' || status === '合作中') return 'success'
  if (status === 'ARCHIVED') return 'dark'
  return 'warning'
}

function platformLabel(code: string | null): string {
  if (!code) return '未配置平台'
  return dicts.options('customerChannels').find((option) => option.code === code)?.label ?? code
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return error.message || '渠道已变更或当前状态不允许操作，请刷新后重试'
    return error.message
  }
  return error instanceof Error ? error.message : '渠道操作失败'
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listChannels({
      page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
    })
    channels.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    channels.value = []
    total.value = 0
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

const formVisible = ref(false)
const saving = ref(false)
const form = reactive({
  id: 0,
  channelNo: '',
  name: '',
  platformCode: '',
  businessOwnerUserId: undefined as number | undefined,
  status: 'ACTIVE' as Channel['status'],
  remarks: '',
  version: 0,
})

const platformOptions = computed(() => {
  const active = dicts.options('customerChannels')
  if (!form.platformCode || active.some((option) => option.code === form.platformCode)) return active
  return [...active, { code: form.platformCode, label: `${form.platformCode}（历史/停用）`, sortNo: -1 }]
})

function openCreate() {
  Object.assign(form, {
    id: 0,
    channelNo: '',
    name: '',
    platformCode: '',
    businessOwnerUserId: undefined,
    status: 'ACTIVE',
    remarks: '',
    version: 0,
  })
  formVisible.value = true
}

function openEdit(item: Channel) {
  Object.assign(form, {
    id: item.id,
    channelNo: item.channelNo,
    name: item.name,
    platformCode: item.platformCode ?? '',
    businessOwnerUserId: item.businessOwnerUserId ?? undefined,
    status: item.status,
    remarks: item.remarks ?? '',
    version: item.version,
  })
  formVisible.value = true
}

async function save() {
  if (saving.value) return
  if (!form.name.trim() || (!form.id && !form.channelNo.trim())) {
    ElMessage.warning('请填写渠道编号与达人/渠道名称')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await updateChannel(form.id, {
        name: form.name.trim(),
        platformCode: form.platformCode || null,
        businessOwnerUserId: form.businessOwnerUserId ?? null,
        status: form.status,
        remarks: form.remarks.trim() || null,
        version: form.version,
      })
    } else {
      await createChannel({
        channelNo: form.channelNo.trim(),
        name: form.name.trim(),
        platformCode: form.platformCode || undefined,
        businessOwnerUserId: form.businessOwnerUserId ?? undefined,
        remarks: form.remarks.trim() || undefined,
      })
    }
    formVisible.value = false
    ElMessage.success(form.id ? '渠道已保存' : '渠道已创建')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function pause(item: Channel) {
  try {
    await ElMessageBox.confirm('暂停后不可用于新的渠道归因，历史订单快照不变。确认暂停？', '暂停合作', { type: 'warning' })
  } catch {
    return
  }
  try {
    await updateChannel(item.id, {
      name: item.name,
      platformCode: item.platformCode,
      businessOwnerUserId: item.businessOwnerUserId,
      status: 'INACTIVE',
      remarks: item.remarks,
      version: item.version,
    })
    ElMessage.success('渠道已暂停，历史归因保持不变')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(() => {
  void dicts.load('customerChannels')
  void load()
})
</script>

<template>
  <div class="proto-page channels-page">
    <PageHeader title="合作渠道 / 达人" description="维护真实渠道主数据与成交时商务归因来源；历史订单保留原渠道和商务快照">
      <template #actions>
        <el-button v-if="canWrite" type="primary" data-testid="channel-create" @click="openCreate">＋ 新增渠道达人</el-button>
      </template>
    </PageHeader>

    <el-alert
      type="info"
      :closable="false"
      title="当前缺少真实归因数据：本页不展示线索量或订单量，也不按 0 计算。业绩只认真实订单的成交快照，不能从当前客户来源或管家身份推断。"
      class="attribution-alert"
    />

    <PageCard>
      <div class="proto-toolbar">
        <div class="proto-toolbar-left">
          <el-input v-model="query.keyword" clearable placeholder="渠道编号 / 名称" class="keyword" @keyup.enter="load(1)" />
          <el-select v-model="query.status" clearable placeholder="合作状态" class="status-filter">
            <el-option label="合作中" value="ACTIVE" />
            <el-option label="暂停合作" value="INACTIVE" />
            <el-option label="已归档" value="ARCHIVED" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="load(1)">查询</el-button>
          <el-button @click="query.keyword = ''; query.status = ''; load(1)">重置</el-button>
        </div>
        <span class="proto-note">平台选项来自客户渠道字典，停用项不可新选</span>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
      <div class="proto-table-wrap">
        <app-table v-loading="loading" :data="channels">
          <el-table-column prop="channelNo" label="渠道编号" min-width="135" />
          <el-table-column prop="name" label="达人 / 渠道" min-width="170" />
          <el-table-column label="推广平台" min-width="120">
            <template #default="{ row }"><span class="proto-pill info">{{ platformLabel(row.platformCode) }}</span></template>
          </el-table-column>
          <el-table-column label="对接商务" min-width="120">
            <template #default="{ row }">{{ row.businessOwnerUserId ? `用户 #${row.businessOwnerUserId}` : '未分配' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><StatusPill :text="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
          </el-table-column>
          <el-table-column prop="remarks" label="备注" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remarks || '—' }}</template>
          </el-table-column>
          <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'ARCHIVED'" text @click="openEdit(row)">修改</el-button>
              <el-button v-if="row.status === 'ACTIVE'" text class="pause-action" @click="pause(row)">暂停</el-button>
            </template>
          </el-table-column>
          <template #empty>暂无渠道</template>
        </app-table>
      </div>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="load(1)"
      />
    </PageCard>

    <el-dialog v-model="formVisible" :title="form.id ? '修改合作渠道' : '新增渠道达人'" width="560px">
      <el-alert type="warning" :closable="false" title="这里只维护渠道主数据，不录入线索或订单数；真实归因由业务流水计算。" class="form-alert" />
      <el-form label-width="110px">
        <el-form-item label="渠道编号" required>
          <el-input v-model="form.channelNo" :disabled="!!form.id" placeholder="稳定且唯一，创建后不可改" />
        </el-form-item>
        <el-form-item label="达人/渠道" required>
          <el-input v-model="form.name" placeholder="博主昵称或合作渠道名称" />
        </el-form-item>
        <el-form-item label="推广平台">
          <el-select v-model="form.platformCode" clearable filterable class="full-width">
            <el-option
              v-for="option in platformOptions"
              :key="option.code"
              :label="option.label"
              :value="option.code"
              :disabled="option.label.endsWith('（历史/停用）')"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对接商务 ID">
          <el-input-number v-model="form.businessOwnerUserId" :min="1" :precision="0" controls-position="right" class="full-width" />
          <span class="form-help">可空；填写员工 ID，由后端校验租户、状态与商务归属</span>
        </el-form-item>
        <el-form-item v-if="form.id" label="当前状态">
          <StatusPill :text="statusLabel(form.status)" :tone="statusTone(form.status)" />
          <span class="form-help">状态通过独立操作变更，不在普通编辑中跳转</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remarks" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="channel-save" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.attribution-alert { margin-bottom: 18px; }
.keyword { width: min(280px, 100%); }
.status-filter { width: 145px; }
.pause-action { color: var(--el-color-danger) !important; }
.full-width { width: 100%; }
.form-alert { margin-bottom: 18px; }
.form-help { display: block; margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
@media (max-width: 760px) {
  .keyword, .status-filter { width: 100%; }
}
</style>
