<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { DictionaryItemDetail, DictionaryTypeItem } from '../api/dictionaryAdmin'
import { createDictionaryItem, disableDictionaryItem, listDictionaryItems, listDictionaryTypes, updateDictionaryItem } from '../api/dictionaryAdmin'
import { listAuditLogs } from '../api/audit'
import type { AuditLogItem } from '../api/audit'
import { useAuthStore } from '../stores/auth'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'

const auth = useAuthStore(), types = ref<DictionaryTypeItem[]>([]), grouped = ref<Record<number, DictionaryItemDetail[]>>({}), audits = ref<AuditLogItem[]>([])
const loading = ref(false), errorMessage = ref('')
const dialogVisible = ref(false), editing = ref<DictionaryItemDetail | null>(null), currentType = ref<DictionaryTypeItem | null>(null), saving = ref(false)
const selectedTypeId = ref<number | null>(null), keyword = ref(''), statusFilter = ref('ALL')
const form = reactive({ optionCode: '', optionLabel: '', sortNo: 0, version: 0 })
const DICTIONARY_ACTION_LABELS: Record<string, string> = {
  DICTIONARY_ITEM_CREATE: '新增字典配置',
  DICTIONARY_ITEM_UPDATE: '修改字典配置',
  DICTIONARY_ITEM_DISABLE: '停用字典配置',
}
const activeType = computed(() => types.value.find((type) => type.id === selectedTypeId.value) ?? null)
const currentItems = computed(() => activeType.value ? grouped.value[activeType.value.id] ?? [] : [])
const filteredItems = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  return currentItems.value.filter((item) => {
    const matchesKeyword = !query || item.optionLabel.toLocaleLowerCase().includes(query) || item.optionCode.toLocaleLowerCase().includes(query)
    return matchesKeyword && (statusFilter.value === 'ALL' || item.status === statusFilter.value)
  })
})
function dictionaryActionLabel(actionCode: string) { return DICTIONARY_ACTION_LABELS[actionCode] ?? '其他字典操作' }
function dictionaryTargetLabel(targetType: string) { return targetType === 'DICTIONARY_ITEM' ? '字典配置' : '其他对象' }
function errorText(error: unknown) { return error instanceof Error ? error.message : '操作失败' }
function activeCount(type: DictionaryTypeItem) { return grouped.value[type.id]?.filter((item) => item.status === 'ACTIVE').length ?? 0 }
function selectType(type: DictionaryTypeItem) {
  selectedTypeId.value = type.id
  keyword.value = ''
  statusFilter.value = 'ALL'
}
async function loadTypes() {
  loading.value = true
  try {
    types.value = await listDictionaryTypes()
    if (!types.value.some((type) => type.id === selectedTypeId.value)) selectedTypeId.value = types.value[0]?.id ?? null
    const results = await Promise.allSettled(types.value.map((type) => listDictionaryItems(type.id)))
    results.forEach((result, index) => { if (result.status === 'fulfilled') grouped.value[types.value[index].id] = result.value })
  } catch (error) { errorMessage.value = errorText(error) }
  finally { loading.value = false }
}
async function loadType(type: DictionaryTypeItem) { grouped.value[type.id] = await listDictionaryItems(type.id) }
function openCreate(type: DictionaryTypeItem) {
  currentType.value = type
  editing.value = null
  Object.assign(form, { optionCode: '', optionLabel: '', sortNo: 0, version: 0 })
  dialogVisible.value = true
}
function openEdit(type: DictionaryTypeItem, item: DictionaryItemDetail) {
  currentType.value = type
  editing.value = item
  Object.assign(form, { optionCode: item.optionCode, optionLabel: item.optionLabel, sortNo: item.sortNo, version: item.version })
  dialogVisible.value = true
}
async function save() {
  if (!currentType.value || !form.optionCode.trim() || !form.optionLabel.trim()) { ElMessage.warning('请填写编码与名称'); return }
  saving.value = true
  try {
    if (editing.value) await updateDictionaryItem(editing.value.id, { optionLabel: form.optionLabel, sortNo: form.sortNo, version: form.version })
    else await createDictionaryItem({ dictCode: currentType.value.dictCode, optionCode: form.optionCode, optionLabel: form.optionLabel, sortNo: form.sortNo })
    ElMessage.success('配置已保存')
    dialogVisible.value = false
    await loadType(currentType.value)
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
async function disable(type: DictionaryTypeItem, item: DictionaryItemDetail) {
  try { await ElMessageBox.confirm(`停用「${item.optionLabel}」后不再用于新建表单，历史值仍保留。确认停用？`, '停用配置', { type: 'warning' }) } catch { return }
  try { await disableDictionaryItem(item.id); await loadType(type); ElMessage.success('已停用，历史数据不受影响') } catch (error) { ElMessage.error(errorText(error)) }
}
function handleItemCommand(command: string, type: DictionaryTypeItem, item: DictionaryItemDetail) {
  if (command === 'disable') void disable(type, item)
}
onMounted(async () => {
  await loadTypes()
  if (auth.hasPermission('audit:read')) audits.value = (await listAuditLogs({ page: 1, size: 30 })).items.filter((item) => item.actionCode.startsWith('DICTIONARY_')).slice(0, 20)
})
</script>

<template>
  <div class="proto-page dictionaries-page">
    <PageHeader title="基础字典 / 列表配置" description="业务下拉、状态与展示值统一配置；已引用配置只能停用，不物理删除历史事实" />
    <div class="dictionary-notice">
      <span class="proto-pill success">可配置化</span>
      <p><strong>{{ types.length }} 类真实字典配置</strong><span>新增、修改与停用均写审计，已停用值不出现在新建表单。</span></p>
    </div>
    <el-alert v-if="errorMessage" class="dictionary-alert" type="error" :title="errorMessage" :closable="false" />

    <div v-loading="loading" class="dictionary-layout">
      <PageCard title="字典分类" :subtitle="`${types.length} 个分类`" class="dictionary-types-panel">
        <nav class="dictionary-type-list" aria-label="字典分类">
          <button
            v-for="type in types"
            :key="type.id"
            type="button"
            class="dictionary-type-button"
            :class="{ 'is-active': selectedTypeId === type.id }"
            :aria-pressed="selectedTypeId === type.id"
            :data-testid="`dictionary-type-${type.id}`"
            @click="selectType(type)"
          >
            <span class="dictionary-type-heading"><strong>{{ type.dictName }}</strong><small>{{ activeCount(type) }} 启用 / {{ grouped[type.id]?.length ?? 0 }} 总数</small></span>
            <code>{{ type.dictCode }}</code>
          </button>
        </nav>
        <div v-if="!loading && types.length === 0" class="dictionary-types-empty">暂无字典分类</div>
      </PageCard>

      <PageCard
        class="dictionary-items-panel"
        :title="activeType?.dictName ?? '字典配置'"
        :subtitle="activeType ? `维护「${activeType.dictName}」的显示名称、保存值与展示顺序；停用不影响历史数据。` : '请选择左侧字典分类'"
      >
        <template #actions>
          <el-button v-if="activeType && auth.hasPermission('dictionary:write')" type="primary" data-testid="dictionary-create" @click="openCreate(activeType)">新增配置</el-button>
        </template>
        <template v-if="activeType">
          <div class="dictionary-filter-bar">
            <el-input v-model="keyword" class="dictionary-search" clearable aria-label="搜索名称或保存值" placeholder="搜索名称或保存值" data-testid="dictionary-search" />
            <el-select v-model="statusFilter" class="dictionary-status-filter" aria-label="状态筛选" data-testid="dictionary-status-filter">
              <el-option label="全部状态" value="ALL" />
              <el-option label="仅启用" value="ACTIVE" />
              <el-option label="仅停用" value="INACTIVE" />
            </el-select>
            <span class="dictionary-result-count">显示 {{ filteredItems.length }} / {{ currentItems.length }} 项</span>
          </div>
          <div class="dictionary-items-body">
            <div class="dictionary-list" :class="{ 'has-actions': auth.hasPermission('dictionary:write') }" role="table" :aria-label="`${activeType.dictName}配置列表`">
              <div class="dictionary-list-head" role="row">
                <span role="columnheader">名称</span><span role="columnheader">保存值</span><span role="columnheader">排序</span><span role="columnheader">状态</span><span v-if="auth.hasPermission('dictionary:write')" role="columnheader">操作</span>
              </div>
              <div v-for="item in filteredItems" :key="item.id" class="dictionary-item" role="row" :data-testid="`dictionary-item-${item.id}`">
                <div class="dictionary-cell dictionary-item-name" role="cell"><span class="dictionary-cell-label">名称</span><strong>{{ item.optionLabel }}</strong></div>
                <div class="dictionary-cell dictionary-item-code" role="cell"><span class="dictionary-cell-label">保存值</span><code>{{ item.optionCode }}</code></div>
                <div class="dictionary-cell dictionary-item-sort" role="cell"><span class="dictionary-cell-label">排序</span><span class="number">{{ item.sortNo }}</span></div>
                <div class="dictionary-cell" role="cell"><span class="dictionary-cell-label">状态</span><span class="proto-pill" :class="item.status === 'ACTIVE' ? 'success' : 'warning'">{{ item.status === 'ACTIVE' ? '启用' : '停用' }}</span></div>
                <div v-if="auth.hasPermission('dictionary:write')" class="dictionary-cell dictionary-row-actions" role="cell">
                  <el-button text data-testid="dictionary-edit" @click="openEdit(activeType, item)">修改</el-button>
                  <el-dropdown trigger="click" placement="bottom-end" popper-class="dictionary-actions-popper" @command="handleItemCommand($event, activeType, item)">
                    <el-button text data-testid="dictionary-more" aria-label="更多操作">更多<span aria-hidden="true">⌄</span></el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item v-if="item.status === 'ACTIVE'" command="disable" data-testid="dictionary-disable">停用配置</el-dropdown-item>
                        <el-dropdown-item disabled class="dictionary-delete-item">
                          <div data-testid="dictionary-delete-reason"><span>删除不可用</span><small>已引用配置仅可停用，不可物理删除</small></div>
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>
              <div v-if="filteredItems.length === 0" class="dictionary-empty">
                <strong>{{ currentItems.length ? '没有匹配的配置' : '暂无配置项' }}</strong>
                <span>{{ currentItems.length ? '请调整搜索关键词或状态筛选。' : '可通过右上角“新增配置”维护当前分类。' }}</span>
              </div>
            </div>
          </div>
        </template>
        <div v-else class="dictionary-empty"><strong>请选择字典分类</strong><span>从左侧分类进入后维护配置项。</span></div>
      </PageCard>
    </div>

    <PageCard title="最近编辑日志" subtitle="来自真实审计记录，仅展示字典相关操作" class="dictionary-audits">
      <template #actions><RouterLink to="/audit-logs" class="el-button">完整审计日志</RouterLink></template>
      <div class="proto-card-body">
        <div class="dictionary-audit-list">
          <div class="dictionary-audit-head"><span>时间</span><span>操作人</span><span>对象</span><span>编号</span><span>动作</span></div>
          <div v-for="row in audits" :key="row.id" class="dictionary-audit-row">
            <span data-label="时间">{{ row.createdAt }}</span>
            <span data-label="操作人">{{ row.actorUserId === null ? '系统' : `用户 #${row.actorUserId}` }}</span>
            <span data-label="对象">{{ dictionaryTargetLabel(row.targetType) }}</span>
            <span data-label="编号">{{ row.targetId }}</span>
            <span data-label="动作">{{ dictionaryActionLabel(row.actionCode) }}</span>
          </div>
          <div v-if="audits.length === 0" class="dictionary-empty"><strong>暂无字典编辑日志</strong></div>
        </div>
      </div>
    </PageCard>

    <el-dialog v-model="dialogVisible" :title="editing ? '修改配置' : '新增配置'" width="480px">
      <el-form label-position="top">
        <el-form-item label="字典类型"><el-input :model-value="currentType?.dictName" disabled /></el-form-item>
        <el-form-item label="保存编码" required><el-input v-model="form.optionCode" :disabled="!!editing" /></el-form-item>
        <el-form-item label="显示名称" required><el-input v-model="form.optionLabel" /></el-form-item>
        <el-form-item label="排序号"><el-input-number v-model="form.sortNo" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dictionaries-page { min-width: 0; }
.dictionary-notice { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; padding: 14px 18px; border: 1px solid #cce3dc; border-radius: 12px; background: #f1f8f5; }
.dictionary-notice p { display: flex; gap: 14px; margin: 0; color: #69847b; font-size: 12px; line-height: 1.7; flex-wrap: wrap; }
.dictionary-notice strong { color: #34584d; }
.dictionary-alert { margin-bottom: 16px; }
.dictionary-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 18px; align-items: start; }
.dictionary-layout > .proto-card { margin-top: 0; }
.dictionary-types-panel { overflow: hidden; }
.dictionary-type-list { display: flex; padding: 16px 12px 12px; flex-direction: column; gap: 5px; }
.dictionary-type-button { width: 100%; min-width: 0; min-height: 78px; padding: 12px 14px; border: 1px solid transparent; border-radius: 10px; color: #355248; background: transparent; text-align: left; cursor: pointer; transition: background-color 160ms ease, border-color 160ms ease; }
.dictionary-type-button:hover { background: #f3f8f6; }
.dictionary-type-button.is-active { border-color: #d8e8df; background: #edf6f1; box-shadow: inset 3px 0 #b49558; }
.dictionary-type-heading { display: flex; min-width: 0; align-items: flex-start; flex-direction: column; gap: 4px; }
.dictionary-type-heading strong { color: #29483e; font-size: 13px; font-weight: 720; }
.dictionary-type-heading small { color: #7c9288; font-size: 11px; }
.dictionary-type-button code { display: block; margin-top: 8px; color: #9aa89f; font-family: inherit; font-size: 10px; overflow-wrap: anywhere; }
.dictionary-types-empty { padding: 24px 16px; color: #8a9d94; text-align: center; }
.dictionary-items-panel { min-width: 0; }
.dictionary-filter-bar { display: flex; min-width: 0; align-items: center; gap: 10px; margin-top: 18px; padding: 16px 22px; border-top: 1px solid #edf1ef; border-bottom: 1px solid #e8eeeb; background: #fbfcfb; flex-wrap: wrap; }
.dictionary-search { width: min(320px, 100%); }
.dictionary-status-filter { width: 128px; }
.dictionary-result-count { margin-left: auto; color: #899b92; font-size: 11px; white-space: nowrap; }
.dictionary-items-body { min-width: 0; padding: 0 22px 18px; }
.dictionary-list { min-width: 0; }
.dictionary-list-head, .dictionary-item { display: grid; grid-template-columns: minmax(130px, 1.35fr) minmax(110px, 1fr) 58px 76px; gap: 14px; align-items: center; min-width: 0; }
.dictionary-list.has-actions .dictionary-list-head, .dictionary-list.has-actions .dictionary-item { grid-template-columns: minmax(130px, 1.35fr) minmax(110px, 1fr) 58px 76px 118px; }
.dictionary-list-head { min-height: 44px; color: #879b91; font-size: 11px; font-weight: 650; }
.dictionary-item { min-height: 60px; padding: 11px 0; border-top: 1px solid #edf1ef; }
.dictionary-item:hover { background: #fafcfa; }
.dictionary-cell { min-width: 0; color: #526b60; }
.dictionary-cell-label { display: none; }
.dictionary-item-name strong { color: #2e4b40; font-size: 13px; font-weight: 680; overflow-wrap: anywhere; }
.dictionary-item-code code { display: block; color: #8a9c93; font-family: inherit; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; word-break: break-word; }
.dictionary-row-actions { display: flex; align-items: center; gap: 9px; white-space: nowrap; }
.dictionary-row-actions .el-button { min-height: 34px; margin: 0; padding: 4px 2px; }
.dictionary-row-actions .el-button span { display: inline-flex; gap: 5px; align-items: center; }
.dictionary-delete-item { height: auto; line-height: 1.5; }
.dictionary-delete-item div { display: flex; padding: 5px 0; flex-direction: column; gap: 3px; white-space: normal; }
.dictionary-delete-item small { color: #9a8f83; font-size: 11px; }
.dictionary-empty { display: flex; min-height: 150px; align-items: center; justify-content: center; flex-direction: column; gap: 7px; padding: 28px 18px; color: #94a59d; text-align: center; }
.dictionary-empty strong { color: #516c60; font-size: 13px; }
.dictionary-empty span { font-size: 11px; line-height: 1.7; }
.dictionary-audits { margin-top: 18px; }
.dictionary-audit-head, .dictionary-audit-row { display: grid; grid-template-columns: minmax(160px, 1.2fr) 100px 100px 80px minmax(140px, 1fr); gap: 14px; align-items: center; min-width: 0; }
.dictionary-audit-head { min-height: 38px; color: #8c9d95; font-size: 11px; }
.dictionary-audit-row { padding: 12px 0; border-top: 1px solid #edf1ef; color: #5c7268; font-size: 11px; }
.dictionary-audit-row > span { min-width: 0; overflow-wrap: anywhere; }
.dictionary-audit-row > span::before { display: none; }
:global(.dictionary-actions-popper .el-dropdown-menu__item) { min-height: 38px; }

@media (max-width: 1100px) {
  .dictionary-layout { grid-template-columns: 220px minmax(0, 1fr); }
  .dictionary-list-head, .dictionary-item, .dictionary-list.has-actions .dictionary-list-head, .dictionary-list.has-actions .dictionary-item { grid-template-columns: minmax(105px, 1.2fr) minmax(95px, 1fr) 48px 68px 108px; gap: 10px; }
  .dictionary-list:not(.has-actions) .dictionary-list-head, .dictionary-list:not(.has-actions) .dictionary-item { grid-template-columns: minmax(105px, 1.2fr) minmax(95px, 1fr) 48px 68px; }
}
@media (max-width: 900px) {
  .dictionary-layout { grid-template-columns: minmax(0, 1fr); }
  .dictionary-types-panel .proto-card-head { padding-bottom: 14px; }
  .dictionary-type-list { padding: 10px 12px 14px; overflow-x: auto; flex-direction: row; gap: 9px; overscroll-behavior-x: contain; scrollbar-width: thin; }
  .dictionary-type-button { width: 190px; min-height: 82px; flex: 0 0 190px; scroll-snap-align: start; }
  .dictionary-type-list { scroll-snap-type: x proximity; }
  .dictionary-type-button.is-active { box-shadow: inset 0 -3px #b49558; }
}
@media (max-width: 640px) {
  .dictionary-notice { align-items: flex-start; padding: 12px 14px; }
  .dictionary-notice p { gap: 2px; flex-direction: column; }
  .dictionary-filter-bar { align-items: stretch; padding: 14px 16px; }
  .dictionary-search, .dictionary-status-filter { width: 100%; }
  .dictionary-filter-bar :deep(.el-input__wrapper), .dictionary-filter-bar :deep(.el-select__wrapper) { min-height: 44px; }
  .dictionary-result-count { margin-left: 0; }
  .dictionary-items-body { padding: 0 16px 16px; }
  .dictionary-list-head { display: none; }
  .dictionary-item, .dictionary-list.has-actions .dictionary-item, .dictionary-list:not(.has-actions) .dictionary-item { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 18px; padding: 17px 0; }
  .dictionary-item:first-of-type { border-top: 0; }
  .dictionary-item-name, .dictionary-item-code { grid-column: 1 / -1; }
  .dictionary-cell-label { display: block; margin-bottom: 6px; color: #91a299; font-size: 10px; }
  .dictionary-row-actions { grid-column: 1 / -1; justify-content: flex-end; gap: 18px; padding-top: 10px; border-top: 1px solid #edf1ef; }
  .dictionary-row-actions .el-button { min-height: 44px; padding: 8px 4px; }
  .dictionary-items-panel :deep(.proto-card-head) { align-items: flex-start; }
  .dictionary-items-panel :deep(.proto-card-head .el-button) { min-height: 44px; }
  .dictionary-audit-head { display: none; }
  .dictionary-audit-row { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 15px 0; }
  .dictionary-audit-row > span:first-child, .dictionary-audit-row > span:last-child { grid-column: 1 / -1; }
  .dictionary-audit-row > span::before { display: block; margin-bottom: 4px; color: #93a49b; content: attr(data-label); font-size: 10px; }
  :global(.dictionary-actions-popper .el-dropdown-menu__item) { min-height: 44px; }
}
</style>
