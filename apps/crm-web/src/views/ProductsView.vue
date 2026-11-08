<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { PackageVersion, PackageVersionSnapshot, ProductPackage, Project, Sku } from '../api/catalog'
import {
  activatePackageVersion,
  archivePackageVersion,
  createPackage,
  createProject,
  getPackageVersionSnapshot,
  listPackages,
  listPackageVersions,
  listProjects,
  listSkus,
  updatePackage,
  updateProject,
} from '../api/catalog'
import { useAuthStore } from '../stores/auth'
import { fenToYuan } from '../utils/money'
import PackageSnapshotDrawer from './catalog/PackageSnapshotDrawer.vue'
import VersionFormDrawer from './catalog/VersionFormDrawer.vue'
import StatusPill from './customer/StatusPill.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { catalogErrorText, skuStatusLabel } from './catalog/catalogUi'
import './customer/customer-ui.css'
import './catalog/catalog.css'

const router = useRouter()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('product:write'))
const packages = ref<ProductPackage[]>([])
const projects = ref<Project[]>([])
const versions = ref<PackageVersion[]>([])
const salesSpecs = ref<Sku[]>([])
const salesSpecsLoaded = ref(false)
const selectedPackageId = ref<number | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const specsByPackage = computed(() => {
  const grouped = new Map<number, Sku[]>()
  for (const sku of salesSpecs.value) {
    if (sku.packageId === null) continue
    grouped.set(sku.packageId, [...(grouped.get(sku.packageId) ?? []), sku])
  }
  return grouped
})

function packageSpecs(packageId: number): Sku[] {
  return specsByPackage.value.get(packageId) ?? []
}

async function loadSalesSpecs(): Promise<Sku[]> {
  const first = await listSkus({ page: 1, size: 100 })
  const remainingPages = Math.ceil(first.total / first.size) - 1
  const rest = await Promise.all(Array.from({ length: Math.max(0, remainingPages) },
    (_, index) => listSkus({ page: index + 2, size: first.size })))
  return [...first.items, ...rest.flatMap((result) => result.items)]
}

