<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CustomerDetail, CustomerStewardRelationItem, LegacyProfileField } from '../../api/customer'
import { getCustomer, getCustomerLegacyProfile } from '../../api/customer'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import CustomerAssignDialog from './CustomerAssignDialog.vue'
import type { CustomerAssignTarget } from './CustomerAssignDialog.vue'
import CustomerAvatar from './CustomerAvatar.vue'
import CustomerPrivacyPanel from './CustomerPrivacyPanel.vue'
import FollowupPanel from './FollowupPanel.vue'
import OwnershipPanel from './OwnershipPanel.vue'
import PointsPanel from './PointsPanel.vue'
import CustomerBusinessPanel from './CustomerBusinessPanel.vue'
import CustomerTierPanel from './CustomerTierPanel.vue'
import CustomerTimelinePanel from './CustomerTimelinePanel.vue'
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
const canViewMultiSteward = computed(() => auth.hasPermission('customer:merge'))

const detail = ref<CustomerDetail | null>(null)
const crossTeamStewardCount = computed(() => (detail.value?.addedStewards ?? []).filter((item) => !item.inMyScope).length)

const STEWARD_RELATION_LABELS: Record<string, string> = {
  CUSTOMER_OWNER: '客户归属',
  WECOM_FOLLOW: '企微好友',
}

function stewardRelationLabel(code: string): string {
  return STEWARD_RELATION_LABELS[code] ?? code
}

function stewardTeamLabel(steward: CustomerStewardRelationItem): string {
  return steward.teamName ? `${steward.staffName}（${steward.teamName}）` : steward.staffName
}
const loading = ref(false)
const errorMessage = ref('')
const activeTab = ref('overview')
const assignVisible = ref(false)

function toAssignTarget(item: CustomerDetail): CustomerAssignTarget {
  return {
    id: item.id,
    customerNo: item.customerNo,
    displayName: item.displayName,
    nickname: item.nickname,
    ownerStaffId: item.ownerStaffId,
    ownerName: item.ownerName,
    version: item.version,
  }
}

// 原业务表 23 字段档案:切到该 tab 才加载;无可用值时不拿其他系统字段凑数
const legacyFields = ref<LegacyProfileField[]>([])
const legacyLoading = ref(false)
const legacyError = ref('')
let legacyLoadedFor: number | null = null
let detailSequence = 0
let legacySequence = 0

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
  const targetCustomerId = props.customerId
  if (targetCustomerId === null || !props.modelValue || legacyLoadedFor === targetCustomerId) return
  const sequence = ++legacySequence
  legacyLoading.value = true
  legacyError.value = ''
  try {
    const result = await getCustomerLegacyProfile(targetCustomerId)
    if (sequence !== legacySequence || !props.modelValue || props.customerId !== targetCustomerId) return
    legacyFields.value = result
    legacyLoadedFor = targetCustomerId
  } catch (error) {
    if (sequence !== legacySequence || !props.modelValue || props.customerId !== targetCustomerId) return
    legacyFields.value = []
    legacyError.value = errorText(error)
  } finally {
    if (sequence === legacySequence) legacyLoading.value = false
  }
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '客户不存在或不在您的可见范围内'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

function resetState() {
  detail.value = null
  loading.value = false
  errorMessage.value = ''
  legacyFields.value = []
  legacyLoading.value = false
  legacyError.value = ''
  legacyLoadedFor = null
}

async function load() {
  const targetCustomerId = props.customerId
  if (targetCustomerId === null || !props.modelValue) return
  const sequence = ++detailSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await getCustomer(targetCustomerId)
    if (sequence !== detailSequence || !props.modelValue || props.customerId !== targetCustomerId) return
    detail.value = result
  } catch (error) {
    if (sequence !== detailSequence || !props.modelValue || props.customerId !== targetCustomerId) return
    detail.value = null
    errorMessage.value = errorText(error)
  } finally {
    if (sequence === detailSequence) loading.value = false
  }
}

