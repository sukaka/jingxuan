<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  InstitutionChangeRequest,
  InstitutionContract,
  InstitutionProfile,
  InstitutionQualification,
} from '../api/institution'
import {
  getInstitutionProfile,
  listInstitutionChanges,
  listInstitutionContracts,
  listInstitutionProfiles,
  listInstitutionQualifications,
  revalidateInstitutionChange,
  reviewInstitutionChangeFinance,
  reviewInstitutionChangeMarket,
} from '../api/institution'
import { useAuthStore } from '../stores/auth'
import { useDictionaries } from './customer/shared'
import StatusPill from './customer/StatusPill.vue'
import PageHeader from '../components/prototype/PageHeader.vue'
import PageCard from '../components/prototype/PageCard.vue'
import StatCard from '../components/prototype/StatCard.vue'
import ChangeRequestPanel from './institution/ChangeRequestPanel.vue'
import DocumentFormDrawer from './institution/DocumentFormDrawer.vue'
import InstitutionDetailDrawer from './institution/InstitutionDetailDrawer.vue'
import ProfileFormDrawer from './institution/ProfileFormDrawer.vue'
import SensitiveChangeDrawer from './institution/SensitiveChangeDrawer.vue'
import WorkflowRequestDialog from './institution/WorkflowRequestDialog.vue'
import { changeStatusLabel, cityLabel, createInstitutionKey, institutionBusinessTypeLabel, institutionGradeLabel, institutionOwnershipLabel, profileStatusLabel, settlementTypeLabel, workflowStepLabel } from './institution/institutionUi'
import { tradingErrorText } from './trading/tradingUi'
import './customer/customer-ui.css'
import './trading/trading.css'
import './fulfillment/fulfillment.css'
import './institution/institution.css'

const auth = useAuthStore()
const { options, load: loadDictionaries } = useDictionaries()
const canWrite = computed(() => auth.hasPermission('institution:write') && auth.user?.userType !== 'INSTITUTION')
const canChange = computed(() => auth.hasPermission('institution:change_request'))
const canMarket = computed(() => auth.hasPermission('institution:approve_market'))
const canFinance = computed(() => auth.hasPermission('institution:approve_finance'))
const activeTab = ref('profiles')

const profiles = ref<InstitutionProfile[]>([])
const profileTotal = ref(0)
const profileLoading = ref(false)
const profileError = ref('')
const profileQuery = reactive({ page: 1, size: 20, keyword: '', status: '', cityCode: '' })

async function loadProfiles() {
  profileLoading.value = true
  profileError.value = ''
  try {
    const result = await listInstitutionProfiles({
      page: profileQuery.page,
      size: profileQuery.size,
      keyword: profileQuery.keyword.trim() || undefined,
      status: profileQuery.status || undefined,
      cityCode: profileQuery.cityCode || undefined,
    })
    profiles.value = result.items
    profileTotal.value = result.total
    profileQuery.page = result.page
    profileQuery.size = result.size
  } catch (error) {
    profiles.value = []
    profileTotal.value = 0
    profileError.value = tradingErrorText(error)
  } finally {
    profileLoading.value = false
  }
}

function searchProfiles() {
  profileQuery.page = 1
  void loadProfiles()
}

function resetProfiles() {
  profileQuery.keyword = ''
  profileQuery.status = ''
  profileQuery.cityCode = ''
  profileQuery.page = 1
  void loadProfiles()
}

const changes = ref<InstitutionChangeRequest[]>([])
const changeTotal = ref(0)
const changeLoading = ref(false)
const changeError = ref('')
const changeQuery = reactive({ page: 1, size: 20, institutionId: undefined as number | undefined, status: '' })

