<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  InstitutionMerchantResource,
  MerchantResourceStatus,
  MerchantResourceType,
} from '../../api/institution'
import {
  createInstitutionMerchantResource,
  listInstitutionMerchantResources,
  updateInstitutionMerchantResource,
} from '../../api/institution'
import { tradingErrorText } from '../trading/tradingUi'
import FileDownloadButton from './FileDownloadButton.vue'
import FileUploadField from './FileUploadField.vue'
import { createInstitutionKey } from './institutionUi'
import './merchant-resources.css'

const props = withDefaults(defineProps<{
  institutionId: number
  canWrite?: boolean
}>(), { canWrite: false })
const emit = defineEmits<{ changed: [] }>()

const resources = ref<InstitutionMerchantResource[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 50
const resourceType = ref<MerchantResourceType | ''>('')
const status = ref<MerchantResourceStatus | 'ALL'>(props.canWrite ? 'ALL' : 'PUBLISHED')
const loading = ref(false)
const errorMessage = ref('')
const formVisible = ref(false)
const editing = ref<InstitutionMerchantResource | null>(null)
const saving = ref(false)
const rowSaving = ref<number | null>(null)
const uploadKey = ref(0)
const form = reactive<{
  resourceType: MerchantResourceType
  title: string
  subtitle: string
  description: string
  fileId?: number
}>({ resourceType: 'DOCTOR', title: '', subtitle: '', description: '', fileId: undefined })

const titleLabel = computed(() => ({ DOCTOR: '医生姓名', EQUIPMENT: '仪器名称', CASE: '案例标题' })[form.resourceType])
const subtitleLabel = computed(() => ({ DOCTOR: '职称', EQUIPMENT: '型号', CASE: '关联项目' })[form.resourceType])
const descriptionLabel = computed(() => ({ DOCTOR: '擅长方向', EQUIPMENT: '仪器说明', CASE: '案例说明' })[form.resourceType])

function typeLabel(type: MerchantResourceType) {
  return ({ DOCTOR: '医生', EQUIPMENT: '仪器', CASE: '案例' })[type]
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listInstitutionMerchantResources({
      institutionId: props.institutionId,
      resourceType: resourceType.value || undefined,
      status: status.value,
      page: page.value,
      size: pageSize,
    })
    resources.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (error) {
    resources.value = []
    total.value = 0
    errorMessage.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function changePage(value: number) {
  page.value = value
  void load()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { resourceType: 'DOCTOR', title: '', subtitle: '', description: '', fileId: undefined })
  uploadKey.value++
  formVisible.value = true
}

function openEdit(resource: InstitutionMerchantResource) {
  editing.value = resource
  Object.assign(form, {
    resourceType: resource.resourceType,
    title: resource.title,
    subtitle: resource.subtitle || '',
    description: resource.description || '',
    fileId: resource.fileId || undefined,
  })
  uploadKey.value++
  formVisible.value = true
}

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning(`请填写${titleLabel.value}`)
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateInstitutionMerchantResource(editing.value.id, {
        version: editing.value.version,
        title: form.title.trim(),
        subtitle: form.subtitle.trim() || undefined,
        description: form.description.trim() || undefined,
        fileId: form.fileId ?? null,
        status: editing.value.status,
      }, createInstitutionKey('institution.merchant-resource.update'))
    } else {
      await createInstitutionMerchantResource(props.institutionId, {
        resourceType: form.resourceType,
        title: form.title.trim(),
        subtitle: form.subtitle.trim() || undefined,
        description: form.description.trim() || undefined,
        fileId: form.fileId,
      }, createInstitutionKey('institution.merchant-resource.create'))
    }
    ElMessage.success(editing.value ? '资料已更新' : '资料已新增')
    formVisible.value = false
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(resource: InstitutionMerchantResource) {
  rowSaving.value = resource.id
  try {
    await updateInstitutionMerchantResource(resource.id, {
      version: resource.version,
      title: resource.title,
      subtitle: resource.subtitle || undefined,
      description: resource.description || undefined,
      fileId: resource.fileId,
      status: resource.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED',
    }, createInstitutionKey('institution.merchant-resource.update'))
    ElMessage.success(resource.status === 'PUBLISHED' ? '资料已下架' : '资料已恢复展示')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    rowSaving.value = null
  }
}

watch(() => props.institutionId, () => {
  page.value = 1
  void load()
})
onMounted(() => { void load() })
</script>

<template>
  <section class="merchant-resources" aria-label="机构展示资料">
    <div class="merchant-resource-heading">
      <div>
        <h3>医生、仪器与案例</h3>
        <p>{{ canWrite ? '维护真实合作资料；下架后保留历史，不再出现在展示页' : '以下内容来自机构已登记且当前展示的资料' }}</p>
      </div>
      <el-button v-if="canWrite" type="primary" data-testid="merchant-resource-create" @click="openCreate">新增资料</el-button>
    </div>

    <div class="merchant-resource-filters">
      <el-radio-group v-model="resourceType" aria-label="资料类型" @change="search">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="DOCTOR">医生</el-radio-button>
        <el-radio-button value="EQUIPMENT">仪器</el-radio-button>
        <el-radio-button value="CASE">案例</el-radio-button>
      </el-radio-group>
      <el-select v-if="canWrite" v-model="status" class="merchant-resource-status-filter" aria-label="展示状态" @change="search">
        <el-option label="全部状态" value="ALL" />
        <el-option label="当前展示" value="PUBLISHED" />
        <el-option label="已下架" value="OFFLINE" />
      </el-select>
      <el-button text :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false">
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>
    <div v-loading="loading" class="merchant-resource-list">
      <article v-for="resource in resources" :key="resource.id" class="merchant-resource-item" :class="{ 'is-offline': resource.status === 'OFFLINE' }">
        <div class="merchant-resource-kind">
          <span class="merchant-resource-type" :class="`is-${resource.resourceType.toLowerCase()}`">{{ typeLabel(resource.resourceType) }}</span>
          <span v-if="resource.status === 'OFFLINE'" class="merchant-resource-offline">已下架</span>
        </div>
        <div class="merchant-resource-copy">
          <strong>{{ resource.title }}</strong>
          <span v-if="resource.subtitle" class="merchant-resource-subtitle">{{ resource.subtitle }}</span>
          <p>{{ resource.description || '暂无补充说明' }}</p>
        </div>
        <div class="merchant-resource-actions">
          <FileDownloadButton v-if="resource.fileId" :file-id="resource.fileId" />
          <el-button v-if="canWrite" text :data-testid="`merchant-resource-edit-${resource.id}`" @click="openEdit(resource)">编辑</el-button>
          <el-button
            v-if="canWrite"
            text
            :type="resource.status === 'PUBLISHED' ? 'warning' : 'success'"
            :loading="rowSaving === resource.id"
            :data-testid="`merchant-resource-toggle-${resource.id}`"
            @click="toggleStatus(resource)"
          >
            {{ resource.status === 'PUBLISHED' ? '下架' : '恢复展示' }}
          </el-button>
        </div>
      </article>
      <div v-if="!resources.length && !loading && !errorMessage" class="merchant-resource-empty">
        {{ resourceType ? `暂无${typeLabel(resourceType)}资料` : '暂无已登记的展示资料' }}
      </div>
    </div>
    <el-pagination
      v-if="total > pageSize"
      :current-page="page"
      :page-size="pageSize"
      :total="total"
      layout="prev, pager, next"
      class="merchant-resource-pager"
      @current-change="changePage"
    />

    <el-dialog v-model="formVisible" :title="editing ? '编辑机构资料' : '新增机构资料'" width="min(560px, 94vw)" append-to-body>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="资料类型" required>
          <el-select v-model="form.resourceType" :disabled="Boolean(editing)" style="width: 100%">
            <el-option label="医生" value="DOCTOR" />
            <el-option label="仪器" value="EQUIPMENT" />
            <el-option label="案例" value="CASE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="titleLabel" required>
          <el-input v-model="form.title" maxlength="120" show-word-limit data-testid="merchant-resource-title" />
        </el-form-item>
        <el-form-item :label="subtitleLabel">
          <el-input v-model="form.subtitle" maxlength="200" data-testid="merchant-resource-subtitle" />
        </el-form-item>
        <el-form-item :label="descriptionLabel">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="附件（可选）">
          <FileUploadField
            :key="uploadKey"
            v-model="form.fileId"
            :institution-id="institutionId"
            classification="MERCHANT_RESOURCE"
            label="上传 PDF、JPEG 或 PNG；扫描通过后才能保存引用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="merchant-resource-save" @click="save">保存资料</el-button>
      </template>
    </el-dialog>
  </section>
</template>
