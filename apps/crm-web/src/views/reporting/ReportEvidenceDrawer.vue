<script setup lang="ts">
import type { ReportFact } from '../../api/report'
import { countText, displayDateTime, factTypeText, moneyText, orderSourceText, receiverTypeText } from './reportUi'

defineProps<{
  modelValue: boolean
  title: string
  facts?: ReportFact[]
  description?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="min(640px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p v-if="description" class="report-drawer-description">{{ description }}</p>
    <div v-if="facts?.length" class="report-evidence-list">
      <article v-for="(item, index) in facts" :key="`${item.factType}-${item.sourceId}-${index}`" class="report-evidence-item">
        <div>
          <span class="proto-pill info">{{ factTypeText(item.factType) }}</span>
          <strong>事实来源 #{{ item.sourceId ?? '待确认' }}</strong>
        </div>
        <dl class="report-fact-grid">
          <div v-if="item.orderId !== null"><dt>订单</dt><dd>#{{ item.orderId }}</dd></div>
          <div v-if="item.customerId !== null"><dt>客户</dt><dd>#{{ item.customerId }}</dd></div>
          <div v-if="item.campaignId !== null"><dt>活动</dt><dd>#{{ item.campaignId }}</dd></div>
          <div v-if="item.campaignBatchId !== null"><dt>批次</dt><dd>#{{ item.campaignBatchId }}</dd></div>
          <div v-if="item.skuId !== null"><dt>SKU</dt><dd>#{{ item.skuId }}</dd></div>
          <div><dt>金额</dt><dd>{{ moneyText(item.amountFen, '不适用') }}</dd></div>
          <div><dt>数量</dt><dd>{{ item.quantity === null ? '不适用' : countText(item.quantity) }}</dd></div>
          <div><dt>事件时间</dt><dd>{{ displayDateTime(item.eventAt) }}</dd></div>
          <div><dt>来源 / 收款方</dt><dd>{{ orderSourceText(item.sourceSystem) }} · {{ receiverTypeText(item.receiverType) }}</dd></div>
        </dl>
      </article>
    </div>
    <el-empty v-else description="当前筛选没有返回事实记录；页面不会生成或拼接虚构凭证" />
  </el-drawer>
</template>

<style scoped>
.report-fact-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px 18px; margin: 16px 0 0; }
.report-fact-grid div { min-width: 0; }
.report-fact-grid dt { margin-bottom: 3px; color: var(--subtle); font-size: 10px; }
.report-fact-grid dd { margin: 0; color: #506979; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 520px) { .report-fact-grid { grid-template-columns: 1fr; } }
</style>
