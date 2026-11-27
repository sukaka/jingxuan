<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getCustomerYouzanHistoryOrders } from '../../api/customer'
import type {
  CustomerYouzanHistoryOrderItem,
  CustomerYouzanHistoryOrders,
} from '../../api/customer'
import { listEntitlements, listOrders } from '../../api/trading'
import type { CrmOrder, Entitlement } from '../../api/trading'
import { listRedemptions } from '../../api/fulfillment'
import type { Redemption } from '../../api/fulfillment'
import { useAuthStore } from '../../stores/auth'
import { fenToYuan } from '../../utils/money'
import { formatDateTime } from './shared'
import { useReferenceNames } from '../references'
import { entitlementStatusLabel, entitlementStatusTone, orderStatusLabel, orderStatusTone, orderTypeLabel } from '../trading/tradingUi'
import YouzanHistorySnapshotSyncDialog from '../youzan/YouzanHistorySnapshotSyncDialog.vue'

const { institutionLabel } = useReferenceNames()
const auth = useAuthStore()

const isInstitution = computed(() => auth.user?.userType?.startsWith('INSTITUTION') ?? false)
const canSyncYouzanSnapshot = computed(() => !isInstitution.value && auth.hasPermission('integration:youzan:manage'))

const REDEMPTION_STATUS: Record<string, [string, string]> = {
  PENDING_INSTITUTION: ['待机构确认', 'warning'],
  POSTED: ['机构已确认', 'success'],
  REJECTED: ['机构已退回', 'danger'],
  REVERSED: ['已冲正', 'dark'],
}

const YOUZAN_ORDER_STATUS: Record<string, [string, string]> = {
  TRADE_SUCCESS: ['交易完成', 'success'],
  TRADE_PAID: ['已支付', 'success'],
  WAIT_CONFIRM: ['待确认', 'info'],
  WAIT_SELLER_SEND_GOODS: ['待发货', 'info'],
  WAIT_BUYER_CONFIRM_GOODS: ['待收货', 'info'],
  WAIT_BUYER_PAY: ['待支付', 'warning'],
  TRADE_CLOSED: ['已关闭', 'dark'],
  TRADE_CLOSED_BY_USER: ['买家关闭', 'dark'],
}

function redemptionPill(status: string) { return REDEMPTION_STATUS[status] || ['未知状态', 'dark'] }
function localizedLabel(value: string, label: string, fallback = '未知状态') {
  return label === value ? fallback : label
}

function youzanStatusPill(row: CustomerYouzanHistoryOrderItem): [string, string] {
  const mapped = YOUZAN_ORDER_STATUS[row.orderStatus]
  if (mapped) return mapped
  return row.paid ? ['已支付', 'success'] : ['未支付', 'dark']
}

const props = defineProps<{ customerId: number; type: 'entitlements' | 'redemptions' | 'orders' }>()
const orders = ref<CrmOrder[]>([])
const entitlements = ref<Entitlement[]>([])
const redemptions = ref<Redemption[]>([])
const loading = ref(false)
const errorMessage = ref('')

const yzHistory = ref<CustomerYouzanHistoryOrders | null>(null)
const yzLoading = ref(false)
const yzError = ref('')
const yzQuery = reactive({ paidOnly: true, page: 1, size: 10 })
const syncDialogVisible = ref(false)
let mainSeq = 0
let yzSeq = 0

const defaultSnapshotShopId = computed(() => yzHistory.value?.linkedShopIds?.[0] ?? '')

async function loadYouzanHistory(page = yzQuery.page) {
  if (props.type !== 'orders' || isInstitution.value) {
    yzHistory.value = null
    yzError.value = ''
    yzLoading.value = false
    return
  }
  const targetId = props.customerId
  const seq = ++yzSeq
  yzLoading.value = true
  yzError.value = ''
  try {
    const result = await getCustomerYouzanHistoryOrders(targetId, {
      paidOnly: yzQuery.paidOnly,
      page,
      size: yzQuery.size,
    })
    if (seq !== yzSeq || props.customerId !== targetId || props.type !== 'orders') return
    yzHistory.value = result
    yzQuery.page = result.orders.page
    yzQuery.size = result.orders.size
  } catch (error) {
    if (seq !== yzSeq || props.customerId !== targetId || props.type !== 'orders') return
    yzHistory.value = null
    yzError.value = error instanceof Error ? error.message : '有赞历史订单加载失败'
  } finally {
    if (seq === yzSeq) yzLoading.value = false
  }
}

