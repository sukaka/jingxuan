<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CustomerListItem } from '../api/customer'
import { formatCustomerRegion } from '../api/region'
import type { Project } from '../api/catalog'
import { listProjects } from '../api/catalog'
import type { CustomerSegment, CustomerSegmentFilters } from '../api/customerSegment'
import {
  createCustomerSegment,
  createSegmentOperationBatch,
  deleteCustomerSegment,
  listCustomerSegments,
  listCustomerSegmentMembers,
  previewCustomerSegment,
  updateCustomerSegment,
} from '../api/customerSegment'
import { useAuthStore } from '../stores/auth'
import { useDictionaries } from './customer/shared'

const auth = useAuthStore()
const dicts = useDictionaries()
const canManage = computed(() => auth.hasPermission('customer:write'))
const segments = ref<CustomerSegment[]>([])
const projects = ref<Project[]>([])
const members = ref<CustomerListItem[]>([])
const total = ref(0)
const memberTotal = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)
const memberLoading = ref(false)
const errorMessage = ref('')
const selectedId = ref<number | null>(null)
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const name = ref('')
const filters = reactive<CustomerSegmentFilters>(emptyFilters())
const tiers = ['S', 'A', 'B', 'C', 'D']

function emptyFilters(): CustomerSegmentFilters {
  return {
    cityCodes: [], tierCodes: [], attentionProjectIds: [], purchasedProjectIds: [],
    redeemedProjectIds: [], redeemedMinCount: 1,
  }
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败'
}

async function loadSegments() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCustomerSegments({ page: page.value, size: size.value, keyword: keyword.value })
    segments.value = result.items
    total.value = result.total
  } catch (error) {
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingId.value = null
  editingVersion.value = 0
  name.value = ''
  Object.assign(filters, emptyFilters())
}

function editSegment(segment: CustomerSegment) {
  editingId.value = segment.id
  editingVersion.value = segment.version
  name.value = segment.name
  Object.assign(filters, {
    cityCodes: [...segment.filters.cityCodes], tierCodes: [...segment.filters.tierCodes],
    attentionProjectIds: [...segment.filters.attentionProjectIds],
    purchasedProjectIds: [...segment.filters.purchasedProjectIds],
    redeemedProjectIds: [...segment.filters.redeemedProjectIds],
    redeemedMinCount: segment.filters.redeemedMinCount,
  })
}