watch(
  () => [props.modelValue, props.customerId],
  ([visible, customerId]) => {
    detailSequence += 1
    legacySequence += 1
    resetState()
    if (visible && customerId !== null) {
      activeTab.value = props.initialTab || 'overview'
      void dicts.load('cities', 'customerSources', 'customerChannels', 'customerStatuses', 'customerTiers', 'customerTags')
      void load()
    }
  },
)

watch(activeTab, (tab) => {
  if (tab === 'profile') void loadLegacyProfile()
})

async function handleChildChanged() {
  emit('changed')
  await Promise.all([dicts.load('customerTags'), load()])
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
          title="该客户已被合并归档，当前档案仅供查看，关联数据已迁移至主客户"
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
              <StatusPill v-if="detail.tierCode" :text="dicts.label('customerTiers', detail.tierCode)" tone="dark" />
              <StatusPill
                v-if="canViewMultiSteward && detail.addedStewardCount && detail.addedStewardCount > 1"
                :text="`已加 ${detail.addedStewardCount} 位管家`"
                tone="warning"
                data-testid="detail-multi-steward-pill"
              />
            </div>
            <div class="crm-muted">
              编号 {{ detail.customerNo }} · 管家 {{ detail.ownerName ?? `#${detail.ownerStaffId}` }} ·
              {{ detail.mobileMasked ?? '未留手机号' }}
            </div>
            <div v-if="detail.tagCodes.length" class="head-tags" data-testid="detail-head-tags">
              <span v-for="code in detail.tagCodes" :key="code" class="proto-pill success tag-item">
                {{ dicts.tagLabel(code) }}
              </span>
            </div>
          </div>
          <div class="head-actions">
            <el-button
              v-if="auth.hasPermission('customer:transfer') && !detail.deleted"
              type="primary"
              plain
              data-testid="detail-assign-owner-btn"
              @click="assignVisible = true"
            >
              分配管家
            </el-button>
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
            <CustomerPrivacyPanel
              :customer-id="detail.id"
              :visible="modelValue"
              :readonly="detail.deleted"
            />
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
            <section
              v-if="canViewMultiSteward && detail.addedStewards && detail.addedStewards.length"
              class="steward-relations-card"
              data-testid="detail-steward-relations-card"
            >
              <div class="steward-relations-head">
                <div>
                  <strong>多管家添加情况（无需合并）</strong>
                  <p class="steward-relations-sub">
                    该顾客当前共添加了 <strong>{{ detail.addedStewardCount ?? detail.addedStewards.length }}</strong> 位管家
                    <template v-if="crossTeamStewardCount > 0">
                      （含 {{ crossTeamStewardCount }} 位跨团队管家，跨组仅展示姓名与团队汇总）
                    </template>
                  </p>
                </div>
                <StatusPill
                  :text="(detail.addedStewardCount ?? detail.addedStewards.length) > 1 ? '已跨管家添加' : '单管家跟进'"
                  :tone="(detail.addedStewardCount ?? detail.addedStewards.length) > 1 ? 'warning' : 'success'"
                />
              </div>
              <div class="steward-relations-list">
                <div
                  v-for="steward in detail.addedStewards"
                  :key="steward.staffUserId"
                  class="steward-relation-row"
                  :class="{ 'is-cross-team': !steward.inMyScope }"
                  :data-testid="`detail-steward-row-${steward.staffUserId}`"
                >
                  <div class="steward-relation-main">
                    <span class="steward-relation-name">{{ stewardTeamLabel(steward) }}</span>
                    <span v-for="rel in steward.relationTypes" :key="rel" class="steward-relation-badge">
                      {{ stewardRelationLabel(rel) }}
                    </span>
                    <span v-if="steward.inMyScope && steward.customerNo" class="steward-relation-no">
                      {{ steward.customerId === detail.id ? '当前档案' : `本组档案 ${steward.customerNo}` }}
                    </span>
                    <span v-else-if="!steward.inMyScope" class="steward-relation-cross" data-testid="detail-cross-team-tag">
                      跨组仅汇总
                    </span>
                  </div>
                  <span class="steward-relation-time">{{ formatDateTime(steward.addedAt) }}</span>
                </div>
              </div>
            </section>
            <section class="tags-overview-card" data-testid="detail-tags-card">
              <TagsIdentityPanel
                section="tags"
                :customer-id="detail.id"
                :tag-codes="detail.tagCodes"
                :identities="detail.identities"
                :readonly="detail.deleted"
                @changed="handleChildChanged"
              />
            </section>
            <el-collapse>
              <el-collapse-item title="关联身份与积分维护" name="more">
                <TagsIdentityPanel
                  section="identities"
                  :customer-id="detail.id"
                  :tag-codes="detail.tagCodes"
                  :identities="detail.identities"
                  :readonly="detail.deleted"
                  @changed="handleChildChanged"
                />
                <el-divider />
                <PointsPanel :customer-id="detail.id" :birthday="detail.birthday" @changed="handleChildChanged" />
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>
          <el-tab-pane label="统一时间线" name="timeline" lazy>
            <CustomerTimelinePanel :customer-id="detail.id" />
          </el-tab-pane>
          <el-tab-pane label="客户分级" name="tier" lazy>
            <CustomerTierPanel :customer-id="detail.id" @changed="handleChildChanged" />
          </el-tab-pane>
          <el-tab-pane label="会员权益" name="entitlements"><CustomerBusinessPanel :customer-id="detail.id" type="entitlements" /></el-tab-pane>
          <el-tab-pane label="核销记录" name="redemptions"><CustomerBusinessPanel :customer-id="detail.id" type="redemptions" /></el-tab-pane>
          <el-tab-pane label="订单与退款" name="orders"><CustomerBusinessPanel :customer-id="detail.id" type="orders" /></el-tab-pane>
          <el-tab-pane label="服务记录" name="service"><FollowupPanel :fixed-customer-id="detail.id" @saved="handleChildChanged" /></el-tab-pane>
          <el-tab-pane label="基础档案" name="profile">
            <el-alert v-if="legacyError" type="error" :closable="false" :title="`档案加载失败：${legacyError}`" class="crm-table-alert" />
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
            <OwnershipPanel
              :customer-id="detail.id"
              :customer="detail.deleted ? undefined : toAssignTarget(detail)"
              class="profile-ownership"
              @changed="handleChildChanged"
            />
          </el-tab-pane>
        </el-tabs>
        <CustomerAssignDialog
          v-model="assignVisible"
          :customers="detail.deleted ? [] : [toAssignTarget(detail)]"
          @assigned="handleChildChanged"
        />
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

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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

.head-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.tags-overview-card {
  margin-bottom: 16px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid #e9eff3;
  border-radius: 12px;
}

.steward-relations-card {
  margin-bottom: 16px;
  padding: 15px 18px;
  background: #fffdf9;
  border: 1px solid #ecdcc0;
  border-radius: 12px;
}

.steward-relations-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.steward-relations-sub {
  margin: 4px 0 0;
  color: #6c7f8c;
  font-size: 12px;
}

.steward-relations-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.steward-relation-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f5faf8;
  border: 1px solid #dcebe6;
  font-size: 12px;
}

.steward-relation-row.is-cross-team {
  background: #fdf6ea;
  border-color: #eedbb9;
}

.steward-relation-main {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.steward-relation-name {
  font-weight: 600;
  color: #21453d;
}

.steward-relation-badge {
  padding: 1px 6px;
  border-radius: 999px;
  background: #e2f1ec;
  color: #1d5d53;
  font-size: 11px;
}

.steward-relation-no {
  color: #5b7783;
}

.steward-relation-cross {
  color: #9a6c20;
  font-weight: 600;
}

.steward-relation-time {
  color: #7c8f9c;
  font-size: 11px;
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
