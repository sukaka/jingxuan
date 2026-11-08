<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  claimTask,
  completeTask,
  createTask,
  listTaskActions,
  listTasks,
  reassignTask,
  returnTask,
  updateTask,
} from '../api/task'
import type { TaskActionLog, TaskItem } from '../api/task'
import { listCustomerOwnerOptions, listCustomers } from '../api/customer'
import type { CustomerListItem, CustomerOwnerOption } from '../api/customer'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useDictionaries } from './customer/shared'
import { priorityTone } from '../utils/tagTone'

const auth = useAuthStore(), dicts = useDictionaries()
const items = ref<TaskItem[]>([]), loading = ref(false), errorMessage = ref('')
const totals = reactive<Record<string, number>>({ OPEN: 0, PROCESSING: 0, DONE: 0 })
const owners = ref<CustomerOwnerOption[]>([]), customerOptions = ref<CustomerListItem[]>([])
const dialogVisible = ref(false), editing = ref<TaskItem | null>(null), submitting = ref(false)
const form = reactive({ customerId: undefined as number | undefined, taskType: 'FOLLOWUP', priority: 'MEDIUM', title: '', description: '', dueAt: '' })
const actionVisible = ref(false), actionSubmitting = ref(false), actionTask = ref<TaskItem | null>(null)
const actionType = ref<'REASSIGN' | 'RETURN'>('REASSIGN')
const actionForm = reactive({ assigneeUserId: undefined as number | undefined, reason: '' })
const actionsVisible = ref(false), actionsLoading = ref(false), actionsError = ref('')
const actionLogs = ref<TaskActionLog[]>([]), actionsTask = ref<TaskItem | null>(null)
const canWrite = computed(() => auth.hasPermission('task:write'))
const canReassign = computed(() => canWrite.value && auth.user?.roles.some((role) => ['ADMIN', 'MANAGER_LEAD'].includes(role)))
const columns = computed(() => [
  { status: 'OPEN', title: '待处理', items: items.value.filter((item) => item.status === 'OPEN') },
  { status: 'PROCESSING', title: '处理中', items: items.value.filter((item) => item.status === 'PROCESSING') },
  { status: 'DONE', title: '已完成', items: items.value.filter((item) => item.status === 'DONE') },
])
const teamOptions = computed(() => {
  const teams = new Map<number, string>()
  owners.value.forEach((owner) => { if (owner.teamId !== null) teams.set(owner.teamId, owner.teamName || `团队 #${owner.teamId}`) })
  return [...teams].map(([id, name]) => ({ id, name }))
})
const actionTitle = computed(() => actionType.value === 'REASSIGN' ? '转派任务' : '退回任务池')

