<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  PackageProjectInput,
  PackageSelectionGroupInput,
  PackageVersionSnapshot,
  Project,
} from '../../api/catalog'
import { createPackageVersion, replaceVersionProjects, updatePackageVersion } from '../../api/catalog'
import { catalogErrorText, optionGroupLabel, selectionModeLabel } from './catalogUi'
import { yuanToFen } from '../../utils/money'

const props = defineProps<{
  modelValue: boolean
  packageId: number | null
  projects: Project[]
  snapshot: PackageVersionSnapshot | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [snapshot: PackageVersionSnapshot]
}>()

interface LineState {
  projectId: number | undefined
  grantCount: number
  selectionWeight: number
  allocationYuan: number
  optionGroup: string
  referenceYuan: number
}

type GroupState = PackageSelectionGroupInput

const form = reactive({
  selectionMode: 'FIXED',
  choiceCount: 0,
  selectionRules: '',
  items: [] as LineState[],
  groups: [] as GroupState[],
})
const saving = ref(false)

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    if (props.snapshot) {
      form.selectionMode = props.snapshot.selectionMode
      form.choiceCount = props.snapshot.choiceCount
      form.selectionRules = props.snapshot.selectionRules ?? ''
      form.groups = (props.snapshot.groups ?? []).map((group) => ({
        groupCode: group.groupCode,
        groupName: group.groupName,
        minSelections: group.minSelections,
        maxSelections: group.maxSelections,
        sortOrder: group.sortOrder,
      }))
      form.items = props.snapshot.projects.map((item) => ({
        projectId: item.projectId,
        grantCount: item.grantCount,
        selectionWeight: item.selectionWeight,
        allocationYuan: (item.allocationPriceFen ?? 0) / 100,
        optionGroup: item.optionGroup || 'MAIN',
        referenceYuan: (item.referencePriceFen ?? item.listPriceFen ?? 0) / 100,
      }))
    } else {
      form.selectionMode = 'FIXED'
      form.choiceCount = 0
      form.selectionRules = ''
      form.groups = []
      form.items = [newLine()]
    }
  },
)

function newLine(groupCode?: string): LineState {
  return {
    projectId: undefined,
    grantCount: 1,
    selectionWeight: 1,
    allocationYuan: 0,
    optionGroup: groupCode ?? 'MAIN',
    referenceYuan: 0,
  }
}

function handleModeChange() {
  if (form.selectionMode === 'CHOOSE_N' && form.groups.length === 0) {
    addGroup()
    const groupCode = form.groups[0]?.groupCode
    if (groupCode) form.items.forEach((item) => { item.optionGroup = groupCode })
  }
  if (form.selectionMode !== 'CHOOSE_N') {
    form.groups = []
    form.items.forEach((item) => { item.optionGroup = 'MAIN' })
  }
}

function addGroup() {
  const nextCode = form.groups.length < 26 ? String.fromCharCode(65 + form.groups.length) : `G${form.groups.length + 1}`
  let groupCode = nextCode
  let suffix = 2
  while (form.groups.some((group) => group.groupCode === groupCode)) groupCode = `${nextCode}${suffix++}`
  form.groups.push({
    groupCode,
    groupName: `${groupCode} 组`,
    minSelections: 1,
    maxSelections: 1,
    sortOrder: form.groups.length + 1,
  })
  if (form.groups.length === 1 && form.selectionMode === 'CHOOSE_N') {
    form.items.forEach((item) => {
      if (!item.optionGroup || item.optionGroup === 'MAIN') item.optionGroup = groupCode
    })
  }
}

function removeGroup(index: number) {
  const removed = form.groups[index]
  if (!removed) return
  form.groups.splice(index, 1)
  form.items = form.items.filter((item) => item.optionGroup !== removed.groupCode)
}

function addLine() {
  if (form.selectionMode === 'CHOOSE_N' && form.groups.length === 0) {
    ElMessage.warning('请先添加选项组')
    return
  }
  form.items.push(newLine(form.selectionMode === 'CHOOSE_N' ? form.groups[0]?.groupCode : 'MAIN'))
}

