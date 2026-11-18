<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { InstitutionSettlementDefault } from '../../api/institution'
import { getInstitutionSettlementDefault, updateInstitutionSettlementDefault } from '../../api/institution'
import { useAuthStore } from '../../stores/auth'
import PageCard from '../../components/prototype/PageCard.vue'
import { tradingErrorText } from '../trading/tradingUi'
import { createInstitutionKey, SETTLEMENT_DAY_OPTIONS, settlementDayLabel } from './institutionUi'

const emit = defineEmits<{
  loaded: [config: InstitutionSettlementDefault]
  saved: [config: InstitutionSettlementDefault]
}>()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasPermission('institution:write') && auth.user?.userType !== 'INSTITUTION')
const config = ref<InstitutionSettlementDefault | null>(null)
const draft = ref('LAST_DAY')
const loading = ref(false)
const saving = ref(false)
let saveAttempt: { signature: string; key: string } | null = null

async function load() {
  loading.value = true
  try {
    config.value = await getInstitutionSettlementDefault()
    draft.value = config.value.defaultSettlementDay
    saveAttempt = null
    emit('loaded', config.value)
  } catch (error) {
    ElMessage.error(`默认结算日加载失败：${tradingErrorText(error)}`)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!config.value || !canWrite.value) return
  const signature = `${config.value.version}:${draft.value}`
  if (saveAttempt?.signature !== signature) {
    saveAttempt = { signature, key: createInstitutionKey('institution.settlement-default.save') }
  }
  saving.value = true
  try {
    const saved = await updateInstitutionSettlementDefault({
      defaultSettlementDay: draft.value,
      version: config.value.version,
    }, saveAttempt.key)
    config.value = saved
    draft.value = saved.defaultSettlementDay
    saveAttempt = null
    emit('saved', saved)
    ElMessage.success('默认结算日已保存，仅之后生成的月结批次使用新值')
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  } finally {
    saving.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <PageCard>
    <section v-loading="loading" class="institution-settlement-default" aria-label="系统默认结算日设置">
      <div class="institution-settlement-default-copy">
        <h3>系统默认结算日</h3>
        <p>机构未单独设置时继承此默认值；当前为 <strong>{{ settlementDayLabel(config?.defaultSettlementDay ?? 'LAST_DAY') }}</strong>（{{ config?.configured ? '本租户配置' : '系统内置默认' }}）。</p>
      </div>
      <div class="institution-settlement-default-control">
        <el-select v-model="draft" :disabled="!canWrite || loading || saving" data-testid="settlement-default-value" aria-label="默认结算日">
          <el-option v-for="item in SETTLEMENT_DAY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button v-if="canWrite" type="primary" :loading="saving" :disabled="!config" data-testid="settlement-default-save" @click="save">保存默认设置</el-button>
      </div>
      <el-alert type="info" :closable="false" title="结算日期按 Asia/Shanghai 业务日历确定；只影响之后生成的月结批次，历史批次保留原快照，次卡次结不适用。" />
    </section>
  </PageCard>
</template>

<style scoped>
.institution-settlement-default { display: grid; gap: 14px; }
.institution-settlement-default-copy h3 { margin: 0 0 6px; font-size: 16px; }
.institution-settlement-default-copy p { margin: 0; color: var(--el-text-color-secondary); line-height: 1.6; }
.institution-settlement-default-control { display: flex; flex-wrap: wrap; gap: 10px; }
.institution-settlement-default-control .el-select { width: min(280px, 100%); }
</style>
