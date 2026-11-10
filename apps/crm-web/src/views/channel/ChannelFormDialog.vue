<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { Channel } from '../../api/channel'
import type { ChannelOwnerOption } from '../../api/channel'
import { createChannel, listChannelOwnerOptions, updateChannel } from '../../api/channel'
import { ApiError } from '../../types/api'
import { isValidChineseIdCard, isValidMainlandMobile, normalizeChineseIdCard } from '../../utils/channelValidation'
import { useDictionaries } from '../customer/shared'

const props = defineProps<{ modelValue: boolean; channel: Channel | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const dicts = useDictionaries()
const formRef = ref<FormInstance>()
const saving = ref(false)
const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const form = reactive({
  id: 0,
  name: '',
  channelType: 'BLOGGER' as Channel['channelType'],
  platformCode: '',
  commissionPercent: undefined as number | undefined,
  businessOwnerUserId: undefined as number | undefined,
  realName: '',
  contactPhone: '',
  idCardNumber: '',
  settlementMethod: '',
  cooperationStartDate: '',
  cooperationEndDate: '',
  paymentAccountName: '',
  paymentAccountNo: '',
  paymentBankName: '',
  status: 'ACTIVE' as Channel['status'],
  remarks: '',
  version: 0,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入博主姓名或昵称', trigger: 'blur' }],
  contactPhone: [{
    validator: (_rule, value, callback) => {
      if (value && !isValidMainlandMobile(String(value))) callback(new Error('请输入有效的 11 位中国大陆手机号'))
      else callback()
    },
    trigger: 'blur',
  }],
  idCardNumber: [{
    validator: (_rule, value, callback) => {
      if (value && !isValidChineseIdCard(String(value))) callback(new Error('请输入有效的 18 位身份证号，出生日期和校验码须正确'))
      else callback()
    },
    trigger: 'blur',
  }],
  cooperationEndDate: [{
    validator: (_rule, _value, callback) => {
      if (form.cooperationStartDate && form.cooperationEndDate && form.cooperationEndDate < form.cooperationStartDate) {
        callback(new Error('合作结束时间不得早于合作开始时间'))
      } else callback()
    },
    trigger: 'change',
  }],
}

const platformOptions = computed(() => {
  const active = dicts.options('customerChannels')
  if (!form.platformCode || active.some((option) => option.code === form.platformCode)) return active
  return [...active, { code: form.platformCode, label: `${form.platformCode}（历史/停用）`, sortNo: -1 }]
})

const settlementOptions = computed(() => {
  const options = form.channelType === 'BLOGGER'
    ? [{ code: 'MONTHLY', label: '电汇月结' }]
    : form.channelType === 'DISTRIBUTION'
      ? [{ code: 'MONTHLY', label: '电汇月结' }, { code: 'TWICE_MONTHLY', label: '电汇每月两次' }]
      : []
  if (form.settlementMethod && !options.some((option) => option.code === form.settlementMethod)) {
    return [...options, { code: form.settlementMethod, label: `${form.settlementMethod}（历史/不适用）` }]
  }
  return options
})

function handleChannelTypeChange() {
  if (form.channelType === 'REFERRAL') form.settlementMethod = ''
  else if (form.channelType === 'BLOGGER' && form.settlementMethod !== 'MONTHLY') form.settlementMethod = 'MONTHLY'
  else if (form.channelType === 'DISTRIBUTION'
    && !['MONTHLY', 'TWICE_MONTHLY'].includes(form.settlementMethod)) form.settlementMethod = 'MONTHLY'
}

const owners = ref<ChannelOwnerOption[]>([])
const ownerOptions = computed(() => {
  const options = [...owners.value]
  const currentId = props.channel?.businessOwnerUserId
  if (currentId && !options.some((option) => option.id === currentId)) {
    options.push({ id: currentId, displayName: `${props.channel?.businessOwnerName || '历史商务'}（已停用）` })
  }
  return options
})

function reset() {
  const item = props.channel
  Object.assign(form, {
    id: item?.id ?? 0,
    name: item?.name ?? '',
    channelType: item?.channelType ?? 'BLOGGER',
    platformCode: item?.platformCode ?? '',
    commissionPercent: item?.commissionPercent ?? undefined,
    businessOwnerUserId: item?.businessOwnerUserId ?? undefined,
    realName: item?.realName ?? '',
    contactPhone: item?.contactPhone ?? '',
    idCardNumber: item?.idCardNumber ?? '',
    settlementMethod: item?.settlementMethod ?? '',
    cooperationStartDate: item?.cooperationStartDate ?? '',
    cooperationEndDate: item?.cooperationEndDate ?? '',
    paymentAccountName: item?.paymentAccountName ?? '',
    paymentAccountNo: item?.paymentAccountNo ?? '',
    paymentBankName: item?.paymentBankName ?? '',
    status: item?.status ?? 'ACTIVE',
    remarks: item?.remarks ?? '',
    version: item?.version ?? 0,
  })
  void nextTick(() => formRef.value?.clearValidate())
}

function text(value: string): string | null {
  return value.trim() || null
}

function profilePayload() {
  return {
    channelType: form.channelType,
    commissionPercent: form.commissionPercent ?? null,
    realName: text(form.realName),
    contactPhone: text(form.contactPhone),
    idCardNumber: text(normalizeChineseIdCard(form.idCardNumber)),
    settlementMethod: text(form.settlementMethod),
    cooperationStartDate: text(form.cooperationStartDate),
    cooperationEndDate: text(form.cooperationEndDate),
    paymentAccountName: text(form.paymentAccountName),
    paymentAccountNo: text(form.paymentAccountNo),
    paymentBankName: text(form.paymentBankName),
  }
}

function validateBusinessRules(): boolean {
  if (form.contactPhone && !isValidMainlandMobile(form.contactPhone)) {
    ElMessage.warning('请输入有效的 11 位中国大陆手机号')
    return false
  }
  if (form.idCardNumber && !isValidChineseIdCard(form.idCardNumber)) {
    ElMessage.warning('请输入有效的 18 位身份证号，出生日期和校验码须正确')
    return false
  }
  if (form.cooperationStartDate && form.cooperationEndDate && form.cooperationEndDate < form.cooperationStartDate) {
    ElMessage.warning('合作结束时间不得早于合作开始时间')
    return false
  }
  if (form.settlementMethod && !settlementOptions.value.some((option) =>
    option.code === form.settlementMethod && !option.label.includes('历史'))) {
    ElMessage.warning('结算方式与当前合作类型不匹配，请重新选择')
    return false
  }
  return true
}

async function save() {
  if (saving.value || !validateBusinessRules()) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const profile = profilePayload()
    if (form.id) {
      await updateChannel(form.id, {
        ...profile,
        name: form.name.trim(),
        platformCode: text(form.platformCode),
        businessOwnerUserId: form.businessOwnerUserId ?? null,
        status: form.status,
        remarks: text(form.remarks),
        version: form.version,
      })
    } else {
      await createChannel({
        ...profile,
        name: form.name.trim(),
        platformCode: text(form.platformCode) ?? undefined,
        businessOwnerUserId: form.businessOwnerUserId ?? undefined,
        remarks: text(form.remarks) ?? undefined,
      })
    }
    ElMessage.success(form.id ? '合作渠道已保存' : '合作渠道已创建')
    visible.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : error instanceof Error ? error.message : '渠道保存失败')
  } finally {
    saving.value = false
  }
}