async function load() {
  const targetId = props.customerId
  const targetType = props.type
  const seq = ++mainSeq
  loading.value = true
  errorMessage.value = ''
  if (targetType === 'orders') {
    yzQuery.page = 1
    void loadYouzanHistory(1)
  }
  try {
    if (targetType === 'orders') {
      const res = await listOrders({ customerId: targetId, page: 1, size: 100 })
      if (seq !== mainSeq || props.customerId !== targetId || props.type !== targetType) return
      orders.value = res.items
    } else if (targetType === 'entitlements') {
      const res = await listEntitlements({ customerId: targetId, page: 1, size: 100 })
      if (seq !== mainSeq || props.customerId !== targetId || props.type !== targetType) return
      entitlements.value = res.items
    } else {
      const res = await listRedemptions({ customerId: targetId, page: 1, size: 100 })
      if (seq !== mainSeq || props.customerId !== targetId || props.type !== targetType) return
      redemptions.value = res.items
    }
  } catch (error) {
    if (seq !== mainSeq || props.customerId !== targetId || props.type !== targetType) return
    errorMessage.value = error instanceof Error ? error.message : '业务记录加载失败'
  } finally {
    if (seq === mainSeq) loading.value = false
  }
}

function handlePaidOnlyChange() {
  yzQuery.page = 1
  void loadYouzanHistory(1)
}

function handleHistorySynced() {
  yzQuery.page = 1
  void loadYouzanHistory(1)
}

watch(() => [props.customerId, props.type], load)
onMounted(load)
</script>

