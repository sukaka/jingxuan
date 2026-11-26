<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ManagedUser, TeamOption } from '../api/users'
import {
  createUser,
  disableUser,
  listUsers,
  listUserTeams,
  updateUser,
  updateUserRoles,
} from '../api/users'
import type { RoleItem } from '../api/roles'
import { listRoles } from '../api/roles'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import ShellIcon from '../components/shell/ShellIcon.vue'
import { roleTone, userStatusTone } from '../utils/tagTone'
import {
  extractApiFieldErrors,
  formatApiError,
  validateCreateUserInput,
  validateEditUserInput,
} from '../utils/userValidation'
import type { CreateUserField, CreateUserFieldErrors, EditUserField, EditUserFieldErrors } from '../utils/userValidation'

const auth = useAuthStore()

const USER_TYPE_LABELS: Record<string, string> = {
  EMPLOYEE: '员工',
  INSTITUTION: '机构用户',
}
const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
  INACTIVE: '停用',
}

const query = reactive({
  keyword: '',
  status: '',
  userType: '',
  teamId: '' as number | '',
  roleCode: '',
  page: 1,
  size: 20,
})
const users = ref<ManagedUser[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const allRoles = ref<RoleItem[]>([])
const allTeams = ref<TeamOption[]>([])
const hasFilters = computed(() => Boolean(
  query.keyword || query.status || query.userType || query.teamId !== '' || query.roleCode,
))

function roleLabel(code: string): string {
  return allRoles.value.find((role) => role.roleCode === code)?.roleName ?? '未识别角色'
}

function teamLabel(id: number): string {
  return allTeams.value.find((team) => team.id === id)?.teamName ?? `团队 #${id}`
}

function formatDateTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '-'
}

function errorText(error: unknown): string {
  return formatApiError(error, '未知错误')
}

function showErrorToast(message: string) {
  ElMessage.error({ message, grouping: true })
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listUsers({
      page,
      size: query.size,
      keyword: query.keyword,
      status: query.status,
      userType: query.userType,
      teamId: query.teamId,
      roleCode: query.roleCode,
    })
    users.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    users.value = []
    total.value = 0
    errorMessage.value = errorText(error)
  } finally {
    loading.value = false
  }
}

function search() {
  void load(1)
}

function resetQuery() {
  query.keyword = ''
  query.status = ''
  query.userType = ''
  query.teamId = ''
  query.roleCode = ''
  void load(1)
}

// ---- 新建用户 ----
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createSubmitError = ref('')
const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  userType: 'EMPLOYEE',
  teamId: undefined as number | undefined,
  roleIds: [] as number[],
})
const createFieldErrors = reactive<CreateUserFieldErrors>({})

const CREATE_USER_FIELDS: readonly CreateUserField[] = [
  'username', 'displayName', 'password', 'userType', 'teamId', 'roleIds',
]

function clearCreateErrors() {
  createSubmitError.value = ''
  for (const field of CREATE_USER_FIELDS) {
    createFieldErrors[field] = undefined
  }
}

function clearCreateFieldError(field: CreateUserField) {
  createFieldErrors[field] = undefined
  if (createSubmitError.value) createSubmitError.value = ''
}

function applyCreateServerErrors(error: ApiError): boolean {
  const details = extractApiFieldErrors(error)
  let matched = false
  for (const field of CREATE_USER_FIELDS) {
    if (details[field]) {
      createFieldErrors[field] = details[field]
      matched = true
    }
  }
  if (!matched && error.status === 409 && error.message.includes('登录名')) {
    createFieldErrors.username = error.message
    matched = true
  }
  return matched
}

function openCreateDialog() {
  createForm.username = ''
  createForm.displayName = ''
  createForm.password = ''
  createForm.userType = 'EMPLOYEE'
  createForm.teamId = undefined
  createForm.roleIds = []
  clearCreateErrors()
  createDialogVisible.value = true
}

