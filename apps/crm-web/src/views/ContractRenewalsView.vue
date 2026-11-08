<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listInstitutionContracts, listInstitutionProfiles } from '../api/institution'
import type { InstitutionContract } from '../api/institution'
import PageHeader from '../components/prototype/PageHeader.vue'
import StatCard from '../components/prototype/StatCard.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatusPill from './customer/StatusPill.vue'
import { documentStatusLabel } from './institution/institutionUi'

const rows = ref<(InstitutionContract & { institutionName: string })[]>([]), loading = ref(false)
const today = new Date().toISOString().slice(0, 10)
const expiring = computed(() => rows.value.filter((row) => row.status === 'ACTIVE' && row.endDate && row.endDate >= today && row.endDate.slice(0, 10) <= addDays(90)).length)
const expired = computed(() => rows.value.filter((row) => row.status === 'ACTIVE' && row.endDate && row.endDate.slice(0, 10) < today).length)
function addDays(days: number) { const date = new Date(); date.setDate(date.getDate() + days); return date.toISOString().slice(0, 10) }
onMounted(async () => {
  loading.value = true
  try {
    const profiles = (await listInstitutionProfiles({ page: 1, size: 100 })).items
    const results = await Promise.allSettled(profiles.map((profile) => listInstitutionContracts({ institutionId: profile.id, page: 1, size: 100 })))
    rows.value = results.flatMap((result, index) => result.status === 'fulfilled' ? result.value.items.map((contract) => ({ ...contract, institutionName: profiles[index].fullName })) : [])
  } finally { loading.value = false }
})
</script>

<template><div class="proto-page"><PageHeader title="合同续签管理 · 1.8 版" description="按真实合同版本与到期时间管理续签；历史合同不可覆盖"><template #actions><RouterLink to="/institutions" class="el-button">机构中心</RouterLink></template></PageHeader><div class="proto-stat-grid three"><StatCard label="有效合同" :value="`${rows.filter(row => row.status==='ACTIVE').length} 份`" sub="当前生效版本" icon="file" /><StatCard label="90天内到期" :value="`${expiring} 份`" sub="需要提前复核" icon="calendar" /><StatCard label="已到期未更新" :value="`${expired} 份`" sub="不代表自动终止合作" icon="shield" /></div><PageCard><div class="proto-table-wrap"><app-table v-loading="loading" :data="rows"><el-table-column prop="contractNo" label="合同编码" min-width="150" /><el-table-column prop="institutionName" label="机构" min-width="180" /><el-table-column prop="versionNo" label="版本" width="80" /><el-table-column prop="startDate" label="开始日期" width="110" /><el-table-column prop="endDate" label="到期日期" width="110" /><el-table-column label="状态" width="110"><template #default="{row}"><StatusPill :text="documentStatusLabel(row.status)" :tone="row.status==='ACTIVE'?'success':row.status==='REJECTED'?'danger':'dark'" /></template></el-table-column><el-table-column label="操作" width="160"><template #default><el-button disabled title="续签审批工作流待配置">续签处理 · 待配置</el-button></template></el-table-column><template #empty>暂无合同版本</template></app-table></div></PageCard><footer class="proto-footer">续签不会修改历史合同；新版本需独立文件与审批</footer></div></template>
