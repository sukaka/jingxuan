<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { CreateCustomerRequest, CustomerOwnerOption } from '../../api/customer'
import { createCustomer, getCustomer, listCustomerOwnerOptions, updateCustomer } from '../../api/customer'
import type { Project } from '../../api/catalog'
import { listProjects } from '../../api/catalog'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
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
void dicts.load('cities', 'customerSources', 'customerChannels', 'customerStatuses')

// 所属管家:管理员建档必须显式选择;MANAGER/MANAGER_LEAD 默认本人(缺省由后端归属当前登录人)
const isAdmin = auth.hasRole('ADMIN')
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
  cityCode: string
  sourceCode: string
  channelCode: string
  customerStatus: string
  intentLevel: string
  spendingLevel: string
  birthday: string
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
    cityCode: '',
    sourceCode: '',
    channelCode: '',
    customerStatus: '',
    intentLevel: '',
    spendingLevel: '',
    birthday: '',
    projectIds: [],
    blockerNote: '',
    remark: '',
    firstAddedAt: '',
  }
}

const form = reactive<FormState>(emptyForm())
const loading = ref(false)
const submitting = ref(false)
const version = ref(0)
let idempotencyKey = ''

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return '该客户已被他人修改，请关闭后重新打开编辑'
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
    void loadProjectOptions()
    if (props.mode === 'create') {
      Object.assign(form, emptyForm())
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
      Object.assign(form, {
        displayName: detail.displayName ?? '',
        nickname: detail.nickname ?? '',
        remarkName: detail.remarkName ?? '',
        mobile: '',
        cityCode: detail.cityCode ?? '',
        sourceCode: detail.sourceCode ?? '',
        channelCode: detail.channelCode ?? '',
        customerStatus: detail.customerStatus ?? '',
        intentLevel: detail.intentLevel ?? '',
        spendingLevel: detail.spendingLevel ?? '',
        birthday: detail.birthday ?? '',
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

/** 空字符串不上送,可空列保持 NULL(尤其 mobile 不能写成空串) */
function buildPayload(): CreateCustomerRequest {
  const payload: Record<string, string | number | number[]> = {}
  for (const [key, value] of Object.entries(form)) {
    if (key === 'projectIds') continue
    if (value) payload[key] = value as string
  }
  payload.projectIds = [...form.projectIds]
  return payload as CreateCustomerRequest
}

async function submit() {
  if (!form.displayName && !form.nickname) {
    ElMessage.warning('昵称与称呼至少填写其一')
    return
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
            <el-input
              v-model="form.mobile"
              :placeholder="mode === 'edit' ? '留空则不修改，页面仅脱敏展示' : '选填；系统加密保存，页面仅脱敏展示'"
            />
          </el-form-item>
          <el-form-item label="备注名">
            <el-input v-model="form.remarkName" />
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
            <el-select v-model="form.sourceCode" clearable>
              <el-option
                v-for="option in dicts.optionsWithHistory('customerSources', form.sourceCode)"
                :key="option.code"
                :label="option.label"
                :value="option.code"
                :disabled="option.disabled"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="客户渠道">
            <el-select v-model="form.channelCode" clearable>
              <el-option
                v-for="option in dicts.optionsWithHistory('customerChannels', form.channelCode)"
                :key="option.code"
                :label="option.label"
                :value="option.code"
                :disabled="option.disabled"
              />
            </el-select>
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

.owner-select {
  width: 100%;
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