async function submitCreate() {
  if (createSubmitting.value) return
  clearCreateErrors()
  const validation = validateCreateUserInput({
    username: createForm.username,
    displayName: createForm.displayName,
    password: createForm.password,
    userType: createForm.userType,
  })
  if (!validation.valid) {
    Object.assign(createFieldErrors, validation.fieldErrors)
    return
  }
  createSubmitting.value = true
  try {
    await createUser({
      username: createForm.username,
      displayName: createForm.displayName.trim(),
      password: createForm.password,
      userType: createForm.userType,
      teamId: createForm.teamId ?? null,
      roleIds: createForm.roleIds,
    })
    ElMessage.success('用户已创建')
    createDialogVisible.value = false
    await load()
  } catch (error) {
    if (error instanceof ApiError) {
      const matched = applyCreateServerErrors(error)
      if (!matched) {
        createSubmitError.value = errorText(error)
      }
    } else {
      createSubmitError.value = errorText(error)
    }
  } finally {
    createSubmitting.value = false
  }
}

// ---- 编辑用户(乐观锁) ----
const editDialogVisible = ref(false)
const editSubmitting = ref(false)
const editSubmitError = ref('')
const editForm = reactive({
  id: 0,
  displayName: '',
  teamId: undefined as number | undefined,
  version: 0,
})
const editFieldErrors = reactive<EditUserFieldErrors>({})

const EDIT_USER_FIELDS: readonly EditUserField[] = ['displayName', 'teamId']

function clearEditErrors() {
  editSubmitError.value = ''
  for (const field of EDIT_USER_FIELDS) {
    editFieldErrors[field] = undefined
  }
}

function clearEditFieldError(field: EditUserField) {
  editFieldErrors[field] = undefined
  if (editSubmitError.value) editSubmitError.value = ''
}

function openEditDialog(user: ManagedUser) {
  editForm.id = user.id
  editForm.displayName = user.displayName
  editForm.teamId = user.teamId ?? undefined
  editForm.version = user.version
  clearEditErrors()
  editDialogVisible.value = true
}

async function submitEdit() {
  if (editSubmitting.value) return
  clearEditErrors()
  const validation = validateEditUserInput({ displayName: editForm.displayName })
  if (!validation.valid) {
    Object.assign(editFieldErrors, validation.fieldErrors)
    return
  }
  editSubmitting.value = true
  try {
    await updateUser(editForm.id, {
      displayName: editForm.displayName.trim(),
      teamId: editForm.teamId ?? null,
      version: editForm.version,
    })
    ElMessage.success('已保存')
    editDialogVisible.value = false
    await load()
  } catch (error) {
    if (error instanceof ApiError) {
      const details = extractApiFieldErrors(error)
      let matched = false
      for (const field of EDIT_USER_FIELDS) {
        if (details[field]) {
          editFieldErrors[field] = details[field]
          matched = true
        }
      }
      if (!matched) {
        editSubmitError.value = errorText(error)
      }
    } else {
      editSubmitError.value = errorText(error)
    }
  } finally {
    editSubmitting.value = false
  }
}

// ---- 分配角色 ----
const roleDialogVisible = ref(false)
const roleSubmitting = ref(false)
const roleForm = reactive({
  id: 0,
  displayName: '',
  roleIds: [] as number[],
})

function openRoleDialog(user: ManagedUser) {
  roleForm.id = user.id
  roleForm.displayName = user.displayName
  roleForm.roleIds = allRoles.value
    .filter((role) => user.roles.includes(role.roleCode))
    .map((role) => role.id)
  roleDialogVisible.value = true
}

async function submitRoles() {
  if (roleSubmitting.value) return
  roleSubmitting.value = true
  try {
    await updateUserRoles(roleForm.id, roleForm.roleIds)
    ElMessage.success('角色已更新')
    roleDialogVisible.value = false
    await load()
  } catch (error) {
    showErrorToast(errorText(error))
  } finally {
    roleSubmitting.value = false
  }
}

