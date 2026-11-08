<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CustomerListItem } from '../../api/customer'
import { listCustomers } from '../../api/customer'
import type { WecomContact, WecomContactSyncRun } from '../../api/wecom'
import { linkWecomContact, syncWecomContacts } from '../../api/wecom'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import { tradingErrorText } from '../trading/tradingUi'
import { useReferenceNames } from '../references'
import { consentStatusLabel, consentStatusTone, createWecomKey } from './wecomUi'

const { loadCustomerNames, customerLabel: crmCustomerLabel } = useReferenceNames()

const props = defineProps<{
  contacts: WecomContact[]
  syncRuns: WecomContactSyncRun[]
  loading: boolean
  canMonitor: boolean
}>()
const emit = defineEmits<{ saved: [] }>()
const contactSyncing = ref(false)
const latestSync = computed(() => props.syncRuns.find((run) => run.runType !== 'CALLBACK'))

async function runContactSync(type: 'FULL' | 'RECONCILE') {
  if (contactSyncing.value) return
  contactSyncing.value = true
  try {
    const run = await syncWecomContacts(type, createWecomKey(`wecom.contact.${type.toLowerCase()}`))
    ElMessage.success(`客户资料同步完成：${run.receivedCount} 条跟进关系，失效 ${run.deactivatedCount} 条；不代表会话存档已开启`)
    emit('saved')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    contactSyncing.value = false
  }
}

const unmatchedOnly = ref(false)
const ADD_WAY_LABELS: Record<string, string> = {
  '0': '未知来源',
  '1': '扫码添加',
  '2': '搜索手机号添加',
  '3': '名片分享',
  '4': '群聊添加',
  '5': '手机通讯录',
  '6': '微信好友',
  '7': '面对面添加',
  '8': '第三方应用',
  '9': '搜索邮箱添加',
  '10': '视频号添加',
}

function addWayLabel(value: number | null): string {
  return value === null ? '-' : ADD_WAY_LABELS[value] ?? '其他添加方式'
}

const visibleContacts = computed(() => unmatchedOnly.value
  ? props.contacts.filter((contact) => contact.customerId === null)
  : props.contacts)

watch(() => props.contacts, (contacts) => {
  void loadCustomerNames(contacts.map((contact) => contact.customerId).filter((id): id is number => id !== null))
}, { immediate: true })

const formVisible = ref(false)
const selectedContact = ref<WecomContact | null>(null)
const customerId = ref<number>()
const selectedCustomer = ref<CustomerListItem | null>(null)
const reason = ref('')
const customers = ref<CustomerListItem[]>([])
const searching = ref(false)
const hasMore = ref(false)
const searchError = ref('')
const saving = ref(false)
let searchSequence = 0
let linkAttempt: { signature: string; key: string } | null = null

const customerOptions = computed(() => {
  const options = new Map<number, CustomerListItem>()
  if (selectedCustomer.value) options.set(selectedCustomer.value.id, selectedCustomer.value)
  for (const item of customers.value) options.set(item.id, item)
  return Array.from(options.values())
})

function openLink(contact: WecomContact) {
  selectedContact.value = contact
  customerId.value = undefined
  selectedCustomer.value = null
  reason.value = ''
  searchError.value = ''
  linkAttempt = null
  formVisible.value = true
  void searchCustomers('')
}

function selectCustomer(id: number | undefined) {
  customerId.value = id
  selectedCustomer.value = customerOptions.value.find((item) => item.id === id) ?? null
}

