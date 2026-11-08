<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getWecomSyncPolicy, saveWecomSyncPolicy } from '../../api/wecom'
import { tradingErrorText } from '../trading/tradingUi'
import { createWecomKey } from './wecomUi'
import { formatDateTime } from '../customer/shared'

const props = defineProps<{ corpId: string; canWrite: boolean }>()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const form = reactive({ enabled: false, contactSyncEnabled: true, archiveSyncEnabled: true, intervalSeconds: 300, nextRunAt: '', lastScheduledAt: null as string | null, lastStatus: 'NEVER', lastError: null as string | null, version: undefined as number | undefined })

function localInput(date: Date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function reset() {
  Object.assign(form, { enabled: false, contactSyncEnabled: true, archiveSyncEnabled: true, intervalSeconds: 300, nextRunAt: localInput(new Date(Date.now() + 300000)), lastScheduledAt: null, lastStatus: 'NEVER', lastError: null, version: undefined })
}

async function load() {
  if (!props.corpId) return
  loading.value = true
  loadError.value = ''
  try {
    const policy = await getWecomSyncPolicy(props.corpId)
    if (policy) Object.assign(form, { enabled: policy.enabled, contactSyncEnabled: policy.contactSyncEnabled, archiveSyncEnabled: policy.archiveSyncEnabled, intervalSeconds: policy.intervalSeconds, nextRunAt: policy.nextRunAt ? localInput(new Date(policy.nextRunAt)) : localInput(new Date(Date.now() + 300000)), lastScheduledAt: policy.lastScheduledAt, lastStatus: policy.lastStatus, lastError: policy.lastError, version: policy.version })
  } catch (error) {
    loadError.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (form.enabled && !form.contactSyncEnabled && !form.archiveSyncEnabled) {
    ElMessage.warning('至少启用一种同步类型')
    return
  }
  if (form.enabled && !form.nextRunAt) {
    ElMessage.warning('请设置下次执行时间')
    return
  }
  saving.value = true
  try {
    const saved = await saveWecomSyncPolicy(props.corpId, { enabled: form.enabled, contactSyncEnabled: form.contactSyncEnabled, archiveSyncEnabled: form.archiveSyncEnabled, intervalSeconds: form.intervalSeconds, nextRunAt: form.nextRunAt ? new Date(form.nextRunAt).toISOString() : undefined, version: form.version }, createWecomKey('wecom.sync.policy'))
    form.version = saved.version
    form.lastScheduledAt = saved.lastScheduledAt
    form.lastStatus = saved.lastStatus
    form.lastError = saved.lastError
    ElMessage.success('定时策略已保存，无需重启即可按新时间执行')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}

function statusText(status: string) {
  return ({ NEVER: '尚未执行', SUCCESS: '执行成功', PARTIAL: '部分成功', ERROR: '执行失败' } as Record<string, string>)[status] ?? status
}

watch(() => props.corpId, () => { reset(); void load() }, { immediate: true })
</script>

<template>
  <section v-loading="loading" class="wecom-policy-panel">
    <div class="wecom-section-head">
      <div><h3>定时同步设置</h3><p>分别控制客户资料与会话归档。保存后下一轮调度按新时间执行，无需重启；每次完成后按间隔计算下一次。</p></div>
      <el-button v-if="canWrite" type="primary" :loading="saving" data-testid="wecom-policy-save" @click="save">保存定时策略</el-button>
    </div>
    <el-alert v-if="loadError" type="warning" :closable="false" :title="loadError" />
    <el-form label-position="top" @submit.prevent>
      <div class="wecom-policy-switches">
        <el-form-item label="启用定时同步"><el-switch v-model="form.enabled" :disabled="!canWrite" active-text="启用" inactive-text="停用" /></el-form-item>
        <el-form-item label="客户资料同步"><el-switch v-model="form.contactSyncEnabled" :disabled="!canWrite" active-text="对账" /></el-form-item>
        <el-form-item label="会话归档同步"><el-switch v-model="form.archiveSyncEnabled" :disabled="!canWrite" active-text="增量" /></el-form-item>
      </div>
      <div class="wecom-policy-grid">
        <el-form-item label="执行间隔（秒）"><el-input-number v-model="form.intervalSeconds" :min="60" :max="86400" :disabled="!canWrite" class="wecom-full-width" /></el-form-item>
        <el-form-item label="下次执行时间" :required="form.enabled"><el-date-picker v-model="form.nextRunAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" :disabled="!canWrite" class="wecom-full-width" data-testid="wecom-next-run-at" placeholder="选择下一次自动同步时间" /></el-form-item>
      </div>
      <el-alert type="info" :closable="false" title="客户资料定时任务执行对账；会话归档从最近成功游标继续，不跳过未成功区间。" />
    </el-form>
    <div class="wecom-policy-status">
      <span>最近状态：{{ statusText(form.lastStatus) }}</span>
      <span v-if="form.lastScheduledAt">最近调度：{{ formatDateTime(form.lastScheduledAt) }}</span>
      <span v-if="form.nextRunAt">下次计划：{{ form.nextRunAt }}</span>
    </div>
    <el-alert v-if="form.lastError" type="error" :closable="false" :title="form.lastError" />
  </section>
</template>

<style scoped>
.wecom-policy-panel { display: grid; gap: 14px; }
.wecom-policy-switches { display: flex; flex-wrap: wrap; gap: 0 28px; }
.wecom-policy-grid { display: grid; grid-template-columns: minmax(180px, 280px) minmax(240px, 360px); gap: 16px; }
.wecom-policy-status { display: flex; flex-wrap: wrap; gap: 8px 20px; color: #587572; font-size: 12px; }
.wecom-full-width { width: 100%; }
@media (max-width: 640px) { .wecom-policy-grid { grid-template-columns: 1fr; } }
</style>