async function loadChanges() {
  changeLoading.value = true
  changeError.value = ''
  try {
    const result = await listInstitutionChanges({
      page: changeQuery.page,
      size: changeQuery.size,
      institutionId: changeQuery.institutionId,
      status: changeQuery.status || undefined,
    })
    changes.value = result.items
    changeTotal.value = result.total
    changeQuery.page = result.page
    changeQuery.size = result.size
  } catch (error) {
    changes.value = []
    changeTotal.value = 0
    changeError.value = tradingErrorText(error)
  } finally {
    changeLoading.value = false
  }
}

function changeTab(value: string | number) {
  activeTab.value = String(value)
  if (activeTab.value === 'changes') void loadChanges()
}

const detailVisible = ref(false)
const detail = ref<InstitutionProfile | null>(null)
const contracts = ref<InstitutionContract[]>([])
const qualifications = ref<InstitutionQualification[]>([])
const contractTotal = ref(0)
const qualificationTotal = ref(0)
const contractPage = ref(1)
const qualificationPage = ref(1)
const detailLoadingId = ref<number | null>(null)

async function loadDocuments() {
  if (!detail.value) return
  const institutionId = detail.value.id
  const [contractResult, qualificationResult] = await Promise.all([
    listInstitutionContracts({ institutionId, page: contractPage.value, size: 20 }),
    listInstitutionQualifications({ institutionId, page: qualificationPage.value, size: 20 }),
  ])
  contracts.value = contractResult.items
  contractTotal.value = contractResult.total
  qualifications.value = qualificationResult.items
  qualificationTotal.value = qualificationResult.total
  contractPage.value = contractResult.page
  qualificationPage.value = qualificationResult.page
}

async function openDetail(id: number) {
  detailLoadingId.value = id
  try {
    contractPage.value = 1
    qualificationPage.value = 1
    detail.value = await getInstitutionProfile(id)
    await loadDocuments()
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    detailLoadingId.value = null
  }
}

async function refreshDetail() {
  if (!detail.value) return
  try {
    detail.value = await getInstitutionProfile(detail.value.id)
    await loadDocuments()
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  }
}

const profileFormVisible = ref(false)
const profileForEdit = ref<InstitutionProfile | null>(null)
const sensitiveVisible = ref(false)
const documentVisible = ref(false)
const documentMode = ref<'contract' | 'qualification'>('contract')
const previousContract = ref<InstitutionContract | null>(null)

function openCreate() {
  profileForEdit.value = null
  profileFormVisible.value = true
}

function openEdit() {
  profileForEdit.value = detail.value
  profileFormVisible.value = true
}

async function openEditFromRow(row: InstitutionProfile) {
  await openDetail(row.id)
  openEdit()
}

function openDocument(mode: 'contract' | 'qualification', previous: InstitutionContract | null) {
  documentMode.value = mode
  previousContract.value = previous
  documentVisible.value = true
}

const workflowVisible = ref(false)
const workflowMode = ref<'institution' | 'contract' | 'qualification'>('institution')
const workflowTargetId = ref(0)
const workflowBaseVersion = ref(0)
const workflowInstitutionId = ref(0)
const workflowTitle = ref('')

function openWorkflow(mode: 'institution' | 'contract' | 'qualification', targetId: number, baseVersion: number, institutionId: number, title: string) {
  workflowMode.value = mode
  workflowTargetId.value = targetId
  workflowBaseVersion.value = baseVersion
  workflowInstitutionId.value = institutionId
  workflowTitle.value = title
  workflowVisible.value = true
}

function activateProfile() {
  const profile = detail.value
  if (profile) openWorkflow('institution', profile.id, profile.version, profile.id, '提交机构合作激活申请')
}

function activateContract(contract: InstitutionContract) {
  openWorkflow('contract', contract.id, contract.version, contract.institutionId, '提交合同版本激活申请')
}

function reviewQualification(qualification: InstitutionQualification) {
  openWorkflow('qualification', qualification.id, qualification.version, qualification.institutionId, '提交资质审核申请')
}

