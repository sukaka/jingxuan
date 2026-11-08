<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { YouzanOrderView } from '../../api/youzan'
import { useAuthStore } from '../../stores/auth'
import { fenToYuan } from '../../utils/money'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { orderSourceLabel, orderStatusLabel } from '../trading/tradingUi'
import { mappingStatusLabel, mappingStatusTone, verificationMethodLabel } from './youzanUi'

const props = defineProps<{
  modelValue: boolean
  order: YouzanOrderView | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const auth = useAuthStore()
const canViewRaw = computed(() => auth.hasPermission('integration:youzan:manage'))
const rawExpanded = ref(false)
const hasRaw = computed(() => Boolean(props.order?.payloadMaskedJson?.trim()))
const formattedRaw = computed(() => {
  const raw = props.order?.payloadMaskedJson
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

watch([() => props.modelValue, () => props.order?.eventId], () => {
  rawExpanded.value = false
})
watch(canViewRaw, (visible) => {
  if (!visible) rawExpanded.value = false
})

async function copyRaw() {
  if (!canViewRaw.value || !hasRaw.value) return
  try {
    await navigator.clipboard.writeText(formattedRaw.value)
    ElMessage.success('脱敏原始数据已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="有赞订单详情"
    size="min(820px, 96vw)"
    class="youzan-order-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="order" class="youzan-order-detail">
      <el-alert v-if="order.shopId.startsWith('MOCK-')" type="warning" :closable="false" title="模拟数据只用于适配验证，不代表真实有赞连接或真实资金到账。" class="detail-tip" />
      <el-alert v-else type="info" :closable="false" title="真实订单以有赞订单详情和 CRM 支付核验结果为准，消息接收不代表资金已确认。" class="detail-tip" />

      <section class="detail-section" aria-labelledby="youzan-order-status">
        <div class="section-header">
          <div>
            <h3 id="youzan-order-status">订单与同步状态</h3>
            <p>核对外部事件与 CRM 内部订单的处理进度</p>
          </div>
          <StatusPill :text="mappingStatusLabel(order.mappingStatus)" :tone="mappingStatusTone(order.mappingStatus)" />
        </div>
        <dl class="detail-grid">
          <div class="detail-item">
            <dt>有赞订单号</dt>
            <dd class="detail-identifier">{{ order.externalOrderNo }}</dd>
          </div>
          <div class="detail-item">
            <dt>原始事件 ID</dt>
            <dd class="detail-identifier">{{ order.eventId }}</dd>
          </div>
          <div class="detail-item">
            <dt>内部订单 ID</dt>
            <dd>{{ order.orderId == null ? '尚未生成' : `#${order.orderId}` }}</dd>
          </div>
          <div class="detail-item">
            <dt>内部订单状态</dt>
            <dd><StatusPill :text="order.orderStatus ? orderStatusLabel(order.orderStatus) : '尚未生成'" tone="info" /></dd>
          </div>
        </dl>
      </section>

      <section class="detail-section" aria-labelledby="youzan-order-payment">
        <div class="section-header">
          <div>
            <h3 id="youzan-order-payment">金额与支付核验</h3>
            <p>外部回报金额不代表 CRM 已确认到账</p>
          </div>
        </div>
        <div class="amount-grid">
          <div class="amount-card amount-card-external">
            <span>有赞外部金额</span>
            <strong v-if="order.externalAmountFen != null" class="amount-value"><small>¥</small>{{ fenToYuan(order.externalAmountFen) }}</strong>
            <strong v-else class="amount-value amount-pending">未记录</strong>
          </div>
          <div class="amount-card amount-card-paid">
            <span>CRM 内部实付</span>
            <strong v-if="order.paidFen != null" class="amount-value"><small>¥</small>{{ fenToYuan(order.paidFen) }}</strong>
            <strong v-else class="amount-value amount-pending">尚未确认</strong>
          </div>
        </div>
        <div class="payment-verification">
          <span>支付核验方式</span>
          <strong>{{ verificationMethodLabel(order.verificationMethod) }}</strong>
        </div>
      </section>

      <section class="detail-section" aria-labelledby="youzan-order-source">
        <div class="section-header">
          <div>
            <h3 id="youzan-order-source">来源与时间</h3>
            <p>确认订单来源店铺及有赞侧最后更新时间</p>
          </div>
        </div>
        <dl class="detail-grid">
          <div class="detail-item">
            <dt>来源系统</dt>
            <dd>{{ orderSourceLabel(order.sourceSystem) }}</dd>
          </div>
          <div class="detail-item">
            <dt>来源店铺 ID</dt>
            <dd class="detail-identifier">{{ order.externalShopId }}</dd>
          </div>
          <div class="detail-item detail-item-wide">
            <dt>有赞更新时间</dt>
            <dd>{{ order.sourceUpdatedAt ? formatDateTime(order.sourceUpdatedAt) : '未记录' }}</dd>
          </div>
        </dl>
      </section>

      <section class="detail-section" aria-labelledby="youzan-order-error">
        <div class="section-header">
          <div>
            <h3 id="youzan-order-error">异常信息</h3>
            <p>异常只用于人工排查，不会自动确认订单或支付</p>
          </div>
        </div>
        <div v-if="order.errorReason" class="error-panel">
          <strong>映射处理异常</strong>
          <p>{{ order.errorReason }}</p>
        </div>
        <div v-else class="no-error-panel">当前记录无映射异常</div>
      </section>

      <section v-if="canViewRaw" class="detail-section raw-section" aria-labelledby="youzan-order-raw" data-testid="youzan-raw-section">
        <div class="section-header raw-header">
          <div>
            <div class="raw-title-row">
              <h3 id="youzan-order-raw">原始脱敏详情</h3>
              <span class="admin-badge">管理员排障</span>
            </div>
            <p>仅用于定位同步问题，默认收起，不作为业务处理依据</p>
          </div>
          <el-button
            v-if="hasRaw"
            text
            type="primary"
            :aria-expanded="rawExpanded"
            aria-controls="youzan-raw-content"
            class="raw-toggle"
            data-testid="youzan-raw-toggle"
            @click="rawExpanded = !rawExpanded"
          >
            {{ rawExpanded ? '收起原始数据' : '展开原始数据' }}
          </el-button>
        </div>
        <el-alert v-if="!hasRaw" type="info" :closable="false" title="当前记录无脱敏原始数据，不能据此自动匹配客户、SKU 或支付。" />
        <div v-else-if="rawExpanded" id="youzan-raw-content" class="raw-content">
          <div class="raw-toolbar">
            <span>已安全格式化；非法 JSON 保留原文</span>
            <el-button size="small" data-testid="youzan-raw-copy" @click="copyRaw">复制原始数据</el-button>
          </div>
          <pre class="youzan-raw" data-testid="youzan-raw-json">{{ formattedRaw }}</pre>
        </div>
      </section>
    </div>
    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </el-drawer>
</template>

<style scoped>
:global(.youzan-order-drawer .el-drawer__body) { overflow-x: hidden; }
:global(.youzan-order-drawer .el-drawer__header) { margin-bottom: 16px; }
.youzan-order-detail { min-width: 0; overflow-x: hidden; }
.detail-tip { margin-bottom: 18px; }
.detail-section { min-width: 0; margin-bottom: 16px; padding: 17px; border: 1px solid #dfe8e4; border-radius: 13px; background: #fff; }
.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.section-header h3 { margin: 0; color: #173f3a; font-size: 15px; line-height: 1.5; }
.section-header p { margin: 4px 0 0; color: #7a8e89; font-size: 12px; line-height: 1.6; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; margin: 0; }
.detail-item { min-width: 0; padding: 11px 13px; border: 1px solid #e7eeeb; border-radius: 9px; background: #fafcfa; }
.detail-item dt { margin-bottom: 6px; color: #81928e; font-size: 12px; }
.detail-item dd { margin: 0; color: #263f3a; font-size: 13px; font-weight: 600; line-height: 1.65; overflow-wrap: anywhere; word-break: break-word; }
.detail-identifier { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 500 !important; }
.detail-item-wide { grid-column: 1 / -1; }
.amount-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.amount-card { min-width: 0; padding: 15px 16px; border: 1px solid; border-radius: 11px; }
.amount-card > span { display: block; margin-bottom: 8px; color: #6f817c; font-size: 12px; }
.amount-card-external { border-color: #ead9b7; background: #fffaf0; }
.amount-card-paid { border-color: #cee0d8; background: #f3f8f5; }
.amount-value { display: block; color: #173f3a; font-size: 25px; font-weight: 700; line-height: 1.25; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.amount-value small { margin-right: 5px; color: #a47532; font-size: 14px; font-weight: 600; }
.amount-pending { color: #81928e; font-size: 17px; font-weight: 500; }
.payment-verification { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; padding: 11px 13px; border-radius: 9px; background: #f6f8f7; color: #71837e; font-size: 12px; }
.payment-verification strong { color: #304a44; font-size: 13px; text-align: right; overflow-wrap: anywhere; }
.error-panel { padding: 13px 14px; border: 1px solid #efcfd2; border-radius: 9px; background: #fff7f7; }
.error-panel strong { color: #a83f4c; font-size: 13px; }
.error-panel p { margin: 6px 0 0; color: #a34852; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.no-error-panel { padding: 12px 14px; border-radius: 9px; background: #f3f8f5; color: #4d7163; font-size: 13px; }
.raw-section { margin-bottom: 0; background: #fbfcfb; }
.raw-title-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.admin-badge { padding: 2px 7px; border: 1px solid #e4d2ad; border-radius: 5px; background: #fff8e9; color: #936726; font-size: 11px; }
.raw-header { align-items: center; margin-bottom: 0; }
.proto-page .raw-header :deep(.raw-toggle) { color: #fff; }
.proto-page .raw-header :deep(.raw-toggle:hover), .proto-page .raw-header :deep(.raw-toggle:focus-visible), .proto-page .raw-header :deep(.raw-toggle:active) { color: #fff; }
.raw-content { min-width: 0; margin-top: 14px; }
.raw-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; color: #81928e; font-size: 11px; }
.youzan-raw { box-sizing: border-box; width: 100%; max-width: 100%; min-width: 0; max-height: 360px; overflow-x: hidden; overflow-y: auto; margin: 0; padding: 14px; border-radius: 10px; background: #142f2c; color: #e2eeea; font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, monospace; tab-size: 2; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
@media (max-width: 640px) {
  :global(.youzan-order-drawer .el-drawer__body) { padding: 16px; }
  .detail-section { padding: 14px; }
  .detail-grid, .amount-grid { grid-template-columns: minmax(0, 1fr); }
  .detail-item-wide { grid-column: auto; }
  .section-header, .raw-header { flex-direction: column; }
  .raw-header :deep(.el-button) { align-self: flex-start; margin-left: 0; }
  .payment-verification, .raw-toolbar { align-items: flex-start; flex-direction: column; }
  .payment-verification strong { text-align: left; }
  .amount-value { font-size: 23px; }
}
</style>
