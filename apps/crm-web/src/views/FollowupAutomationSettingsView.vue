<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getFollowupAutomationPolicy,
  updateFollowupAutomationPolicy,
} from '../api/followup-automation'
import type { IntentIntervalOverride } from '../api/followup-automation'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('followup:policy:manage'))
const defaultIntervalDays = ref(14)
const intentOverrides = ref<IntentIntervalOverride[]>([])
const version = ref(0)
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
let idempotencyKey = ''
let idempotencyPayload = ''

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const policy = await getFollowupAutomationPolicy()
    defaultIntervalDays.value = policy.defaultIntervalDays
    intentOverrides.value = policy.intentOverrides.map((item) => ({ ...item }))
    version.value = policy.version
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '策略加载失败'
  } finally {
    loading.value = false
  }
}

function addOverride() {
  if (!canManage.value) return
  const used = new Set(intentOverrides.value.map((item) => item.intentLevel))
  const intentLevel = ['HIGH', 'MEDIUM', 'LOW'].find((code) => !used.has(code)) ?? 'HIGH'
  intentOverrides.value.push({ intentLevel, intervalDays: 7 })
}

async function save() {
  if (!canManage.value || saving.value) return
  successMessage.value = ''
  errorMessage.value = ''
  if (defaultIntervalDays.value < 1 || defaultIntervalDays.value > 365) {
    errorMessage.value = '默认间隔必须在 1 至 365 天之间'
    return
  }
  const codes = new Set<string>()
  for (const item of intentOverrides.value) {
    if (!item.intentLevel || item.intervalDays < 1 || item.intervalDays > 365 || codes.has(item.intentLevel)) {
      errorMessage.value = '意向覆盖需使用不同级别，且间隔在 1 至 365 天之间'
      return
    }
    codes.add(item.intentLevel)
  }
  const request = {
    defaultIntervalDays: defaultIntervalDays.value,
    intentOverrides: intentOverrides.value.map((item) => ({ ...item })),
    version: version.value,
  }
  const payload = JSON.stringify(request)
  if (!idempotencyKey || idempotencyPayload !== payload) {
    idempotencyKey = crypto.randomUUID()
    idempotencyPayload = payload
  }
  saving.value = true
  try {
    const policy = await updateFollowupAutomationPolicy(request, idempotencyKey)
    defaultIntervalDays.value = policy.defaultIntervalDays
    intentOverrides.value = policy.intentOverrides.map((item) => ({ ...item }))
    version.value = policy.version
    successMessage.value = '策略已保存，仅作用于之后新建的跟进'
    idempotencyKey = ''
    idempotencyPayload = ''
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '策略保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page">
    <PageHeader title="跟进自动化策略" description="按客户意向配置默认跟进周期；手动填写的下次跟进时间始终优先，不影响已有历史任务" />
    <PageCard title="跟进间隔" subtitle="默认 14 天，可按意向级别覆盖为 1–365 天">
      <div v-loading="loading" class="policy-form">
        <el-alert
          v-if="!canManage"
          type="info"
          title="当前账号仅可查看跟进策略；如需修改配置，请联系管理员开通对应权限。"
          :closable="false"
          data-testid="followup-readonly-alert"
        />
        <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" role="alert" />
        <el-alert v-if="successMessage" type="success" :title="successMessage" :closable="false" role="status" />
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="默认跟进间隔（天）" required>
            <el-input-number v-model="defaultIntervalDays" :min="1" :max="365" :disabled="!canManage" data-testid="default-interval" />
          </el-form-item>
          <div class="override-heading">
            <div><strong>意向级别覆盖</strong><p>客户意向级别匹配时使用对应间隔</p></div>
            <el-button v-if="canManage" data-testid="add-override" @click="addOverride">＋ 添加覆盖</el-button>
          </div>
          <div v-if="intentOverrides.length" class="override-list">
            <div v-for="(item, index) in intentOverrides" :key="`${item.intentLevel}-${index}`" class="override-row">
              <el-select v-model="item.intentLevel" :disabled="!canManage" aria-label="意向级别">
                <el-option label="高意向" value="HIGH" />
                <el-option label="中意向" value="MEDIUM" />
                <el-option label="低意向" value="LOW" />
              </el-select>
              <el-input-number v-model="item.intervalDays" :min="1" :max="365" :disabled="!canManage" aria-label="覆盖间隔天数" />
              <span>天</span>
              <el-button v-if="canManage" text type="danger" @click="intentOverrides.splice(index, 1)">删除</el-button>
            </div>
          </div>
          <div v-else class="proto-empty">暂无意向覆盖，全部使用默认间隔</div>
          <div class="policy-actions">
            <span>当前版本：第 {{ version }} 版</span>
            <el-button v-if="canManage" type="primary" :loading="saving" data-testid="save-policy" @click="save">保存策略</el-button>
          </div>
        </el-form>
      </div>
    </PageCard>
  </div>
</template>

<style scoped>
.policy-form{max-width:780px}.el-alert{margin-bottom:14px}.override-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:20px 0 12px}.override-heading p{margin:4px 0 0;color:#7b919d;font-size:12px}.override-list{display:grid;gap:10px}.override-row{display:grid;grid-template-columns:minmax(150px,1fr) 170px 24px 60px;align-items:center;gap:10px}.policy-actions{display:flex;align-items:center;justify-content:space-between;margin-top:22px;padding-top:16px;border-top:1px solid #e6edf0;color:#7b919d;font-size:12px}@media(max-width:640px){.override-row{grid-template-columns:1fr 1fr}.override-heading{align-items:flex-start;flex-direction:column}}
</style>
