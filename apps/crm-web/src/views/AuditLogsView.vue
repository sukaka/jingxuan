<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { AuditLogItem } from '../api/audit'
import { listAuditLogs } from '../api/audit'
import { ApiError } from '../types/api'

const query = reactive({
  actorId: undefined as number | undefined,
  actionCode: '',
  targetType: '',
  page: 1,
  size: 20,
})
const logs = ref<AuditLogItem[]>([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const ACTION_LABELS: Record<string, string> = {
  'auth.login': '账号登录',
  'auth.logout': '退出登录',
  'user.create': '新建用户',
  'user.update': '编辑用户',
  'user.disable': '停用用户',
  'user.roles.update': '更新用户角色',
  'role.create': '新建角色',
  'role.permissions.update': '更新角色权限',
  DICTIONARY_ITEM_CREATE: '新增字典配置',
  DICTIONARY_ITEM_UPDATE: '修改字典配置',
  DICTIONARY_ITEM_DISABLE: '停用字典配置',
  DICTIONARY_ITEM_ENABLE: '启用字典配置',
  EXPORT_REQUEST: '申请数据导出',
  EXPORT_DOWNLOAD: '下载导出文件',
  CUSTOMER_CREATE: '新建客户',
  CUSTOMER_UPDATE: '更新客户资料',
  CUSTOMER_TAGS_UPDATE: '更新客户标签',
  CUSTOMER_OWNER_CHANGE: '变更归属管家',
  ORDER_CREATE: '创建订单',
  ORDER_PAYMENT_CONFIRM: '确认订单收款',
  ORDER_CANCEL: '取消订单',
  REFUND_CREATE: '发起退款',
  REFUND_APPROVE: '审批退款',
  APPOINTMENT_CREATE: '创建预约',
  APPOINTMENT_CONFIRM: '确认预约',
  REDEMPTION_CONFIRM: '确认核销',
  INSTITUTION_CREATE: '新增合作机构',
  INSTITUTION_UPDATE: '编辑合作机构',
  MATERIAL_CREATE: '新增素材',
  MATERIAL_APPROVE: '审核素材',
  QUICK_REPLY_CREATE: '新增话术模板',
  QUICK_REPLY_UPDATE: '修改话术模板',
  QUICK_REPLY_DELETE: '停用话术模板',
  AI_SETTINGS_UPDATE: '更新 AI 配置',
  WECOM_CONFIG_UPDATE: '更新企微配置',
  WECOM_TAG_CATALOG_SYNC: '企微标签库同步',
  WECOM_TAG_MAPPING_UPDATE: '更新企微标签映射',
  WECOM_CUSTOMER_TAG_PULL: '企微客户标签拉取',
  WECOM_CUSTOMER_TAG_PUSH: '企微客户标签推送',
  YOUZAN_CONFIG_UPDATE: '更新有赞配置',
  YOUZAN_CUSTOMER_SYNC: '有赞客户拉取建档',
  YOUZAN_CUSTOMER_CURSOR_RESET: '重置有赞客户同步游标',
}

const TARGET_TYPE_LABELS: Record<string, string> = {
  sys_user: '系统用户',
  sys_role: '系统角色',
  USER: '系统用户',
  ROLE: '系统角色',
  DICTIONARY_ITEM: '基础字典',
  CUSTOMER: '客户档案',
  ORDER: '业务订单',
  REFUND: '退款申请',
  APPOINTMENT: '预约记录',
  REDEMPTION: '核销记录',
  ENTITLEMENT: '客户权益',
  INSTITUTION: '合作机构',
  SETTLEMENT: '财务结算',
  MATERIAL: '合规素材',
  QUICK_REPLY: '快捷话术',
  EXPORT_JOB: '导出任务',
  NOTIFICATION: '系统通知',
  COMPLAINT: '客诉工单',
  FOLLOWUP: '客户跟进',
  TASK: '回访任务',
  WECOM_CONTACT: '企微联系人',
  YOUZAN_ORDER: '有赞订单',
}

const ACTION_VERB_LABELS: Record<string, string> = {
  create: '新建',
  update: '更新',
  delete: '删除',
  disable: '停用',
  enable: '启用',
  approve: '审核通过',
  reject: '驳回',
  confirm: '确认',
  cancel: '取消',
  login: '登录',
  logout: '退出登录',
  export: '导出',
  download: '下载',
  sync: '同步',
  bind: '关联',
  link: '关联',
  unbind: '解除关联',
  send: '发送',
  revoke: '撤销',
}

function actionLabel(code: string): string {
  if (!code) return '—'
  if (ACTION_LABELS[code]) return ACTION_LABELS[code]
  const normalized = code.toLowerCase().replace(/\./g, '_')
  const parts = normalized.split('_').filter(Boolean)
  const verb = parts.length ? ACTION_VERB_LABELS[parts[parts.length - 1]!] : undefined
  const target = parts.length > 1
    ? TARGET_TYPE_LABELS[parts[0]!.toUpperCase()] ?? TARGET_TYPE_LABELS[parts[0]!]
    : undefined
  if (target && verb) return `${verb}${target}`
  if (verb) return `${verb}操作`
  return '系统操作'
}

function targetTypeLabel(type: string): string {
  if (!type) return '—'
  return TARGET_TYPE_LABELS[type] ?? TARGET_TYPE_LABELS[type.toUpperCase()] ?? '业务对象'
}

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString()
}

function formatSnapshot(value: unknown): string {
  if (value === null || value === undefined) return '-'
  // 后端 JSON 列可能以字符串形式返回,直接展示原文;对象则格式化
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

async function load(page = query.page) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAuditLogs({
      page,
      size: query.size,
      actorId: query.actorId ?? null,
      actionCode: query.actionCode,
      targetType: query.targetType,
    })
    logs.value = result.items
    total.value = result.total
    query.page = result.page
    query.size = result.size
  } catch (error) {
    logs.value = []
    total.value = 0
    errorMessage.value =
      error instanceof ApiError ? error.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function search() {
  void load(1)
}

function resetQuery() {
  query.actorId = undefined
  query.actionCode = ''
  query.targetType = ''
  void load(1)
}

onMounted(() => load())
</script>

<template>
  <div>
    <h2 class="page-title">审计日志</h2>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input-number
          v-model="query.actorId"
          :min="1"
          controls-position="right"
          placeholder="操作人 ID"
          class="filter-number"
        />
        <el-input
          v-model="query.actionCode"
          placeholder="按操作类型筛选"
          class="filter-input"
          clearable
          @keyup.enter="search"
        />
        <el-input
          v-model="query.targetType"
          placeholder="按对象类型筛选"
          class="filter-input"
          clearable
          @keyup.enter="search"
        />
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        :title="`加载失败：${errorMessage}`"
        class="table-alert"
      />

      <app-table v-else v-loading="loading" :data="logs">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="snapshot">
              <div>
                <div class="snapshot-title">变更前内容（已脱敏）</div>
                <pre class="snapshot-json">{{ formatSnapshot(row.beforeJson) }}</pre>
              </div>
              <div>
                <div class="snapshot-title">变更后内容（已脱敏）</div>
                <pre class="snapshot-json">{{ formatSnapshot(row.afterJson) }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="actorUserId" label="操作人" width="90">
          <template #default="{ row }">{{ row.actorUserId ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作动作" min-width="140">
          <template #default="{ row }">{{ actionLabel(row.actionCode) }}</template>
        </el-table-column>
        <el-table-column label="业务对象" width="120">
          <template #default="{ row }">{{ targetTypeLabel(row.targetType) }}</template>
        </el-table-column>
        <el-table-column prop="targetId" label="对象 ID" width="100" />
        <el-table-column prop="clientIp" label="客户端 IP" width="130">
          <template #default="{ row }">{{ row.clientIp ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="traceId" label="请求追踪号" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.traceId ?? '-' }}</template>
        </el-table-column>
        <template #empty>
          <span>暂无审计日志</span>
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
    </el-card>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-number {
  width: 150px;
}

.filter-input {
  width: 220px;
}

.table-alert {
  margin-bottom: 4px;
}

.snapshot {
  display: flex;
  gap: 24px;
  padding: 8px 16px;
}

.snapshot-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.snapshot-json {
  margin: 0;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  max-width: 480px;
  overflow: auto;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
