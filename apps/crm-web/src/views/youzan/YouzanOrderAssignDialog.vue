<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { YouzanOrderView, YouzanStewardOption } from '../../api/youzan'
import { assignYouzanOrderSteward } from '../../api/youzan'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'

const props = defineProps<{
  modelValue: boolean
  order: YouzanOrderView | null
  stewardOptions: YouzanStewardOption[]
  canSaveAlias: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [order: YouzanOrderView, savedAlias: boolean]
}>()

const form = reactive<{
  stewardUserId: number | undefined
  reason: string
  saveAlias: boolean
  aliasName: string
}>({
  stewardUserId: undefined,
  reason: '',
  saveAlias: false,
  aliasName: '',
})

const submitting = ref(false)
let submitAttempt: { signature: string; key: string } | null = null

const hasMemoAccount = computed(() => Boolean(props.order?.memoAccountName?.trim()))

watch(
  () => [props.modelValue, props.order?.externalOrderNo, props.order?.assignedStewardId],
  ([visible]) => {
    if (!visible || !props.order) return
    form.stewardUserId = props.order.assignedStewardId ?? undefined
    form.reason = ''
    form.aliasName = props.order.memoAccountName?.trim() ?? ''
    form.saveAlias = Boolean(
      props.canSaveAlias
      && form.aliasName
      && props.order.assignmentStatus !== 'ASSIGNED',
    )
    submitAttempt = null
  },
  { immediate: true },
)

function optionLabel(option: YouzanStewardOption): string {
  return option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName
}

async function submit() {
  if (!props.order || submitting.value) return
  if (form.stewardUserId == null) {
    ElMessage.warning('请选择目标归属管家')
    return
  }
  const cleanAlias = form.aliasName.trim()
  const shouldSaveAlias = Boolean(props.canSaveAlias && form.saveAlias && cleanAlias)
  const cleanReason = form.reason.trim()
  const signature = `${props.order.shopId}:${props.order.externalOrderNo}:${form.stewardUserId}:${cleanReason}:${shouldSaveAlias}:${cleanAlias}:${props.order.assignmentVersion ?? ''}`
  if (submitAttempt?.signature !== signature) {
    submitAttempt = { signature, key: createYouzanKey('youzan.order.assign') }
  }
  const key = submitAttempt.key
  submitting.value = true
  try {
    const updated = await assignYouzanOrderSteward(
      props.order.shopId,
      props.order.externalOrderNo,
      {
        stewardUserId: form.stewardUserId,
        ...(cleanReason ? { reason: cleanReason } : {}),
        ...(props.order.assignmentVersion != null ? { version: props.order.assignmentVersion } : {}),
        ...(shouldSaveAlias ? { saveAlias: true, aliasName: cleanAlias } : {}),
      },
      key,
    )
    submitAttempt = null
    ElMessage.success(
      shouldSaveAlias
        ? `已分配给 ${updated.assignedStewardName || '目标管家'}，并同步保存账号别名「${cleanAlias}」`
        : `已分配给 ${updated.assignedStewardName || '目标管家'}`,
    )
    emit('saved', updated, shouldSaveAlias)
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="分配有赞订单归属管家"
    width="520px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="order" class="assign-dialog-body">
      <div class="assign-order-summary">
        <div><span>有赞订单号</span><code class="assign-code">{{ order.externalOrderNo }}</code></div>
        <div v-if="order.memoAccountName"><span>备注账号名</span><strong>{{ order.memoAccountName }}</strong></div>
        <div v-if="order.tradeMemoMasked"><span>商家备注</span><p class="assign-memo">{{ order.tradeMemoMasked }}</p></div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        title="人工分配仅作用于有赞订单归属与管家可见范围，不会自动覆盖已有 CRM 老客户的归属管家。"
        class="assign-tip"
      />

      <el-form label-position="top">
        <el-form-item label="目标归属管家" required>
          <el-select
            v-model="form.stewardUserId"
            filterable
            placeholder="请选择在职管家或管家主管"
            style="width: 100%"
            data-testid="youzan-assign-steward"
          >
            <el-option
              v-for="item in stewardOptions"
              :key="item.id"
              :label="optionLabel(item)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="分配理由（选填）">
          <el-input
            v-model="form.reason"
            maxlength="500"
            placeholder="如：按备注账号确认归属，或团队内部改派"
            data-testid="youzan-assign-reason"
          />
        </el-form-item>

        <el-form-item v-if="canSaveAlias && hasMemoAccount">
          <el-checkbox v-model="form.saveAlias" data-testid="youzan-assign-save-alias">
            同时保存别名映射「{{ form.aliasName }} → 所选管家」，后续同账号订单自动分配并回填其他待分配订单
          </el-checkbox>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" data-testid="youzan-assign-submit" @click="submit">
        确认分配
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.assign-order-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #e1ebe7;
  border-radius: 10px;
  background: #f8fbfa;
  font-size: 13px;
}
.assign-order-summary span {
  display: inline-block;
  min-width: 76px;
  color: #6c817c;
  font-size: 12px;
}
.assign-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #173f3a;
}
.assign-memo {
  margin: 4px 0 0;
  color: #324f49;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.assign-tip {
  margin-bottom: 14px;
}
</style>
