<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { CascaderNode, CascaderProps } from 'element-plus'
import type { RegionNode } from '../api/region'
import { loadRegionTree } from '../api/region'

const props = withDefaults(defineProps<{
  modelValue?: string[]
  placeholder?: string
  disabled?: boolean
  clearable?: boolean
  checkStrictly?: boolean
}>(), {
  modelValue: () => [],
  placeholder: '请选择省 / 市 / 区（选填）',
  disabled: false,
  clearable: true,
  checkStrictly: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
  change: [value: string[]]
}>()

const options = ref<RegionNode[]>([])
const loading = ref(false)
const loadError = ref('')

const cascaderProps = computed<CascaderProps>(() => ({
  value: 'code',
  label: 'name',
  children: 'children',
  checkStrictly: props.checkStrictly,
  emitPath: true,
}))

const normalizedValue = computed<string[]>({
  get: () => props.modelValue ?? [],
  set: (value) => {
    const next = Array.isArray(value) ? value.map(String).filter(Boolean) : []
    emit('update:modelValue', next)
  },
})

function filterRegion(node: CascaderNode, keyword: string): boolean {
  const query = keyword.trim().replace(/[\s/,，-]+/g, '').toLowerCase()
  if (!query) return true
  const pathText = (node.pathLabels ?? [node.label])
    .join('')
    .replace(/[\s/,，-]+/g, '')
    .toLowerCase()
  return pathText.includes(query)
}

function handleChange(value: unknown) {
  const next = Array.isArray(value) ? value.map(String).filter(Boolean) : []
  emit('update:modelValue', next)
  emit('change', next)
}

onMounted(async () => {
  loading.value = true
  loadError.value = ''
  try {
    options.value = await loadRegionTree()
  } catch {
    options.value = []
    loadError.value = '行政区划加载失败，请刷新重试'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-cascader
    v-model="normalizedValue"
    :options="options"
    :props="cascaderProps"
    :placeholder="loading ? '正在加载省市区...' : (loadError || placeholder)"
    :disabled="disabled || loading"
    :clearable="clearable"
    filterable
    :filter-method="filterRegion"
    separator=" / "
    class="region-cascader"
    @change="handleChange"
  />
</template>

<style scoped>
.region-cascader {
  width: 100%;
}
</style>
