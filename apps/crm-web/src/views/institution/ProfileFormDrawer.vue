<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { InstitutionProfile } from '../../api/institution'
import { createInstitutionProfile, updateInstitutionProfile } from '../../api/institution'
import { useDictionaries } from '../customer/shared'
import { tradingErrorText } from '../trading/tradingUi'
import { createInstitutionKey } from './institutionUi'
import { percentToDecimalRate as percentRate, yuanToFenExact as yuanFen } from './number'

const props = defineProps<{
  modelValue: boolean
  profile?: InstitutionProfile | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [profile: InstitutionProfile]
}>()

const { options, load } = useDictionaries()
const saving = ref(false)
const idempotencyKey = ref('')
const form = reactive({
  institutionNo: '',
  fullName: '',
  shortName: '',
  cityCode: '',
  businessTypeCode: '',
  ownershipCode: '',
  gradeCode: '',
  address: '',
  contactName: '',
  contactPhone: '',
  settlementType: '',
  onlineRate: '',
  offlineRate: '',
  platformRate: '',
  otherRateRule: '',
  chargesFee: false,
  depositYuan: '0',
  guaranteeRoi: false,
  roiTerms: '',
  settlementName: '',
  bankName: '',
  bankAccount: '',
  taxId: '',
  introduction: '',
})

function optional(value: string): string | undefined {
  return value.trim() || undefined
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, {
      institutionNo: '',
      fullName: '',
      shortName: props.profile?.shortName ?? '',
      cityCode: props.profile?.cityCode ?? '',
      businessTypeCode: '',
      ownershipCode: '',
      gradeCode: '',
      address: props.profile?.address ?? '',
      contactName: props.profile?.contactName ?? '',
      contactPhone: '',
      settlementType: '',
      onlineRate: '',
      offlineRate: '',
      platformRate: '',
      otherRateRule: '',
      chargesFee: false,
      depositYuan: '0',
      guaranteeRoi: false,
      roiTerms: '',
      settlementName: '',
      bankName: '',
      bankAccount: '',
      taxId: '',
      introduction: props.profile?.introduction ?? '',
    })
    idempotencyKey.value = createInstitutionKey(props.profile ? 'institution.profile.update' : 'institution.profile.create')
    void load('cities', 'institutionBusinessTypes', 'institutionOwnerships', 'merchantGrades', 'settlementTypes')
  },
)

