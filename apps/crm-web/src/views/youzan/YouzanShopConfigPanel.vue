<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { YouzanShopConfig } from '../../api/youzan'
import { saveYouzanShop, testYouzanShop } from '../../api/youzan'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'
import { formatDateTime } from '../customer/shared'

defineProps<{ shops: YouzanShopConfig[]; loading: boolean; canWrite: boolean }>()
const emit = defineEmits<{ saved: []; select: [shopId: string] }>()
const formVisible = ref(false)
const saving = ref(false)
const testingId = ref<number | null>(null)
const editingId = ref<number | null>(null)
const form = reactive({ shopId: '', shopName: '', clientId: '', clientSecret: '', syncEnabled: false, version: undefined as number | undefined })

function openCreate() {
  editingId.value = null
  Object.assign(form, { shopId: '', shopName: '', clientId: '', clientSecret: '', syncEnabled: false, version: undefined })
  formVisible.value = true
}

function openEdit(shop: YouzanShopConfig) {
  editingId.value = shop.id
  Object.assign(form, { shopId: shop.shopId, shopName: shop.shopName, clientId: shop.clientId, clientSecret: '', syncEnabled: shop.syncEnabled, version: shop.version })
  formVisible.value = true
}

async function save() {
  if (!form.shopId.trim() || !form.shopName.trim() || !form.clientId.trim() || (!editingId.value && !form.clientSecret)) {
    ElMessage.warning('请完整填写店铺、应用和密钥信息')
    return
  }
  saving.value = true
  try {
    const saved = await saveYouzanShop({ shopId: form.shopId.trim(), shopName: form.shopName.trim(), clientId: form.clientId.trim(), clientSecret: form.clientSecret || undefined, syncEnabled: form.syncEnabled, version: form.version }, createYouzanKey('youzan.shop.config'))
    ElMessage.success('店铺配置已保存，连接成功前不会视为真实店铺已联调')
    formVisible.value = false
    emit('saved')
    emit('select', saved.shopId)
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}

async function test(shop: YouzanShopConfig) {
  testingId.value = shop.id
  try {
    await testYouzanShop(shop.shopId, createYouzanKey('youzan.shop.test'))
    ElMessage.success('授权与订单接口连接测试通过')
    emit('saved')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    testingId.value = null
  }
}

function statusText(status: string) {
  return ({ CONNECTED: '连接通过', FAILED: '连接失败', UNVERIFIED: '待验证' } as Record<string, string>)[status] ?? status
}
</script>

<template>
  <section>
    <div class="youzan-section-head">
      <div><h3>店铺接入</h3><p>凭证仅加密保存在服务端。保存配置不等于授权成功，需通过连接测试后再启用同步。</p></div>
      <el-button v-if="canWrite" size="small" data-testid="youzan-shop-create" @click="openCreate">接入店铺</el-button>
    </div>
    <el-alert type="info" :closable="false" title="当前为真实接入功能预建。未完成实际店铺授权和沙箱验收前，请勿视为生产已联通。" class="dialog-tip" />
    <app-table v-loading="loading" :data="shops" size="small">
      <el-table-column label="店铺" min-width="150"><template #default="{ row }"><el-button text @click="emit('select', row.shopId)">{{ row.shopName }}</el-button><div class="proto-subcell">店铺编号：{{ row.shopId }}</div></template></el-table-column>
      <el-table-column prop="clientId" label="应用 ID" min-width="150" show-overflow-tooltip />
      <el-table-column label="密钥" width="90"><template #default="{ row }">{{ row.secretConfigured ? '已配置' : '未配置' }}</template></el-table-column>
      <el-table-column label="连接状态" width="105"><template #default="{ row }">{{ statusText(row.connectionStatus) }}</template></el-table-column>
      <el-table-column label="自动同步" width="90"><template #default="{ row }">{{ row.syncEnabled ? '已启用' : '已停用' }}</template></el-table-column>
      <el-table-column label="访问凭证到期" min-width="150"><template #default="{ row }">{{ row.tokenExpiresAt ? formatDateTime(row.tokenExpiresAt) : '尚未获取' }}</template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="180" fixed="right"><template #default="{ row }"><el-button text @click="openEdit(row)">编辑</el-button><el-button text :loading="testingId === row.id" data-testid="youzan-shop-test" @click="test(row)">测试连接</el-button></template></el-table-column>
      <template #empty><el-empty description="尚无真实店铺配置；模拟演示店铺无需在此登记" /></template>
    </app-table>
    <div v-for="shop in shops.filter(item => item.lastError)" :key="`error-${shop.id}`" class="youzan-warning-text">{{ shop.shopName }}：{{ shop.lastError }}</div>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑有赞店铺' : '接入有赞店铺'" width="min(620px, 94vw)">
      <el-form label-position="top" @submit.prevent>
        <div class="youzan-form-grid">
          <el-form-item label="有赞店铺编号" required><el-input v-model="form.shopId" :disabled="!!editingId" maxlength="64" data-testid="youzan-shop-kdt" /></el-form-item>
          <el-form-item label="店铺名称" required><el-input v-model="form.shopName" maxlength="128" /></el-form-item>
        </div>
        <el-form-item label="应用 ID" required><el-input v-model="form.clientId" maxlength="128" autocomplete="off" /></el-form-item>
        <el-form-item :label="editingId ? '应用密钥（留空保持不变）' : '应用密钥'" :required="!editingId"><el-input v-model="form.clientSecret" type="password" show-password maxlength="512" autocomplete="new-password" data-testid="youzan-shop-secret" /></el-form-item>
        <el-form-item label="真实同步开关"><el-switch v-model="form.syncEnabled" active-text="允许同步" inactive-text="仅保存配置" /><div class="proto-note">还需服务端 FEATURE_REAL_YOUZAN 开启，且授权与连接测试通过。</div></el-form-item>
        <el-form-item label="官方消息推送地址"><el-input model-value="/api/v1/webhooks/youzan/official" readonly><template #prepend>CRM</template></el-input><div class="proto-note">在有赞云控制台配置公网安全地址，并订阅交易支付等已授权消息。</div></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="formVisible = false">取消</el-button><el-button type="primary" :loading="saving" data-testid="youzan-shop-save" @click="save">保存配置</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.youzan-form-grid { display: grid; grid-template-columns: 1fr 1.4fr; gap: 16px; }
.dialog-tip { margin-bottom: 14px; }
@media (max-width: 640px) { .youzan-form-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
