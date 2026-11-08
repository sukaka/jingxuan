<script setup lang="ts">
import type { ReportContext } from '../../api/report'
import { displayDateTime, metricVersionText, orderSourceText, receiverTypeText } from './reportUi'

defineProps<{ context: ReportContext }>()
</script>

<template>
  <section class="report-context" aria-label="报表统计口径">
    <div><span>统计期间</span><strong>{{ context.from }} 至 {{ context.to }}</strong></div>
    <div><span>订单来源</span><strong>{{ orderSourceText(context.sourceSystem) }}</strong></div>
    <div><span>收款方</span><strong>{{ receiverTypeText(context.receiverType) }}</strong></div>
    <div><span title="本报表采用的成交、退款、净支付及预约等计算规则版本">统计口径</span><strong>{{ metricVersionText(context.metricVersion) }}</strong></div>
    <div><span title="本次报表包含的最新业务事实时间">数据更新至</span><strong>{{ displayDateTime(context.sourceWatermark) }}</strong></div>
  </section>
</template>