async function save() {
  saving.value = true
  try {
    if (props.profile) {
      if (!form.shortName.trim() && !form.cityCode && !form.address.trim() && !form.contactName.trim() && !form.introduction.trim() && !form.contactPhone.trim()) {
        ElMessage.warning('请至少修改一项普通资料')
        return
      }
      const saved = await updateInstitutionProfile(props.profile.id, {
        version: props.profile.version,
        shortName: optional(form.shortName),
        cityCode: form.cityCode || undefined,
        address: optional(form.address),
        contactName: optional(form.contactName),
        contactPhone: optional(form.contactPhone),
        introduction: optional(form.introduction),
      }, idempotencyKey.value)
      ElMessage.success('普通资料已更新')
      emit('saved', saved)
      emit('update:modelValue', false)
      return
    }

    if (!form.institutionNo.trim() || !form.fullName.trim() || !form.businessTypeCode || !form.ownershipCode || !form.gradeCode || !form.settlementType) {
      ElMessage.warning('请完整填写机构编号、名称、类型、性质、等级和结算方式')
      return
    }
    if (!form.onlineRate || !form.offlineRate || !form.platformRate) {
      ElMessage.warning('合作激活前需完整填写线上、线下和平台佣金率')
      return
    }
    const saved = await createInstitutionProfile({
      institutionNo: form.institutionNo.trim(),
      fullName: form.fullName.trim(),
      shortName: optional(form.shortName),
      cityCode: form.cityCode || undefined,
      businessTypeCode: form.businessTypeCode,
      ownershipCode: form.ownershipCode,
      gradeCode: form.gradeCode,
      address: optional(form.address),
      contactName: optional(form.contactName),
      contactPhone: optional(form.contactPhone),
      settlementType: form.settlementType,
      onlineRate: percentRate(form.onlineRate),
      offlineRate: percentRate(form.offlineRate),
      platformRate: percentRate(form.platformRate),
      otherRateRule: optional(form.otherRateRule),
      chargesFee: form.chargesFee,
      depositFen: yuanFen(form.depositYuan),
      guaranteeRoi: form.guaranteeRoi,
      roiTerms: optional(form.roiTerms),
      settlementName: optional(form.settlementName),
      bankName: optional(form.bankName),
      bankAccount: optional(form.bankAccount),
      taxId: optional(form.taxId),
      introduction: optional(form.introduction),
    }, idempotencyKey.value)
    ElMessage.success('机构已创建为待合作审核状态，请继续提交激活申请')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    if (error instanceof Error && /最多支持|有效/.test(error.message)) {
      ElMessage.error(error.message)
    } else {
      ElMessage.error(`${tradingErrorText(error)}；重试将沿用同一幂等键`)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer :model-value="modelValue" :title="profile ? '修改普通资料' : '新建机构档案'" size="min(900px, 96vw)" @update:model-value="emit('update:modelValue', $event)">
    <el-alert v-if="!profile" type="info" :closable="false" title="创建后仅为待合作审核状态，合作激活必须另走市场→财务审核；敏感字段不会以明文回显。" class="form-tip" />
    <el-alert v-else type="info" :closable="false" title="普通资料修改不允许变更佣金、保证金、合同或银行税务字段；联系电话留空表示保持不变。" class="form-tip" />
    <el-form label-position="top" @submit.prevent>
      <div class="institution-profile-grid">
        <el-form-item v-if="!profile" label="机构编号" required><el-input v-model="form.institutionNo" maxlength="48" data-testid="profile-institution-no" /></el-form-item>
        <el-form-item label="机构全称" required><el-input v-model="form.fullName" :disabled="!!profile" maxlength="160" data-testid="profile-full-name" /></el-form-item>
        <el-form-item label="机构简称"><el-input v-model="form.shortName" maxlength="96" data-testid="profile-short-name" /></el-form-item>
        <el-form-item label="城市">
          <el-select v-model="form.cityCode" clearable filterable class="full-width"><el-option v-for="item in options('cities')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
        </el-form-item>
        <template v-if="!profile">
          <el-form-item label="业务类型" required>
            <el-select v-model="form.businessTypeCode" class="full-width" data-testid="profile-business-type"><el-option v-for="item in options('institutionBusinessTypes')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
          </el-form-item>
          <el-form-item label="机构性质" required>
            <el-select v-model="form.ownershipCode" class="full-width" data-testid="profile-ownership"><el-option v-for="item in options('institutionOwnerships')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
          </el-form-item>
          <el-form-item label="机构等级" required>
            <el-select v-model="form.gradeCode" class="full-width" data-testid="profile-grade"><el-option v-for="item in options('merchantGrades')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
          </el-form-item>
        </template>
        <el-form-item label="详细地址"><el-input v-model="form.address" maxlength="255" /></el-form-item>
        <el-form-item label="对接联系人"><el-input v-model="form.contactName" maxlength="128" /></el-form-item>
        <el-form-item :label="profile ? '新联系电话（留空保持不变）' : '联系电话'"><el-input v-model="form.contactPhone" maxlength="32" data-testid="profile-contact-phone" /></el-form-item>
        <template v-if="!profile">
          <el-form-item label="结算方式" required>
            <el-select v-model="form.settlementType" class="full-width" data-testid="profile-settlement"><el-option v-for="item in options('settlementTypes')" :key="item.code" :label="item.label" :value="item.code" /></el-select>
          </el-form-item>
          <el-form-item label="线上佣金率（%）" required><el-input v-model="form.onlineRate" placeholder="如 18.25" data-testid="profile-online-rate" /></el-form-item>
          <el-form-item label="线下佣金率（%）" required><el-input v-model="form.offlineRate" placeholder="如 12" data-testid="profile-offline-rate" /></el-form-item>
          <el-form-item label="点评等平台佣金率（%）" required><el-input v-model="form.platformRate" placeholder="如 15" data-testid="profile-platform-rate" /></el-form-item>
          <el-form-item label="其他佣金规则"><el-input v-model="form.otherRateRule" type="textarea" :rows="2" maxlength="2000" /></el-form-item>
          <el-form-item label="保证金（元）"><el-input v-model="form.depositYuan" placeholder="0.00" data-testid="profile-deposit-yuan" /></el-form-item>
          <el-form-item label="结款名称"><el-input v-model="form.settlementName" maxlength="160" /></el-form-item>
          <el-form-item label="开户行"><el-input v-model="form.bankName" maxlength="160" /></el-form-item>
          <el-form-item label="银行账号"><el-input v-model="form.bankAccount" maxlength="64" autocomplete="off" /></el-form-item>
          <el-form-item label="纳税人识别号"><el-input v-model="form.taxId" maxlength="64" autocomplete="off" /></el-form-item>
          <el-form-item label="保投产约定"><el-input v-model="form.roiTerms" type="textarea" :rows="2" maxlength="2000" /></el-form-item>
          <el-form-item label="合作选项">
            <el-checkbox v-model="form.chargesFee">收取费用</el-checkbox>
            <el-checkbox v-model="form.guaranteeRoi">保投产</el-checkbox>
          </el-form-item>
        </template>
        <el-form-item label="机构简介" class="institution-full"><el-input v-model="form.introduction" type="textarea" :rows="4" maxlength="4000" /></el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="profile-save" @click="save">{{ profile ? '保存普通资料' : '保存为待合作审核' }}</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.full-width { width: 100%; }
.form-tip { margin-bottom: 16px; }
</style>
