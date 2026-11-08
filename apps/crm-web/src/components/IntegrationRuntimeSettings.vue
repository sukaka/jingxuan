<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getIntegrationRuntimeConfig, saveIntegrationRuntimeConfig } from '../api/integration'

const props = defineProps<{ provider: 'youzan' | 'wecom' }>()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const source = ref<'DATABASE' | 'ENVIRONMENT'>('ENVIRONMENT')
const form = reactive({ youzanRealEnabled: false, wecomArchiveRealEnabled: false, wecomOfficialEnabled: false, version: 0 })

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const config = await getIntegrationRuntimeConfig()
    Object.assign(form, config)
    source.value = config.source
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '集成开关加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const config = await saveIntegrationRuntimeConfig({
      youzanRealEnabled: form.youzanRealEnabled,
      wecomArchiveRealEnabled: form.wecomArchiveRealEnabled,
      wecomOfficialEnabled: form.wecomOfficialEnabled,
      version: form.version,
    }, crypto.randomUUID())
    Object.assign(form, config)
    source.value = config.source
    ElMessage.success('全局集成开关已保存并即时生效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '集成开关保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="integration-runtime-settings" data-testid="integration-runtime-settings">
    <div class="integration-runtime-settings__head">
      <div>
        <h3>真实接入全局开关</h3>
        <p>仅控制当前企业，保存后立即生效，无需重启。供应商授权、凭证及 Q10/Q11 验收仍需独立完成。</p>
      </div>
      <el-button type="primary" :loading="saving" :disabled="!!loadError" data-testid="integration-runtime-save" @click="save">保存开关</el-button>
    </div>
    <el-alert v-if="loadError" type="error" :closable="false" :title="loadError" />
    <template v-else>
      <div class="integration-runtime-settings__source">配置来源：{{ source === 'DATABASE' ? '页面配置' : '环境变量默认值（保存后由页面配置接管）' }}</div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item v-if="props.provider === 'youzan'" label="有赞真实订单同步（FEATURE_REAL_YOUZAN）">
          <el-switch v-model="form.youzanRealEnabled" active-text="允许真实接入" inactive-text="禁用" data-testid="youzan-real-enabled" />
          <div class="integration-runtime-settings__note">还需逐一启用店铺同步、完成授权和连接测试；开启本身不会拉取订单或确认支付。</div>
        </el-form-item>
        <template v-else>
          <el-form-item label="企微官方客户联系 API（WECOM_OFFICIAL_ENABLED）">
            <el-switch v-model="form.wecomOfficialEnabled" active-text="允许官方 API" inactive-text="使用模拟/禁用" data-testid="wecom-official-enabled" />
            <div class="integration-runtime-settings__note">还需在服务端配置真实 CorpID、Secret 和回调参数，并维护启用的成员映射。</div>
          </el-form-item>
          <el-form-item label="企微真实会话归档（FEATURE_REAL_WECOM）">
            <el-switch v-model="form.wecomArchiveRealEnabled" active-text="允许真实归档" inactive-text="使用模拟/禁用" data-testid="wecom-archive-real-enabled" />
            <div class="integration-runtime-settings__note">Q10 与官方 Finance SDK 尚未验收完成；开启不代表已采集真实聊天，未授权时仍拒绝归档。</div>
          </el-form-item>
        </template>
      </el-form>
    </template>
  </section>
</template>

<style scoped>
.integration-runtime-settings {
  display: grid;
  gap: 14px;
  margin-bottom: 18px;
  padding: 18px 20px;
  border: 1px solid #dce9e7;
  border-radius: 12px;
  background: #f7fbfa;
}

.integration-runtime-settings__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.integration-runtime-settings__head h3 {
  margin: 0 0 5px;
  color: #183f42;
  font-size: 15px;
}

.integration-runtime-settings__head p,
.integration-runtime-settings__note,
.integration-runtime-settings__source {
  margin: 0;
  color: #6d8582;
  font-size: 11px;
  line-height: 1.7;
}

.integration-runtime-settings__note {
  margin-top: 6px;
}

.integration-runtime-settings :deep(.el-form-item) {
  margin-bottom: 4px;
}

@media (max-width: 640px) {
  .integration-runtime-settings__head {
    flex-direction: column;
  }
}
</style>
