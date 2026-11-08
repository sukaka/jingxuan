<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { InstitutionMerchantResourceSummary, InstitutionProfile } from '../api/institution'
import { listInstitutionMerchantResourceSummary, listInstitutionProfiles } from '../api/institution'
import PageHeader from '../components/prototype/PageHeader.vue'
import MerchantResourcePanel from './institution/MerchantResourcePanel.vue'
import { cityLabel, institutionBusinessTypeLabel, institutionGradeLabel, institutionOwnershipLabel } from './institution/institutionUi'
import { tradingErrorText } from './trading/tradingUi'

const rows = ref<InstitutionProfile[]>([])
const summaries = ref<InstitutionMerchantResourceSummary[]>([])
const keyword = ref('')
const loading = ref(false)
const errorMessage = ref('')
const selected = ref<InstitutionProfile | null>(null)
const detailVisible = ref(false)
const summaryByInstitution = computed(() => new Map(summaries.value.map((item) => [item.institutionId, item])))

function summaryFor(institutionId: number) {
  return summaryByInstitution.value.get(institutionId) || {
    institutionId,
    doctorCount: 0,
    equipmentCount: 0,
    caseCount: 0,
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [profiles, counts] = await Promise.all([
      listInstitutionProfiles({
        page: 1,
        size: 100,
        status: 'ACTIVE',
        merchantKeyword: keyword.value.trim() || undefined,
      }),
      listInstitutionMerchantResourceSummary(),
    ])
    rows.value = profiles.items
    summaries.value = counts
  } catch (error) {
    rows.value = []
    summaries.value = []
    errorMessage.value = tradingErrorText(error)
  } finally {
    loading.value = false
  }
}

function reset() {
  keyword.value = ''
  void load()
}

function openDetail(profile: InstitutionProfile) {
  selected.value = profile
  detailVisible.value = true
}

onMounted(() => { void load() })
</script>

