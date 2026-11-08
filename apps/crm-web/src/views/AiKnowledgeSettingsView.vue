<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { DifyKnowledgeConfig, KnowledgeProvider } from '../api/knowledge'
import { getKnowledgeConfig, saveKnowledgeConfig, testKnowledgeConfig } from '../api/knowledge'
import PageCard from '../components/prototype/PageCard.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import { ApiError } from '../types/api'
import { formatDateTime } from './customer/shared'

const form = reactive({
  provider: 'disabled' as KnowledgeProvider,
  baseUrl: '',
  apiKey: '',
  datasetId: '',
  datasetApiKey: '',
  syncEnabled: false,
  timeoutSeconds: 30,
  enabled: false,
})
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const apiKeyConfigured = ref(false)
const datasetApiKeyConfigured = ref(false)
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
  dify: 'Dify Chatflow',
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

function applyConfig(config: DifyKnowledgeConfig) {
  Object.assign(form, {
    provider: config.provider,
    baseUrl: config.baseUrl ?? '',
    apiKey: '',
    datasetId: config.datasetId ?? '',
    datasetApiKey: '',
    syncEnabled: config.syncEnabled,
    timeoutSeconds: config.timeoutSeconds,
    enabled: config.enabled,
  })
  apiKeyConfigured.value = config.apiKeyConfigured
  datasetApiKeyConfigured.value = config.datasetApiKeyConfigured
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
    applyConfig(await getKnowledgeConfig())
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function providerChanged(provider: KnowledgeProvider) {
  if (provider === 'disabled') {
    form.enabled = false
    form.syncEnabled = false
  }
  formError.value = ''
  successMessage.value = ''
}

function validate(): boolean {
  if (form.provider === 'dify' && (!form.baseUrl.trim() || (!apiKeyConfigured.value && !form.apiKey))) {
    formError.value = '请填写 Dify 接口地址和 API Key'
    return false
  }
  if (form.provider === 'dify' && form.syncEnabled
      && (!form.datasetId.trim() || (!datasetApiKeyConfigured.value && !form.datasetApiKey))) {
    formError.value = '启用同步前请填写知识数据集 ID 和知识数据集 API 密钥'
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
    const result = await saveKnowledgeConfig({
      provider: form.provider,
      baseUrl: form.baseUrl.trim(),
      ...(form.apiKey ? { apiKey: form.apiKey } : {}),
      datasetId: form.datasetId.trim(),
      ...(form.datasetApiKey ? { datasetApiKey: form.datasetApiKey } : {}),
      syncEnabled: form.syncEnabled,
      timeoutSeconds: form.timeoutSeconds,
      enabled: form.enabled,
      version: version.value,
    }, crypto.randomUUID())
    applyConfig(result)
    saved = true
    if (!testAfterSave) {
      successMessage.value = '知识库配置已保存'
      ElMessage.success(successMessage.value)
      return
    }
    testing.value = true
    const tested = await testKnowledgeConfig(crypto.randomUUID())
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
  <div class="proto-page knowledge-settings-page">
    <PageHeader title="知识库配置" description="独立配置 Dify Chatflow，不改变现有会话证据抽取服务。" />

    <div class="knowledge-notice">
      <span class="proto-pill warning">默认安全关闭</span>
      <div>
        <strong>先验证连接，再开放知识能力</strong>
        <p>资料同步按阶段开放，公开问答仍未开放。真实 Dify 还受部署开关与 Q19 合规确认限制，模拟模式不代表真实联通。</p>
      </div>
    </div>

    <el-alert v-if="errorMessage" class="knowledge-message" type="error" :title="errorMessage" :closable="false" />
    <el-alert v-if="successMessage" class="knowledge-message" type="success" :title="successMessage" :closable="false" />

    <div v-loading="loading" class="knowledge-layout">
      <PageCard title="Dify 服务" subtitle="Chatflow App Key 由服务端加密保存，不在响应或审计中回显">
        <form class="proto-card-body knowledge-form" @submit.prevent="submit(false)">
          <el-form label-position="top">
            <el-form-item label="服务方式">
              <el-radio-group v-model="form.provider" class="knowledge-provider-options" @change="providerChanged">
                <el-radio-button value="disabled" data-testid="dify-provider-disabled">关闭</el-radio-button>
                <el-radio-button value="mock" data-testid="dify-provider-mock">模拟</el-radio-button>
                <el-radio-button value="dify" data-testid="dify-provider-real">Dify</el-radio-button>
              </el-radio-group>
              <div class="proto-note">连接测试只发送固定合成问题，不读取客户、聊天或素材内容。</div>
            </el-form-item>

            <template v-if="form.provider === 'dify'">
              <el-form-item label="API 基础地址" required>
                <el-input v-model="form.baseUrl" data-testid="dify-base-url" placeholder="https://dify.example.com/v1" maxlength="1024" autocomplete="off" />
                <div class="proto-note">填写包含 /v1 的 Dify API 地址，由服务端决定是否允许真实请求。</div>
              </el-form-item>
              <el-form-item label="Chatflow App API Key" :required="!apiKeyConfigured">
                <el-input v-model="form.apiKey" data-testid="dify-api-key" type="password" show-password maxlength="512" autocomplete="new-password" placeholder="输入新的 App API Key" />
                <div class="proto-note knowledge-key-state">{{ apiKeyConfigured ? '已配置，留空保持不变' : '尚未配置，请填写 API Key' }}；填写新值将替换原 Key。</div>
              </el-form-item>
              <el-form-item label="知识数据集 ID" :required="form.syncEnabled">
                <el-input v-model="form.datasetId" data-testid="dify-dataset-id" placeholder="输入当前租户的知识数据集 ID" maxlength="128" autocomplete="off" />
                <div class="proto-note">知识数据集与 Chatflow 分开授权，资料同步只使用知识数据集 API 密钥。</div>
              </el-form-item>
              <el-form-item label="知识数据集 API 密钥" :required="form.syncEnabled && !datasetApiKeyConfigured">
                <el-input v-model="form.datasetApiKey" data-testid="dify-dataset-api-key" type="password" show-password maxlength="512" autocomplete="new-password" placeholder="输入新的知识数据集 API 密钥" />
                <div class="proto-note knowledge-key-state">{{ datasetApiKeyConfigured ? '已配置，留空保持不变' : '尚未配置，请填写知识数据集 API 密钥' }}；不会用于 Chatflow。</div>
              </el-form-item>
            </template>

            <el-form-item v-if="form.provider !== 'disabled'" label="超时时间（秒）" required>
              <el-input-number v-model="form.timeoutSeconds" data-testid="dify-timeout" :min="1" :max="300" :step="1" step-strictly controls-position="right" />
            </el-form-item>

            <el-form-item label="知识问答开关">
              <div class="knowledge-enabled-control">
                <el-switch v-model="form.enabled" data-testid="dify-enabled" :disabled="form.provider === 'disabled'" active-text="允许执行" inactive-text="暂停执行" />
                <span>仅保存当前租户配置，不代表资料已发布或真实能力已验收。</span>
              </div>
            </el-form-item>

            <el-form-item label="资料同步开关">
              <div class="knowledge-enabled-control">
                <el-switch v-model="form.syncEnabled" data-testid="dify-sync-enabled" :disabled="form.provider === 'disabled'" active-text="允许发布" inactive-text="停止发布" />
                <span>只允许已审核素材进入同步队列；真实 Dify 仍受部署开关和 Q19/Q20 限制。</span>
              </div>
            </el-form-item>

            <el-alert v-if="form.provider === 'dify'" class="knowledge-message" type="warning" title="真实连接测试可能消耗模型调用量、产生费用；连通也不代表知识回答质量通过。" :closable="false" />
            <el-alert v-if="formError" class="knowledge-message" type="warning" :title="formError" :closable="false" />
            <div class="knowledge-actions">
              <el-button native-type="submit" :loading="saving && !testing" :disabled="!loaded || saving" data-testid="dify-config-save">保存配置</el-button>
              <el-button type="primary" :loading="testing" :disabled="!loaded || saving || form.provider === 'disabled'" data-testid="dify-config-save-test" @click="submit(true)">保存并测试连接</el-button>
            </div>
          </el-form>
        </form>
      </PageCard>

      <PageCard title="最近验证" subtitle="仅表示最近一次连接测试的结果">
        <div class="proto-card-body knowledge-status-body">
          <div class="knowledge-status-heading">
            <span class="proto-pill" :class="statusClass">{{ statusLabel }}</span>
            <strong>{{ providerLabel }}</strong>
          </div>
          <dl class="knowledge-status-list">
            <div><dt>知识问答</dt><dd>{{ form.enabled && form.provider !== 'disabled' ? '已允许' : '已暂停' }}</dd></div>
            <div><dt>App API Key</dt><dd>{{ apiKeyConfigured ? '已配置' : '未配置' }}</dd></div>
            <div><dt>知识数据集 API 密钥</dt><dd>{{ datasetApiKeyConfigured ? '已配置' : '未配置' }}</dd></div>
            <div><dt>资料同步</dt><dd>{{ form.syncEnabled && form.provider !== 'disabled' ? '允许发布' : '停止发布' }}</dd></div>
            <div><dt>最近连接</dt><dd>{{ formatDateTime(lastConnectedAt) }}</dd></div>
            <div><dt>配置版本</dt><dd>{{ version }}</dd></div>
          </dl>
          <div v-if="lastErrorMasked" class="knowledge-last-error">
            <strong>最近错误</strong>
            <p>{{ lastErrorMasked }}</p>
          </div>
          <p class="proto-note">修改配置后需重新测试。资料发布与同步状态在“知识资料”页面管理，公开问答仍未开放。</p>
        </div>
      </PageCard>
    </div>
  </div>
</template>

<style scoped>
.knowledge-notice { display: flex; align-items: flex-start; gap: 14px; margin-bottom: 20px; padding: 18px 20px; border: 1px solid #e6d7b8; border-radius: 12px; color: #6e5935; background: #fffaf0; }
.knowledge-notice strong { color: #3e594d; font-size: 13px; }
.knowledge-notice p { margin: 5px 0 0; font-size: 11px; line-height: 1.7; }
.knowledge-message { margin-bottom: 14px; }
.knowledge-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(280px, .8fr); gap: 20px; align-items: start; }
.knowledge-layout .proto-card { margin-top: 0; }
.knowledge-form { max-width: 780px; }
.knowledge-form :deep(.el-form-item__content) { flex-wrap: wrap; }
.knowledge-form :deep(.proto-note) { flex-basis: 100%; }
.knowledge-form :deep(.el-input-number) { width: 100%; }
.knowledge-provider-options { max-width: 100%; flex-wrap: wrap; row-gap: 8px; }
.knowledge-key-state { color: #876f47; }
.knowledge-enabled-control { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.knowledge-enabled-control > span { color: #7d919c; font-size: 11px; line-height: 1.6; }
.knowledge-actions { display: flex; justify-content: flex-end; gap: 10px; padding-top: 16px; border-top: 1px solid #e4ebe7; }
.knowledge-actions .el-button + .el-button { margin-left: 0; }
.knowledge-status-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 17px; border-bottom: 1px solid #e4ebe7; }
.knowledge-status-heading strong { color: #354f45; }
.knowledge-status-list { margin: 6px 0 18px; }
.knowledge-status-list > div { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid #edf2ef; }
.knowledge-status-list dt { color: #849792; }
.knowledge-status-list dd { margin: 0; color: #354f45; font-weight: 650; text-align: right; overflow-wrap: anywhere; }
.knowledge-last-error { margin-bottom: 15px; padding: 12px; border-radius: 9px; color: #974a54; background: #fff3f1; overflow-wrap: anywhere; }
.knowledge-last-error p { margin: 5px 0 0; font-size: 11px; line-height: 1.7; }
@media (max-width: 900px) { .knowledge-layout { grid-template-columns: 1fr; } }
@media (max-width: 480px) {
  .knowledge-notice { padding: 14px; }
  .knowledge-actions { align-items: stretch; flex-direction: column; }
  .knowledge-actions .el-button { width: 100%; }
}
</style>
