<script setup lang="ts">
import type { PackageVersionSnapshot } from '../../api/catalog'
import StatusPill from '../customer/StatusPill.vue'
import { fenToYuan } from '../../utils/money'
import { optionGroupLabel, selectionModeLabel, skuStatusLabel, versionStatusLabel } from './catalogUi'

const props = defineProps<{
  modelValue: boolean
  snapshot: PackageVersionSnapshot | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; createSku: [] }>()

function groupSummary(snapshot: PackageVersionSnapshot): string {
  return (snapshot.groups ?? [])
    .map((group) => `${group.groupName}：选 ${group.minSelections === group.maxSelections ? group.minSelections : `${group.minSelections}-${group.maxSelections}`} 项`)
    .join(' · ')
}

function groupName(groupCode: string): string {
  const group = props.snapshot?.groups.find((item) => item.groupCode === groupCode)
  return group ? `${group.groupName}（${group.groupCode}）` : optionGroupLabel(groupCode)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="min(960px, 96vw)"
    :title="snapshot ? `${snapshot.packageName} · V${snapshot.versionNo}` : '版本快照'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="snapshot">
      <div class="snapshot-head">
        <div>
          <div class="crm-muted">{{ snapshot.packageCode }} · 快照版本 {{ snapshot.version }}</div>
          <h3 v-if="snapshot.groups.length">{{ groupSummary(snapshot) }}</h3>
          <h3 v-else>{{ snapshot.selectionMode === 'CHOOSE_N' ? `可从候选子项目中选择 ${snapshot.choiceCount || '待定'} 项（不是使用次数）` : `${selectionModeLabel(snapshot.selectionMode)} / ${snapshot.choiceCount || '-'}` }}</h3>
        </div>
        <StatusPill :text="versionStatusLabel(snapshot.status)" :tone="snapshot.status === 'ACTIVE' ? 'success' : snapshot.status === 'DRAFT' ? 'warning' : 'dark'" />
      </div>
      <el-alert
        v-if="snapshot.rulesStatus === 'PENDING_Q01'"
        type="warning"
        :closable="false"
        title="Q01 选择规则未确认：当前仅为模板，不能上架发放客户权益"
        class="block-gap"
      />
      <el-alert
        v-if="snapshot.allocationStatus === 'PENDING_Q02'"
        type="info"
        :closable="false"
        title="Q02 多项目金额分摊未配置：不会计算机构应结金额，也不按单品参考价自动分摊退款"
        class="block-gap"
      />
      <el-descriptions :column="2" border class="block-gap">
        <el-descriptions-item label="选择模式">{{ selectionModeLabel(snapshot.selectionMode) }}</el-descriptions-item>
        <el-descriptions-item label="次数 / 选项数">{{ snapshot.choiceCount || '-' }}</el-descriptions-item>
        <el-descriptions-item label="可发放权益">
          <StatusPill :text="snapshot.entitlementIssuable ? '配置完整' : '不可发放'" :tone="snapshot.entitlementIssuable ? 'success' : 'warning'" />
        </el-descriptions-item>
        <el-descriptions-item label="分摊状态">{{ snapshot.allocationStatus === 'PENDING_Q02' ? 'Q02 待确认' : '已配置' }}</el-descriptions-item>
        <el-descriptions-item label="选择规则" :span="2">{{ snapshot.selectionRules || '未填写补充说明' }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="snapshot.groups.length">
        <h4 class="section-title">分组选择规则</h4>
        <app-table :data="snapshot.groups" size="small" class="block-gap">
          <el-table-column prop="groupCode" label="组编码" width="90" />
          <el-table-column prop="groupName" label="组名称" min-width="140" />
          <el-table-column label="最少选择" width="100" align="right"><template #default="{ row }">{{ row.minSelections }}</template></el-table-column>
          <el-table-column label="最多选择" width="100" align="right"><template #default="{ row }">{{ row.maxSelections }}</template></el-table-column>
          <el-table-column prop="sortOrder" label="顺序" width="80" align="right" />
        </app-table>
      </template>

      <h4 class="section-title">子项目与权益模板</h4>
      <app-table :data="snapshot.projects" size="small" class="block-gap">
        <el-table-column prop="projectName" label="子项目" min-width="160" />
        <el-table-column prop="grantCount" label="次数" width="80" align="right" />
        <el-table-column label="选项组" min-width="130">
          <template #default="{ row }">{{ groupName(row.optionGroup) }}</template>
        </el-table-column>
        <el-table-column label="单品参考价（元）" width="135" align="right">
          <template #default="{ row }">{{ fenToYuan(row.referencePriceFen) }}</template>
        </el-table-column>
        <el-table-column label="分摊基准（元）" width="125" align="right">
          <template #default="{ row }">{{ fenToYuan(row.allocationPriceFen) }}</template>
        </el-table-column>
      </app-table>

      <h4 class="section-title">关联 SKU（独立售价）</h4>
      <app-table :data="snapshot.skus" size="small" class="block-gap">
        <el-table-column prop="skuCode" label="SKU 编码" min-width="170" />
        <el-table-column prop="specName" label="规格" min-width="100" />
        <el-table-column label="售价（元）" width="110" align="right">
          <template #default="{ row }">{{ fenToYuan(row.salePriceFen) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ skuStatusLabel(row.status) }}</template>
        </el-table-column>
      </app-table>

      <h4 class="section-title">适用机构</h4>
      <div class="institution-list">
        <el-tag v-for="item in snapshot.institutions" :key="item.id" effect="plain">
          {{ item.shortName || item.fullName }}（{{ item.cityCode || '未设城市' }}）
        </el-tag>
        <span v-if="snapshot.institutions.length === 0" class="crm-muted">尚未配置机构，不能上架</span>
      </div>
      <div class="snapshot-note">已激活版本只能创建新版本，不能覆盖本快照。订单保存实际组与项目、次数和参考价，套餐售价不要求等于参考价合计。</div>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button v-if="snapshot && snapshot.status !== 'ARCHIVED'" type="primary" @click="emit('createSku')">
        {{ snapshot?.entitlementIssuable ? '基于此版本创建 SKU' : '创建模板 SKU' }}
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.snapshot-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.snapshot-head h3 { margin: 6px 0 0; font-size: 21px; }
.block-gap { margin-bottom: 18px; }
.section-title { margin: 22px 0 10px; font-size: 14px; }
.institution-list { display: flex; flex-wrap: wrap; gap: 8px; }
.snapshot-note { margin-top: 22px; color: #79909f; font-size: 12px; line-height: 1.8; }
</style>