function ownerName(id: number | null | undefined, empty = '未分配') {
  if (id === null || id === undefined) return empty
  return owners.value.find((item) => item.id === id)?.displayName || (id === auth.user?.userId ? auth.user.displayName : `用户 #${id}`)
}
function teamName(id: number | null | undefined) {
  if (id === null || id === undefined) return '未设置团队'
  return teamOptions.value.find((item) => item.id === id)?.name || `团队 #${id}`
}
function formatDateTime(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}
function priorityLabel(value: string) { return ({ URGENT: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' })[value] || '未知' }
function taskTypeLabel(value: string) { return dicts.options('taskTypes').find((item) => item.code === value)?.label || '未知任务' }
function actionLabel(value: string) { return ({ CLAIM: '接手', REASSIGN: '转派', RETURN: '退回', COMPLETE: '完成' })[value] || value }
function statusLabel(value: string | null) { return value ? ({ OPEN: '待处理', PROCESSING: '处理中', DONE: '已完成', CANCELED: '已取消' })[value] || value : '—' }
function canOperateOwnedTask(task: TaskItem) {
  return canWrite.value && task.status === 'PROCESSING' && task.assigneeUserId === auth.user?.userId && Boolean(task.claimedAt)
}
async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const statuses = ['OPEN', 'PROCESSING', 'DONE']
    const results = await Promise.all(statuses.map((status) => listTasks({ page: 1, size: 100, status })))
    items.value = results.flatMap((result) => result.items)
    results.forEach((result, index) => { totals[statuses[index]] = result.total })
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '任务加载失败' }
  finally { loading.value = false }
}
function openCreate() {
  editing.value = null
  Object.assign(form, { customerId: undefined, taskType: 'FOLLOWUP', priority: 'MEDIUM', title: '', description: '', dueAt: '' })
  dialogVisible.value = true
}
function openEdit(row: TaskItem) {
  editing.value = row
  Object.assign(form, { customerId: row.customerId ?? undefined, taskType: row.taskType, priority: row.priority, title: row.title, description: row.description ?? '', dueAt: row.dueAt ?? '' })
  dialogVisible.value = true
}
async function submit() {
  if (!form.title.trim()) { ElMessage.warning('请填写任务标题'); return }
  submitting.value = true
  try {
    if (editing.value) {
      await updateTask(editing.value.id, { taskType: form.taskType, priority: form.priority, title: form.title, description: form.description, dueAt: form.dueAt || undefined, version: editing.value.version })
    } else {
      await createTask({ customerId: form.customerId, taskType: form.taskType, priority: form.priority, title: form.title, description: form.description, dueAt: form.dueAt || undefined })
    }
    ElMessage.success(editing.value ? '任务已修改' : '任务已创建')
    dialogVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
  finally { submitting.value = false }
}
async function runDirectAction(task: TaskItem, type: 'CLAIM' | 'COMPLETE') {
  actionSubmitting.value = true
  try {
    if (type === 'CLAIM') await claimTask(task.id, task.version, crypto.randomUUID())
    else await completeTask(task.id, task.version, crypto.randomUUID())
    ElMessage.success(type === 'CLAIM' ? '任务已接手' : '任务已完成')
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败') }
  finally { actionSubmitting.value = false }
}
function openReasonAction(task: TaskItem, type: 'REASSIGN' | 'RETURN') {
  actionTask.value = task
  actionType.value = type
  Object.assign(actionForm, { assigneeUserId: undefined, reason: '' })
  actionVisible.value = true
}
async function submitReasonAction() {
  const task = actionTask.value
  if (!task) return
  const reason = actionForm.reason.trim()
  if (!reason) { ElMessage.warning('请填写操作原因'); return }
  if (actionType.value === 'REASSIGN' && !actionForm.assigneeUserId) { ElMessage.warning('请选择新处理人'); return }
  actionSubmitting.value = true
  try {
    if (actionType.value === 'REASSIGN') {
      await reassignTask(task.id, { assigneeUserId: actionForm.assigneeUserId!, reason, version: task.version }, crypto.randomUUID())
    } else {
      await returnTask(task.id, { reason, version: task.version }, crypto.randomUUID())
    }
    ElMessage.success(actionType.value === 'REASSIGN' ? '任务已转派' : '任务已退回任务池')
    actionVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败') }
  finally { actionSubmitting.value = false }
}
async function openActions(task: TaskItem) {
  actionsTask.value = task
  actionLogs.value = []
  actionsError.value = ''
  actionsVisible.value = true
  actionsLoading.value = true
  try { actionLogs.value = await listTaskActions(task.id) }
  catch (error) { actionsError.value = error instanceof Error ? error.message : '流转记录加载失败' }
  finally { actionsLoading.value = false }
}
async function searchCustomers(keyword: string) { customerOptions.value = (await listCustomers({ page: 1, size: 20, keyword })).items }
onMounted(() => {
  void dicts.load('taskTypes'); void load(); void searchCustomers('')
  void listCustomerOwnerOptions().then((result) => { owners.value = result }).catch(() => { owners.value = [] })
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="回访任务" description="任务接手、转派、退回与完成均保留独立流转记录">
      <template #actions><el-button v-if="canWrite" type="primary" @click="openCreate">＋ 新建任务</el-button></template>
    </PageHeader>
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" />
    <div v-loading="loading" class="task-kanban">
      <section v-for="column in columns" :key="column.status" class="kanban-col">
        <div class="kanban-head"><span>{{ column.title }}</span><span class="proto-pill dark">{{ totals[column.status] }}</span></div>
        <article v-for="task in column.items" :key="task.id" class="kanban-card">
          <div><span class="proto-pill info">{{ taskTypeLabel(task.taskType) }}</span> <span class="proto-pill" :class="priorityTone(task.priority)">{{ priorityLabel(task.priority) }}</span></div>
          <h4>{{ task.title }}</h4>
          <p>{{ task.description || '暂无跟进依据' }}</p>
          <dl class="task-facts">
            <div><dt>首次负责人</dt><dd>{{ ownerName(task.originalAssigneeUserId, '未记录') }}</dd></div>
            <div><dt>当前处理人</dt><dd>{{ ownerName(task.assigneeUserId, '未分配（任务池）') }}</dd></div>
            <div><dt>任务团队</dt><dd>{{ teamName(task.taskTeamId) }}</dd></div>
            <div><dt>截止时间</dt><dd>{{ formatDateTime(task.dueAt) }}</dd></div>
            <div><dt>接手时间</dt><dd>{{ formatDateTime(task.claimedAt) }}</dd></div>
            <div><dt>完成时间</dt><dd>{{ formatDateTime(task.completedAt) }}</dd></div>
          </dl>
          <div class="kanban-foot">
            <el-button size="small" text :data-testid="`actions-${task.id}`" @click="openActions(task)">流转记录</el-button>
            <div class="task-actions">
              <el-button v-if="canWrite" size="small" :data-testid="`edit-${task.id}`" @click="openEdit(task)">修改</el-button>
              <el-button v-if="canWrite && task.status === 'OPEN'" size="small" type="primary" :loading="actionSubmitting" :data-testid="`claim-${task.id}`" @click="runDirectAction(task, 'CLAIM')">接手</el-button>
              <el-button v-if="canReassign && task.status !== 'DONE' && task.status !== 'CANCELED'" size="small" :data-testid="`reassign-${task.id}`" @click="openReasonAction(task, 'REASSIGN')">转派</el-button>
              <el-button v-if="canOperateOwnedTask(task)" size="small" :data-testid="`return-${task.id}`" @click="openReasonAction(task, 'RETURN')">退回</el-button>
              <el-button v-if="canOperateOwnedTask(task)" size="small" type="primary" :loading="actionSubmitting" :data-testid="`complete-${task.id}`" @click="runDirectAction(task, 'COMPLETE')">完成</el-button>
            </div>
          </div>
        </article>
        <div v-if="!column.items.length" class="proto-empty">暂无任务</div>
      </section>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '修改任务' : '新建任务'" width="min(600px,96vw)" data-testid="task-edit-dialog">
      <el-form label-position="top" class="proto-form-grid">
        <el-form-item label="关联客户"><el-select v-model="form.customerId" filterable remote clearable :remote-method="searchCustomers"><el-option v-for="item in customerOptions" :key="item.id" :label="item.displayName || item.nickname || item.customerNo" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="任务类型"><el-select v-model="form.taskType"><el-option v-for="item in dicts.options('taskTypes')" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="任务标题" class="proto-full"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="截止日期"><el-date-picker v-model="form.dueAt" type="datetime" value-format="YYYY-MM-DD[T]HH:mm:ssZ" style="width:100%" /></el-form-item>
        <el-form-item label="优先级"><el-select v-model="form.priority"><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" /><el-option label="紧急" value="URGENT" /></el-select></el-form-item>
        <el-form-item label="跟进依据" class="proto-full"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submit">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="actionVisible" :title="actionTitle" width="min(520px,96vw)" :close-on-click-modal="!actionSubmitting" :close-on-press-escape="!actionSubmitting" :show-close="!actionSubmitting">
      <p v-if="actionTask" class="action-task-title">{{ actionTask.title }} · 当前版本 {{ actionTask.version }}</p>
      <el-form label-position="top" @submit.prevent>
        <template v-if="actionType === 'REASSIGN'">
          <el-form-item label="新处理人" required><el-select v-model="actionForm.assigneeUserId" filterable placeholder="请选择新处理人" style="width:100%" data-testid="task-action-assignee"><el-option v-for="item in owners" :key="item.id" :label="`${item.displayName}${item.teamName ? `（${item.teamName}）` : ''}`" :value="item.id" /></el-select></el-form-item>
        </template>
        <el-alert v-else title="退回后任务回到待处理任务池，当前处理人与接手时间将清空。" type="info" :closable="false" class="action-alert" />
        <el-form-item label="操作原因" required><el-input v-model="actionForm.reason" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="必填：请说明操作原因" data-testid="task-action-reason" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="actionSubmitting" @click="actionVisible=false">取消</el-button><el-button type="primary" :loading="actionSubmitting" data-testid="task-action-submit" @click="submitReasonAction">确认{{ actionType === 'REASSIGN' ? '转派' : '退回' }}</el-button></template>
    </el-dialog>

    <el-drawer v-model="actionsVisible" size="min(620px,100vw)" :title="actionsTask ? `${actionsTask.title} · 流转记录` : '流转记录'">
      <div v-loading="actionsLoading" class="action-history" data-testid="task-action-history">
        <el-alert v-if="actionsError" type="error" :title="actionsError" :closable="false" />
        <ol v-if="actionLogs.length">
          <li v-for="entry in actionLogs" :key="entry.id">
            <div class="history-heading"><strong>{{ actionLabel(entry.action) }}</strong><time>{{ formatDateTime(entry.createdAt) }}</time></div>
            <p>状态：{{ statusLabel(entry.fromStatus) }} → {{ statusLabel(entry.toStatus) }}</p>
            <p>处理人：{{ ownerName(entry.fromAssigneeUserId ?? null) }} → {{ ownerName(entry.toAssigneeUserId ?? null) }}</p>
            <p>操作人：{{ ownerName(entry.actorUserId, '系统记录') }}</p>
            <blockquote>原因：{{ entry.reason || '—' }}</blockquote>
          </li>
        </ol>
        <div v-else-if="!actionsLoading && !actionsError" class="proto-empty">暂无流转记录</div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.task-kanban{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:18px}.kanban-col{min-height:420px;padding:14px;border-radius:13px;background:#f0f4f6}.kanban-head{display:flex;justify-content:space-between;padding:5px 4px 16px;font-weight:750}.kanban-card{margin-bottom:12px;padding:17px;border:1px solid #e7edf0;border-radius:11px;background:#fff;box-shadow:0 4px 12px rgb(21 51 69 / 3.5%)}.kanban-card h4{margin:12px 0 8px;font-size:14px}.kanban-card>p{margin:0 0 14px;color:#748e9b;font-size:12px;line-height:1.8}.task-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0 0 15px}.task-facts div{min-width:0}.task-facts dt{margin-bottom:3px;color:#91a4ae;font-size:10px}.task-facts dd{margin:0;color:#405d6c;font-size:11px;line-height:1.5;overflow-wrap:anywhere}.kanban-foot{display:flex;align-items:flex-start;justify-content:space-between;gap:6px;border-top:1px solid #edf2f4;padding-top:10px}.task-actions{display:flex;justify-content:flex-end;gap:6px;flex-wrap:wrap}.task-actions .el-button{margin-left:0}.action-task-title{margin:0 0 18px;color:#5f7886;font-size:13px}.action-alert{margin-bottom:18px}.action-history{min-height:160px}.action-history ol{margin:0;padding:0;list-style:none}.action-history li{padding:0 0 20px 18px;border-left:1px solid #dce6eb;margin-left:5px;position:relative}.action-history li:before{content:'';position:absolute;width:7px;height:7px;border-radius:50%;background:#4a809b;left:-4px;top:6px}.history-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.history-heading time{color:#8aa0ab;font-size:11px;white-space:nowrap}.action-history p{margin:8px 0 0;color:#607b89;font-size:12px}.action-history blockquote{margin:10px 0 0;padding:10px 12px;border-radius:7px;background:#f3f7f9;color:#405d6c;font-size:12px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}
@media(max-width:760px){.task-kanban{grid-template-columns:1fr}.kanban-col{min-height:180px}.kanban-foot{flex-direction:column}.task-actions{justify-content:flex-start;width:100%}.task-actions .el-button{min-height:36px}.task-facts{gap:12px 8px}}
</style>
