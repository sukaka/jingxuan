<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteFollowup, listFollowups } from '../api/followup'
import type { FollowupItem } from '../api/followup'
import { useAuthStore } from '../stores/auth'
import FollowupFormDialog from './customer/FollowupFormDialog.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import { fenToYuan, formatDateTime, useDictionaries } from './customer/shared'

const auth = useAuthStore(), dicts = useDictionaries()
const items = ref<FollowupItem[]>([]), total = ref(0), loading = ref(false), errorMessage = ref('')
const summary = reactive({ today: 0, upcoming: 0 })
const dialogVisible = ref(false), editingFollowup = ref<FollowupItem | null>(null)
const today = new Date().toISOString().slice(0, 10)
async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listFollowups({ page: 1, size: 100 })
    items.value = result.items
    total.value = result.total
    summary.today = result.items.filter((item) => item.contactAt?.slice(0, 10) === today).length
    summary.upcoming = result.items.filter((item) => item.nextFollowupAt && item.nextFollowupAt.slice(0, 10) >= today).length
  } catch (error) { errorMessage.value = `加载失败：${error instanceof Error ? error.message : '跟进加载失败'}` }
  finally { loading.value = false }
}
function openCreate() { editingFollowup.value = null; dialogVisible.value = true }
function openEdit(row: FollowupItem) { editingFollowup.value = row; dialogVisible.value = true }
async function remove(row: FollowupItem) {
  try { await ElMessageBox.confirm('仅软删除误录记录并保留审计，确认删除？', '删除跟进记录', { type: 'warning' }) } catch { return }
  try { await deleteFollowup(row.id, row.version); ElMessage.success('误录记录已软删除'); await load() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除失败') }
}
onMounted(() => { void dicts.load('communicationChannels'); void load() })
</script>

<template>
  <div class="proto-page">
    <PageHeader title="每日跟进台账" description="每次沟通独立流水；支持修改/删除误录记录，并保留编辑日志"><template #actions><el-button v-if="auth.hasPermission('followup:write')" type="primary" @click="openCreate">＋ 新增沟通记录</el-button></template></PageHeader>
    <div class="proto-stat-grid three"><StatCard label="跟进记录" :value="`${total} 条`" sub="当前数据范围" icon="chat" /><StatCard label="今天沟通客户" :value="`${summary.today} 位`" sub="按真实沟通时间" icon="users" /><StatCard label="近期待跟进" :value="`${summary.upcoming} 条`" sub="已设置下次跟进" icon="calendar" /></div>
    <PageCard>
      <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" /><div class="proto-table-wrap">
        <app-table v-loading="loading" :data="items">
          <el-table-column label="日期" width="115"><template #default="{row}">{{ row.contactAt?.slice(0,10) || '—' }}</template></el-table-column>
          <el-table-column label="客户" min-width="110"><template #default="{row}">{{ row.customerName || `客户 #${row.customerId}` }}</template></el-table-column>
          <el-table-column label="管家" width="90"><template #default="{row}">{{ row.ownerStaffName || `#${row.ownerStaffId}` }}</template></el-table-column>
          <el-table-column label="方式" width="100"><template #default="{row}">{{ dicts.label('communicationChannels',row.channelCode) }}</template></el-table-column>
          <el-table-column label="关注项目" min-width="120"><template #default="{row}">{{ row.intentProject || '—' }}<div class="proto-subcell">预计 ¥{{ fenToYuan(row.estimatedAmountFen) }}</div></template></el-table-column>
          <el-table-column label="下次跟进" width="120"><template #default="{row}">{{ row.nextFollowupAt?.slice(0,10) || '待设置' }}</template></el-table-column>
          <el-table-column prop="blockerNote" label="卡点" min-width="130" show-overflow-tooltip />
          <el-table-column prop="summary" label="沟通内容" min-width="220" show-overflow-tooltip />
          <el-table-column v-if="auth.hasPermission('followup:write')" label="操作" width="130" fixed="right"><template #default="{row}"><el-button text @click="openEdit(row)">修改</el-button><el-button text class="danger-link" @click="remove(row)">删除</el-button></template></el-table-column>
          <template #empty>暂无跟进记录</template>
        </app-table>
      </div>
    </PageCard>
    <footer class="proto-footer">每次沟通独立追加；软删除不物理抹除审计历史 · {{ formatDateTime(new Date().toISOString()) }}</footer>
    <FollowupFormDialog v-model="dialogVisible" :followup="editingFollowup" @saved="load" />
  </div>
</template>
<style scoped>.danger-link{color:#ce5c64!important}</style>
