<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FollowupItem } from '../../api/followup'
import { createFollowup, updateFollowup } from '../../api/followup'
import { ApiError } from '../../types/api'
import { useDictionaries, yuanToFen } from './shared'

const props = defineProps<{
  modelValue: boolean
  /** 编辑时传入原记录;新建为 null */
  followup: FollowupItem | null
  /** 从客户详情进入时固定客户,不可改 */
  fixedCustomerId?: number | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const dicts = useDictionaries()
void dicts.load('communicationChannels')

interface FormState {
  customerId: number | undefined
  channelCode: string
  contactAt: string
  summary: string
  intentProject: string
  estimatedAmountYuan: number | undefined
  nextFollowupAt: string
  blockerNote: string
}

const form = reactive<FormState>({
  customerId: undefined,
  channelCode: '',
  contactAt: '',
  summary: '',
  intentProject: '',
  estimatedAmountYuan: undefined,
  nextFollowupAt: '',
  blockerNote: '',
})
const submitting = ref(false)

const isEdit = () => props.followup !== null

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    const source = props.followup
    form.customerId = source?.customerId ?? props.fixedCustomerId ?? undefined
    form.channelCode = source?.channelCode ?? ''
    form.contactAt = source?.contactAt ?? ''
    form.summary = source?.summary ?? ''
    form.intentProject = source?.intentProject ?? ''
    // 分→元只在输入边界做一次性数值转换,提交时元→分整数化
    form.estimatedAmountYuan =
      source && source.estimatedAmountFen != null ? source.estimatedAmountFen / 100 : undefined
    form.nextFollowupAt = source?.nextFollowupAt ?? ''
    form.blockerNote = source?.blockerNote ?? ''
  },
)

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return '该跟进已被他人修改,请刷新后重试'
    if (error.status === 404) return '客户不存在或不在你的数据范围内'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function submit() {
  if (!form.customerId) {
    ElMessage.warning('请填写客户 ID')
    return
  }
  if (!form.channelCode) {
    ElMessage.warning('请选择沟通方式')
    return
  }
  submitting.value = true
  try {
    const base = {
      channelCode: form.channelCode,
      contactAt: form.contactAt || undefined,
      summary: form.summary || undefined,
      intentProject: form.intentProject || undefined,
      estimatedAmountFen:
        form.estimatedAmountYuan === undefined ? undefined : yuanToFen(form.estimatedAmountYuan),
      nextFollowupAt: form.nextFollowupAt || undefined,
      blockerNote: form.blockerNote || undefined,
    }
    if (isEdit() && props.followup) {
      await updateFollowup(props.followup.id, { ...base, version: props.followup.version })
      ElMessage.success('跟进已更新')
    } else {
      await createFollowup({ customerId: form.customerId, ...base })
      ElMessage.success('跟进已登记')
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit() ? '编辑跟进' : '登记跟进'"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="96px">
      <el-form-item label="客户 ID" required>
        <el-input-number
          v-model="form.customerId"
          :min="1"
          controls-position="right"
          :disabled="isEdit() || fixedCustomerId != null"
          class="full-width"
        />
      </el-form-item>
      <el-form-item label="沟通方式" required>
        <el-select v-model="form.channelCode" class="full-width">
          <el-option
            v-for="option in dicts.options('communicationChannels')"
            :key="option.code"
            :label="option.label"
            :value="option.code"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="沟通时间">
        <el-date-picker
          v-model="form.contactAt"
          type="datetime"
          value-format="YYYY-MM-DD[T]HH:mm:ssZ"
          class="full-width"
        />
      </el-form-item>
      <el-form-item label="沟通内容">
        <el-input v-model="form.summary" type="textarea" :rows="3" placeholder="每次跟进追加新流水,不覆盖历史" />
      </el-form-item>
      <el-form-item label="意向项目">
        <el-input v-model="form.intentProject" />
      </el-form-item>
      <el-form-item label="预计金额">
        <el-input-number
          v-model="form.estimatedAmountYuan"
          :min="0"
          :precision="2"
          controls-position="right"
          class="full-width"
        />
        <span class="crm-muted">单位:元(提交按整数分存储)</span>
      </el-form-item>
      <el-form-item label="下次跟进">
        <el-date-picker
          v-model="form.nextFollowupAt"
          type="datetime"
          value-format="YYYY-MM-DD[T]HH:mm:ssZ"
          class="full-width"
        />
        <span class="crm-muted">填写后自动生成回访任务；改期仅更新待处理任务</span>
      </el-form-item>
      <el-form-item label="卡点">
        <el-input v-model="form.blockerNote" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ isEdit() ? '保存' : '登记' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full-width {
  width: 100%;
}
</style>
