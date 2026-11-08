<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { AiProvider, AiProviderConfig } from '../api/ai'
import { getAiConfig, saveAiConfig, testAiConfig } from '../api/ai'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { ApiError } from '../types/api'
import { formatDateTime } from './customer/shared'

const form = reactive({
  provider: 'disabled' as AiProvider,
  baseUrl: '',
  apiKey: '',
  model: '',
  timeoutSeconds: 30,
  enabled: false,
})
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const apiKeyConfigured = ref(false)
const connectionStatus = ref('UNVERIFIED')
const lastErrorMasked = ref<string | null>(null)
const lastConnectedAt = ref<string | null>(null)
const version = ref(0)
const errorMessage = ref('')
const formError = ref('')
const successMessage = ref('')

const providerLabel = computed(() => ({
  disabled: '已关闭',
  mock: '模拟模式',
  openai_compatible: 'OpenAI 兼容',
}[form.provider]))
const statusLabel = computed(() => ({
  CONNECTED: '连接正常',
  FAILED: '连接失败',
  UNVERIFIED: '尚未验证',
}[connectionStatus.value] ?? '尚未验证'))
const statusClass = computed(() => ({
  CONNECTED: 'success',
  FAILED: 'danger',
  UNVERIFIED: 'warning',
}[connectionStatus.value] ?? 'warning'))

