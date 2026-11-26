<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { YouzanOrderView } from '../../api/youzan'
import { useAuthStore } from '../../stores/auth'
import { fenToYuan } from '../../utils/money'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { orderSourceLabel, orderStatusLabel } from '../trading/tradingUi'
import {
  mappingStatusLabel,
  mappingStatusTone,
  resolveYouzanOrderItems,
  verificationMethodLabel,
  youzanOrderItemMappingLabel,
  youzanOrderItemQuantityText,
  youzanOrderItemsTotalQuantity,
  youzanOrderItemTitle,
} from './youzanUi'

const props = defineProps<{
  modelValue: boolean
  order: YouzanOrderView | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const auth = useAuthStore()
const canViewRaw = computed(() => auth.hasPermission('integration:youzan:manage'))
const rawExpanded = ref(false)
const hasRaw = computed(() => Boolean(props.order?.payloadMaskedJson?.trim()))
const orderItems = computed(() => resolveYouzanOrderItems(props.order))
const itemsSummaryLabel = computed(() => {
  const count = orderItems.value.length
  if (count === 0) return '共 0 项'
  const hasAnyQuantity = orderItems.value.some((item) => item.quantity != null)
  if (!hasAnyQuantity) return `共 ${count} 项`
  return `共 ${count} 项 · 合计 ${youzanOrderItemsTotalQuantity(orderItems.value)} 件`
})
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
      <el-alert v-if="order.shopId.startsWith('MOCK-')" type="warning" :closable="false" title="模拟演示数据仅用于功能验证，不代表真实有赞连接或真实资金到账。" class="detail-tip" />
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

      <section class="detail-section" aria-labelledby="youzan-order-items" data-testid="youzan-order-items">
        <div class="section-header">
          <div>
            <h3 id="youzan-order-items">购买商品明细</h3>
            <p>展示有赞订单商品、规格、价格与 CRM SKU 映射状态</p>
          </div>
          <span class="items-summary-badge" data-testid="youzan-order-items-summary">{{ itemsSummaryLabel }}</span>
        </div>
        <ul v-if="orderItems.length > 0" class="order-items-list">
          <li
            v-for="(item, index) in orderItems"
            :key="`${item.externalSkuId || 'item'}-${index}`"
            class="order-item-card"
            data-testid="youzan-order-item"
          >
            <div class="order-item-main">
              <div class="order-item-heading">
                <strong class="order-item-title">{{ youzanOrderItemTitle(item, 'drawer') }}</strong>
                <span v-if="item.skuSpec" class="order-item-spec">{{ item.skuSpec }}</span>
              </div>
              <div class="order-item-pricing">
                <span class="order-item-qty">× {{ youzanOrderItemQuantityText(item.quantity) }}</span>
                <span v-if="item.priceFen != null" class="order-item-price">单价 ¥{{ fenToYuan(item.priceFen) }}</span>
                <span v-if="item.paymentFen != null" class="order-item-payment">小计 ¥{{ fenToYuan(item.paymentFen) }}</span>
              </div>
            </div>
            <div class="order-item-meta">
              <div class="order-item-codes">
                <span class="order-item-code-item">
                  <span class="order-item-code-label">外部 SKU</span>
                  <code class="detail-identifier order-item-code">{{ item.externalSkuId || '—' }}</code>
                </span>
                <span v-if="item.outerSkuCode" class="order-item-code-item">
                  <span class="order-item-code-label">商家编码</span>
                  <code class="detail-identifier order-item-code">{{ item.outerSkuCode }}</code>
                </span>
              </div>
              <span
                class="item-mapping-pill"
                :class="item.crmSkuId != null ? 'is-mapped' : 'is-unmapped'"
                data-testid="youzan-item-mapping-pill"
              >
                {{ youzanOrderItemMappingLabel(item) }}
              </span>
            </div>
          </li>
        </ul>
        <div v-else class="items-empty" data-testid="youzan-order-items-empty">当前订单暂无商品明细</div>
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
          <div v-if="order.externalCustomerId" class="detail-item detail-item-wide">
            <dt>外部客户标识（yz_open_id）</dt>
            <dd class="detail-identifier" data-testid="youzan-detail-external-customer">{{ order.externalCustomerId }}</dd>
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
            <span>已安全格式化；格式异常时保留原始内容</span>
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
.items-summary-badge { display: inline-flex; align-items: center; flex-shrink: 0; padding: 4px 10px; border: 1px solid #cce0db; border-radius: 999px; background: #eef7f5; color: #175c55; font-size: 12px; font-weight: 600; white-space: nowrap; }
.order-items-list { display: flex; flex-direction: column; gap: 10px; margin: 0; padding: 0; list-style: none; }
.order-item-card { min-width: 0; padding: 13px 14px; border: 1px solid #e3ece8; border-radius: 10px; background: #fafcfa; }
.order-item-main { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.order-item-heading { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; min-width: 0; flex: 1; }
.order-item-title { color: #173f3a; font-size: 14px; font-weight: 700; line-height: 1.5; overflow-wrap: anywhere; word-break: break-word; }
.order-item-spec { display: inline-flex; max-width: 100%; padding: 2px 8px; border: 1px solid #d9e6e2; border-radius: 6px; background: #f1f7f5; color: #476862; font-size: 12px; line-height: 1.45; overflow-wrap: anywhere; word-break: break-word; }
.order-item-pricing { display: flex; flex-direction: column; align-items: flex-end; gap: 3px; flex-shrink: 0; text-align: right; font-variant-numeric: tabular-nums; }
.order-item-qty { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 6px; background: #e8f4f1; color: #175c55; font-size: 12px; font-weight: 700; }
.order-item-price { color: #5d746e; font-size: 12px; line-height: 1.5; }
.order-item-payment { color: #173f3a; font-size: 13px; font-weight: 700; line-height: 1.5; }
.order-item-meta { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 12px; margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e2ebe7; }
.order-item-codes { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; min-width: 0; }
.order-item-code-item { display: inline-flex; align-items: baseline; gap: 5px; min-width: 0; font-size: 12px; }
.order-item-code-label { color: #7c908b; flex-shrink: 0; }
.order-item-code { color: #2c4a44; font-size: 12px; overflow-wrap: anywhere; word-break: break-word; }
.item-mapping-pill { display: inline-flex; align-items: center; max-width: 100%; padding: 3px 9px; border-radius: 6px; font-size: 11px; font-weight: 700; line-height: 1.45; overflow-wrap: anywhere; word-break: break-word; }
.item-mapping-pill.is-mapped { border: 1px solid #c3e2d8; background: #e7f6f1; color: #175c55; }
.item-mapping-pill.is-unmapped { border: 1px solid #dfe5e8; background: #f0f4f5; color: #647882; }
.items-empty { padding: 14px; border: 1px dashed #d6e3df; border-radius: 9px; background: #fafcfa; color: #7a8e89; font-size: 13px; text-align: center; }
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
.youzan-order-detail .raw-header :deep(.raw-toggle.el-button) {
  --el-button-text-color: #175c55;
  --el-button-hover-text-color: #0f443f;
  --el-button-active-text-color: #0b3632;
  --el-button-bg-color: #f2f8f7;
  --el-button-hover-bg-color: #eaf4f2;
  --el-button-active-bg-color: #dceee9;
  --el-button-border-color: #c5ddd8;
  --el-button-hover-border-color: #96c2ba;
  --el-button-active-border-color: #77aea4;
  min-height: 32px;
  padding: 6px 12px;
  border: 1px solid #c5ddd8;
  border-radius: 8px;
  background-color: #f2f8f7;
  color: #175c55;
  font-weight: 600;
  flex-shrink: 0;
}
.youzan-order-detail .raw-header :deep(.raw-toggle.el-button:hover),
.youzan-order-detail .raw-header :deep(.raw-toggle.el-button:focus-visible) {
  border-color: #96c2ba;
  background-color: #eaf4f2;
  color: #0f443f;
}
.youzan-order-detail .raw-header :deep(.raw-toggle.el-button:active) {
  border-color: #77aea4;
  background-color: #dceee9;
  color: #0b3632;
}
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
  .order-item-main { flex-direction: column; }
  .order-item-pricing { flex-direction: row; flex-wrap: wrap; align-items: center; justify-content: flex-start; gap: 6px 10px; text-align: left; }
  .order-item-meta { align-items: flex-start; flex-direction: column; }
  .payment-verification, .raw-toolbar { align-items: flex-start; flex-direction: column; }
  .payment-verification strong { text-align: left; }
  .amount-value { font-size: 23px; }
}
</style>