function handleProfileSaved(profile: InstitutionProfile) {
  detail.value = profile
  detailVisible.value = true
  void loadProfiles()
  void loadDocuments()
}

function handleWorkflowSaved() {
  activeTab.value = 'changes'
  void loadChanges()
  void refreshDetail()
  void loadProfiles()
}

function handleDocumentSaved() {
  void loadDocuments()
  void loadChanges()
}

let reviewAttempt: { signature: string; key: string } | null = null

async function review(request: InstitutionChangeRequest, step: 'MARKET' | 'FINANCE', decision: 'APPROVE' | 'REJECT') {
  let comment = ''
  try {
    const result = await ElMessageBox.prompt(
      `${step === 'MARKET' ? '市场' : '财务'}审核：请填写本次${decision === 'APPROVE' ? '通过' : '驳回'}意见。`,
      '变更申请审核',
      {
        confirmButtonText: decision === 'APPROVE' ? '确认通过' : '确认驳回',
        cancelButtonText: '返回',
        inputType: 'textarea',
        inputValidator: (value) => {
          const trimmed = value.trim()
          if (!trimmed) return '请填写审核意见'
          return trimmed.length <= 1000 || '审核意见不能超过 1000 字'
        },
      },
    )
    comment = result.value.trim()
  } catch {
    return
  }

  const signature = `${request.id}:${step}:${decision}:${request.version}:${comment}`
  if (reviewAttempt?.signature !== signature) reviewAttempt = { signature, key: createInstitutionKey('institution.change.review') }
  const key = reviewAttempt.key
  try {
    const body = { version: request.version, decision, comment }
    const saved = step === 'MARKET'
      ? await reviewInstitutionChangeMarket(request.id, body, key)
      : await reviewInstitutionChangeFinance(request.id, body, key)
    reviewAttempt = null
    await loadChanges()
    await refreshDetail()
    await loadProfiles()
    ElMessage.success(`审核已返回：${changeStatusLabel(saved.status)} / ${workflowStepLabel(saved.currentStep)}`)
  } catch (error) {
    ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
  }
}

let revalidateAttempt: { signature: string; key: string } | null = null

async function revalidate(request: InstitutionChangeRequest) {
  try {
    const latest = await getInstitutionProfile(request.institutionId)
    const result = await ElMessageBox.prompt(
      `将以机构当前版本 ${latest.version} 重新复核，不覆盖较新正式值。请填写复核原因。`,
      '基准版本冲突复核',
      {
        confirmButtonText: '提交重新复核',
        cancelButtonText: '返回',
        inputType: 'textarea',
        inputValidator: (value) => value.trim() ? true : '请填写复核原因',
      },
    )
    const reason = result.value.trim()
    const signature = `${request.id}:${request.version}:${latest.version}:${reason}`
    if (revalidateAttempt?.signature !== signature) revalidateAttempt = { signature, key: createInstitutionKey('institution.change.revalidate') }
    const key = revalidateAttempt.key
    const saved = await revalidateInstitutionChange(request.id, { version: request.version, baseVersion: latest.version, reason }, key)
    revalidateAttempt = null
    await loadChanges()
    ElMessage.success(`已重新进入审核：${changeStatusLabel(saved.status)} / ${workflowStepLabel(saved.currentStep)}`)
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(`${tradingErrorText(error)}；请核对最新基准版本后重试`)
  }
}

onMounted(() => {
  void loadProfiles()
  void loadDictionaries('cities')
})
</script>

