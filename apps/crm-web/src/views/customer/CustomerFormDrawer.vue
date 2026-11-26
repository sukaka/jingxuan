<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CreateCustomerRequest, CustomerOwnerOption } from '../../api/customer'
import { createCustomer, getCustomer, listCustomerOwnerOptions, updateCustomer } from '../../api/customer'
import type { Project } from '../../api/catalog'
import { listProjects } from '../../api/catalog'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import CustomerSourceChannelManageDialog from './CustomerSourceChannelManageDialog.vue'
import CustomerTagManageDialog from './CustomerTagManageDialog.vue'
import { useDictionaries } from './shared'

const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit'
  customerId: number | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const auth = useAuthStore()
const dicts = useDictionaries()
void dicts.load('cities', 'customerSources', 'customerChannels', 'customerStatuses', 'customerTags')

// 所属管家:管理员建档必须显式选择;MANAGER/MANAGER_LEAD 默认本人(缺省由后端归属当前登录人)
const isAdmin = auth.hasRole('ADMIN')
const canManageTagCatalog = computed(() => auth.hasPermission('customer:tag:manage'))
const canManageSourceChannel = computed(() => auth.hasPermission('customer:source_channel:manage'))
const tagManageVisible = ref(false)
const sourceChannelManageVisible = ref(false)
const sourceChannelManageTab = ref<'sources' | 'channels'>('sources')
const ownerOptions = ref<CustomerOwnerOption[]>([])
const ownerOptionsError = ref('')
const formOwnerId = ref<number | undefined>(undefined)
const projectOptions = ref<Project[]>([])
const projectOptionsError = ref('')
const legacyAttentionProjects = ref('')

// 意向/消费能力未入字典(口径待确认),暂用静态选项并允许自定义
const LEVEL_OPTIONS = [
  { code: 'HIGH', label: '高' },
  { code: 'MEDIUM', label: '中' },
  { code: 'LOW', label: '低' },
]

interface FormState {
  displayName: string
  nickname: string
  remarkName: string
  mobile: string
  idCard: string
  email: string
  wechatId: string
  cityCode: string
  sourceCode: string
  channelCode: string
  customerStatus: string
  intentLevel: string
  spendingLevel: string
  birthday: string
  tagCodes: string[]
  projectIds: number[]
  blockerNote: string
  remark: string
  firstAddedAt: string
}

function emptyForm(): FormState {
  return {
    displayName: '',
    nickname: '',
    remarkName: '',
    mobile: '',
    idCard: '',
    email: '',
    wechatId: '',
    cityCode: '',
    sourceCode: '',
    channelCode: '',
    customerStatus: '',
    intentLevel: '',
    spendingLevel: '',
    birthday: '',
    tagCodes: [],
    projectIds: [],
    blockerNote: '',
    remark: '',
    firstAddedAt: '',
  }
}

const form = reactive<FormState>(emptyForm())
const currentPrivacyMasked = reactive({
  mobile: '' as string,
  idCard: '' as string,
  email: '' as string,
  wechatId: '' as string,
})
const clearPrivacy = reactive({
  mobile: false,
  idCard: false,
  email: false,
  wechatId: false,
})
const tagSelectOptions = computed(() => dicts.optionsWithHistoryList('customerTags', form.tagCodes))
const loading = ref(false)
const submitting = ref(false)
const version = ref(0)
let idempotencyKey = ''

async function onTagCatalogChanged() {
  await dicts.load('customerTags')
}

function openSourceChannelManage(tab: 'sources' | 'channels') {
  sourceChannelManageTab.value = tab
  sourceChannelManageVisible.value = true
}