watch(() => props.modelValue, (value) => { if (value) reset() })
onMounted(() => {
  void dicts.load('customerChannels')
  void dicts.load('settlementTypes')
  void listChannelOwnerOptions().then((options) => { owners.value = options })
    .catch((error: unknown) => ElMessage.error(error instanceof Error ? error.message : '商务选项加载失败'))
})
</script>

<template>
  <el-dialog v-model="visible" :title="form.id ? '修改合作渠道' : '新增合作渠道'" width="min(960px, 96vw)" destroy-on-close>
    <el-alert type="info" :closable="false" title="联系方式、身份证和收款账号将加密保存；请仅录入真实合作所需资料。" class="form-alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <h3 class="form-section-title">基本信息</h3>
      <div class="form-grid">
        <el-form-item label="合作类型" prop="channelType" required>
          <el-select v-model="form.channelType" class="full-width" data-testid="channel-form-type" @change="handleChannelTypeChange">
            <el-option label="博主/达人" value="BLOGGER" />
            <el-option label="分销" value="DISTRIBUTION" />
            <el-option label="老带新（老客带新客）" value="REFERRAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="博主姓名/昵称" prop="name" required>
          <el-input v-model="form.name" maxlength="160" placeholder="用于列表展示与业务归因" data-testid="channel-form-name" />
        </el-form-item>
        <el-form-item label="推广渠道" prop="platformCode">
          <el-select v-model="form.platformCode" clearable filterable class="full-width" data-testid="channel-form-platform">
            <el-option v-for="option in platformOptions" :key="option.code" :label="option.label" :value="option.code" :disabled="option.label.endsWith('（历史/停用）')" />
          </el-select>
        </el-form-item>
        <el-form-item label="对接商务" prop="businessOwnerUserId">
          <el-select v-model="form.businessOwnerUserId" clearable filterable class="full-width" placeholder="选择商务姓名" data-testid="channel-form-owner">
            <el-option v-for="option in ownerOptions" :key="option.id" :label="option.displayName" :value="option.id" :disabled="option.displayName.endsWith('（已停用）')" />
          </el-select>
        </el-form-item>
      </div>

      <h3 class="form-section-title">身份与联系</h3>
      <div class="form-grid">
        <el-form-item label="真实姓名" prop="realName"><el-input v-model="form.realName" maxlength="80" /></el-form-item>
        <el-form-item label="联系方式" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="11" placeholder="11 位中国大陆手机号" data-testid="channel-form-phone" />
        </el-form-item>
        <el-form-item label="身份证号" prop="idCardNumber">
          <el-input v-model="form.idCardNumber" maxlength="18" placeholder="18 位，末位可为 X" data-testid="channel-form-id-card" />
        </el-form-item>
      </div>

      <h3 class="form-section-title">合作与结算</h3>
      <div class="form-grid">
        <el-form-item label="分佣比例（%）" prop="commissionPercent">
          <el-input-number v-model="form.commissionPercent" :min="0" :max="100" :precision="2" controls-position="right" class="full-width" placeholder="例如 12.50" data-testid="channel-form-commission" />
        </el-form-item>
        <el-form-item label="结算方式" prop="settlementMethod">
          <el-select v-model="form.settlementMethod" clearable class="full-width" data-testid="channel-form-settlement" :disabled="form.channelType === 'REFERRAL'" :placeholder="form.channelType === 'REFERRAL' ? '老带新暂无固定结算规则' : '请选择结算方式'">
            <el-option v-for="option in settlementOptions" :key="option.code" :label="option.label" :value="option.code" :disabled="option.label.includes('历史')" />
          </el-select>
        </el-form-item>
        <el-form-item label="合作开始时间" prop="cooperationStartDate">
          <el-date-picker v-model="form.cooperationStartDate" type="date" value-format="YYYY-MM-DD" class="full-width" data-testid="channel-form-start" />
        </el-form-item>
        <el-form-item label="合作结束时间" prop="cooperationEndDate">
          <el-date-picker v-model="form.cooperationEndDate" type="date" value-format="YYYY-MM-DD" class="full-width" data-testid="channel-form-end" />
        </el-form-item>
      </div>

      <h3 class="form-section-title">收款信息</h3>
      <div class="form-grid">
        <el-form-item label="收款账户信息" prop="paymentAccountName"><el-input v-model="form.paymentAccountName" maxlength="160" placeholder="收款账户名称" /></el-form-item>
        <el-form-item label="收款账号" prop="paymentAccountNo"><el-input v-model="form.paymentAccountNo" maxlength="64" /></el-form-item>
        <el-form-item label="收款开户行" prop="paymentBankName"><el-input v-model="form.paymentBankName" maxlength="255" /></el-form-item>
        <el-form-item label="备注" prop="remarks"><el-input v-model="form.remarks" type="textarea" :rows="2" maxlength="4000" /></el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" data-testid="channel-save" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-alert { margin-bottom: 18px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.form-section-title { margin: 18px 0 14px; padding-bottom: 8px; border-bottom: 1px solid #e8efef; color: #274b50; font-size: 14px; }
.form-section-title:first-of-type { margin-top: 0; }
.full-width { width: 100%; }
@media (max-width: 680px) { .form-grid { grid-template-columns: 1fr; } }
</style>