// ---- 停用 ----
async function handleDisable(user: ManagedUser) {
  try {
    await ElMessageBox.confirm(
      `停用后 ${user.displayName}（${user.username}）将立即被强制退出并回收权限，确定停用吗？`,
      '停用用户',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await disableUser(user.id)
    ElMessage.success('已停用')
    await load()
  } catch (error) {
    showErrorToast(errorText(error))
  }
}

onMounted(async () => {
  await load()
  allRoles.value = await listRoles().catch(() => [])
  allTeams.value = await listUserTeams().catch(() => [])
})
</script>

<template>
  <div class="proto-page users-page">
    <PageHeader title="用户管理" description="统一维护账号、团队归属与访问角色">
      <template #actions>
        <el-button v-if="auth.hasPermission('user:write')" type="primary" @click="openCreateDialog">
          <ShellIcon name="plus" :size="15" />新建用户
        </el-button>
      </template>
    </PageHeader>

    <PageCard>
      <div class="user-filter">
        <div class="filter-field filter-keyword">
          <span>关键词</span>
          <el-input v-model="query.keyword" placeholder="用户名 / 姓名" clearable @keyup.enter="search" />
        </div>
        <div class="filter-field">
          <span>用户类型</span>
          <el-select v-model="query.userType" placeholder="全部">
            <el-option label="全部" value="" />
            <el-option label="员工" value="EMPLOYEE" />
            <el-option label="机构用户" value="INSTITUTION" />
          </el-select>
        </div>
        <div class="filter-field">
          <span>所属团队</span>
          <el-select v-model="query.teamId" placeholder="全部">
            <el-option label="全部" value="" />
            <el-option v-for="team in allTeams" :key="team.id" :label="team.teamName" :value="team.id" />
          </el-select>
        </div>
        <div class="filter-field">
          <span>角色</span>
          <el-select v-model="query.roleCode" placeholder="全部">
            <el-option label="全部" value="" />
            <el-option v-for="role in allRoles" :key="role.id" :label="role.roleName" :value="role.roleCode" />
          </el-select>
        </div>
        <div class="filter-field">
          <span>状态</span>
          <el-select v-model="query.status" placeholder="全部">
            <el-option label="全部" value="" />
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </div>
        <div class="filter-actions">
          <el-button type="primary" class="filter-search" :loading="loading" @click="search"><ShellIcon name="search" :size="14" />查询</el-button>
          <el-button class="filter-reset" @click="resetQuery"><ShellIcon name="refresh" :size="14" />重置</el-button>
        </div>
      </div>

      <div v-if="hasFilters" class="filter-summary">
        <span class="filter-summary-label">已选条件</span>
        <span v-if="query.keyword" class="filter-chip">关键词：{{ query.keyword }}</span>
        <span v-if="query.userType" class="filter-chip">类型：{{ USER_TYPE_LABELS[query.userType] }}</span>
        <span v-if="query.teamId !== ''" class="filter-chip">团队：{{ teamLabel(Number(query.teamId)) }}</span>
        <span v-if="query.roleCode" class="filter-chip">角色：{{ roleLabel(query.roleCode) }}</span>
        <span v-if="query.status" class="filter-chip">状态：{{ STATUS_LABELS[query.status] }}</span>
        <button type="button" class="filter-clear" @click="resetQuery">清除全部</button>
      </div>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        :title="`加载失败：${errorMessage}`"
        class="table-alert"
      />

      <app-table v-else v-loading="loading" :data="users" class="user-table" table-id="user-management">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="displayName" label="姓名" min-width="120" />
        <el-table-column label="用户类型" min-width="110">
          <template #default="{ row }">{{ USER_TYPE_LABELS[row.userType] ?? '未知类型' }}</template>
        </el-table-column>
        <el-table-column label="团队" min-width="110">
          <template #default="{ row }">{{ row.teamName ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="code in row.roles" :key="code" size="small" class="role-tag" :class="roleTone(code)">{{ roleLabel(code) }}</el-tag>
            <span v-if="row.roles.length === 0" class="muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="90">
          <template #default="{ row }">
            <span class="proto-pill" :class="userStatusTone(row.status)">{{ STATUS_LABELS[row.status] ?? '未知状态' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column v-if="auth.hasPermission('user:write')" label="操作" min-width="215" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button text size="small" @click="openRoleDialog(row)">分配角色</el-button>
            <el-button text size="small" class="table-action-danger" :disabled="row.status !== 'ACTIVE'" @click="handleDisable(row)">停用</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span>{{ hasFilters ? '没有符合当前筛选条件的用户' : '暂无用户，可点击右上角「新建用户」创建' }}</span>
        </template>
      </app-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="(page: number) => load(page)"
        @size-change="(size: number) => { query.size = size; void load(1) }"
      />
    </PageCard>

    <el-dialog
      v-model="createDialogVisible"
      title="新建用户"
      width="500px"
      :close-on-click-modal="false"
      @closed="clearCreateErrors"
    >
      <el-alert
        v-if="createSubmitError"
        type="error"
        :closable="false"
        :title="createSubmitError"
        class="dialog-alert"
        data-testid="create-user-submit-error"
      />
      <el-form label-width="90px" @submit.prevent="submitCreate">
        <el-form-item label="用户名" required :class="{ 'is-error': Boolean(createFieldErrors.username) }">
          <el-input
            v-model="createForm.username"
            maxlength="32"
            placeholder="3-32 位字母、数字或 ._-（创建后不可修改）"
            data-testid="create-username-input"
            @input="clearCreateFieldError('username')"
          />
          <p v-if="createFieldErrors.username" class="field-error" role="alert" data-testid="create-username-error">
            {{ createFieldErrors.username }}
          </p>
          <p v-else class="field-hint">仅支持 3-32 位英文字母、数字及 . _ -，不支持中文和空格</p>
        </el-form-item>
        <el-form-item label="姓名" required :class="{ 'is-error': Boolean(createFieldErrors.displayName) }">
          <el-input
            v-model="createForm.displayName"
            maxlength="64"
            placeholder="请输入显示姓名（最多 64 字）"
            data-testid="create-display-name-input"
            @input="clearCreateFieldError('displayName')"
          />
          <p v-if="createFieldErrors.displayName" class="field-error" role="alert" data-testid="create-display-name-error">
            {{ createFieldErrors.displayName }}
          </p>
        </el-form-item>
        <el-form-item label="初始密码" required :class="{ 'is-error': Boolean(createFieldErrors.password) }">
          <el-input
            v-model="createForm.password"
            type="password"
            maxlength="64"
            show-password
            autocomplete="new-password"
            placeholder="请输入 8-64 位初始密码"
            data-testid="create-password-input"
            @input="clearCreateFieldError('password')"
          />
          <p v-if="createFieldErrors.password" class="field-error" role="alert" data-testid="create-password-error">
            {{ createFieldErrors.password }}
          </p>
          <p v-else class="field-hint">密码长度须为 8-64 位，不能全为空格</p>
        </el-form-item>
        <el-form-item label="用户类型" required :class="{ 'is-error': Boolean(createFieldErrors.userType) }">
          <el-select v-model="createForm.userType" @change="clearCreateFieldError('userType')">
            <el-option label="员工" value="EMPLOYEE" />
            <el-option label="机构用户" value="INSTITUTION" />
          </el-select>
          <p v-if="createFieldErrors.userType" class="field-error" role="alert">
            {{ createFieldErrors.userType }}
          </p>
        </el-form-item>
        <el-form-item label="所属团队" :class="{ 'is-error': Boolean(createFieldErrors.teamId) }">
          <el-select v-model="createForm.teamId" clearable placeholder="不分配团队" @change="clearCreateFieldError('teamId')">
            <el-option v-for="team in allTeams" :key="team.id" :label="team.teamName" :value="team.id" />
          </el-select>
          <p v-if="createFieldErrors.teamId" class="field-error" role="alert" data-testid="create-team-error">
            {{ createFieldErrors.teamId }}
          </p>
        </el-form-item>
        <el-form-item label="角色" :class="{ 'is-error': Boolean(createFieldErrors.roleIds) }">
          <el-checkbox-group v-model="createForm.roleIds" @change="clearCreateFieldError('roleIds')">
            <el-checkbox v-for="role in allRoles" :key="role.id" :value="role.id">
              {{ role.roleName }}
            </el-checkbox>
          </el-checkbox-group>
          <p v-if="createFieldErrors.roleIds" class="field-error" role="alert" data-testid="create-role-error">
            {{ createFieldErrors.roleIds }}
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" data-testid="create-user-submit" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="editDialogVisible"
      title="编辑用户"
      width="480px"
      :close-on-click-modal="false"
      @closed="clearEditErrors"
    >
      <el-alert
        v-if="editSubmitError"
        type="error"
        :closable="false"
        :title="editSubmitError"
        class="dialog-alert"
        data-testid="edit-user-submit-error"
      />
      <el-form label-width="90px" @submit.prevent="submitEdit">
        <el-form-item label="姓名" required :class="{ 'is-error': Boolean(editFieldErrors.displayName) }">
          <el-input
            v-model="editForm.displayName"
            maxlength="64"
            placeholder="请输入显示姓名（最多 64 字）"
            data-testid="edit-display-name-input"
            @input="clearEditFieldError('displayName')"
          />
          <p v-if="editFieldErrors.displayName" class="field-error" role="alert" data-testid="edit-display-name-error">
            {{ editFieldErrors.displayName }}
          </p>
        </el-form-item>
        <el-form-item label="所属团队" :class="{ 'is-error': Boolean(editFieldErrors.teamId) }">
          <el-select v-model="editForm.teamId" clearable placeholder="不分配团队" @change="clearEditFieldError('teamId')">
            <el-option v-for="team in allTeams" :key="team.id" :label="team.teamName" :value="team.id" />
          </el-select>
          <p v-if="editFieldErrors.teamId" class="field-error" role="alert" data-testid="edit-team-error">
            {{ editFieldErrors.teamId }}
          </p>
        </el-form-item>
        <el-form-item label="数据版本">
          <span class="muted">第 {{ editForm.version }} 版（多人同时编辑时将提示刷新）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" data-testid="edit-user-submit" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialogVisible" :title="`分配角色 - ${roleForm.displayName}`" width="480px">
      <el-checkbox-group v-model="roleForm.roleIds">
        <el-checkbox v-for="role in allRoles" :key="role.id" :value="role.id" class="role-option">
          {{ role.roleName }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSubmitting" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-filter {
  display: grid;
  grid-template-columns: minmax(190px, 1.45fr) repeat(4, minmax(118px, 1fr)) auto;
  gap: 12px;
  align-items: end;
  padding: 18px 20px;
  border-bottom: 1px solid #e8eef2;
}

.filter-field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
  color: #71899b;
  font-size: 11px;
  font-weight: 600;
}

.filter-field :deep(.el-input),
.filter-field :deep(.el-select) {
  width: 100%;
}

.filter-field :deep(.el-input__wrapper),
.filter-field :deep(.el-select__wrapper) {
  min-height: 38px;
  border-radius: 8px;
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 1px;
}

.filter-actions .el-button {
  min-height: 38px;
  margin-left: 0;
  padding: 9px 13px;
  border-radius: 8px;
}

.filter-actions .shell-icon {
  display: inline-block;
  margin-right: 5px;
  vertical-align: -2px;
}

.filter-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 11px 20px;
  border-bottom: 1px solid #e8eef2;
  background: #f8fafb;
}

.filter-summary-label {
  margin-right: 2px;
  color: #71899b;
  font-size: 11px;
  font-weight: 650;
}

.filter-chip {
  display: inline-flex;
  padding: 4px 8px;
  border: 1px solid #d9e9e5;
  border-radius: 6px;
  color: #397c72;
  background: #eef8f5;
  font-size: 11px;
  line-height: 1.5;
}

.filter-clear {
  margin-left: auto;
  padding: 3px 0;
  border: 0;
  color: #0c968a;
  background: transparent;
  font-size: 11px;
  cursor: pointer;
}

.filter-clear:hover {
  color: #08766f;
}

.user-table :deep(.el-table__cell) {
  padding: 12px 0;
}

.table-action-danger {
  color: #ce5c64;
}

.table-action-danger:hover {
  color: #b63f4e;
}

.table-alert {
  margin: 12px 20px 4px;
}

.dialog-alert {
  margin-bottom: 14px;
}

.field-hint,
.field-error {
  flex-basis: 100%;
  width: 100%;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.45;
}

.field-hint {
  color: var(--el-text-color-secondary);
}

.field-error {
  color: var(--el-color-danger);
}

.role-tag {
  margin: 2px 4px 2px 0;
}

.role-option {
  display: flex;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.pager {
  justify-content: flex-end;
  padding: 16px 20px;
}

@media (max-width: 1200px) {
  .user-filter {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .filter-keyword {
    grid-column: span 2;
  }

  .filter-actions {
    justify-content: flex-end;
  }
}

@media (max-width: 720px) {
  .user-filter {
    grid-template-columns: 1fr;
    padding: 16px;
  }

  .filter-keyword {
    grid-column: auto;
  }

  .filter-actions {
    justify-content: stretch;
  }

  .filter-actions .el-button {
    flex: 1;
  }

  .filter-summary {
    padding: 10px 16px;
  }

  .filter-clear {
    margin-left: 0;
  }
}
</style>