async function onSourceChannelCatalogChanged() {
  await dicts.load('customerSources', 'customerChannels')
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return error.message || '该客户已被他人修改，请关闭后重新打开编辑'
    if (error.status === 404) return '客户不存在或不在您的可见范围内'
    return error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

async function loadProjectOptions() {
  projectOptionsError.value = ''
  try {
    const result = await listProjects({ page: 1, size: 100 })
    projectOptions.value = result.items
  } catch (error) {
    projectOptions.value = []
    projectOptionsError.value = errorText(error)
  }
}

watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) return
    void dicts.load('customerTags', 'customerSources', 'customerChannels')
    void loadProjectOptions()
    if (props.mode === 'create') {
      Object.assign(form, emptyForm())
      Object.assign(currentPrivacyMasked, { mobile: '', idCard: '', email: '', wechatId: '' })
      Object.assign(clearPrivacy, { mobile: false, idCard: false, email: false, wechatId: false })
      legacyAttentionProjects.value = ''
      idempotencyKey = crypto.randomUUID()
      formOwnerId.value = isAdmin ? undefined : (auth.user?.userId ?? undefined)
      ownerOptionsError.value = ''
      try {
        ownerOptions.value = await listCustomerOwnerOptions()
      } catch (error) {
        // 加载失败/无权限:明确提示,不伪造选项;非管理员可缺省提交(归属本人)
        ownerOptions.value = []
        ownerOptionsError.value =
          error instanceof ApiError ? error.message : '管家选项加载失败'
      }
      return
    }
    if (props.customerId === null) return
    loading.value = true
    try {
      const detail = await getCustomer(props.customerId)
      version.value = detail.version
      legacyAttentionProjects.value = detail.attentionProjects ?? ''
      Object.assign(currentPrivacyMasked, {
        mobile: detail.mobileMasked ?? '',
        idCard: detail.idCardMasked ?? '',
        email: detail.emailMasked ?? '',
        wechatId: detail.wechatIdMasked ?? '',
      })
      Object.assign(clearPrivacy, { mobile: false, idCard: false, email: false, wechatId: false })
      Object.assign(form, {
        displayName: detail.displayName ?? '',
        nickname: detail.nickname ?? '',
        remarkName: detail.remarkName ?? '',
        mobile: '',
        idCard: '',
        email: '',
        wechatId: '',
        cityCode: detail.cityCode ?? '',
        sourceCode: detail.sourceCode ?? '',
        channelCode: detail.channelCode ?? '',
        customerStatus: detail.customerStatus ?? '',
        intentLevel: detail.intentLevel ?? '',
        spendingLevel: detail.spendingLevel ?? '',
        birthday: detail.birthday ?? '',
        tagCodes: [...(detail.tagCodes ?? [])],
        projectIds: [...(detail.attentionProjectIds ?? [])],
        blockerNote: detail.blockerNote ?? '',
        remark: detail.remark ?? '',
        firstAddedAt: detail.firstAddedAt ?? '',
      })
    } catch (error) {
      emit('update:modelValue', false)
      ElMessage.error(errorText(error))
    } finally {
      loading.value = false
    }
  },
)

/** 空字符串不上送,可空列保持 NULL;编辑模式下勾选清空才上送空串通知后端清除隐私字段 */
function buildPayload(): CreateCustomerRequest {
  const payload: Record<string, string | number | number[] | string[]> = {}
  const privacyKeys = new Set(['mobile', 'idCard', 'email', 'wechatId'])
  for (const [key, value] of Object.entries(form)) {
    if (key === 'projectIds' || key === 'tagCodes') continue
    if (privacyKeys.has(key)) {
      const k = key as 'mobile' | 'idCard' | 'email' | 'wechatId'
      if (props.mode === 'edit' && clearPrivacy[k]) {
        payload[key] = ''
      } else if (typeof value === 'string' && value.trim()) {
        payload[key] = value.trim()
      }
      continue
    }
    if (value) payload[key] = value as string
  }
  payload.projectIds = [...form.projectIds]
  if (props.mode === 'edit' || form.tagCodes.length > 0) {
    payload.tagCodes = [...form.tagCodes]
  }
  return payload as CreateCustomerRequest
}