async function loadPackages() {
  loading.value = true
  errorMessage.value = ''
  salesSpecsLoaded.value = false
  try {
    const [result, skuItems] = await Promise.all([listPackages({ page: 1, size: 100 }), loadSalesSpecs()])
    packages.value = result.items
    salesSpecs.value = skuItems
    salesSpecsLoaded.value = true
    if (!selectedPackageId.value && result.items.length) selectedPackageId.value = result.items[0]!.id
    if (selectedPackageId.value) await loadVersions()
  } catch (error) {
    errorMessage.value = catalogErrorText(error)
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  if (!selectedPackageId.value) {
    versions.value = []
    return
  }
  try {
    versions.value = await listPackageVersions(selectedPackageId.value)
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

async function selectPackage(id: number) {
  selectedPackageId.value = id
  await loadVersions()
}

async function loadProjects() {
  try {
    const result = await listProjects({ page: 1, size: 100 })
    projects.value = result.items
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

const packageDialog = ref(false)
const packageSaving = ref(false)
const packageForm = reactive({ id: 0, packageCode: '', packageName: '', status: 'ACTIVE', version: 0 })

function openPackageDialog(item?: ProductPackage) {
  Object.assign(packageForm, item
    ? { id: item.id, packageCode: item.packageCode, packageName: item.packageName, status: item.status, version: item.version }
    : { id: 0, packageCode: '', packageName: '', status: 'ACTIVE', version: 0 })
  packageDialog.value = true
}

async function savePackage() {
  if (!packageForm.packageCode.trim() || !packageForm.packageName.trim()) {
    ElMessage.warning('请填写套餐编码与标准名称')
    return
  }
  packageSaving.value = true
  try {
    const saved = packageForm.id
      ? await updatePackage(packageForm.id, { packageName: packageForm.packageName, status: packageForm.status, version: packageForm.version })
      : await createPackage({ packageCode: packageForm.packageCode, packageName: packageForm.packageName })
    packageDialog.value = false
    selectedPackageId.value = saved.id
    await loadPackages()
    ElMessage.success(packageForm.id ? '套餐已更新' : '套餐已创建，请继续配置版本')
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  } finally {
    packageSaving.value = false
  }
}

const projectDialog = ref(false)
const projectSaving = ref(false)
const projectForm = reactive({ id: 0, projectCode: '', projectName: '', categoryCode: '', listPriceYuan: 0, status: 'ACTIVE', version: 0 })

function openProjectDialog(item?: Project) {
  Object.assign(projectForm, item
    ? { id: item.id, projectCode: item.projectCode, projectName: item.projectName, categoryCode: item.categoryCode ?? '', listPriceYuan: item.listPriceFen / 100, status: item.status, version: item.version }
    : { id: 0, projectCode: '', projectName: '', categoryCode: '', listPriceYuan: 0, status: 'ACTIVE', version: 0 })
  projectDialog.value = true
}

async function saveProject() {
  if (!projectForm.projectCode.trim() || !projectForm.projectName.trim() || projectForm.listPriceYuan < 0) {
    ElMessage.warning('请填写项目编码、名称与非负单价')
    return
  }
  projectSaving.value = true
  try {
    const listPriceFen = Math.round(projectForm.listPriceYuan * 100)
    if (projectForm.id) {
      await updateProject(projectForm.id, { projectName: projectForm.projectName, categoryCode: projectForm.categoryCode || undefined, listPriceFen, status: projectForm.status, version: projectForm.version })
    } else {
      await createProject({ projectCode: projectForm.projectCode, projectName: projectForm.projectName, categoryCode: projectForm.categoryCode || undefined, listPriceFen })
    }
    projectDialog.value = false
    await loadProjects()
    ElMessage.success('子项目已保存')
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  } finally {
    projectSaving.value = false
  }
}

const versionFormVisible = ref(false)
const versionForEdit = ref<PackageVersionSnapshot | null>(null)
const snapshotVisible = ref(false)
const snapshot = ref<PackageVersionSnapshot | null>(null)

function openVersionCreate() {
  versionForEdit.value = null
  versionFormVisible.value = true
}

async function openVersionEdit(item: PackageVersion) {
  try {
    versionForEdit.value = await getPackageVersionSnapshot(item.id)
    versionFormVisible.value = true
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

async function openSnapshot(id: number) {
  try {
    snapshot.value = await getPackageVersionSnapshot(id)
    snapshotVisible.value = true
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

async function openPackageDetails(item: ProductPackage) {
  try {
    const [version] = await listPackageVersions(item.id)
    if (version) await openSnapshot(version.id)
    else ElMessage.info('当前套餐暂无版本，请先创建套餐规格')
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  }
}

async function handleVersionSaved(saved: PackageVersionSnapshot) {
  await loadVersions()
  snapshot.value = saved
  snapshotVisible.value = true
}

async function activateVersion(item: PackageVersion) {
  try {
    await ElMessageBox.confirm('激活后该版本配置将冻结，确认激活？', '激活套餐版本', { type: 'warning' })
    await activatePackageVersion(item.id, item.version)
    await loadVersions()
    ElMessage.success('版本已激活')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(catalogErrorText(error))
  }
}

async function archiveVersion(item: PackageVersion) {
  try {
    await ElMessageBox.confirm('归档后关联 SKU 将下架，历史快照仍保留。确认归档？', '归档套餐版本', { type: 'warning' })
    await archivePackageVersion(item.id, item.version)
    await loadVersions()
    ElMessage.success('版本已归档')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(catalogErrorText(error))
  }
}

function createSku(versionId?: number) {
  snapshotVisible.value = false
  void router.push({ path: '/skus', query: { create: '1', packageId: selectedPackageId.value ?? undefined, versionId } })
}

onMounted(() => {
  void loadPackages()
  void loadProjects()
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="产品与套餐管理" description="套餐和子项目均可维护；已有销售的套餐继续使用版本化/下架机制">
      <template #actions><el-button v-if="canWrite" @click="openProjectDialog()">＋ 新增子项目</el-button><el-button v-if="canWrite" type="primary" @click="openVersionCreate">＋ 新增套餐规格</el-button></template>
    </PageHeader>
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
    <PageCard title="组合套餐 / 销售规格" subtitle="直接展示 SKU 规格与售价；需要时再查看版本机构、权益规则和冻结状态">
      <template #actions><el-button v-if="canWrite" @click="openPackageDialog()">新增套餐</el-button></template>
      <div class="proto-card-body">
        <app-table v-loading="loading" :data="packages" @row-click="(row: ProductPackage) => selectPackage(row.id)">
          <el-table-column label="套餐" min-width="180"><template #default="{row}"><strong>{{ row.packageName }}</strong><div class="proto-subcell">{{ row.packageCode }}</div></template></el-table-column>
          <el-table-column label="销售规格 / 售价" min-width="250">
            <template #default="{row}">
              <div v-for="sku in packageSpecs(row.id)" :key="sku.id" class="package-spec">
                <div class="package-spec-line"><strong>{{ sku.specName }}</strong><span class="package-price">¥{{ fenToYuan(sku.salePriceFen) }}</span></div>
                <div class="proto-subcell">{{ sku.selectionMode === 'CHOOSE_N' ? '从候选子项目中选择，不等于使用次数' : skuStatusLabel(sku.status) }}</div>
              </div>
              <span v-if="salesSpecsLoaded && packageSpecs(row.id).length === 0" class="proto-subcell">暂未配置销售规格</span>
              <span v-else-if="!salesSpecsLoaded" class="proto-subcell">销售规格加载失败</span>
            </template>
          </el-table-column>
          <el-table-column label="参与机构" min-width="140"><template #default="{row}"><el-button text @click.stop="openPackageDetails(row)">查看版本机构</el-button></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{row}"><StatusPill :text="row.status==='ACTIVE'?'上架中':'已下架'" :tone="row.status==='ACTIVE'?'success':'dark'" /></template></el-table-column>
          <el-table-column label="操作" width="310" fixed="right"><template #default="{row}"><el-button text @click.stop="openPackageDialog(row)">修改</el-button><el-button v-if="canWrite && versions.length && row.id===selectedPackageId && versions[0].status==='DRAFT'" text @click.stop="openVersionEdit(versions[0])">编辑版本</el-button><el-button v-if="canWrite && versions.length && row.id===selectedPackageId && versions[0].status==='DRAFT'" text @click.stop="activateVersion(versions[0])">激活</el-button><el-button v-if="canWrite" text @click.stop="openVersionCreate">新增规格</el-button><el-button v-if="canWrite && versions.length && row.id===selectedPackageId && versions[0].status==='ACTIVE'" text @click.stop="archiveVersion(versions[0])">下架</el-button></template></el-table-column>
        </app-table>
      </div>
    </PageCard>
    <PageCard title="产品子项目库" subtitle="套餐权益和机构服务统一引用标准项目" style="margin-top:18px">
      <template #actions><el-button v-if="canWrite" @click="openProjectDialog()">新增子项目</el-button></template>
      <div class="proto-card-body"><app-table :data="projects"><el-table-column prop="categoryCode" label="分类" width="110" /><el-table-column label="项目" min-width="180"><template #default="{row}"><strong>{{ row.projectName }}</strong><div class="proto-subcell">{{ row.projectCode }}</div></template></el-table-column><el-table-column label="标准单价" width="120" align="right"><template #default="{row}">{{ fenToYuan(row.listPriceFen) }}</template></el-table-column><el-table-column label="参与机构" min-width="140">按套餐版本配置</el-table-column><el-table-column label="状态" width="90"><template #default="{row}"><StatusPill :text="row.status==='ACTIVE'?'启用':'停用'" :tone="row.status==='ACTIVE'?'success':'dark'" /></template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button v-if="canWrite" text @click="openProjectDialog(row)">修改</el-button></template></el-table-column></app-table></div>
    </PageCard>

    <el-dialog v-model="packageDialog" :title="packageForm.id ? '编辑套餐基本信息' : '新建套餐'" width="480px">
      <el-form label-position="top">
        <el-form-item label="套餐编码" required><el-input v-model="packageForm.packageCode" :disabled="packageForm.id !== 0" placeholder="例如 PKG-WG-002" /></el-form-item>
        <el-form-item label="套餐标准名称" required><el-input v-model="packageForm.packageName" placeholder="例如 光子水光囤货卡" /></el-form-item>
        <el-form-item v-if="packageForm.id" label="状态">
          <el-radio-group v-model="packageForm.status"><el-radio-button value="ACTIVE">启用</el-radio-button><el-radio-button value="INACTIVE">停用</el-radio-button></el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="packageDialog = false">取消</el-button><el-button type="primary" :loading="packageSaving" @click="savePackage">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="projectDialog" :title="projectForm.id ? '编辑子项目' : '新增子项目'" width="520px">
      <el-form label-position="top">
        <el-form-item label="项目编码" required><el-input v-model="projectForm.projectCode" :disabled="projectForm.id !== 0" placeholder="例如 PRJ-WATER" /></el-form-item>
        <el-form-item label="标准项目名称" required><el-input v-model="projectForm.projectName" placeholder="例如 基础水光" /></el-form-item>
        <el-form-item label="项目分类"><el-input v-model="projectForm.categoryCode" placeholder="例如 水光" /></el-form-item>
        <el-form-item label="参考单价（元）" required><el-input-number v-model="projectForm.listPriceYuan" :min="0" :precision="2" class="full-width" /></el-form-item>
        <el-form-item v-if="projectForm.id" label="状态">
          <el-radio-group v-model="projectForm.status"><el-radio-button value="ACTIVE">启用</el-radio-button><el-radio-button value="INACTIVE">停用</el-radio-button></el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="projectDialog = false">取消</el-button><el-button type="primary" :loading="projectSaving" @click="saveProject">保存</el-button></template>
    </el-dialog>

    <VersionFormDrawer v-model="versionFormVisible" :package-id="selectedPackageId" :projects="projects.filter((item) => item.status === 'ACTIVE')" :snapshot="versionForEdit" @saved="handleVersionSaved" />
    <PackageSnapshotDrawer v-model="snapshotVisible" :snapshot="snapshot" @create-sku="createSku(snapshot?.id)" />
  </div>
</template>

<style scoped>
.full-width { width: 100%; }
.package-spec + .package-spec { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.package-spec-line { display: flex; align-items: center; justify-content: space-between; gap: 16px; line-height: 1.6; }
.package-price { white-space: nowrap; font-variant-numeric: tabular-nums; color: var(--el-text-color-primary); }
</style>