function removeLine(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

function payload(): PackageProjectInput[] {
  return form.items.map((item) => ({
    projectId: item.projectId!,
    grantCount: item.grantCount,
    selectionWeight: item.selectionWeight,
    allocationPriceFen: yuanToFen(item.allocationYuan),
    optionGroup: item.optionGroup || 'MAIN',
    referencePriceFen: yuanToFen(item.referenceYuan),
  }))
}

function groupPayload(): PackageSelectionGroupInput[] {
  return form.groups.map((group, index) => ({
    groupCode: group.groupCode.trim(),
    groupName: group.groupName.trim(),
    minSelections: group.minSelections,
    maxSelections: group.maxSelections,
    sortOrder: index + 1,
  }))
}

function effectiveChoiceCount(): number {
  if (form.selectionMode === 'CHOOSE_N' && form.groups.length) {
    return form.groups.reduce((total, group) => total + (group.maxSelections || 0), 0)
  }
  return form.choiceCount
}

function validateGroups(): string {
  if (form.selectionMode !== 'CHOOSE_N' || form.groups.length === 0) return ''
  const codes = new Set<string>()
  const projectIds = new Set<number>()
  for (const group of form.groups) {
    const code = group.groupCode.trim()
    if (!/^[A-Za-z0-9_-]{1,48}$/.test(code) || !group.groupName.trim()) return '请完整填写有效的组编码和组名称'
    if (codes.has(code.toLowerCase())) return '选项组编码不能重复'
    codes.add(code.toLowerCase())
    if (!Number.isInteger(group.minSelections) || !Number.isInteger(group.maxSelections)
      || group.minSelections < 1 || group.maxSelections < group.minSelections) {
      return `${group.groupName}的最少/最多选择数无效`
    }
    const count = form.items.filter((item) => item.optionGroup === group.groupCode).length
    if (count < group.maxSelections) return `${group.groupName}至少配置 ${group.maxSelections} 个候选子项目`
  }
  for (const item of form.items) {
    if (!codes.has(item.optionGroup.toLowerCase())) return '每个子项目都必须属于已声明的选项组'
    if (item.projectId && projectIds.has(item.projectId)) return '分组套餐中同一子项目不能重复配置'
    if (item.projectId) projectIds.add(item.projectId)
  }
  return ''
}

async function save() {
  if (!props.packageId) return
  if (form.selectionMode === 'USE_N') {
    ElMessage.warning('USE_N 选择模式待 Q01 确认，不能创建或发放权益，请改用固定组合或选择子项目')
    return
  }
  const choiceCount = effectiveChoiceCount()
  if (form.selectionMode !== 'FIXED' && choiceCount <= 0) {
    ElMessage.warning('选 N 项或 N 次时，N 必须大于 0')
    return
  }
  if (form.items.length === 0 || form.items.some((item) => !item.projectId || !Number.isInteger(item.grantCount) || item.grantCount <= 0
    || !Number.isFinite(item.referenceYuan) || item.referenceYuan < 0)) {
    ElMessage.warning('请完整选择子项目，填写正整数次数和非负单品参考价')
    return
  }
  const groupIssue = validateGroups()
  if (groupIssue) {
    ElMessage.warning(groupIssue)
    return
  }
  saving.value = true
  try {
    let saved: PackageVersionSnapshot
    if (props.snapshot) {
      await updatePackageVersion(props.snapshot.id, {
        selectionMode: form.selectionMode,
        choiceCount,
        selectionRules: form.selectionRules || undefined,
        version: props.snapshot.version,
      })
      saved = await replaceVersionProjects(props.snapshot.id, {
        version: props.snapshot.version + 1,
        projects: payload(),
        groups: groupPayload(),
      })
    } else {
      saved = await createPackageVersion(props.packageId, {
        selectionMode: form.selectionMode,
        choiceCount,
        selectionRules: form.selectionRules || undefined,
        projects: payload(),
        groups: groupPayload(),
      })
    }
    ElMessage.success(props.snapshot ? '草稿已更新' : '新版本草稿已创建')
    emit('update:modelValue', false)
    emit('saved', saved)
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
    :title="snapshot ? `编辑 V${snapshot.versionNo} 草稿` : '创建套餐版本草稿'"
    size="min(840px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      :title="form.selectionMode === 'CHOOSE_N' && form.groups.length
        ? '按组配置最少/最多选择数；激活后冻结。参考价只用于展示，套餐 SKU 售价独立'
        : '草稿可编辑；旧版无分组 CHOOSE_N 仍按 Q01 待确认模板处理，不能上架发放权益'"
      class="drawer-tip"
    />
    <el-alert
      v-if="form.selectionMode === 'USE_N'"
      type="warning"
      :closable="false"
      title="Q01 选择规则未确认：USE_N 仅作历史模板查看，不能激活、保存为可售规格或发放客户权益；请改用「固定组合」或「选择子项目」。"
      class="drawer-tip"
      data-testid="version-use-n-warning"
    />
    <el-form label-position="top">
      <div class="catalog-form-grid">
        <el-form-item label="选择模式" required>
          <el-select v-model="form.selectionMode" class="full-width" data-testid="version-selection-mode" @change="handleModeChange">
            <el-option
              v-for="mode in ['FIXED', 'CHOOSE_N', 'USE_N']"
              :key="mode"
              :label="selectionModeLabel(mode)"
              :value="mode"
              :disabled="mode === 'USE_N'"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.selectionMode === 'CHOOSE_N' && form.groups.length" label="兼容总选项数（由组规则计算）">
          <el-input-number :model-value="effectiveChoiceCount()" disabled class="full-width" />
        </el-form-item>
        <el-form-item v-else :label="form.selectionMode === 'CHOOSE_N' ? '可选项目数 N' : '使用次数 N'" required>
          <el-input-number v-model="form.choiceCount" :min="form.selectionMode === 'FIXED' ? 0 : 1" :max="1000" class="full-width" />
        </el-form-item>
        <el-form-item label="选项与重复规则说明" class="catalog-form-full">
          <el-input
            v-model="form.selectionRules"
            type="textarea"
            :rows="2"
            maxlength="4000"
            show-word-limit
            placeholder="结构化组规则负责实际校验；此处仅记录补充说明"
          />
        </el-form-item>
      </div>

      <template v-if="form.selectionMode === 'CHOOSE_N'">
        <div class="section-head">
          <strong>选项组规则</strong>
          <el-button size="small" data-testid="add-selection-group" @click="addGroup">添加选项组</el-button>
        </div>
        <el-alert v-if="form.groups.length === 0" type="warning" :closable="false" title="无结构化组时保留旧版模板行为，不能上架发放权益" class="group-tip" />
        <div v-for="(group, groupIndex) in form.groups" :key="groupIndex" class="selection-group-editor">
          <div class="group-editor-head">
            <strong>选项组 {{ groupIndex + 1 }}</strong>
            <el-button text type="danger" size="small" @click="removeGroup(groupIndex)">移除组及组内行</el-button>
          </div>
          <div class="group-editor-grid">
            <el-form-item label="组编码" required><el-input v-model="group.groupCode" maxlength="48" placeholder="A / B" /></el-form-item>
            <el-form-item label="组名称" required><el-input v-model="group.groupName" maxlength="96" placeholder="焕肤组" /></el-form-item>
            <el-form-item label="最少选择" required><el-input-number v-model="group.minSelections" :min="1" :max="1000" class="full-width" /></el-form-item>
            <el-form-item label="最多选择" required><el-input-number v-model="group.maxSelections" :min="1" :max="1000" class="full-width" /></el-form-item>
          </div>
        </div>
      </template>

      <div class="section-head">
        <strong>子项目与权益模板</strong>
        <el-button size="small" @click="addLine">添加子项目</el-button>
      </div>
      <div v-for="(item, index) in form.items" :key="index" class="catalog-line-item">
        <div class="catalog-line-head">
          <span>子项目 {{ index + 1 }}</span>
          <el-button text type="danger" size="small" :disabled="form.items.length === 1" @click="removeLine(index)">移除</el-button>
        </div>
        <div class="catalog-form-grid">
          <el-form-item label="子项目" required>
            <el-select v-model="item.projectId" filterable class="full-width" placeholder="选择标准子项目">
              <el-option v-for="project in projects" :key="project.id" :label="`${project.projectName}（${project.projectCode}）`" :value="project.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="发放次数" required>
            <el-input-number v-model="item.grantCount" :min="1" :max="1000" class="full-width" />
          </el-form-item>
          <el-form-item label="选项组">
            <el-select v-if="form.selectionMode === 'CHOOSE_N' && form.groups.length" v-model="item.optionGroup" class="full-width">
              <el-option v-for="group in form.groups" :key="group.groupCode" :label="`${group.groupName}（${group.groupCode}）`" :value="group.groupCode" />
            </el-select>
            <el-input
              v-else
              :model-value="optionGroupLabel(item.optionGroup)"
              placeholder="主选项组"
              @update:model-value="item.optionGroup = $event === '主选项组' ? 'MAIN' : $event"
            />
          </el-form-item>
          <el-form-item label="单品参考单价（元）" required>
            <el-input-number v-model="item.referenceYuan" :min="0" :precision="2" :step="100" class="full-width" />
          </el-form-item>
          <el-form-item label="选择权重">
            <el-input-number v-model="item.selectionWeight" :min="0" :max="10000" class="full-width" />
          </el-form-item>
          <el-form-item label="分摊基准（元，Q02 可暂留 0）">
            <el-input-number v-model="item.allocationYuan" :min="0" :precision="2" :step="10" class="full-width" />
          </el-form-item>
        </div>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ snapshot ? '保存草稿' : '创建草稿' }}</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.drawer-tip { margin-bottom: 18px; }
.section-head { display: flex; align-items: center; justify-content: space-between; margin: 12px 0; }
.group-tip { margin-bottom: 12px; }
.selection-group-editor { margin-bottom: 12px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.group-editor-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.group-editor-grid { display: grid; grid-template-columns: 1fr 2fr 1fr 1fr; gap: 10px; }
@media (max-width: 720px) { .group-editor-grid { grid-template-columns: 1fr 1fr; } }
</style>
