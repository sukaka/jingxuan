<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getYouzanSyncPolicy, saveYouzanSyncPolicy } from '../../api/youzan'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'

const props = defineProps<{ shopId: string; canWrite: boolean }>()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const form = reactive({ initialStartTime: '', intervalSeconds: 300, overlapSeconds: 300, pageSize: 50, enabled: false, nextRunAt: '', lastScheduledAt: null as string | null, version: undefined as number | undefined })

function localInput(date: Date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

async function load() {
  if (!props.shopId || props.shopId.startsWith('MOCK-')) return
  loading.value = true
  loadError.value = ''
  try {
    const policy = await getYouzanSyncPolicy(props.shopId)
    if (policy) Object.assign(form, { initialStartTime: localInput(new Date(policy.initialStartTime)), intervalSeconds: policy.intervalSeconds, overlapSeconds: policy.overlapSeconds, pageSize: policy.pageSize, enabled: policy.enabled, nextRunAt: policy.nextRunAt ? localInput(new Date(policy.nextRunAt)) : localInput(new Date(Date.now() + 300000)), lastScheduledAt: policy.lastScheduledAt, version: policy.version })
  } catch (error) {
    loadError.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.initialStartTime) {
    ElMessage.warning('请选择首次历史导入开始时间')
    return
  }
  if (form.enabled && !form.nextRunAt) {
    ElMessage.warning('启用定时同步前，请设置下次执行时间')
    return
  }
  saving.value = true
  try {
    const saved = await saveYouzanSyncPolicy(props.shopId, { initialStartTime: new Date(form.initialStartTime).toISOString(), intervalSeconds: form.intervalSeconds, overlapSeconds: form.overlapSeconds, pageSize: form.pageSize, enabled: form.enabled, nextRunAt: form.nextRunAt ? new Date(form.nextRunAt).toISOString() : undefined, version: form.version }, createYouzanKey('youzan.sync.policy'))
    form.version = saved.version
    form.lastScheduledAt = saved.lastScheduledAt
    ElMessage.success('同步策略已保存，调度器将按下次执行时间运行')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}

watch(() => props.shopId, () => {
  Object.assign(form, { initialStartTime: localInput(new Date(Date.now() - 7 * 86400000)), intervalSeconds: 300, overlapSeconds: 300, pageSize: 50, enabled: false, nextRunAt: localInput(new Date(Date.now() + 300000)), lastScheduledAt: null, version: undefined })
  void load()
}, { immediate: true })
</script>

<template>
  <section v-loading="loading">
    <div class="youzan-section-head"><div><h3>同步策略</h3><p>首次按创建时间分段导入；日常按更新时间增量补漏，重叠数据依靠订单与支付幂等去重。</p></div></div>
    <el-alert v-if="shopId.startsWith('MOCK-')" type="info" :closable="false" title="模拟店铺使用固定演示数据，不需要真实同步策略。" />
    <el-alert v-else-if="loadError" type="warning" :closable="false" :title="loadError" />
    <el-form v-else label-position="top" class="policy-form" @submit.prevent>
      <div class="youzan-form-grid">
        <el-form-item label="首次导入开始时间" required><el-date-picker v-model="form.initialStartTime" type="datetime" value-format="YYYY-MM-DDTHH:mm" class="full-width" /></el-form-item>
        <el-form-item label="订单列表页大小"><el-input-number v-model="form.pageSize" :min="1" :max="100" class="full-width" /></el-form-item>
        <el-form-item label="增量间隔（秒）"><el-input-number v-model="form.intervalSeconds" :min="60" :max="86400" class="full-width" /></el-form-item>
        <el-form-item label="窗口重叠（秒）"><el-input-number v-model="form.overlapSeconds" :min="0" :max="3600" class="full-width" /></el-form-item>
        <el-form-item label="下次执行时间" :required="form.enabled"><el-date-picker v-model="form.nextRunAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" class="full-width" data-testid="youzan-next-run-at" placeholder="选择下一次自动同步时间" /></el-form-item>
      </div>
      <el-form-item label="自动增量同步"><el-switch v-model="form.enabled" :disabled="!canWrite" active-text="启用" inactive-text="停用" /></el-form-item>
      <p v-if="form.lastScheduledAt" class="proto-note">最近调度：{{ form.lastScheduledAt }}；每次执行后按间隔自动计算下一次。</p>
      <el-button v-if="canWrite" type="primary" :loading="saving" data-testid="youzan-policy-save" @click="save">保存策略</el-button>
    </el-form>
  </section>
</template>

<style scoped>
.youzan-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; max-width: 760px; }
.full-width { width: 100%; }
.policy-form { margin-top: 12px; }
@media (max-width: 640px) { .youzan-form-grid { grid-template-columns: 1fr; } }
</style>