<template>
  <div v-if="type === 'orders'" class="business-orders-wrap">
    <section v-loading="loading" class="orders-section" data-testid="crm-formal-orders-section">
      <div class="orders-section-head">
        <div>
          <h4 class="orders-section-title">CRM 正式订单与退款</h4>
          <p class="orders-section-sub">已入账订单，关联会员权益、核销与正式业绩统计</p>
        </div>
      </div>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="section-alert" />
      <app-table v-else :data="orders">
        <el-table-column prop="orderNo" label="订单号" min-width="155" />
        <el-table-column label="类型" width="105"><template #default="{row}">{{ localizedLabel(row.orderTypeCode, orderTypeLabel(row.orderTypeCode), '未知类型') }}</template></el-table-column>
        <el-table-column label="应付（元）" width="110" align="right"><template #default="{row}">{{ fenToYuan(row.payableFen) }}</template></el-table-column>
        <el-table-column label="实付（元）" width="110" align="right"><template #default="{row}">{{ fenToYuan(row.paidFen) }}</template></el-table-column>
        <el-table-column label="已退款（元）" width="110" align="right"><template #default="{row}">{{ fenToYuan(row.refundedFen) }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{row}"><span class="proto-pill" :class="orderStatusTone(row.status)">{{ localizedLabel(row.status, orderStatusLabel(row.status)) }}</span></template></el-table-column>
        <el-table-column label="下单时间" min-width="160"><template #default="{row}">{{ formatDateTime(row.orderedAt) }}</template></el-table-column>
        <template #empty>暂无 CRM 正式订单或退款记录</template>
      </app-table>
    </section>

    <section
      v-if="!isInstitution"
      v-loading="yzLoading"
      class="orders-section yz-history-section"
      data-testid="customer-youzan-history-section"
    >
      <div class="orders-section-head">
        <div>
          <div class="orders-section-title-row">
            <h4 class="orders-section-title">有赞历史订单（只读快照）</h4>
            <span class="proto-pill info">免配 SKU · 只读不发权益</span>
          </div>
          <p class="orders-section-sub">展示该客户在有赞店铺的历史消费商品与实付金额，不计入 CRM 正式账本，不触发权益发放</p>
        </div>
        <div class="orders-section-actions">
          <el-switch
            v-model="yzQuery.paidOnly"
            active-text="仅已支付"
            inactive-text="全部"
            size="small"
            data-testid="yz-history-paid-only"
            @change="handlePaidOnlyChange"
          />
          <el-button text size="small" :loading="yzLoading" data-testid="yz-history-refresh" @click="loadYouzanHistory(yzQuery.page)">
            刷新
          </el-button>
          <el-button
            v-if="canSyncYouzanSnapshot"
            type="primary"
            plain
            size="small"
            data-testid="yz-history-sync-btn"
            @click="syncDialogVisible = true"
          >
            同步历史快照
          </el-button>
        </div>
      </div>

      <el-alert v-if="yzError" type="error" :title="yzError" :closable="false" class="section-alert" />

      <template v-else-if="yzHistory">
        <div
          v-if="yzHistory.hasYouzanIdentity || yzHistory.summary.totalOrderCount > 0"
          class="yz-history-stats"
          data-testid="yz-history-summary"
        >
          <div class="yz-history-stat">
            <span class="yz-history-stat-label">已支付订单</span>
            <strong class="yz-history-stat-value">{{ yzHistory.summary.paidOrderCount }} 笔</strong>
            <span class="yz-history-stat-sub">全部快照 {{ yzHistory.summary.totalOrderCount }} 笔</span>
          </div>
          <div class="yz-history-stat">
            <span class="yz-history-stat-label">累计实付</span>
            <strong class="yz-history-stat-value">¥{{ fenToYuan(yzHistory.summary.totalPaymentFen) }}</strong>
            <span class="yz-history-stat-sub">已退款 ¥{{ fenToYuan(yzHistory.summary.totalRefundedFen) }}</span>
          </div>
          <div class="yz-history-stat">
            <span class="yz-history-stat-label">历史净消费</span>
            <strong class="yz-history-stat-value is-accent">¥{{ fenToYuan(yzHistory.summary.netSpendFen) }}</strong>
            <span class="yz-history-stat-sub">仅统计已支付快照</span>
          </div>
          <div class="yz-history-stat">
            <span class="yz-history-stat-label">消费时间跨度</span>
            <strong class="yz-history-stat-text">
              {{ yzHistory.summary.firstOrderAt ? formatDateTime(yzHistory.summary.firstOrderAt) : '—' }}
            </strong>
            <span class="yz-history-stat-sub">
              最近：{{ yzHistory.summary.lastOrderAt ? formatDateTime(yzHistory.summary.lastOrderAt) : '—' }}
            </span>
          </div>
        </div>

        <app-table :data="yzHistory.orders.items" size="small" data-testid="customer-youzan-history-table">
          <el-table-column label="有赞订单号" min-width="155">
            <template #default="{ row }">
              <div class="yz-mono">{{ row.tid }}</div>
              <div class="yz-sub">店铺 {{ row.shopId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="购买商品明细" min-width="230">
            <template #default="{ row }">
              <div v-if="row.items && row.items.length" class="yz-goods-list">
                <div v-for="(line, idx) in row.items" :key="`${row.tid}-${idx}`" class="yz-goods-line">
                  <span class="yz-goods-title">{{ line.title }}</span>
                  <span v-if="line.skuProps" class="yz-goods-spec">（{{ line.skuProps }}）</span>
                  <span class="yz-goods-qty">× {{ line.num }}</span>
                  <span class="yz-goods-price">¥{{ fenToYuan(line.priceFen) }}</span>
                </div>
              </div>
              <span v-else>{{ row.titleSummary || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实付（元）" width="100" align="right">
            <template #default="{ row }">{{ fenToYuan(row.paymentFen) }}</template>
          </el-table-column>
          <el-table-column label="已退款（元）" width="100" align="right">
            <template #default="{ row }">{{ fenToYuan(row.refundedFen) }}</template>
          </el-table-column>
          <el-table-column label="净实付（元）" width="100" align="right">
            <template #default="{ row }">
              <strong>{{ fenToYuan(row.netPaymentFen) }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="95">
            <template #default="{ row }">
              <span class="proto-pill" :class="youzanStatusPill(row)[1]">{{ youzanStatusPill(row)[0] }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付 / 下单时间" min-width="155">
            <template #default="{ row }">
              {{ formatDateTime(row.paidAt || row.orderedAt) }}
            </template>
          </el-table-column>
          <template #empty>
            <span v-if="!yzHistory.hasYouzanIdentity" data-testid="yz-history-empty-unlinked">
              该客户暂未关联有赞账号（可通过「有赞订单同步中心 → 客户拉取与建档」或「人工客户链接」建立绑定）
            </span>
            <span v-else data-testid="yz-history-empty-no-orders">
              暂未拉取到该客户的有赞历史订单快照；管理员可点击右上角「同步历史快照」按年份拉取（免配 SKU、不发权益）
            </span>
          </template>
        </app-table>

        <el-pagination
          v-if="yzHistory.orders.total > yzQuery.size"
          v-model:current-page="yzQuery.page"
          v-model:page-size="yzQuery.size"
          :total="yzHistory.orders.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="crm-pager"
          @current-change="(page: number) => loadYouzanHistory(page)"
          @size-change="yzQuery.page = 1; loadYouzanHistory(1)"
        />
      </template>

      <YouzanHistorySnapshotSyncDialog
        v-if="canSyncYouzanSnapshot"
        v-model="syncDialogVisible"
        :default-shop-id="defaultSnapshotShopId"
        @synced="handleHistorySynced"
      />
    </section>
  </div>

  <div v-else v-loading="loading">
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
    <app-table v-else-if="type === 'entitlements'" :data="entitlements">
      <el-table-column prop="projectName" label="项目" min-width="160" />
      <el-table-column prop="grantedCount" label="发放次数" width="100" align="right" />
      <el-table-column prop="availableCount" label="剩余次数" width="100" align="right" />
      <el-table-column label="状态" width="110"><template #default="{row}"><span class="proto-pill" :class="entitlementStatusTone(row.status)">{{ localizedLabel(row.status, entitlementStatusLabel(row.status)) }}</span></template></el-table-column>
      <el-table-column label="有效期至" width="120"><template #default="{row}">{{ row.validTo || '长期' }}</template></el-table-column>
      <template #empty>暂无权益记录</template>
    </app-table>
    <app-table v-else :data="redemptions">
      <el-table-column label="核销流水" min-width="110"><template #default="{row}">#{{ row.id }}</template></el-table-column>
      <el-table-column label="预约" min-width="150"><template #default="{row}">#{{ row.appointmentId }}</template></el-table-column>
      <el-table-column label="机构" min-width="100"><template #default="{row}">{{ institutionLabel(row.institutionId) }}</template></el-table-column>
      <el-table-column label="服务时间" min-width="160"><template #default="{row}">{{ formatDateTime(row.serviceAt) }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{row}"><span class="proto-pill" :class="redemptionPill(row.status)[1]">{{ redemptionPill(row.status)[0] }}</span></template></el-table-column>
      <template #empty>暂无核销记录</template>
    </app-table>
  </div>
</template>

<style scoped>
.business-orders-wrap {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.orders-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.orders-section-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.orders-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: #21404e;
}

.orders-section-sub {
  margin: 3px 0 0;
  font-size: 12px;
  color: #748a97;
}

.orders-section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.section-alert {
  margin-bottom: 10px;
}

.yz-history-section {
  padding-top: 16px;
  border-top: 1px dashed #d7e5e9;
}

.yz-history-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.yz-history-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f7fbfc;
  border: 1px solid #e2edf0;
}

.yz-history-stat-label {
  font-size: 11px;
  color: #6c8391;
}

.yz-history-stat-value {
  font-size: 18px;
  font-weight: 700;
  color: #1f4150;
}

.yz-history-stat-value.is-accent {
  color: #0f8a82;
}

.yz-history-stat-text {
  font-size: 12px;
  font-weight: 600;
  color: #2c4a58;
}

.yz-history-stat-sub {
  font-size: 11px;
  color: #7d929f;
}

.yz-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: #214252;
}

.yz-sub {
  font-size: 11px;
  color: #7a8f9c;
  margin-top: 2px;
}

.yz-goods-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.yz-goods-line {
  font-size: 12px;
  line-height: 1.5;
  color: #294554;
}

.yz-goods-title {
  font-weight: 500;
}

.yz-goods-spec {
  color: #6e8390;
}

.yz-goods-qty {
  margin-left: 6px;
  color: #466575;
}

.yz-goods-price {
  margin-left: 6px;
  color: #0e827b;
  font-weight: 600;
}
</style>
