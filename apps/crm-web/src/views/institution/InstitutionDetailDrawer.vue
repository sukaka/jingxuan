<script setup lang="ts">
import { ref } from 'vue'
import type { InstitutionContract, InstitutionProfile, InstitutionQualification } from '../../api/institution'
import { formatDateTime } from '../customer/shared'
import StatusPill from '../customer/StatusPill.vue'
import FileDownloadButton from './FileDownloadButton.vue'
import MerchantResourcePanel from './MerchantResourcePanel.vue'
import { cityLabel, contractTypeLabel, documentStatusLabel, institutionBusinessTypeLabel, institutionGradeLabel, institutionOwnershipLabel, profileStatusLabel, qualificationTypeLabel, rateDisplay, settlementTypeLabel } from './institutionUi'
import { fenToYuanExact } from './number'

defineProps<{
  modelValue: boolean
  profile: InstitutionProfile
  contracts: InstitutionContract[]
  qualifications: InstitutionQualification[]
  contractTotal: number
  qualificationTotal: number
  contractPage: number
  qualificationPage: number
  canWrite: boolean
  canChange: boolean
  canViewFinance: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  edit: []
  sensitive: []
  activate: []
  'new-contract': [previous: InstitutionContract | null]
  'activate-contract': [contract: InstitutionContract]
  'new-qualification': []
  'review-qualification': [qualification: InstitutionQualification]
  'contract-page': [page: number]
  'qualification-page': [page: number]
}>()

const activeTab = ref('profile')
</script>

