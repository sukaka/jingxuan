<script setup lang="ts">
import { ref, watch } from 'vue'
import { getOrder } from '../../api/trading'
import type { CrmOrder, OrderFulfillmentView } from '../../api/trading'
import { listInstitutionProfiles } from '../../api/institution'
import { fenToYuan } from '../../utils/money'
import { orderStatusTone, orderTypeTone } from '../../utils/tagTone'

const props = defineProps<{ orders: CrmOrder[]; canWrite: boolean; canConfirmPayment: boolean }>()
const emit = defineEmits<{ detail: [id: number]; submit: [order: CrmOrder]; cancel: [order: CrmOrder]; payment: [order: CrmOrder] }>()
const details = ref<Record<number, OrderFulfillmentView>>({})
const institutionNames = ref<Record<number, string>>({})
watch(() => props.orders, async (orders) => {
  const results = await Promise.allSettled(orders.map((order) => getOrder(order.id)))
  const next: Record<number, OrderFulfillmentView> = {}
  results.forEach((result, index) => { if (result.status === 'fulfilled') next[orders[index].id] = result.value })
  details.value = next
}, { immediate: true })
void listInstitutionProfiles({ page: 1, size: 100 }).then((result) => {
  const map: Record<number, string> = {}
  result.items.forEach((item) => { map[item.id] = item.shortName || item.fullName })
  institutionNames.value = map
}).catch(() => {})
function items(id: number) { return details.value[id]?.order.items || [] }
function itemNames(id: number) { return items(id).map((item) => item.skuNameSnapshot).join('、') || '—' }
function institutions(id: number) {
  const values = items(id).flatMap((item) => {
    try { return JSON.parse(item.entitlementSnapshot).institutionIds || [] } catch { return [] }
  })
  return values.length ? values.map((id: number) => institutionNames.value[id] || `机构 #${id}`).join('、') : '待分配'
}
function orderTypeLabel(code: string) { return ({ REGULAR:'普通订单', FLASH:'快闪订单', PROMOTION:'大促活动' })[code] || '未知订单类型' }
function statusLabel(status: string) { return ({ DRAFT:'草稿',PENDING_PAYMENT:'待支付',PAID:'已支付',PARTIALLY_REDEEMED:'部分履约',FULFILLED:'已完成',CANCELED:'已取消' })[status] || '未知状态' }
</script>

<template>
  <app-table :data="orders">
    <el-table-column prop="orderNo" label="订单号" min-width="145"><template #default="{row}"><el-button text @click="emit('detail',row.id)">{{ row.orderNo }}</el-button></template></el-table-column>
    <el-table-column label="客户" min-width="110"><template #default="{row}">{{ row.customerName || `客户 #${row.customerId}` }}</template></el-table-column>
    <el-table-column label="卡项 / 项目" min-width="190"><template #default="{row}">{{ itemNames(row.id) }}</template></el-table-column>
    <el-table-column label="类型 / 活动" min-width="140"><template #default="{row}"><span class="proto-pill" :class="orderTypeTone(row.orderTypeCode)">{{ orderTypeLabel(row.orderTypeCode) }}</span><div class="proto-subcell">活动 #{{ row.campaignId || '—' }} / 批次 #{{ row.campaignBatchId || '—' }}</div></template></el-table-column>
    <el-table-column label="实付" width="105" align="right"><template #default="{row}">{{ fenToYuan(row.paidFen) }}</template></el-table-column>
    <el-table-column label="机构" min-width="120"><template #default="{row}">{{ institutions(row.id) }}</template></el-table-column>
    <el-table-column label="状态" width="105"><template #default="{row}"><span class="proto-pill" :class="orderStatusTone(row.status)">{{ statusLabel(row.status) }}</span></template></el-table-column>
    <el-table-column label="操作" width="210" fixed="right"><template #default="{row}"><el-button text @click="emit('detail',row.id)">修改</el-button><el-button v-if="row.status==='DRAFT' && canWrite" text @click="emit('submit',row)">提交</el-button><el-button v-if="row.status==='PENDING_PAYMENT' && canConfirmPayment" text @click="emit('payment',row)">确认支付</el-button><el-button v-if="['DRAFT','PENDING_PAYMENT'].includes(row.status) && canWrite" text @click="emit('cancel',row)">取消</el-button></template></el-table-column>
    <template #empty>暂无订单</template>
  </app-table>
</template>
