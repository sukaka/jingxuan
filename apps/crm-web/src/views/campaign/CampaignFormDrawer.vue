<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  CampaignBatch,
  CampaignBatchStatus,
  CampaignDetail,
  CampaignStatus,
  CampaignType,
  CreateCampaignInput,
} from '../../api/campaign'
import {
  createCampaign,
  createCampaignBatch,
  updateCampaign,
  updateCampaignBatch,
} from '../../api/campaign'
import { campaignErrorText } from '../reporting/reportUi'
import { instantToShanghaiInput, shanghaiInputToInstant } from './campaignTime'

const props = defineProps<{
  modelValue: boolean
  detail?: CampaignDetail | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
  changed: []
}>()

interface BatchDraft {
  key: string
  id: number | null
  batchCode: string
  batchName: string
  startsAt: string | null
  endsAt: string | null
  status: CampaignBatchStatus
  version: number
  dirty: boolean
  readOnly: boolean
}

const form = reactive({
  id: null as number | null,
  campaignCode: '',
  campaignName: '',
  campaignType: 'REGULAR' as CampaignType,
  startsAt: null as string | null,
  endsAt: null as string | null,
  status: 'DRAFT' as CampaignStatus,
  version: 0,
})
const batches = ref<BatchDraft[]>([])
const saving = ref(false)
const formError = ref('')
let campaignKey = ''
let campaignNeedsSave = true

function newBatch(): BatchDraft {
  return {
    key: crypto.randomUUID(),
    id: null,
    batchCode: '',
    batchName: '',
    startsAt: null,
    endsAt: null,
    status: 'DRAFT',
    version: 0,
    dirty: true,
    readOnly: false,
  }
}

function existingBatch(batch: CampaignBatch): BatchDraft {
  return {
    key: crypto.randomUUID(),
    id: batch.id,
    batchCode: batch.batchCode,
    batchName: batch.batchName,
    startsAt: instantToShanghaiInput(batch.startsAt),
    endsAt: instantToShanghaiInput(batch.endsAt),
    status: batch.status,
    version: batch.version,
    dirty: false,
    readOnly: batch.status === 'ARCHIVED',
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    const campaign = props.detail
    Object.assign(form, {
      id: campaign?.id ?? null,
      campaignCode: campaign?.campaignCode ?? '',
      campaignName: campaign?.campaignName ?? '',
      campaignType: campaign?.campaignType ?? 'REGULAR',
      startsAt: instantToShanghaiInput(campaign?.startsAt ?? null),
      endsAt: instantToShanghaiInput(campaign?.endsAt ?? null),
      status: campaign?.status ?? 'DRAFT',
      version: campaign?.version ?? 0,
    })
    batches.value = props.detail?.batches.map(existingBatch) ?? [newBatch()]
    campaignKey = `campaign:${crypto.randomUUID()}`
    campaignNeedsSave = !props.detail
    formError.value = ''
  },
)

function addBatch() {
  batches.value.push(newBatch())
}

function removeBatch(index: number) {
  if (batches.value[index]?.id || batches.value.length <= 1) return
  batches.value.splice(index, 1)
}

function validRange(startsAt: string | null, endsAt: string | null): boolean {
  return !startsAt || !endsAt || startsAt < endsAt
}

function validate(): string {
  if (!/^[A-Za-z0-9_-]{2,64}$/.test(form.campaignCode.trim())) return '活动编码需为 2–64 位字母、数字、下划线或连字符'
  if (!form.campaignName.trim()) return '请填写活动名称'
  if (form.id === null && form.status === 'ARCHIVED') return '新活动不能直接归档'
  if (form.status === 'ARCHIVED' && batches.value.some((batch) => batch.dirty && !batch.readOnly)) return '请先单独保存批次变更，再归档活动'
  if (!validRange(form.startsAt, form.endsAt)) return '活动结束时间必须晚于开始时间'
  if (batches.value.length === 0) return '请至少保留一个活动批次'
  for (const batch of batches.value) {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(batch.batchCode.trim())) return '批次编码需为 1–64 位字母、数字、下划线或连字符'
    if (!batch.batchName.trim()) return '请完整填写每个批次的名称'
    if (!validRange(batch.startsAt, batch.endsAt)) return `批次 ${batch.batchCode || '未命名'} 的结束时间必须晚于开始时间`
    if (form.startsAt && batch.startsAt && batch.startsAt < form.startsAt) return `批次 ${batch.batchCode} 不能早于活动开始时间`
    if (form.endsAt && batch.endsAt && batch.endsAt > form.endsAt) return `批次 ${batch.batchCode} 不能晚于活动结束时间`
  }
  return ''
}

