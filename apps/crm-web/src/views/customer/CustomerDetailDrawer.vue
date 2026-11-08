<script setup lang="ts">
import { ref, watch } from 'vue'
import type { CustomerDetail, LegacyProfileField } from '../../api/customer'
import { getCustomer, getCustomerLegacyProfile } from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import CustomerAvatar from './CustomerAvatar.vue'
import FollowupPanel from './FollowupPanel.vue'
import OwnershipPanel from './OwnershipPanel.vue'
import PointsPanel from './PointsPanel.vue'
import CustomerBusinessPanel from './CustomerBusinessPanel.vue'
import StatusPill from './StatusPill.vue'
import TagsIdentityPanel from './TagsIdentityPanel.vue'
import { formatDate, formatDateTime, levelLabel, useDictionaries } from './shared'

const props = defineProps<{
  modelValue: boolean
  customerId: number | null
  initialTab?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  edit: [id: number]
  changed: []
}>()

const auth = useAuthStore()
const dicts = useDictionaries()

const detail = ref<CustomerDetail | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const activeTab = ref('overview')

// 原业务表 23 字段档案:切到该 tab 才加载;无可用值时不拿其他系统字段凑数
const legacyFields = ref<LegacyProfileField[]>([])
const legacyLoading = ref(false)
const legacyError = ref('')
let legacyLoadedFor: number | null = null

function hasLegacyValue(field: LegacyProfileField): boolean {
  return field.availability === 'AVAILABLE' && Boolean(field.value?.trim())
}

const LEGACY_FIELD_DICTIONARIES: Record<string, string> = {
  SOURCE: 'customerSources',
  CHANNEL: 'customerChannels',
  CITY: 'cities',
  CUSTOMER_STATUS: 'customerStatuses',
}
const LEGACY_TIME_FIELDS = new Set([
  'SUBMITTED_AT',
  'FIRST_IMPORT_AT',
  'FIRST_ADDED_AT',
  'FIRST_ORDER_AT',
  'LAST_FOLLOWUP_AT',
])

function legacyValueText(field: LegacyProfileField): string {
  if (!hasLegacyValue(field)) return '暂未维护'
  const value = field.value!
  const dictionaryCode = LEGACY_FIELD_DICTIONARIES[field.fieldKey]
  if (dictionaryCode) return dicts.label(dictionaryCode, value)
  if (LEGACY_TIME_FIELDS.has(field.fieldKey)) return formatDateTime(value)
  return value
}

async function loadLegacyProfile() {
  if (props.customerId === null || legacyLoadedFor === props.customerId) return
  legacyLoading.value = true
  legacyError.value = ''
  try {
    legacyFields.value = await getCustomerLegacyProfile(props.customerId)
    legacyLoadedFor = props.customerId
  } catch (error) {
    legacyFields.value = []
    legacyError.value = errorText(error)
  } finally {
    legacyLoading.value = false
  }
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '客户不存在或不在你的数据范围内(不泄露存在性)'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function load() {
  if (props.customerId === null) return
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getCustomer(props.customerId)
  } catch (error) {
    detail.value = null
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.customerId],
  ([visible]) => {
    if (visible) {
      activeTab.value = props.initialTab || 'overview'
      legacyLoadedFor = null
      void dicts.load('cities', 'customerSources', 'customerChannels', 'customerStatuses', 'customerTiers')
      void load()
    }
  },
)

watch(activeTab, (tab) => {
  if (tab === 'profile') void loadLegacyProfile()
})

async function handleChildChanged() {
  emit('changed')
  await load()
}