async function saveSegment() {
  if (!name.value.trim()) {
    ElMessage.warning('请填写分组名称')
    return
  }
  try {
    const input = { name: name.value.trim(), filters: { ...filters } }
    if (editingId.value) {
      await updateCustomerSegment(editingId.value, { ...input, version: editingVersion.value })
    } else {
      await createCustomerSegment(input, crypto.randomUUID())
    }
    ElMessage.success('动态分组已保存')
    resetForm()
    await loadSegments()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function previewSegment(segment: CustomerSegment) {
  selectedId.value = segment.id
  memberLoading.value = true
  try {
    const result = await listCustomerSegmentMembers(segment.id, 1, 20)
    members.value = result.items
    memberTotal.value = result.total
  } catch (error) {
    members.value = []
    memberTotal.value = 0
    ElMessage.error(errorText(error))
  } finally {
    memberLoading.value = false
  }
}

async function previewForm() {
  selectedId.value = null
  memberLoading.value = true
  try {
    const result = await previewCustomerSegment({ filters: { ...filters }, page: 1, size: 20 })
    members.value = result.items
    memberTotal.value = result.total
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    memberLoading.value = false
  }
}

async function createBatch(segment: CustomerSegment) {
  try {
    const batch = await createSegmentOperationBatch(segment.id, {
      operationType: 'FOLLOWUP', version: segment.version,
    }, crypto.randomUUID())
    ElMessage.success(`操作批次 ${batch.batchNo} 已生成，共锁定 ${batch.memberCount} 位客户，尚未执行后续操作`)
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function removeSegment(segment: CustomerSegment) {
  try {
    await ElMessageBox.confirm(`确认删除动态分组“${segment.name}”？已生成的操作批次名单仍会保留。`, '删除分组')
    await deleteCustomerSegment(segment.id, segment.version)
    if (selectedId.value === segment.id) selectedId.value = null
    await loadSegments()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

function projectNames(ids: number[]): string {
  if (!ids.length) return '不限'
  return ids.map((id) => projects.value.find((item) => item.id === id)?.projectName ?? `#${id}`).join('、')
}

function tierLabel(code: string | null | undefined): string {
  if (!code) return '—'
  const hit = dicts.options('customerTiers').find((item) => item.code === code)
  return hit ? hit.label : `${code} 级`
}

function filterSummary(segment: CustomerSegment): string {
  const value = segment.filters
  const cities = value.cityCodes.length
    ? value.cityCodes.map((code) => dicts.label('cities', code)).join('、') : '不限城市'
  const tier = value.tierCodes.length ? value.tierCodes.map((code) => tierLabel(code)).join('、') : '不限等级'
  return `${cities} / ${tier} / 关注 ${projectNames(value.attentionProjectIds)} / 已购 ${projectNames(value.purchasedProjectIds)} / 净核销 ${projectNames(value.redeemedProjectIds)} ≥ ${value.redeemedMinCount}`
}

onMounted(() => {
  void dicts.load('cities', 'customerTiers')
  void loadSegments()
  void listProjects({ page: 1, size: 100 }).then((result) => {
    projects.value = result.items
  }).catch((error) => ElMessage.error(`项目选项加载失败：${errorText(error)}`))
})
</script>

<template>
  <div class="segments-view">
    <header class="segments-heading">
      <div>
        <h1>客户动态分组</h1>
        <p>按筛选条件实时匹配当前可见范围内的客户；预览名单不会生成固定批次。</p>
      </div>
      <el-button v-if="canManage" data-testid="segment-new" @click="resetForm">新建分组</el-button>
    </header>

    <el-card v-if="canManage" shadow="never" class="segment-editor">
      <template #header>{{ editingId ? `编辑分组 #${editingId}` : '新建动态分组' }}</template>
      <el-form label-width="110px">
        <el-form-item label="分组名称" required><el-input v-model="name" maxlength="128" /></el-form-item>
        <div class="filter-grid">
          <el-form-item label="城市">
            <el-select v-model="filters.cityCodes" multiple clearable filterable>
              <el-option v-for="item in dicts.options('cities')" :key="item.code" :label="item.label" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="当前等级">
            <el-select v-model="filters.tierCodes" multiple clearable>
              <el-option v-for="tier in tiers" :key="tier" :label="dicts.label('customerTiers', tier)" :value="tier" />
            </el-select>
          </el-form-item>
          <el-form-item label="关注项目">
            <el-select v-model="filters.attentionProjectIds" multiple clearable filterable>
              <el-option v-for="item in projects" :key="item.id" :label="item.projectName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="已购项目">
            <el-select v-model="filters.purchasedProjectIds" multiple clearable filterable>
              <el-option v-for="item in projects" :key="item.id" :label="item.projectName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="已核销项目">
            <el-select v-model="filters.redeemedProjectIds" multiple clearable filterable>
              <el-option v-for="item in projects" :key="item.id" :label="item.projectName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="净核销至少">
            <el-input-number v-model="filters.redeemedMinCount" :min="1" :max="10000" />
          </el-form-item>
        </div>
        <el-alert
          title="不同筛选条件需同时满足，同一条件内选择多个项目时满足任意一项即可；已购按有效购入记录计算，净核销已扣除冲正核销次数。"
          type="info" :closable="false"
        />
        <div class="editor-actions">
          <el-button @click="previewForm">实时预览</el-button>
          <el-button type="primary" @click="saveSegment">保存分组</el-button>
        </div>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="segment-toolbar">
        <el-input v-model="keyword" placeholder="搜索分组名称" clearable @keyup.enter="loadSegments" />
        <el-button :loading="loading" @click="loadSegments">查询</el-button>
      </div>
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
      <el-table v-loading="loading" :data="segments">
        <el-table-column prop="name" label="分组" min-width="150" />
        <el-table-column label="组合条件" min-width="380">
          <template #default="{ row }">{{ filterSummary(row) }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button text :data-testid="`segment-preview-${row.id}`" @click="previewSegment(row)">成员</el-button>
            <el-button v-if="canManage" text @click="editSegment(row)">编辑</el-button>
            <el-button
              v-if="canManage" text :data-testid="`segment-batch-${row.id}`"
              @click="createBatch(row)"
            >
              创建跟进批次
            </el-button>
            <el-button v-if="canManage" text type="danger" @click="removeSegment(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="loadSegments"
      />
    </el-card>

    <el-card shadow="never">
      <template #header>
        {{ selectedId ? `分组 #${selectedId} 实时成员` : '当前条件实时预览' }}（共 {{ memberTotal }} 人，仅实时查看）
      </template>
      <el-table v-loading="memberLoading" :data="members">
        <el-table-column prop="customerNo" label="客户编号" min-width="130" />
        <el-table-column label="客户" min-width="140">
          <template #default="{ row }">{{ row.displayName ?? row.nickname ?? '未命名客户' }}</template>
        </el-table-column>
        <el-table-column label="地区" min-width="150">
          <template #default="{ row }">{{ formatCustomerRegion(row, row.cityCode ? dicts.label('cities', row.cityCode) : null) }}</template>
        </el-table-column>
        <el-table-column label="等级" width="95">
          <template #default="{ row }">{{ tierLabel(row.tierCode) }}</template>
        </el-table-column>
        <el-table-column prop="ownerName" label="所属管家" min-width="110" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.segments-view { display: grid; gap: 16px; }
.segments-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.segments-heading h1 { margin: 0 0 6px; font-size: 22px; }
.segments-heading p { margin: 0; color: #667085; }
.segment-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.segment-toolbar .el-input { max-width: 300px; }
.filter-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 16px; }
.filter-grid .el-select { width: 100%; }
.editor-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.el-pagination { margin-top: 14px; }
@media (max-width: 800px) { .filter-grid { grid-template-columns: 1fr; } }
</style>