function close() {
  if (!saving.value) emit('update:modelValue', false)
}

async function save() {
  const validationError = validate()
  if (validationError) {
    formError.value = validationError
    return
  }
  saving.value = true
  formError.value = ''
  let changed = false
  try {
    if (campaignNeedsSave || form.id === null) {
      const campaignBody = {
        campaignName: form.campaignName.trim(),
        campaignType: form.campaignType,
        startsAt: shanghaiInputToInstant(form.startsAt),
        endsAt: shanghaiInputToInstant(form.endsAt),
        status: form.status,
      }
      const saved = form.id === null
        ? await createCampaign({
          ...campaignBody,
          campaignCode: form.campaignCode.trim(),
          status: form.status as CreateCampaignInput['status'],
        }, campaignKey)
        : await updateCampaign(form.id, { ...campaignBody, version: form.version }, campaignKey)
      Object.assign(form, {
        id: saved.id,
        campaignCode: saved.campaignCode,
        campaignName: saved.campaignName,
        campaignType: saved.campaignType,
        startsAt: instantToShanghaiInput(saved.startsAt),
        endsAt: instantToShanghaiInput(saved.endsAt),
        status: saved.status,
        version: saved.version,
      })
      campaignNeedsSave = false
      changed = true
    }

    const campaignId = form.id
    if (campaignId === null) throw new Error('活动保存后未返回活动 ID，无法追加批次')
    for (const batch of batches.value) {
      if (!batch.dirty || batch.readOnly) continue
      const body = {
        batchName: batch.batchName.trim(),
        startsAt: shanghaiInputToInstant(batch.startsAt),
        endsAt: shanghaiInputToInstant(batch.endsAt),
        status: batch.status,
      }
      const saved = batch.id === null
        ? await createCampaignBatch(campaignId, {
          ...body,
          batchCode: batch.batchCode.trim(),
          status: batch.status as 'DRAFT' | 'ACTIVE',
        }, `batch:${batch.key}`)
        : await updateCampaignBatch(campaignId, batch.id, { ...body, version: batch.version }, `batch:${batch.key}`)
      Object.assign(batch, {
        id: saved.id,
        batchCode: saved.batchCode,
        batchName: saved.batchName,
        startsAt: instantToShanghaiInput(saved.startsAt),
        endsAt: instantToShanghaiInput(saved.endsAt),
        status: saved.status,
        version: saved.version,
        dirty: false,
        readOnly: saved.status === 'ARCHIVED',
      })
      changed = true
    }

    ElMessage.success('活动与批次已保存')
    emit('saved')
    saving.value = false
    emit('update:modelValue', false)
  } catch (error) {
    const prefix = !props.detail && form.id !== null
      ? '活动已创建，但批次未全部保存。请核对后重试，已成功内容不会重复追加。'
      : '保存未完成，请核对活动与批次后再重试。'
    formError.value = `${prefix}${campaignErrorText(error)}`
    if (changed) emit('changed')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="form.id ? `编辑活动 · ${form.campaignCode}` : '创建活动与批次'"
    size="min(760px, 96vw)"
    :close-on-click-modal="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="编码保存后固定；不允许回到草稿或编辑已归档记录，状态转换与批次归属由服务端复核"
      class="campaign-form-tip"
    />
    <el-alert v-if="formError" type="error" :title="formError" :closable="false" class="campaign-form-tip" />
    <el-form label-position="top" @submit.prevent="save">
      <div class="campaign-form-grid">
        <el-form-item label="活动编码" required>
          <el-input v-model="form.campaignCode" :disabled="form.id !== null" maxlength="64" placeholder="例如 CMP-2026-11" data-testid="campaign-code" />
        </el-form-item>
        <el-form-item label="活动名称" required>
          <el-input v-model="form.campaignName" maxlength="160" placeholder="输入真实活动名称" data-testid="campaign-name" @input="campaignNeedsSave = true" />
        </el-form-item>
        <el-form-item label="活动类型" required>
          <el-select v-model="form.campaignType" class="campaign-full-width" @change="campaignNeedsSave = true">
            <el-option label="普通销售" value="REGULAR" /><el-option label="快闪" value="FLASH" /><el-option label="大促" value="PROMOTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="活动状态" required>
          <el-select v-model="form.status" class="campaign-full-width" @change="campaignNeedsSave = true">
            <el-option label="草稿" value="DRAFT" :disabled="form.id !== null && form.status !== 'DRAFT'" />
            <el-option label="进行中" value="ACTIVE" /><el-option label="已停用" value="INACTIVE" />
            <el-option v-if="form.id !== null" label="已归档（只读）" value="ARCHIVED" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择活动开始时间" class="campaign-full-width" @change="campaignNeedsSave = true" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="form.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择活动结束时间" class="campaign-full-width" @change="campaignNeedsSave = true" />
        </el-form-item>
      </div>

      <section class="campaign-batch-section">
        <header>
          <div><h3>活动批次</h3><p>活动与批次分别保存；订单只能归属到真实有效且归属正确的批次。</p></div>
          <el-button :disabled="saving" data-testid="batch-add" @click="addBatch">＋ 添加批次</el-button>
        </header>
        <article v-for="(batch, index) in batches" :key="batch.key" class="campaign-batch-editor">
          <div class="campaign-batch-heading">
            <strong>批次 {{ index + 1 }}<span v-if="batch.id"> · #{{ batch.id }}</span><span v-if="batch.readOnly"> · 已归档只读</span></strong>
            <el-button v-if="!batch.id && batches.length > 1" text type="danger" :disabled="saving" @click="removeBatch(index)">移除未保存批次</el-button>
          </div>
          <div class="campaign-form-grid">
            <el-form-item label="批次编码" required>
              <el-input v-model="batch.batchCode" :disabled="batch.id !== null" maxlength="64" placeholder="例如 WAVE-01" data-testid="batch-code" />
            </el-form-item>
            <el-form-item label="批次名称" required>
              <el-input v-model="batch.batchName" :disabled="batch.readOnly" maxlength="96" placeholder="输入真实批次名称" data-testid="batch-name" @input="batch.dirty = true" />
            </el-form-item>
            <el-form-item label="批次状态" required>
              <el-select v-model="batch.status" :disabled="batch.readOnly" class="campaign-full-width" data-testid="batch-status" @change="batch.dirty = true">
                <el-option label="草稿" value="DRAFT" :disabled="batch.id !== null && batch.status !== 'DRAFT'" />
                <el-option label="进行中" value="ACTIVE" /><el-option v-if="batch.id !== null" label="归档" value="ARCHIVED" />
              </el-select>
            </el-form-item>
            <div class="campaign-form-spacer" />
            <el-form-item label="开始时间">
              <el-date-picker v-model="batch.startsAt" :disabled="batch.readOnly" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择批次开始时间" class="campaign-full-width" @change="batch.dirty = true" />
            </el-form-item>
            <el-form-item label="结束时间">
              <el-date-picker v-model="batch.endsAt" :disabled="batch.readOnly" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择批次结束时间" class="campaign-full-width" @change="batch.dirty = true" />
            </el-form-item>
          </div>
        </article>
      </section>
      <el-alert type="warning" :closable="false" title="渠道与商务归因来自订单成交快照，本表单不提供或伪造归因编辑。" />
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="campaign-save" @click="save">保存活动与批次</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.campaign-form-tip { margin-bottom: 18px; }
.campaign-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.campaign-full-width { width: 100%; }
.campaign-batch-section { margin: 4px 0 20px; border-top: 1px solid var(--line); }
.campaign-batch-section > header { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 19px 0 8px; }
.campaign-batch-section h3 { margin: 0; color: #294456; font-size: 15px; }
.campaign-batch-section p { margin: 5px 0 0; color: #8a9eaa; font-size: 11px; line-height: 1.7; }
.campaign-batch-editor { padding: 15px 0 3px; border-bottom: 1px solid var(--line); }
.campaign-batch-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #4c6676; }
@media (max-width: 640px) {
  .campaign-form-grid { grid-template-columns: 1fr; }
  .campaign-form-spacer { display: none; }
  .campaign-batch-section > header { align-items: flex-start; flex-direction: column; }
}
</style>