<template>
  <div class="proto-page merchant-library-page">
    <PageHeader
      title="机构产品库 / 商户一页纸"
      description="查询合作机构及已登记的医生、仪器和案例；不展示银行、税号、佣金或其他财务敏感字段"
    >
      <template #actions><RouterLink to="/institutions" class="el-button">返回机构中心</RouterLink></template>
    </PageHeader>

    <form class="merchant-library-toolbar" @submit.prevent="load">
      <el-input
        v-model="keyword"
        placeholder="搜索机构、项目、医生、仪器或案例"
        clearable
        aria-label="搜索商户资料"
        @clear="load"
      />
      <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
      <el-button :disabled="!keyword && !loading" @click="reset">重置</el-button>
      <span>当前只展示已启用机构及展示中的资料</span>
    </form>

    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" class="merchant-library-error">
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>
    <div v-loading="loading" class="merchant-grid">
      <article v-for="row in rows" :key="row.id" class="merchant-card">
        <div class="merchant-card-topline">
          <span class="merchant-city">{{ cityLabel(row.cityCode) }}</span>
          <span class="merchant-cooperating">合作中</span>
        </div>
        <h3>{{ row.fullName }}</h3>
        <p class="merchant-card-meta">{{ institutionBusinessTypeLabel(row.businessTypeCode) }} · {{ institutionOwnershipLabel(row.ownershipCode) }} · {{ institutionGradeLabel(row.gradeCode) }}商户</p>
        <p class="merchant-introduction">{{ row.introduction || '机构简介尚未填写' }}</p>
        <div class="merchant-resource-counts" aria-label="机构资料数量">
          <span class="is-doctor"><strong>{{ summaryFor(row.id).doctorCount }}</strong> 位医生</span>
          <span class="is-equipment"><strong>{{ summaryFor(row.id).equipmentCount }}</strong> 项仪器</span>
          <span class="is-case"><strong>{{ summaryFor(row.id).caseCount }}</strong> 个案例</span>
        </div>
        <div class="merchant-card-footer">
          <span>服务项目以机构合作配置为准</span>
          <el-button type="primary" plain :data-testid="`merchant-open-${row.id}`" @click="openDetail(row)">查看一页纸</el-button>
        </div>
      </article>
      <div v-if="!rows.length && !loading && !errorMessage" class="merchant-library-empty">
        {{ keyword ? '没有找到匹配的机构或展示资料' : '暂无已启用的合作机构' }}
      </div>
    </div>

    <el-dialog v-model="detailVisible" :title="selected?.fullName || '商户一页纸'" width="min(920px, 96vw)">
      <template v-if="selected">
        <div class="merchant-dialog-summary">
          <div>
            <span>{{ cityLabel(selected.cityCode) }} · {{ institutionBusinessTypeLabel(selected.businessTypeCode) }}</span>
            <h3>{{ selected.fullName }}</h3>
            <p>{{ selected.introduction || '机构简介尚未填写' }}</p>
          </div>
          <dl>
            <div><dt>服务联系</dt><dd>{{ selected.contactName || '未登记' }} · {{ selected.contactPhoneMasked || '未登记' }}</dd></div>
            <div><dt>机构地址</dt><dd>{{ selected.address || '未登记' }}</dd></div>
          </dl>
        </div>
        <MerchantResourcePanel :key="selected.id" :institution-id="selected.id" />
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.merchant-library-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; }
.merchant-library-toolbar .el-input { width: min(420px,100%); }
.merchant-library-toolbar > span { margin-left: auto; color: #879ba7; font-size: 11px; }
.merchant-library-error { margin-bottom: 16px; }
.merchant-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px; min-height: 100px; }
.merchant-card { display: flex; flex-direction: column; padding: 24px; border: 1px solid #e3edef; border-radius: 14px; background: #fff; box-shadow: var(--shadow); }
.merchant-card-topline { display: flex; align-items: center; justify-content: space-between; }
.merchant-city { color: #0f857c; font-size: 11px; font-weight: 700; }
.merchant-cooperating { padding: 3px 8px; border-radius: 6px; color: #257457; background: #e8f6ed; font-size: 10px; }
.merchant-card h3 { margin: 13px 0 8px; color: #294456; font-size: 18px; }
.merchant-card-meta { margin: 0; color: #8a9ca7; font-size: 11px; }
.merchant-introduction { min-height: 45px; margin: 16px 0; color: #647e8d; font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.merchant-resource-counts { display: flex; gap: 8px; flex-wrap: wrap; }
.merchant-resource-counts span { padding: 6px 9px; border-radius: 7px; font-size: 11px; }
.merchant-resource-counts strong { margin-right: 3px; font-size: 14px; }
.merchant-resource-counts .is-doctor { color: #117f73; background: #e6f6f1; }
.merchant-resource-counts .is-equipment { color: #316caa; background: #eaf2fc; }
.merchant-resource-counts .is-case { color: #7955a6; background: #f1ebf9; }
.merchant-card-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 20px; padding-top: 15px; border-top: 1px solid #edf2f4; }
.merchant-card-footer > span { color: #93a4af; font-size: 10px; }
.merchant-library-empty { grid-column: 1/-1; padding: 60px 0; color: #94a6b1; text-align: center; }
.merchant-dialog-summary { display: grid; grid-template-columns: minmax(0,1.3fr) minmax(260px,.7fr); gap: 20px; padding: 18px; border-radius: 12px; background: #f4f8f8; }
.merchant-dialog-summary span { color: #12877d; font-size: 11px; }
.merchant-dialog-summary h3 { margin: 8px 0; color: #294456; font-size: 17px; }
.merchant-dialog-summary p { margin: 0; color: #6c8391; font-size: 12px; line-height: 1.8; }
.merchant-dialog-summary dl { margin: 0; }
.merchant-dialog-summary dl > div { margin-bottom: 9px; }
.merchant-dialog-summary dt { color: #8a9da8; font-size: 10px; }
.merchant-dialog-summary dd { margin: 4px 0 0; color: #506b7b; font-size: 11px; line-height: 1.7; }
@media (max-width: 900px) { .merchant-grid { grid-template-columns: 1fr; } .merchant-library-toolbar > span { display: none; } }
@media (max-width: 640px) {
  .merchant-library-toolbar { flex-wrap: wrap; }
  .merchant-library-toolbar .el-input { flex-basis: 100%; }
  .merchant-dialog-summary { grid-template-columns: 1fr; }
  .merchant-card { padding: 20px; }
}
</style>