defineExpose({ reload: load })
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="min(930px, 96vw)"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <span class="drawer-title">客户详情</span>
    </template>

    <div v-loading="loading" class="drawer-body">
      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        :title="errorMessage"
      />

      <template v-else-if="detail">
        <el-alert
          v-if="detail.deleted"
          type="warning"
          :closable="false"
          title="该客户已被合并归档,档案只读;关联数据已迁移至主客户"
          class="crm-table-alert"
        />

        <div class="head-card">
          <CustomerAvatar :name="detail.displayName ?? detail.nickname" :size="44" />
          <div class="head-main">
            <div class="head-name">
              {{ detail.displayName ?? detail.nickname ?? '未命名客户' }}
              <StatusPill
                v-if="detail.customerStatus"
                :text="dicts.label('customerStatuses', detail.customerStatus)"
                tone="info"
              />
              <StatusPill v-if="detail.tierCode" :text="`${detail.tierCode} 层`" tone="dark" />
            </div>
            <div class="crm-muted">
              编号 {{ detail.customerNo }} · 管家 {{ detail.ownerName ?? `#${detail.ownerStaffId}` }} ·
              {{ detail.mobileMasked ?? '未留手机号' }}
            </div>
          </div>
          <div class="head-actions">
            <el-button
              v-if="auth.hasPermission('customer:write') && !detail.deleted"
              @click="emit('edit', detail.id)"
            >
              编辑档案
            </el-button>
          </div>
        </div>

        <el-tabs v-model="activeTab">
          <el-tab-pane label="客户概览" name="overview">
            <div class="stat-grid">
              <div class="mini-stat"><div class="stat-label">积分余额</div><div class="stat-value">{{ detail.pointsCached }}</div></div>
              <div class="mini-stat"><div class="stat-label">最后跟进</div><div class="stat-text">{{ formatDateTime(detail.lastFollowupAt) }}</div></div>
              <div class="mini-stat"><div class="stat-label">首次下单</div><div class="stat-text">{{ formatDateTime(detail.firstOrderAt) }}</div></div>
              <div class="mini-stat"><div class="stat-label">建档时间</div><div class="stat-text">{{ formatDateTime(detail.createdAt) }}</div></div>
            </div>
            <el-descriptions :column="2" border class="desc-block">
              <el-descriptions-item label="客户来源">{{ dicts.label('customerSources', detail.sourceCode) }}</el-descriptions-item>
              <el-descriptions-item label="客户渠道">{{ dicts.label('customerChannels', detail.channelCode) }}</el-descriptions-item>
              <el-descriptions-item label="城市">{{ dicts.label('cities', detail.cityCode) }}</el-descriptions-item>
              <el-descriptions-item label="意向级别">{{ levelLabel(detail.intentLevel) }}</el-descriptions-item>
              <el-descriptions-item label="消费能力">{{ levelLabel(detail.spendingLevel) }}</el-descriptions-item>
              <el-descriptions-item label="生日">{{ formatDate(detail.birthday) }}</el-descriptions-item>
            </el-descriptions>
            <el-collapse>
              <el-collapse-item title="标签、身份与积分维护" name="more">
                <TagsIdentityPanel :customer-id="detail.id" :tag-codes="detail.tagCodes" :identities="detail.identities" :readonly="detail.deleted" @changed="handleChildChanged" />
                <el-divider />
                <PointsPanel :customer-id="detail.id" :birthday="detail.birthday" @changed="handleChildChanged" />
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>
          <el-tab-pane label="会员权益" name="entitlements"><CustomerBusinessPanel :customer-id="detail.id" type="entitlements" /></el-tab-pane>
          <el-tab-pane label="核销记录" name="redemptions"><CustomerBusinessPanel :customer-id="detail.id" type="redemptions" /></el-tab-pane>
          <el-tab-pane label="订单与退款" name="orders"><CustomerBusinessPanel :customer-id="detail.id" type="orders" /></el-tab-pane>
          <el-tab-pane label="服务记录" name="service"><FollowupPanel :fixed-customer-id="detail.id" @saved="handleChildChanged" /></el-tab-pane>
          <el-tab-pane label="基础档案" name="profile">
            <el-alert v-if="legacyError" type="error" :closable="false" :title="`档案加载失败:${legacyError}`" class="crm-table-alert" />
            <section v-else v-loading="legacyLoading" class="profile-card" aria-label="客户基础档案">
              <h3 class="profile-title">基础档案</h3>
              <div v-if="legacyFields.length" class="profile-grid">
                <div v-for="field in legacyFields" :key="field.fieldKey" class="profile-item">
                  <span class="profile-label">{{ field.originalName }}</span>
                  <span class="profile-value" :class="{ 'is-empty': !hasLegacyValue(field) }">{{ legacyValueText(field) }}</span>
                </div>
              </div>
              <div v-else-if="!legacyLoading" class="profile-empty">暂无档案信息</div>
            </section>
            <OwnershipPanel :customer-id="detail.id" class="profile-ownership" @changed="handleChildChanged" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-title {
  font-size: 16px;
  font-weight: 700;
}

.drawer-body {
  min-height: 320px;
}

.head-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border: 1px solid #e9eff3;
  border-radius: 14px;
  padding: 16px 18px;
  margin-bottom: 16px;
}

.head-main {
  flex: 1;
  min-width: 0;
}

.head-name {
  font-size: 16px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.mini-stat {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e9eff2;
  border-radius: 11px;
}

.stat-label {
  font-size: 12px;
  color: #79909f;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #0f948b;
}

.stat-text {
  font-size: 13px;
  font-weight: 600;
}

.desc-block {
  margin-bottom: 16px;
}

.tag-item {
  margin-right: 6px;
}

.profile-card {
  background: #fff;
  border: 1px solid #e9eff3;
  border-radius: 14px;
  box-shadow: 0 6px 28px rgba(30, 62, 84, 0.045);
  overflow: hidden;
}

.profile-title {
  margin: 0;
  padding: 20px 24px;
  border-bottom: 1px solid #e9eff3;
  font-size: 16px;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 32px;
  padding: 0 24px 12px;
}

.profile-item {
  display: grid;
  grid-template-columns: minmax(112px, 42%) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  min-width: 0;
  padding: 18px 12px;
  border-bottom: 1px solid #edf2f4;
}

.profile-label {
  color: #79909f;
  font-size: 13px;
  line-height: 1.6;
}

.profile-value {
  color: #304b5c;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.profile-value.is-empty {
  color: #9aabba;
  font-weight: 400;
}

.profile-empty {
  padding: 40px 24px;
  color: #79909f;
  text-align: center;
}

.profile-ownership {
  display: block;
  margin-top: 20px;
}

@media (max-width: 768px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }

  .profile-title {
    padding: 18px 20px;
  }

  .profile-item {
    grid-template-columns: minmax(110px, 36%) minmax(0, 1fr);
    padding: 16px 8px;
  }
}
</style>
