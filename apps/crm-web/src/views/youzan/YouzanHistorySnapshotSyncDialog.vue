<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { YouzanAnnualSnapshotSyncResult } from '../../api/annualReport'
import { syncYouzanAnnualSnapshots } from '../../api/annualReport'
import { tradingErrorText } from '../trading/tradingUi'
import { createYouzanKey } from './youzanUi'

const props = defineProps<{
  modelValue: boolean
  defaultShopId?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  synced: [result: YouzanAnnualSnapshotSyncResult]
}>()

function formatDateInput(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function defaultRange(): { start: string; end: string } {
  const now = new Date()
  const start = new Date(now.getTime() - 364 * 86400000)
  return { start: formatDateInput(start), end: formatDateInput(now) }
}

const currentYear = new Date().getFullYear()
const yearPresets = computed(() => {
  const years: number[] = []
  for (let y = currentYear; y >= Math.max(2020, currentYear - 5); y -= 1) {
    years.push(y)
  }
  return years
})

const initial = defaultRange()
const form = reactive({
  shopId: props.defaultShopId?.trim() || '',
  startDate: initial.start,
  endDate: initial.end,
  enrichBuyerProfile: false,
  maxPagesPerWindow: 20,
})

const syncing = ref(false)
const lastResult = ref<YouzanAnnualSnapshotSyncResult | null>(null)
let syncAttempt: { signature: string; key: string } | null = null

watch(
  () => [props.modelValue, props.defaultShopId] as const,
  ([visible, nextShop]) => {
    if (visible) {
      if (nextShop && nextShop.trim()) {
        form.shopId = nextShop.trim()
      }
      lastResult.value = null
      syncAttempt = null
    }
  },
)

function applyRecentYear() {
  const range = defaultRange()
  form.startDate = range.start
  form.endDate = range.end
  syncAttempt = null
}

function applyCalendarYear(year: number) {
  const today = new Date()
  const isCurrentYear = year === today.getFullYear()
  form.startDate = `${year}-01-01`
  form.endDate = isCurrentYear ? formatDateInput(today) : `${year}-12-31`
  syncAttempt = null
}

function spanDays(start: string, end: string): number {
  const s = Date.parse(`${start}T00:00:00Z`)
  const e = Date.parse(`${end}T00:00:00Z`)
  if (Number.isNaN(s) || Number.isNaN(e)) return -1
  return Math.round((e - s) / 86400000)
}

async function handleSync() {
  if (syncing.value) return
  const shopId = form.shopId.trim()
  if (!shopId) {
    ElMessage.warning('请填写有赞店铺编号（kdt_id）')
    return
  }
  if (!form.startDate || !form.endDate) {
    ElMessage.warning('请选择拉取起止日期')
    return
  }
  const days = spanDays(form.startDate, form.endDate)
  if (days < 0) {
    ElMessage.warning('结束日期不能早于开始日期')
    return
  }
  if (days > 370) {
    ElMessage.warning('单次拉取跨度不能超过 370 天，请按年份分段拉取')
    return
  }

  const payload = {
    shopId,
    startDate: form.startDate,
    endDate: form.endDate,
    enrichBuyerProfile: form.enrichBuyerProfile,
    maxPagesPerWindow: form.maxPagesPerWindow,
  }
  const signature = JSON.stringify(payload)
  if (syncAttempt?.signature !== signature) {
    syncAttempt = { signature, key: createYouzanKey('youzan.history.snapshot') }
  }

  syncing.value = true
  try {
    const result = await syncYouzanAnnualSnapshots(payload, syncAttempt.key)
    syncAttempt = null
    lastResult.value = result
    emit('synced', result)
    ElMessage.success(
      `历史快照同步完成：共同步 ${result.ordersSynced} 笔（已支付 ${result.paidOrdersSynced} 笔），覆盖 ${result.buyersSynced} 位买家`,
    )
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    syncing.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="拉取有赞历史订单只读快照"
    width="560px"
    destroy-on-close
    data-testid="yz-history-snapshot-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="只读历史轨迹：无需逐个配置 SKU 映射，绝不写入 CRM 正式订单账本，绝不自动发放权益；重复拉取按有赞订单号自动去重。"
      class="yz-snap-alert"
    />

    <el-form label-position="top" @submit.prevent>
      <el-form-item label="有赞店铺编号（kdt_id）" required>
        <el-input
          v-model="form.shopId"
          :disabled="syncing"
          placeholder="请输入有赞店铺编号（kdt_id）"
          data-testid="yz-snap-shop-id"
        />
      </el-form-item>

      <el-form-item label="快捷年份（单次最多 370 天，多年可按年依次拉取）">
        <div class="yz-snap-presets">
          <el-button size="small" plain :disabled="syncing" data-testid="yz-snap-preset-recent" @click="applyRecentYear">
            近 1 年
          </el-button>
          <el-button
            v-for="year in yearPresets"
            :key="year"
            size="small"
            plain
            :disabled="syncing"
            :data-testid="`yz-snap-preset-${year}`"
            @click="applyCalendarYear(year)"
          >
            {{ year }} 年
          </el-button>
        </div>
      </el-form-item>

      <div class="yz-snap-dates">
        <el-form-item label="开始日期" required>
          <el-date-picker
            v-model="form.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="syncing"
            placeholder="开始日期"
            class="full-width"
            data-testid="yz-snap-start-date"
          />
        </el-form-item>
        <el-form-item label="结束日期" required>
          <el-date-picker
            v-model="form.endDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="syncing"
            placeholder="结束日期"
            class="full-width"
            data-testid="yz-snap-end-date"
          />
        </el-form-item>
      </div>

      <el-form-item>
        <el-switch
          v-model="form.enrichBuyerProfile"
          :disabled="syncing"
          active-text="同步时补全买家微信/手机号信息（勾选后会额外查询买家接口，耗时更长）"
          data-testid="yz-snap-enrich"
        />
      </el-form-item>
    </el-form>

    <div v-if="lastResult" class="yz-snap-result" data-testid="yz-snap-result">
      <div>
        同步区间：<strong>{{ lastResult.startDate }} ~ {{ lastResult.endDate }}</strong>（分 {{ lastResult.windowsPulled }} 窗）
      </div>
      <div>
        同步订单：<strong>{{ lastResult.ordersSynced }}</strong> 笔（已支付 <strong>{{ lastResult.paidOrdersSynced }}</strong> 笔），
        覆盖买家 <strong>{{ lastResult.buyersSynced }}</strong> 位，关联 CRM 客户 <strong>{{ lastResult.linkedCustomerCount }}</strong> 位
      </div>
      <div v-for="(warn, idx) in lastResult.warnings" :key="idx" class="yz-snap-warn">{{ warn }}</div>
    </div>

    <template #footer>
      <el-button :disabled="syncing" @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :loading="syncing" data-testid="yz-snap-submit" @click="handleSync">
        开始拉取只读快照
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.yz-snap-alert {
  margin-bottom: 14px;
}

.yz-snap-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.yz-snap-dates {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.full-width {
  width: 100%;
}

.yz-snap-result {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #edf9f5;
  border: 1px solid #bce0d6;
  color: #194d46;
  font-size: 12px;
  line-height: 1.7;
}

.yz-snap-warn {
  margin-top: 4px;
  color: #9e5b11;
}
</style>