async function searchCustomers(keyword: string) {
  const sequence = ++searchSequence
  searching.value = true
  searchError.value = ''
  try {
    const result = await listCustomers({ page: 1, size: 20, keyword: keyword.trim() || undefined })
    if (sequence !== searchSequence) return
    customers.value = result.items
    hasMore.value = result.total > result.items.length
  } catch (error) {
    if (sequence === searchSequence) searchError.value = tradingErrorText(error)
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

function customerLabel(customer: CustomerListItem): string {
  return `${customer.displayName || customer.nickname || `客户 #${customer.id}`}（${customer.customerNo}）`
}

async function save() {
  const contact = selectedContact.value
  if (!contact) return
  if (customerId.value == null) {
    ElMessage.warning('请选择当前数据范围内的 CRM 客户')
    return
  }
  if (!reason.value.trim()) {
    ElMessage.warning('必须填写人工匹配理由与证据')
    return
  }
  const signature = `${contact.id}:${contact.version}:${customerId.value}:${reason.value.trim()}`
  if (linkAttempt?.signature !== signature) {
    linkAttempt = { signature, key: createWecomKey('wecom.contact.link') }
  }
  saving.value = true
  try {
    await linkWecomContact(contact.id, {
      customerId: customerId.value,
      version: contact.version,
      reason: reason.value.trim(),
    }, linkAttempt.key)
    linkAttempt = null
    ElMessage.success('人工客户匹配已保存；不代表真实自然人或 Q10 授权已核实')
    formVisible.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section>
    <el-card shadow="never" class="wecom-control-card">
      <div class="wecom-section-head">
        <div>
          <h3>官方客户资料同步</h3>
          <p v-if="latestSync">最近{{ latestSync.runType === 'RECONCILE' ? '对账' : '全量' }}：{{ ({ RUNNING: '执行中', SUCCESS: '成功', ERROR: '失败' } as Record<string, string>)[latestSync.status] || '未知状态' }} · {{ latestSync.receivedCount }} 条 · {{ formatDateTime(latestSync.finishedAt || latestSync.startedAt) }}</p>
          <p v-else>尚未执行客户资料同步；此状态与会话存档同意及采集覆盖缺口独立。</p>
          <el-alert v-if="latestSync?.errorMasked" type="error" :closable="false" :title="latestSync.errorMasked" />
        </div>
        <div v-if="canMonitor">
          <el-button :loading="contactSyncing" data-testid="wecom-contact-full-sync" @click="runContactSync('FULL')">全量同步</el-button>
          <el-button :loading="contactSyncing" data-testid="wecom-contact-reconcile" @click="runContactSync('RECONCILE')">执行对账</el-button>
        </div>
      </div>
    </el-card>
    <div class="wecom-section-head">
      <div>
        <h3>联系人与未关联队列</h3>
        <p>外部身份不等于唯一自然人；只人工关联可见 CRM 客户，不按昵称或手机号自动合并。</p>
      </div>
      <el-checkbox v-model="unmatchedOnly" data-testid="wecom-unmatched-filter">仅看未关联</el-checkbox>
    </div>
    <app-table v-loading="loading" :data="visibleContacts" row-key="id">
      <el-table-column label="客户资料" min-width="160"><template #default="{ row }">{{ row.name || row.externalUserid }}<div class="wecom-cell-sub">{{ row.remark || row.externalUserid }}</div></template></el-table-column>
      <el-table-column prop="staffUserId" label="跟进成员" width="105"><template #default="{ row }">员工 #{{ row.staffUserId }}</template></el-table-column>
      <el-table-column label="跟进状态" width="150"><template #default="{ row }">{{ ({ ACTIVE: '正常', DELETED_BY_STAFF: '员工已删除', DELETED_BY_CUSTOMER: '客户已删除员工' } as Record<string, string>)[row.followStatus] || '未知跟进状态' }}</template></el-table-column>
      <el-table-column label="来源 / 添加时间" min-width="180"><template #default="{ row }">{{ addWayLabel(row.addWay) }}<div class="wecom-cell-sub">{{ formatDateTime(row.followCreatedAt) }}</div></template></el-table-column>
      <el-table-column label="存档同意状态" width="125"><template #default="{ row }"><StatusPill :text="consentStatusLabel(row.consentStatus)" :tone="consentStatusTone(row.consentStatus)" /></template></el-table-column>
      <el-table-column label="CRM 客户" width="150">
        <template #default="{ row }">
          <template v-if="row.customerId !== null">{{ crmCustomerLabel(row.customerId) }}<div class="wecom-cell-sub">{{ ({ MANUAL: '人工确认' } as Record<string, string>)[row.linkType] || '关联方式未知' }}</div></template>
          <span v-else class="wecom-error-text">未关联</span>
        </template>
      </el-table-column>
      <el-table-column label="人工理由" min-width="190" show-overflow-tooltip><template #default="{ row }">{{ row.linkReason || '-' }}</template></el-table-column>
      <el-table-column label="最近联系人同步" min-width="170"><template #default="{ row }">{{ formatDateTime(row.lastSyncedAt) }}</template></el-table-column>
      <el-table-column prop="version" label="版本" width="75" />
      <el-table-column v-if="canMonitor" label="操作" width="125" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.customerId === null" text type="primary" size="small" data-testid="wecom-contact-link" @click="openLink(row)">人工匹配</el-button>
          <span v-else class="wecom-readonly-note">已确认不可改绑</span>
        </template>
      </el-table-column>
      <template #empty><el-empty :description="unmatchedOnly ? '当前没有未关联联系人；不能据此推断采集已完整' : '当前数据范围暂无企微联系人；请结合采集监控判断覆盖情况'" /></template>
    </app-table>

    <el-dialog v-model="formVisible" title="人工匹配 CRM 客户" width="min(580px, 94vw)">
      <el-alert type="warning" :closable="false" title="仅保存人工核对后的客户关联；不使用昵称或手机号自动决定客户 ID，也不核实真实 Q10 授权。" class="wecom-link-tip" />
      <template v-if="selectedContact">
        <p class="dialog-contact">{{ selectedContact.externalUserid }} · 员工 #{{ selectedContact.staffUserId }} · 版本 {{ selectedContact.version }}</p>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="CRM 客户（当前数据范围）" required>
            <el-select :model-value="customerId" filterable remote :remote-method="searchCustomers" :loading="searching" class="wecom-full-width" data-testid="wecom-link-customer" @change="selectCustomer">
              <el-option v-for="item in customerOptions" :key="item.id" :label="customerLabel(item)" :value="item.id" />
            </el-select>
            <div v-if="searchError" class="wecom-error-text">{{ searchError }} <el-button text size="small" @click="searchCustomers('')">重试</el-button></div>
            <div v-else-if="hasMore" class="wecom-readonly-note">结果较多，请继续输入客户编号或名称缩小范围。</div>
          </el-form-item>
          <el-form-item label="人工匹配理由与证据" required>
            <el-input v-model="reason" type="textarea" :rows="4" maxlength="1000" data-testid="wecom-link-reason" placeholder="说明人工核对的外部身份、成员上下文与 CRM 档案依据" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button :disabled="saving" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="wecom-link-save" @click="save">保存人工匹配</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.dialog-contact { margin: 0 0 16px; color: #587572; font-size: 12px; overflow-wrap: anywhere; }
</style>
