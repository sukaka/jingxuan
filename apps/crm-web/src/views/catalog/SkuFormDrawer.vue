<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Institution, PackageVersion, ProductPackage, Sku } from '../../api/catalog'
import { createSku, listPackageVersions, updateSku } from '../../api/catalog'
import { catalogErrorText, selectionModeLabel, versionStatusLabel } from './catalogUi'
import { yuanToFen } from '../../utils/money'

const props = defineProps<{
  modelValue: boolean
  packages: ProductPackage[]
  institutions: Institution[]
  presetPackageId?: number | null
  presetVersionId?: number | null
  sku?: Sku | null
  initialInstitutionIds?: number[]
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()

const form = reactive({
  skuCode: '',
  packageId: undefined as number | undefined,
  packageVersionId: undefined as number | undefined,
  specName: '',
  salePriceYuan: 0,
  institutionIds: [] as number[],
})
const versions = ref<PackageVersion[]>([])
const versionsLoading = ref(false)
const saving = ref(false)

watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) return
    Object.assign(form, {
      skuCode: props.sku?.skuCode ?? '',
      packageId: props.sku?.packageId ?? props.presetPackageId ?? props.packages[0]?.id,
      packageVersionId: props.sku?.packageVersionId ?? props.presetVersionId,
      specName: props.sku?.specName ?? '',
      salePriceYuan: props.sku ? props.sku.salePriceFen / 100 : 0,
      institutionIds: [...(props.initialInstitutionIds ?? [])],
    })
    await loadVersions()
  },
)

async function loadVersions() {
  if (!form.packageId) {
    versions.value = []
    return
  }
  versionsLoading.value = true
  try {
    versions.value = await listPackageVersions(form.packageId)
    if (!props.sku && form.packageVersionId && !versions.value.some((item) => item.id === form.packageVersionId && item.status !== 'ARCHIVED')) {
      form.packageVersionId = undefined
    }
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  } finally {
    versionsLoading.value = false
  }
}

watch(() => form.packageId, loadVersions)

async function save() {
  if (!form.skuCode.trim() || !form.packageVersionId || !form.specName.trim() || form.salePriceYuan < 0) {
    ElMessage.warning('请填写 SKU 编码、版本、规格和非负售价')
    return
  }
  saving.value = true
  try {
    const body = {
      specName: form.specName,
      salePriceFen: yuanToFen(form.salePriceYuan),
      institutionIds: form.institutionIds,
    }
    if (props.sku) {
      await updateSku(props.sku.id, { ...body, version: props.sku.version })
    } else {
      await createSku({ ...body, skuCode: form.skuCode, packageVersionId: form.packageVersionId })
    }
    ElMessage.success(props.sku ? 'SKU 已保存' : 'SKU 已创建为停用状态，请检查配置后上架')
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    ElMessage.error(catalogErrorText(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="sku ? `编辑 ${sku.skuCode}` : '创建 SKU'"
    size="min(640px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="新 SKU 默认停用。上架前后端会复核版本、权益规则与机构；选 N 项模板不会被误发权益"
      class="drawer-tip"
    />
    <el-form label-position="top">
      <el-form-item label="SKU 编码" required>
        <el-input v-model="form.skuCode" :disabled="Boolean(sku)" placeholder="例如 SKU-WG-001-08" />
      </el-form-item>
      <div class="catalog-form-grid">
        <el-form-item label="所属套餐" required>
          <el-select v-model="form.packageId" :disabled="Boolean(sku)" filterable class="full-width">
            <el-option v-for="item in packages" :key="item.id" :label="item.packageName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售版本" required>
          <el-select v-model="form.packageVersionId" :disabled="Boolean(sku)" :loading="versionsLoading" class="full-width">
            <el-option v-for="item in versions.filter((v) => v.status !== 'ARCHIVED')" :key="item.id" :label="`V${item.versionNo} · ${selectionModeLabel(item.selectionMode)} · ${versionStatusLabel(item.status)}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售规格" required>
          <el-input v-model="form.specName" placeholder="3次 / 6次 / 选3项" />
        </el-form-item>
        <el-form-item label="售价（元）" required>
          <el-input-number v-model="form.salePriceYuan" :min="0" :precision="2" :step="100" class="full-width" />
        </el-form-item>
      </div>
      <el-form-item label="适用合作机构">
        <el-select v-model="form.institutionIds" multiple filterable collapse-tags collapse-tags-tooltip class="full-width" placeholder="选择实际可履约机构">
          <el-option v-for="item in institutions" :key="item.id" :label="`${item.fullName}（${item.cityCode || '未设城市'}）`" :value="item.id" />
        </el-select>
        <div v-if="form.institutionIds.length === 0" class="field-warning">未配置机构：可保存模板，但不能上架。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.drawer-tip { margin-bottom: 18px; }
.field-warning { color: #ad752e; font-size: 11px; line-height: 1.7; margin-top: 6px; }
</style>