function errorText(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) return '配置已被其他管理员修改，请刷新页面后重试'
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function applyConfig(config: AiProviderConfig) {
  Object.assign(form, {
    provider: config.provider,
    baseUrl: config.baseUrl ?? '',
    apiKey: '',
    model: config.model ?? '',
    timeoutSeconds: config.timeoutSeconds,
    enabled: config.enabled,
  })
  apiKeyConfigured.value = config.apiKeyConfigured
  connectionStatus.value = config.connectionStatus
  lastErrorMasked.value = config.lastErrorMasked
  lastConnectedAt.value = config.lastConnectedAt
  version.value = config.version
  loaded.value = true
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    applyConfig(await getAiConfig())
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function providerChanged(provider: AiProvider) {
  if (provider === 'disabled') form.enabled = false
  formError.value = ''
  successMessage.value = ''
}

function validate(): boolean {
  if (form.provider === 'openai_compatible' && (!form.baseUrl.trim() || !form.model.trim() || (!apiKeyConfigured.value && !form.apiKey))) {
    formError.value = '请填写接口地址、模型和 API Key'
    return false
  }
  formError.value = ''
  return true
}

async function submit(testAfterSave: boolean) {
  if (!validate()) return
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  let saved = false
  try {
    const result = await saveAiConfig({
      provider: form.provider,
      baseUrl: form.baseUrl.trim(),
      ...(form.apiKey ? { apiKey: form.apiKey } : {}),
      model: form.model.trim(),
      timeoutSeconds: form.timeoutSeconds,
      enabled: form.enabled,
      version: version.value,
    }, crypto.randomUUID())
    applyConfig(result)
    saved = true
    if (!testAfterSave) {
      successMessage.value = '配置已保存，后续 AI 任务将使用新配置'
      ElMessage.success(successMessage.value)
      return
    }
    testing.value = true
    const tested = await testAiConfig(crypto.randomUUID())
    applyConfig(tested)
    if (tested.connectionStatus !== 'CONNECTED') {
      errorMessage.value = '配置已保存，但连接测试未通过，请检查运行状态中的错误信息'
      return
    }
    successMessage.value = '配置已保存并测试通过'
    ElMessage.success(successMessage.value)
  } catch (error) {
    errorMessage.value = saved ? `配置已保存，但连接测试失败：${errorText(error)}` : errorText(error)
  } finally {
    saving.value = false
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="proto-page ai-settings-page">
    <PageHeader title="大模型配置" description="为当前企业配置 AI 服务。保存后无需重启，新配置作用于后续任务。" />

    <div class="ai-config-notice">
      <span class="proto-pill success">动态生效</span>
      <div>
        <strong>配置与执行分开管理</strong>
        <p>Worker 地址与内部令牌仍由部署环境管理。财务写回始终禁用，AI 结果仍需人工确认。</p>
      </div>
    </div>

    <el-alert v-if="errorMessage" class="ai-message" type="error" :title="errorMessage" :closable="false" />
    <el-alert v-if="successMessage" class="ai-message" type="success" :title="successMessage" :closable="false" />

    <div v-loading="loading" class="ai-config-layout">
      <PageCard title="服务配置" subtitle="API Key 由服务端加密保存，绝不回显或写入审计日志">
        <form class="proto-card-body ai-config-form" @submit.prevent="submit(false)">
          <el-form label-position="top">
            <el-form-item label="服务方式">
              <el-radio-group v-model="form.provider" class="ai-provider-options" @change="providerChanged">
                <el-radio-button value="disabled" data-testid="ai-provider-disabled">关闭</el-radio-button>
                <el-radio-button value="mock" data-testid="ai-provider-mock">模拟</el-radio-button>
                <el-radio-button value="openai_compatible" data-testid="ai-provider-openai">OpenAI 兼容</el-radio-button>
              </el-radio-group>
              <div class="proto-note">关闭后不发起大模型请求；模拟模式仅用于流程验证，不代表真实模型已联通。</div>
            </el-form-item>

            <template v-if="form.provider === 'openai_compatible'">
              <el-form-item label="接口地址" required>
                <el-input v-model="form.baseUrl" data-testid="ai-base-url" placeholder="https://example.com/v1" maxlength="1024" autocomplete="off" />
                <div class="proto-note">填写 OpenAI 兼容服务的基础地址，由服务端发起连接。</div>
              </el-form-item>
              <el-form-item label="API Key" :required="!apiKeyConfigured">
                <el-input v-model="form.apiKey" data-testid="ai-api-key" type="password" show-password maxlength="512" autocomplete="new-password" placeholder="输入新的 API Key" />
                <div class="proto-note ai-key-state">{{ apiKeyConfigured ? '已配置，留空保持不变' : '尚未配置，请填写 API Key' }}；填写新值将替换原 Key。</div>
              </el-form-item>
            </template>

            <div v-if="form.provider !== 'disabled'" class="proto-form-grid">
              <el-form-item label="模型" :required="form.provider === 'openai_compatible'">
                <el-input v-model="form.model" data-testid="ai-model" placeholder="例如 gpt-4o-mini" maxlength="128" />
              </el-form-item>
              <el-form-item label="超时时间（秒）" required>
                <el-input-number v-model="form.timeoutSeconds" data-testid="ai-timeout" :min="1" :max="300" :step="1" step-strictly controls-position="right" />
              </el-form-item>
            </div>

            <el-form-item label="AI 执行开关">
              <div class="ai-enabled-control">
                <el-switch v-model="form.enabled" data-testid="ai-enabled" :disabled="form.provider === 'disabled'" active-text="允许执行" inactive-text="暂停执行" />
                <span>仅控制后续 AI 任务，不影响历史记录与人工操作。</span>
              </div>
            </el-form-item>

            <el-alert v-if="formError" class="ai-message" type="warning" :title="formError" :closable="false" />
            <div class="ai-action-bar">
              <el-button native-type="submit" :loading="saving && !testing" :disabled="!loaded || saving" data-testid="ai-config-save">保存配置</el-button>
              <el-button type="primary" :loading="testing" :disabled="!loaded || saving || form.provider === 'disabled'" data-testid="ai-config-save-test" @click="submit(true)">保存并测试连接</el-button>
            </div>
          </el-form>
        </form>
      </PageCard>

      <PageCard title="运行状态" subtitle="状态来自服务端最近一次连接测试">
        <div class="proto-card-body ai-status-body">
          <div class="ai-status-heading">
            <span class="proto-pill" :class="statusClass">{{ statusLabel }}</span>
            <strong>{{ providerLabel }}</strong>
          </div>
          <dl class="ai-status-list">
            <div><dt>任务执行</dt><dd>{{ form.enabled && form.provider !== 'disabled' ? '已允许' : '已暂停' }}</dd></div>
            <div><dt>API Key</dt><dd>{{ apiKeyConfigured ? '已配置' : '未配置' }}</dd></div>
            <div><dt>最近连接</dt><dd>{{ formatDateTime(lastConnectedAt) }}</dd></div>
            <div><dt>配置版本</dt><dd>{{ version }}</dd></div>
          </dl>
          <div v-if="lastErrorMasked" class="ai-last-error">
            <strong>最近错误</strong>
            <p>{{ lastErrorMasked }}</p>
          </div>
          <p class="proto-note">修改配置后请重新执行“保存并测试连接”。测试成功仅表示连接可用，不会自动确认订单、核销或退款。</p>
        </div>
      </PageCard>
    </div>
  </div>
</template>

<style scoped>
.ai-config-notice { display: flex; align-items: flex-start; gap: 13px; margin-bottom: 20px; padding: 17px 20px; border: 1px solid #ead9b7; border-radius: 12px; color: #715b37; background: #fffaf0; }
.ai-config-notice strong { color: #425d50; font-size: 13px; }
.ai-config-notice p { margin: 5px 0 0; font-size: 11px; line-height: 1.7; }
.ai-message { margin-bottom: 14px; }
.ai-config-layout { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(280px, .8fr); gap: 18px; align-items: start; }
.ai-config-layout .proto-card { margin-top: 0; }
.ai-config-form { max-width: 780px; }
.ai-config-form :deep(.el-input-number) { width: 100%; }
.ai-provider-options { max-width: 100%; flex-wrap: wrap; row-gap: 8px; }
.ai-key-state { color: #8a724c; }
.ai-enabled-control { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.ai-enabled-control > span { color: #879aa5; font-size: 11px; }
.ai-action-bar { display: flex; justify-content: flex-end; gap: 10px; padding-top: 16px; border-top: 1px solid #e8eef2; }
.ai-action-bar .el-button + .el-button { margin-left: 0; }
.ai-status-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 17px; border-bottom: 1px solid #e8eef2; }
.ai-status-heading strong { color: #385248; }
.ai-status-list { margin: 6px 0 18px; }
.ai-status-list > div { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid #f0f3f4; }
.ai-status-list dt { color: #8a9ba5; }
.ai-status-list dd { margin: 0; color: #385248; font-weight: 650; text-align: right; overflow-wrap: anywhere; }
.ai-last-error { margin-bottom: 15px; padding: 12px; border-radius: 9px; color: #9b4d56; background: #fff3f3; overflow-wrap: anywhere; }
.ai-last-error p { margin: 5px 0 0; font-size: 11px; line-height: 1.7; }
@media (max-width: 900px) { .ai-config-layout { grid-template-columns: 1fr; } }
@media (max-width: 480px) {
  .ai-config-notice { padding: 14px; }
  .ai-action-bar { align-items: stretch; flex-direction: column; }
  .ai-action-bar .el-button { width: 100%; }
  .ai-provider-options { display: grid; grid-template-columns: .8fr .8fr 1.6fr; width: 100%; }
  .ai-provider-options :deep(.el-radio-button__inner) { width: 100%; padding-right: 8px; padding-left: 8px; }
}
</style>
