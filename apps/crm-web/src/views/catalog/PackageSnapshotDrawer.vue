<script setup lang="ts">
import type { PackageVersionSnapshot } from '../../api/catalog'
import StatusPill from '../customer/StatusPill.vue'
import { fenToYuan } from '../../utils/money'
import { optionGroupLabel, selectionModeLabel, skuStatusLabel, versionStatusLabel } from './catalogUi'

defineProps<{
  modelValue: boolean
  snapshot: PackageVersionSnapshot | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; createSku: [] }>()
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="min(900px, 96vw)"
    :title="snapshot ? `${snapshot.packageName} · V${snapshot.versionNo}` : '版本快照'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="snapshot">
      <div class="snapshot-head">
        <div>
          <div class="crm-muted">{{ snapshot.packageCode }} · 快照版本 {{ snapshot.version }}</div>
          <h3>{{ snapshot.selectionMode === 'CHOOSE_N' ? `可从候选子项目中选择 ${snapshot.choiceCount || '待定'} 项（不是使用次数）` : `${selectionModeLabel(snapshot.selectionMode)} / ${snapshot.choiceCount || '-'}` }}</h3>
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
        title="Q02 多项目金额分摊未配置：不会计算机构应结金额"
        class="block-gap"
      />
      <el-descriptions :column="2" border class="block-gap">
        <el-descriptions-item label="选择模式">{{ selectionModeLabel(snapshot.selectionMode) }}</el-descriptions-item>
        <el-descriptions-item label="次数 / 选项数">{{ snapshot.choiceCount || '-' }}</el-descriptions-item>
        <el-descriptions-item label="可发放权益">
          <StatusPill :text="snapshot.entitlementIssuable ? '配置完整' : '不可发放'" :tone="snapshot.entitlementIssuable ? 'success' : 'warning'" />
        </el-descriptions-item>
        <el-descriptions-item label="分摊状态">{{ snapshot.allocationStatus === 'PENDING_Q02' ? 'Q02 待确认' : '已配置' }}</el-descriptions-item>
        <el-descriptions-item label="选择规则" :span="2">{{ snapshot.selectionRules || '未填写；按待确认处理' }}</el-descriptions-item>
      </el-descriptions>

      <h4 class="section-title">子项目与权益模板</h4>
      <app-table :data="snapshot.projects" size="small" class="block-gap">
        <el-table-column prop="projectName" label="子项目" min-width="170" />
        <el-table-column prop="grantCount" label="次数 / 上限" width="110" align="right" />
        <el-table-column label="选项组" width="100">
          <template #default="{ row }">{{ optionGroupLabel(row.optionGroup) }}</template>
        </el-table-column>
        <el-table-column label="分摊基准（元）" width="130" align="right">
          <template #default="{ row }">{{ fenToYuan(row.allocationPriceFen) }}</template>
        </el-table-column>
      </app-table>

      <h4 class="section-title">关联 SKU</h4>
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
      <div class="snapshot-note">已激活版本只能创建新版本，不能覆盖本快照。订单阶段将另行保存成交时快照。</div>
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
