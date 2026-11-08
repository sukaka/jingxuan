<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { PackageProjectInput, PackageVersionSnapshot, Project } from '../../api/catalog'
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
}

const form = reactive({
  selectionMode: 'USE_N',
  choiceCount: 3,
  selectionRules: '',
  items: [] as LineState[],
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
      form.items = props.snapshot.projects.map((item) => ({
        projectId: item.projectId,
        grantCount: item.grantCount,
        selectionWeight: item.selectionWeight,
        allocationYuan: (item.allocationPriceFen ?? 0) / 100,
        optionGroup: item.optionGroup || 'MAIN',
      }))
    } else {
      form.selectionMode = 'USE_N'
      form.choiceCount = 3
      form.selectionRules = ''
      form.items = [newLine()]
    }
  },
)

function newLine(): LineState {
  return { projectId: undefined, grantCount: 1, selectionWeight: 1, allocationYuan: 0, optionGroup: 'MAIN' }
}

function addLine() {
  form.items.push(newLine())
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
  }))
}

async function save() {
  if (!props.packageId) return
  if (form.selectionMode !== 'FIXED' && form.choiceCount <= 0) {
    ElMessage.warning('选 N 项或 N 次时，N 必须大于 0')
    return
  }
  if (form.items.some((item) => !item.projectId || !Number.isInteger(item.grantCount) || item.grantCount <= 0)) {
    ElMessage.warning('请完整选择子项目，并填写大于 0 的整数次数')
    return
  }
  saving.value = true
  try {
    let saved: PackageVersionSnapshot
    if (props.snapshot) {
      await updatePackageVersion(props.snapshot.id, {
        selectionMode: form.selectionMode,
        choiceCount: form.choiceCount,
        selectionRules: form.selectionRules || undefined,
        version: props.snapshot.version,
      })
      saved = await replaceVersionProjects(props.snapshot.id, {
        version: props.snapshot.version + 1,
        projects: payload(),
      })
    } else {
      saved = await createPackageVersion(props.packageId, {
        selectionMode: form.selectionMode,
        choiceCount: form.choiceCount,
        selectionRules: form.selectionRules || undefined,
        projects: payload(),
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
    size="min(780px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      title="草稿可编辑；激活后配置冻结。选 N 项不等于 N 次，Q01 未确认前只能保存模板"
      class="drawer-tip"
    />
    <el-form label-position="top">
      <div class="catalog-form-grid">
        <el-form-item label="选择模式" required>
          <el-select v-model="form.selectionMode" class="full-width">
            <el-option v-for="mode in ['USE_N', 'FIXED', 'CHOOSE_N']" :key="mode" :label="selectionModeLabel(mode)" :value="mode" />
          </el-select>
        </el-form-item>
        <el-form-item :label="form.selectionMode === 'CHOOSE_N' ? '可选项目数 N' : '使用次数 N'" required>
          <el-input-number v-model="form.choiceCount" :min="form.selectionMode === 'FIXED' ? 0 : 1" :max="1000" class="full-width" />
        </el-form-item>
        <el-form-item label="选项与重复规则（可留待确认）" class="catalog-form-full">
          <el-input
            v-model="form.selectionRules"
            type="textarea"
            :rows="3"
            maxlength="4000"
            show-word-limit
            placeholder="记录选项约束；不会仅凭文本视为 Q01 已确认"
          />
        </el-form-item>
      </div>

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
          <el-form-item label="发放次数 / 可选上限" required>
            <el-input-number v-model="item.grantCount" :min="1" :max="1000" class="full-width" />
          </el-form-item>
          <el-form-item label="选项组">
            <el-input
              :model-value="optionGroupLabel(item.optionGroup)"
              placeholder="主选项组"
              @update:model-value="item.optionGroup = $event === '主选项组' ? 'MAIN' : $event"
            />
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
</style>