<template>
  <el-drawer :model-value="modelValue" :title="profile.fullName" size="min(980px, 96vw)" @update:model-value="emit('update:modelValue', $event)">
    <div class="fulfillment-detail-head">
      <div>
        <h3 class="fulfillment-detail-title">{{ profile.fullName }}</h3>
        <div class="crm-muted">{{ profile.institutionNo }} · {{ cityLabel(profile.cityCode) }} · 版本 {{ profile.version }}</div>
      </div>
      <StatusPill :text="profileStatusLabel(profile.status)" :tone="profile.status === 'ACTIVE' ? 'success' : 'warning'" />
    </div>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="合作档案" name="profile">
        <el-descriptions :column="3" border class="trading-descriptions">
          <el-descriptions-item label="机构简称">{{ profile.shortName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务类型">{{ institutionBusinessTypeLabel(profile.businessTypeCode) }}</el-descriptions-item>
          <el-descriptions-item label="机构性质">{{ institutionOwnershipLabel(profile.ownershipCode) }}</el-descriptions-item>
          <el-descriptions-item label="机构等级">{{ institutionGradeLabel(profile.gradeCode) }}</el-descriptions-item>
          <el-descriptions-item label="对接人">{{ profile.contactName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ profile.contactPhoneMasked || '-' }}</el-descriptions-item>
          <el-descriptions-item label="详细地址" :span="3">{{ profile.address || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(profile.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="当前版本">{{ profile.version }}</el-descriptions-item>
          <el-descriptions-item label="机构简介" :span="3">{{ profile.introduction || '-' }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="!canViewFinance">
          <el-alert type="info" :closable="false" title="当前账号没有财务敏感字段查看权限，结算、佣金与银行税务信息不展示。" class="finance-tip" />
        </template>
        <template v-else>
          <h4 class="trading-section-title">结算与财务敏感信息（掩码）</h4>
          <el-descriptions :column="3" border class="trading-descriptions">
            <el-descriptions-item label="结算方式">{{ settlementTypeLabel(profile.settlementType) }}</el-descriptions-item>
            <el-descriptions-item label="线上佣金">{{ rateDisplay(profile.onlineRate) }}</el-descriptions-item>
            <el-descriptions-item label="线下佣金">{{ rateDisplay(profile.offlineRate) }}</el-descriptions-item>
            <el-descriptions-item label="点评等平台">{{ rateDisplay(profile.platformRate) }}</el-descriptions-item>
            <el-descriptions-item label="其他佣金">{{ profile.otherRateRule || '-' }}</el-descriptions-item>
            <el-descriptions-item label="是否收费">{{ profile.chargesFee === null ? '-' : profile.chargesFee ? '是' : '否' }}</el-descriptions-item>
            <el-descriptions-item label="保证金（元）">{{ fenToYuanExact(profile.depositFen) }}</el-descriptions-item>
            <el-descriptions-item label="保投产">{{ profile.guaranteeRoi === null ? '-' : profile.guaranteeRoi ? '是' : '否' }}</el-descriptions-item>
            <el-descriptions-item label="保投产约定">{{ profile.roiTerms || '-' }}</el-descriptions-item>
            <el-descriptions-item label="结款名称">{{ profile.settlementName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="开户行"><span class="institution-masked">{{ profile.bankNameMasked || '-' }}</span></el-descriptions-item>
            <el-descriptions-item label="银行账号"><span class="institution-masked">{{ profile.bankAccountMasked || '-' }}</span></el-descriptions-item>
            <el-descriptions-item label="纳税人识别号"><span class="institution-masked">{{ profile.taxIdMasked || '-' }}</span></el-descriptions-item>
          </el-descriptions>
        </template>
      </el-tab-pane>

      <el-tab-pane label="商户一页纸" name="merchant">
        <div class="institution-card-grid">
          <article class="institution-soft-card">
            <h3>{{ profile.fullName }}</h3>
            <p>{{ profile.shortName || '暂无简称' }} · {{ cityLabel(profile.cityCode) }} · {{ institutionBusinessTypeLabel(profile.businessTypeCode) }} · {{ institutionOwnershipLabel(profile.ownershipCode) }} · {{ institutionGradeLabel(profile.gradeCode) }}商户</p>
          </article>
          <article class="institution-soft-card">
            <h3>服务联系</h3>
            <p>{{ profile.contactName || '联系人待维护' }} · {{ profile.contactPhoneMasked || '电话待维护' }}</p>
            <p>{{ profile.address || '地址待维护' }}</p>
          </article>
          <article class="institution-soft-card institution-full">
            <h3>机构简介</h3>
            <p>{{ profile.introduction || '机构简介待维护' }}</p>
          </article>
        </div>
        <MerchantResourcePanel :key="profile.id" :institution-id="profile.id" :can-write="canWrite" />
        <el-alert type="info" :closable="false" title="一页纸不包含银行账号、税号、合同金额或其他财务结算字段。" />
      </el-tab-pane>

      <el-tab-pane label="合同版本" name="contracts">
        <div class="institution-section-head">
          <div><h3>合同与历史版本</h3><p>已生效合同只读，新版本通过独立激活流程生效</p></div>
          <el-button v-if="canChange" size="small" @click="emit('new-contract', null)">新建合同</el-button>
        </div>
        <app-table :data="contracts" size="small">
          <el-table-column label="合同 / 版本" min-width="155">
            <template #default="{ row }">{{ row.contractNo }}<div class="trading-cell-sub">{{ row.title }} · 第 {{ row.versionNo }} 版</div></template>
          </el-table-column>
          <el-table-column label="类型" width="110"><template #default="{ row }">{{ contractTypeLabel(row.contractType) }}</template></el-table-column>
          <el-table-column label="有效期" min-width="170"><template #default="{ row }">{{ row.startDate }} 至 {{ row.endDate || '未设到期日' }}</template></el-table-column>
          <el-table-column label="金额（元）" width="110" align="right"><template #default="{ row }">{{ fenToYuanExact(row.amountFen) }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }">{{ documentStatusLabel(row.status) }}</template></el-table-column>
          <el-table-column label="文件" width="95"><template #default="{ row }"><FileDownloadButton :file-id="row.fileId" /></template></el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canChange && row.status === 'PENDING_REVIEW'" text size="small" type="primary" @click="emit('activate-contract', row)">提交激活申请</el-button>
              <el-button v-if="canChange && row.status === 'ACTIVE'" text size="small" @click="emit('new-contract', row)">创建下一版本</el-button>
              <span v-if="row.status === 'SUPERSEDED'" class="crm-muted">历史只读</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无合同版本" /></template>
        </app-table>
        <el-pagination :current-page="contractPage" :page-size="20" :total="contractTotal" layout="prev, pager, next" class="crm-pager" @current-change="emit('contract-page', $event)" />
      </el-tab-pane>

      <el-tab-pane label="资质文件" name="qualifications">
        <div class="institution-section-head">
          <div><h3>资质证照与审核</h3><p>资质文件扫描通过后才能提交审核</p></div>
          <el-button v-if="canChange" size="small" @click="emit('new-qualification')">新建资质</el-button>
        </div>
        <app-table :data="qualifications" size="small">
          <el-table-column label="资质 / 类型" min-width="170"><template #default="{ row }">{{ row.title }}<div class="trading-cell-sub">{{ qualificationTypeLabel(row.qualificationType) }}</div></template></el-table-column>
          <el-table-column label="证照号（掩码）" width="140"><template #default="{ row }"><span class="institution-masked">{{ row.licenseNoMasked || '-' }}</span></template></el-table-column>
          <el-table-column label="有效期" min-width="160"><template #default="{ row }">{{ row.validFrom || '-' }} 至 {{ row.validTo || '未设到期日' }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }">{{ documentStatusLabel(row.status) }}</template></el-table-column>
          <el-table-column label="文件" width="95"><template #default="{ row }"><FileDownloadButton :file-id="row.fileId" /></template></el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }"><el-button v-if="canChange && row.status === 'PENDING_REVIEW'" text size="small" type="primary" @click="emit('review-qualification', row)">提交审核申请</el-button></template>
          </el-table-column>
          <template #empty><el-empty description="暂无资质文件" /></template>
        </app-table>
        <el-pagination :current-page="qualificationPage" :page-size="20" :total="qualificationTotal" layout="prev, pager, next" class="crm-pager" @current-change="emit('qualification-page', $event)" />
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button v-if="canWrite" data-testid="profile-edit" @click="emit('edit')">修改普通资料</el-button>
      <el-button v-if="canChange" data-testid="profile-sensitive" @click="emit('sensitive')">申请敏感变更</el-button>
      <el-button v-if="canChange && profile.status === 'PENDING'" type="primary" data-testid="profile-activate" @click="emit('activate')">提交合作激活申请</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.finance-tip { margin-top: 16px; }
</style>
