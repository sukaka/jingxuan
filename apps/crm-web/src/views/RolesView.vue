<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { PermissionItem, RoleItem } from '../api/roles'
import { createRole, listPermissions, listRoles, updateRolePermissions } from '../api/roles'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

const auth = useAuthStore()

const PERMISSION_GROUP_LABELS: Record<string, string> = {
  customer: '客户管理',
  followup: '客户跟进',
  task: '回访任务',
  dictionary: '基础字典',
  product: '产品运营',
  order: '订单管理',
  entitlement: '会员权益',
  appointment: '预约管理',
  redemption: '核销管理',
  refund: '退款管理',
  institution: '机构合作',
  settlement: '财务结算',
  material: '素材管理',
  campaign: '活动管理',
  channel: '渠道管理',
  report: '经营报表',
  export: '数据导出',
  audit: '审计日志',
  user: '用户与角色',
  wecom: '企业微信',
  ai: 'AI 助手',
  risk: '风险控制',
  integration: '外部集成',
}

function permissionGroupLabel(resource: string): string {
  return PERMISSION_GROUP_LABELS[resource] ?? '其他权限'
}

const roles = ref<RoleItem[]>([])
const permissions = ref<PermissionItem[]>([])
const loading = ref(false)
const errorMessage = ref('')

const selectedRoleId = ref<number | null>(null)
const selectedPermissionIds = ref<number[]>([])
const permissionSubmitting = ref(false)

const selectedRole = computed(
  () => roles.value.find((role) => role.id === selectedRoleId.value) ?? null,
)

/** 权限按资源前缀(冒号前)分组展示,如 customer → customer:read/write/... */
const permissionGroups = computed(() => {
  const groups = new Map<string, PermissionItem[]>()
  for (const permission of permissions.value) {
    const resource = permission.permissionCode.split(':')[0] ?? permission.permissionCode
    const list = groups.get(resource) ?? []
    list.push(permission)
    groups.set(resource, list)
  }
  return [...groups.entries()].map(([resource, items]) => ({ resource, items }))
})

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '未知错误'
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [roleList, permissionList] = await Promise.all([listRoles(), listPermissions()])
    roles.value = roleList
    permissions.value = permissionList
    if (selectedRoleId.value !== null) {
      selectRole(selectedRoleId.value)
    }
  } catch (error) {
    roles.value = []
    permissions.value = []
    errorMessage.value =
      error instanceof ApiError ? error.message : '加载失败,请稍后重试'
  } finally {
    loading.value = false
  }
}

function selectRole(roleId: number) {
  selectedRoleId.value = roleId
  const role = roles.value.find((item) => item.id === roleId)
  selectedPermissionIds.value = permissions.value
    .filter((permission) => role?.permissions.includes(permission.permissionCode))
    .map((permission) => permission.id)
}

// ---- 新建角色 ----
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createForm = reactive({
  roleCode: '',
  roleName: '',
})

function openCreateDialog() {
  createForm.roleCode = ''
  createForm.roleName = ''
  createDialogVisible.value = true
}

async function submitCreate() {
  if (!createForm.roleCode || !createForm.roleName) {
    ElMessage.warning('角色编码与角色名称为必填项')
    return
  }
  createSubmitting.value = true
  try {
    await createRole({ roleCode: createForm.roleCode, roleName: createForm.roleName })
    ElMessage.success('角色已创建')
    createDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    createSubmitting.value = false
  }
}

// ---- 保存角色权限 ----
async function submitPermissions() {
  if (selectedRoleId.value === null) return
  permissionSubmitting.value = true
  try {
    await updateRolePermissions(selectedRoleId.value, selectedPermissionIds.value)
    ElMessage.success('权限已更新,该角色下用户立即生效')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    permissionSubmitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h2 class="page-title">角色权限</h2>
    <el-alert
      v-if="errorMessage"
      type="error"
      :closable="false"
      :title="`加载失败:${errorMessage}`"
      class="table-alert"
    />
    <div v-else class="role-layout">
      <el-card shadow="never" class="role-card">
        <div class="card-toolbar">
          <span class="card-title">角色列表</span>
          <el-button
            v-if="auth.hasPermission('user:write')"
            type="primary"
            size="small"
            @click="openCreateDialog"
          >
            新建角色
          </el-button>
        </div>
        <app-table
          v-loading="loading"
          :data="roles"
          highlight-current-row
          @current-change="(row: RoleItem | null) => row && selectRole(row.id)"
        >
          <el-table-column prop="roleCode" label="角色编码" width="160" />
          <el-table-column prop="roleName" label="角色名称" min-width="120" />
          <el-table-column label="权限数" width="80">
            <template #default="{ row }">{{ row.permissions.length }}</template>
          </el-table-column>
          <template #empty>
            <span>暂无角色</span>
          </template>
        </app-table>
      </el-card>

      <el-card shadow="never" class="permission-card">
        <div class="card-toolbar">
          <span class="card-title">
            {{ selectedRole ? `配置权限：${selectedRole.roleName}（${selectedRole.roleCode}）` : '配置权限（请先在左侧选择角色）' }}
          </span>
          <el-button
            v-if="auth.hasPermission('user:write')"
            type="primary"
            size="small"
            :disabled="!selectedRole"
            :loading="permissionSubmitting"
            @click="submitPermissions"
          >
            保存权限
          </el-button>
        </div>
        <template v-if="selectedRole">
          <div v-for="group in permissionGroups" :key="group.resource" class="permission-group">
            <div class="group-name">{{ permissionGroupLabel(group.resource) }}</div>
            <el-checkbox-group v-model="selectedPermissionIds">
              <el-checkbox
                v-for="permission in group.items"
                :key="permission.id"
                :value="permission.id"
                :disabled="!auth.hasPermission('user:write')"
              >
                {{ permission.permissionCode
                }}<span v-if="permission.description" class="muted"> - {{ permission.description }}</span>
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </template>
        <el-empty v-else description="未选择角色" :image-size="80" />
      </el-card>
    </div>

    <el-dialog v-model="createDialogVisible" title="新建角色" width="440px">
      <el-form label-width="90px">
        <el-form-item label="角色编码" required>
          <el-input v-model="createForm.roleCode" placeholder="如 OPERATION_LEAD" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="createForm.roleName" placeholder="如 运营主管" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.table-alert {
  margin-bottom: 4px;
}

.role-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.role-card {
  flex: 1;
  min-width: 0;
}

.permission-card {
  flex: 1;
  min-width: 0;
}

.card-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.card-title {
  font-weight: 600;
}

.permission-group {
  margin-bottom: 12px;
}

.group-name {
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--el-text-color-regular);
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