async function submit() {
  if (!form.displayName && !form.nickname) {
    ElMessage.warning('昵称与称呼至少填写其一')
    return
  }
  const privacyChecks: Array<[string, string]> = [
    ['手机号', form.mobile],
    ['身份证号', form.idCard],
    ['邮箱', form.email],
    ['微信号', form.wechatId],
  ]
  for (const [label, value] of privacyChecks) {
    if (value && value.includes('*')) {
      ElMessage.warning(`${label}不能包含脱敏占位符 *，请输入完整新值或留空`)
      return
    }
  }
  if (props.mode === 'create' && isAdmin && formOwnerId.value === undefined) {
    ElMessage.warning(
      ownerOptionsError.value ? '管家选项加载失败，请关闭重试后再建档' : '管理员建档必须选择所属管家',
    )
    return
  }
  submitting.value = true
  try {
    if (props.mode === 'create') {
      const payload = buildPayload()
      if (formOwnerId.value !== undefined) payload.ownerStaffId = formOwnerId.value
      const created = await createCustomer(payload, idempotencyKey)
      ElMessage.success(`客户已建档，编号 ${created.customerNo}`)
    } else if (props.customerId !== null) {
      const payload = buildPayload()
      delete payload.firstAddedAt
      await updateCustomer(props.customerId, { ...payload, version: version.value })
      ElMessage.success('已保存')
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="mode === 'create' ? '新建客户' : '编辑客户'"
    size="min(640px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="form-body">
      <el-alert
        v-if="mode === 'create'"
        type="info"
        :closable="false"
        title="称呼与昵称至少填写一项，手机号可不填；客户编号由系统建档后自动生成，不依赖手机号或昵称"
        class="form-tip"
      />
      <el-form label-width="96px">
        <div class="form-grid">
          <el-form-item v-if="mode === 'create'" label="所属管家" :required="isAdmin" class="form-grid-full">
            <el-select
              v-model="formOwnerId"
              filterable
              :disabled="ownerOptionsError !== ''"
              :placeholder="isAdmin ? '管理员建档必须选择所属管家' : '默认为本人，可按需调整'"
              class="owner-select"
            >
              <el-option
                v-for="option in ownerOptions"
                :key="option.id"
                :label="option.teamName ? `${option.displayName}（${option.teamName}）` : option.displayName"
                :value="option.id"
              />
            </el-select>
            <span v-if="ownerOptionsError" class="owner-error">管家选项加载失败：{{ ownerOptionsError }}</span>
          </el-form-item>
          <el-form-item label="客户称呼">
            <el-input v-model="form.displayName" placeholder="真实姓名或称呼（选填）" />
          </el-form-item>
          <el-form-item label="客户昵称">
            <el-input v-model="form.nickname" placeholder="与真实姓名区分" />
          </el-form-item>
          <el-form-item label="手机号">
            <div class="tag-field-wrap">
              <el-input
                v-model="form.mobile"
                :disabled="mode === 'edit' && clearPrivacy.mobile"
                :placeholder="mode === 'edit'
                  ? (currentPrivacyMasked.mobile ? `当前 ${currentPrivacyMasked.mobile}，留空则不修改` : '选填；加密保存，页面仅脱敏展示')
                  : '选填；系统加密保存，页面仅脱敏展示'"
                data-testid="customer-mobile-input"
              />
              <el-checkbox
                v-if="mode === 'edit' && currentPrivacyMasked.mobile"
                v-model="clearPrivacy.mobile"
                size="small"
                @change="(checked: boolean) => { if (checked) form.mobile = '' }"
              >
                清空已存手机号
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="身份证号">
            <div class="tag-field-wrap">
              <el-input
                v-model="form.idCard"
                :disabled="mode === 'edit' && clearPrivacy.idCard"
                :placeholder="mode === 'edit'
                  ? (currentPrivacyMasked.idCard ? `当前 ${currentPrivacyMasked.idCard}，留空则不修改` : '选填 18 位身份证号，加密保存')
                  : '选填 18 位身份证号，加密保存'"
                data-testid="customer-idcard-input"
              />
              <el-checkbox
                v-if="mode === 'edit' && currentPrivacyMasked.idCard"
                v-model="clearPrivacy.idCard"
                size="small"
                @change="(checked: boolean) => { if (checked) form.idCard = '' }"
              >
                清空已存身份证号
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="邮箱">
            <div class="tag-field-wrap">
              <el-input
                v-model="form.email"
                :disabled="mode === 'edit' && clearPrivacy.email"
                :placeholder="mode === 'edit'
                  ? (currentPrivacyMasked.email ? `当前 ${currentPrivacyMasked.email}，留空则不修改` : '选填；加密保存，默认脱敏展示')
                  : '选填；加密保存，默认脱敏展示'"
                data-testid="customer-email-input"
              />
              <el-checkbox
                v-if="mode === 'edit' && currentPrivacyMasked.email"
                v-model="clearPrivacy.email"
                size="small"
                @change="(checked: boolean) => { if (checked) form.email = '' }"
              >
                清空已存邮箱
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="微信号">
            <div class="tag-field-wrap">
              <el-input
                v-model="form.wechatId"
                :disabled="mode === 'edit' && clearPrivacy.wechatId"
                :placeholder="mode === 'edit'
                  ? (currentPrivacyMasked.wechatId ? `当前 ${currentPrivacyMasked.wechatId}，留空则不修改` : '选填；加密保存，默认脱敏展示')
                  : '选填；加密保存，默认脱敏展示'"
                data-testid="customer-wechat-input"
              />
              <el-checkbox
                v-if="mode === 'edit' && currentPrivacyMasked.wechatId"
                v-model="clearPrivacy.wechatId"
                size="small"
                @change="(checked: boolean) => { if (checked) form.wechatId = '' }"
              >
                清空已存微信号
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="备注名">
            <el-input v-model="form.remarkName" />
          </el-form-item>
          <el-form-item label="客户标签" class="form-grid-full">
            <div class="tag-field-wrap">
              <el-select
                v-model="form.tagCodes"
                multiple
                filterable
                clearable
                data-testid="customer-tag-select"
                placeholder="请选择客户标签（可多选）"
                class="tag-select"
              >
                <el-option
                  v-for="option in tagSelectOptions"
                  :key="option.code"
                  :label="option.label"
                  :value="option.code"
                  :disabled="option.disabled"
                />
              </el-select>
              <div class="tag-field-meta">
                <el-button
                  v-if="canManageTagCatalog"
                  text
                  type="primary"
                  size="small"
                  data-testid="form-manage-tags-btn"
                  @click="tagManageVisible = true"
                >
                  管理标签内容
                </el-button>
                <span v-else class="tag-field-hint">
                  仅可选择已有标签；如需新增或修改标签内容，请联系管家主管维护
                </span>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="城市">
            <el-select v-model="form.cityCode" clearable filterable data-testid="customer-city-select">
              <el-option
                v-for="option in dicts.optionsWithHistory('cities', form.cityCode)"
                :key="option.code"
                :label="option.label"
                :value="option.code"
                :disabled="option.disabled"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="客户来源">
            <div class="tag-field-wrap">
              <el-select
                v-model="form.sourceCode"
                clearable
                filterable
                placeholder="请选择客户来源"
                data-testid="customer-source-select"
                class="tag-select"
              >
                <el-option
                  v-for="option in dicts.optionsWithHistory('customerSources', form.sourceCode)"
                  :key="option.code"
                  :label="option.label"
                  :value="option.code"
                  :disabled="option.disabled"
                />
              </el-select>
              <div class="tag-field-meta">
                <el-button
                  v-if="canManageSourceChannel"
                  text
                  type="primary"
                  size="small"
                  data-testid="form-manage-sources-btn"
                  @click="openSourceChannelManage('sources')"
                >
                  管理来源
                </el-button>
                <span v-else class="tag-field-hint" data-testid="form-source-readonly-hint">
                  仅可下拉选择，由管家主管统一维护
                </span>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="客户渠道">
            <div class="tag-field-wrap">
              <el-select
                v-model="form.channelCode"
                clearable
                filterable
                placeholder="请选择客户渠道"
                data-testid="customer-channel-select"
                class="tag-select"
              >
                <el-option
                  v-for="option in dicts.optionsWithHistory('customerChannels', form.channelCode)"
                  :key="option.code"
                  :label="option.label"
                  :value="option.code"
                  :disabled="option.disabled"
                />
              </el-select>
              <div class="tag-field-meta">
                <el-button
                  v-if="canManageSourceChannel"
                  text
                  type="primary"
                  size="small"
                  data-testid="form-manage-channels-btn"
                  @click="openSourceChannelManage('channels')"
                >
                  管理渠道
                </el-button>
                <span v-else class="tag-field-hint" data-testid="form-channel-readonly-hint">
                  仅可下拉选择，由管家主管统一维护
                </span>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="客户状态">
            <el-select v-model="form.customerStatus" clearable>
              <el-option
                v-for="option in dicts.optionsWithHistory('customerStatuses', form.customerStatus)"
                :key="option.code"
                :label="option.label"
                :value="option.code"
                :disabled="option.disabled"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="意向级别">
            <el-select v-model="form.intentLevel" clearable filterable allow-create default-first-option>
              <el-option
                v-for="option in LEVEL_OPTIONS"
                :key="option.code"
                :label="option.label"
                :value="option.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="消费能力">
            <el-select v-model="form.spendingLevel" clearable filterable allow-create default-first-option>
              <el-option
                v-for="option in LEVEL_OPTIONS"
                :key="option.code"
                :label="option.label"
                :value="option.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="生日">
            <el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item v-if="mode === 'create'" label="首次添加时间">
            <el-date-picker
              v-model="form.firstAddedAt"
              type="datetime"
              value-format="YYYY-MM-DD[T]HH:mm:ssZ"
              placeholder="历史客户补录（选填）"
            />
          </el-form-item>
          <el-form-item label="关注项目" class="form-grid-full">
            <el-select
              v-model="form.projectIds" multiple filterable clearable
              data-testid="attention-project-select" placeholder="请选择关注项目"
            >
              <el-option
                v-for="project in projectOptions" :key="project.id"
                :label="project.status === 'ACTIVE' ? project.projectName : `${project.projectName}（停用）`"
                :value="project.id"
                :disabled="project.status !== 'ACTIVE' && !form.projectIds.includes(project.id)"
              />
            </el-select>
            <div v-if="projectOptionsError" class="owner-error">项目选项加载失败：{{ projectOptionsError }}</div>
            <div v-if="legacyAttentionProjects" class="legacy-attention">
              历史备注项目（仅供参考，不自动关联）：{{ legacyAttentionProjects }}
            </div>
          </el-form-item>
          <el-form-item label="卡点情况" class="form-grid-full">
            <el-input v-model="form.blockerNote" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="备注" class="form-grid-full">
            <el-input v-model="form.remark" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item v-if="mode === 'edit'" label="资料版本">
            <span class="crm-muted">第 {{ version }} 版（若他人同时修改，保存时将提示刷新）</span>
          </el-form-item>
        </div>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ mode === 'create' ? '建档' : '保存' }}
      </el-button>
    </template>
    <CustomerTagManageDialog
      v-if="canManageTagCatalog"
      v-model="tagManageVisible"
      @changed="onTagCatalogChanged"
    />
    <CustomerSourceChannelManageDialog
      v-if="canManageSourceChannel"
      v-model="sourceChannelManageVisible"
      :initial-tab="sourceChannelManageTab"
      @changed="onSourceChannelCatalogChanged"
    />
  </el-drawer>
</template>

<style scoped>
.form-body {
  min-height: 200px;
}

.form-tip {
  margin-bottom: 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 8px;
}

.form-grid-full {
  grid-column: 1 / -1;
}

.owner-select,
.tag-select {
  width: 100%;
}

.tag-field-wrap {
  width: 100%;
}

.tag-field-meta {
  display: flex;
  align-items: center;
  margin-top: 4px;
}

.tag-field-hint {
  color: #7c9288;
  font-size: 12px;
  line-height: 1.5;
}

.owner-error {
  color: var(--el-color-danger);
  font-size: 12px;
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