<template>
  <div class="proto-page">
    <PageHeader title="机构中心 · 机构端" description="城市、业务类型、机构性质、商户等级、结算、收费、合同与财务资料统一维护">
      <template #actions><RouterLink to="/institutions/merchant" class="el-button">商户一页纸</RouterLink><el-button v-if="canWrite" type="primary" @click="openCreate">＋ 新增合作机构</el-button></template>
    </PageHeader>
    <div class="proto-stat-grid"><StatCard label="合作机构" :value="`${profileTotal} 家`" sub="当前数据范围" icon="hospital" /><StatCard label="S/A级商户" :value="`${profiles.filter(item => ['S','A'].includes((item.gradeCode || '').toUpperCase())).length} 家`" sub="按机构等级" icon="users" /><StatCard label="30天内资质到期" value="待核实" sub="按真实资质有效期" icon="calendar" /><StatCard label="收费合作商户" :value="`${profiles.filter(item => item.chargesFee).length} 家`" sub="按收费合作标识" icon="wallet" /></div>
    <PageCard>
      <el-tabs v-model="activeTab" class="fulfillment-status-tabs" @tab-change="changeTab">
        <el-tab-pane label="机构档案" name="profiles" />
        <el-tab-pane label="变更与激活审批" name="changes" />
      </el-tabs>

      <template v-if="activeTab === 'profiles'">
        <div class="crm-toolbar">
          <el-input v-model="profileQuery.keyword" placeholder="机构编号 / 名称 / 联系人" clearable class="institution-filter-keyword" @keyup.enter="searchProfiles" />
          <el-select v-model="profileQuery.status" placeholder="全部合作状态" clearable class="institution-filter-select">
            <el-option label="待合作审核" value="PENDING" />
            <el-option label="合作中" value="ACTIVE" />
            <el-option label="停止合作" value="INACTIVE" />
          </el-select>
          <el-select v-model="profileQuery.cityCode" placeholder="全部城市" clearable filterable class="institution-filter-select">
            <el-option v-for="item in options('cities')" :key="item.code" :label="item.label" :value="item.code" />
          </el-select>
          <el-button type="primary" :loading="profileLoading" @click="searchProfiles">查询</el-button>
          <el-button @click="resetProfiles">重置</el-button>
        </div>
        <el-alert v-if="profileError" type="error" :title="`机构加载失败：${profileError}`" :closable="false" class="crm-table-alert" />
        <div class="proto-table-wrap">
          <app-table v-loading="profileLoading" :data="profiles" row-key="id" @row-click="(row: InstitutionProfile) => openDetail(row.id)">
            <el-table-column label="机构" min-width="195"><template #default="{row}"><strong>{{ row.fullName }}</strong><div class="proto-subcell">{{ row.institutionNo }} · {{ row.shortName || '无简称' }}</div></template></el-table-column>
            <el-table-column label="城市" width="80"><template #default="{row}">{{ cityLabel(row.cityCode) }}</template></el-table-column>
            <el-table-column label="业务类型 / 性质" min-width="160"><template #default="{row}"><StatusPill :text="institutionBusinessTypeLabel(row.businessTypeCode)" tone="info" /> <StatusPill :text="institutionOwnershipLabel(row.ownershipCode)" tone="dark" /></template></el-table-column>
            <el-table-column label="商户等级" width="90"><template #default="{row}"><StatusPill :text="institutionGradeLabel(row.gradeCode)" :tone="row.gradeCode==='S'?'danger':row.gradeCode==='A'?'warning':row.gradeCode==='B'?'info':'dark'" /></template></el-table-column>
            <el-table-column label="结算方式" width="100"><template #default="{row}">{{ settlementTypeLabel(row.settlementType) }}</template></el-table-column>
            <el-table-column label="佣金规则" min-width="130"><template #default="{row}">线上 {{ row.onlineRate ?? '—' }}<div class="proto-subcell">线下 {{ row.offlineRate ?? '—' }} · 点评 {{ row.platformRate ?? '—' }}</div></template></el-table-column>
            <el-table-column label="收费合作" width="90"><template #default="{row}">{{ row.chargesFee ? '收费' : '不收费' }}</template></el-table-column>
            <el-table-column label="合同到期" width="105">待核实</el-table-column>
            <el-table-column label="状态" width="100"><template #default="{row}"><StatusPill :text="profileStatusLabel(row.status)" :tone="row.status==='ACTIVE'?'success':'warning'" /></template></el-table-column>
            <el-table-column label="操作" width="115" fixed="right"><template #default="{row}"><el-button text @click.stop="openDetail(row.id)">详情</el-button><el-button v-if="canWrite" text @click.stop="openEditFromRow(row)">修改</el-button></template></el-table-column>
            <template #empty>暂无机构档案</template>
          </app-table>
        </div>
        <el-pagination v-model:current-page="profileQuery.page" v-model:page-size="profileQuery.size" :total="profileTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="loadProfiles" @size-change="profileQuery.page = 1; loadProfiles()" />
      </template>

      <template v-else>
        <div class="crm-toolbar">
          <el-input-number v-model="changeQuery.institutionId" :min="1" :precision="0" :controls="false" placeholder="机构 ID" class="fulfillment-filter-id" @keyup.enter="changeQuery.page = 1; loadChanges()" />
          <el-select v-model="changeQuery.status" placeholder="全部审核状态" clearable class="institution-filter-select">
            <el-option label="待审核" value="PENDING_REVIEW" />
            <el-option label="已生效" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="版本冲突待复核" value="CONFLICT_REVALIDATE" />
          </el-select>
          <el-button type="primary" :loading="changeLoading" @click="changeQuery.page = 1; loadChanges()">查询</el-button>
          <el-button @click="changeQuery.institutionId = undefined; changeQuery.status = ''; changeQuery.page = 1; loadChanges()">重置</el-button>
        </div>
        <el-alert v-if="changeError" type="error" :title="`审批加载失败：${changeError}`" :closable="false" class="crm-table-alert" />
        <ChangeRequestPanel
          :requests="changes"
          :loading="changeLoading"
          :can-market="canMarket"
          :can-finance="canFinance"
          :can-change="canChange"
          :current-user-id="auth.user?.userId ?? -1"
          @review="review"
          @revalidate="revalidate"
        />
        <el-pagination v-model:current-page="changeQuery.page" v-model:page-size="changeQuery.size" :total="changeTotal" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" class="crm-pager" @current-change="loadChanges" @size-change="changeQuery.page = 1; loadChanges()" />
      </template>
    </PageCard>

    <ProfileFormDrawer v-if="canWrite" v-model="profileFormVisible" :profile="profileForEdit" @saved="handleProfileSaved" />
    <template v-if="detail">
      <InstitutionDetailDrawer
        v-model="detailVisible"
        :profile="detail"
        :contracts="contracts"
        :qualifications="qualifications"
        :contract-total="contractTotal"
        :qualification-total="qualificationTotal"
        :contract-page="contractPage"
        :qualification-page="qualificationPage"
        :can-write="canWrite"
        :can-change="canChange"
        :can-view-finance="canFinance"
        @edit="openEdit"
        @sensitive="sensitiveVisible = true"
        @activate="activateProfile"
        @new-contract="openDocument('contract', $event)"
        @activate-contract="activateContract"
        @new-qualification="openDocument('qualification', null)"
        @review-qualification="reviewQualification"
        @contract-page="contractPage = $event; loadDocuments()"
        @qualification-page="qualificationPage = $event; loadDocuments()"
      />
      <SensitiveChangeDrawer v-if="canChange" v-model="sensitiveVisible" :profile="detail" @saved="handleWorkflowSaved" />
      <DocumentFormDrawer v-if="canChange" v-model="documentVisible" :mode="documentMode" :institution-id="detail.id" :previous-contract="previousContract" @saved="handleDocumentSaved" />
      <WorkflowRequestDialog v-if="canChange" v-model="workflowVisible" :mode="workflowMode" :target-id="workflowTargetId" :base-version="workflowBaseVersion" :institution-id="workflowInstitutionId" :title="workflowTitle" @saved="handleWorkflowSaved" />
    </template>
  </div>
</template>
