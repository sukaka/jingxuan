<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { PasswordPolicy } from '../../api/auth'
import { DEFAULT_PASSWORD_POLICY, getPasswordPolicy } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import {
  evaluatePasswordRules,
  evaluatePasswordStrength,
  validateChangePasswordInput,
} from '../../utils/passwordPolicy'
import type { ChangePasswordFieldErrors } from '../../utils/passwordPolicy'
import ShellIcon from './ShellIcon.vue'

const props = defineProps<{
  modelValue: boolean
  username?: string
  displayName?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const auth = useAuthStore()
const policy = ref<PasswordPolicy>({ ...DEFAULT_PASSWORD_POLICY })
const submitting = ref(false)
const submitError = ref('')
const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const fieldErrors = reactive<ChangePasswordFieldErrors>({})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const ruleChecks = computed(() =>
  evaluatePasswordRules(form.newPassword, policy.value, {
    username: props.username,
    currentPassword: form.currentPassword,
  }),
)

const strength = computed(() =>
  evaluatePasswordStrength(form.newPassword, policy.value, {
    username: props.username,
    currentPassword: form.currentPassword,
  }),
)

const confirmState = computed<'idle' | 'match' | 'mismatch'>(() => {
  if (!form.confirmPassword) return 'idle'
  return form.newPassword === form.confirmPassword ? 'match' : 'mismatch'
})

function resetForm() {
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  fieldErrors.currentPassword = undefined
  fieldErrors.newPassword = undefined
  fieldErrors.confirmPassword = undefined
  submitError.value = ''
}

async function loadPolicy() {
  try {
    policy.value = await getPasswordPolicy()
  } catch {
    policy.value = { ...DEFAULT_PASSWORD_POLICY }
  }
}

watch(
  () => props.modelValue,
  (open) => {
    resetForm()
    if (open) {
      void loadPolicy()
    }
  },
)

function applyServerFieldErrors(error: ApiError): boolean {
  if (!Array.isArray(error.details)) return false
  let matched = false
  for (const item of error.details) {
    if (!item || typeof item !== 'object') continue
    const detail = item as { field?: unknown; reason?: unknown; message?: unknown }
    const text = typeof detail.reason === 'string'
      ? detail.reason
      : (typeof detail.message === 'string' ? detail.message : '')
    if (typeof detail.field !== 'string' || !text) continue
    if (detail.field === 'currentPassword' && !fieldErrors.currentPassword) {
      fieldErrors.currentPassword = text
      matched = true
    } else if (detail.field === 'newPassword' && !fieldErrors.newPassword) {
      fieldErrors.newPassword = text
      matched = true
    } else if (detail.field === 'confirmPassword' && !fieldErrors.confirmPassword) {
      fieldErrors.confirmPassword = text
      matched = true
    }
  }
  return matched
}

function handleBeforeClose(done: () => void) {
  if (submitting.value) return
  resetForm()
  done()
}

function handleClose() {
  if (submitting.value) return
  resetForm()
  visible.value = false
}

async function handleSubmit() {
  if (submitting.value) return
  submitError.value = ''
  fieldErrors.currentPassword = undefined
  fieldErrors.newPassword = undefined
  fieldErrors.confirmPassword = undefined

  const check = validateChangePasswordInput(
    {
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
      confirmPassword: form.confirmPassword,
    },
    policy.value,
    props.username,
  )
  if (!check.valid) {
    fieldErrors.currentPassword = check.fieldErrors.currentPassword
    fieldErrors.newPassword = check.fieldErrors.newPassword
    fieldErrors.confirmPassword = check.fieldErrors.confirmPassword
    return
  }

  submitting.value = true
  try {
    await auth.changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
      confirmPassword: form.confirmPassword,
    })
    resetForm()
    visible.value = false
    emit('success')
  } catch (error) {
    if (error instanceof ApiError) {
      const hasFieldError = applyServerFieldErrors(error)
      if (!hasFieldError || error.status !== 400) {
        submitError.value = error.isNetworkError
          ? '服务暂时无法连接，请稍后重试'
          : (error.message || '修改密码失败，请稍后重试')
      }
    } else {
      submitError.value = error instanceof Error ? error.message : '修改密码失败，请稍后重试'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="修改登录密码"
    width="480px"
    :close-on-click-modal="false"
    :before-close="handleBeforeClose"
    destroy-on-close
    class="change-password-dialog"
  >
    <div class="pwd-account-banner">
      <ShellIcon name="shield" :size="16" />
      <div>
        <strong>{{ displayName || username || '当前账号' }}<span v-if="username" class="pwd-username">@{{ username }}</span></strong>
        <p>修改密码成功后将退出当前及所有其他设备的登录会话，请使用新密码重新登录。</p>
      </div>
    </div>

    <el-alert
      v-if="submitError"
      type="error"
      :closable="false"
      :title="submitError"
      class="pwd-alert"
    />

    <el-form label-position="top" class="pwd-form" @submit.prevent="handleSubmit">
      <el-form-item label="当前密码（原密码）" :class="{ 'is-error': Boolean(fieldErrors.currentPassword) }" required>
        <el-input
          v-model="form.currentPassword"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="请输入当前正在使用的密码"
          :disabled="submitting"
          data-testid="current-password-input"
          @input="fieldErrors.currentPassword = undefined"
        />
        <p v-if="fieldErrors.currentPassword" class="pwd-field-error" role="alert" data-testid="current-password-error">
          {{ fieldErrors.currentPassword }}
        </p>
      </el-form-item>

      <el-form-item label="新密码" :class="{ 'is-error': Boolean(fieldErrors.newPassword) }" required>
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="`请输入 ${policy.minLength}-${policy.maxLength} 位高强度新密码`"
          :disabled="submitting"
          data-testid="new-password-input"
          @input="fieldErrors.newPassword = undefined"
        />
        <p v-if="fieldErrors.newPassword" class="pwd-field-error" role="alert" data-testid="new-password-error">
          {{ fieldErrors.newPassword }}
        </p>

        <div class="pwd-strength" data-testid="password-strength">
          <div class="pwd-strength-head">
            <span>密码强度</span>
            <strong :class="`is-${strength.level}`">{{ strength.label }}</strong>
          </div>
          <div class="pwd-strength-track" aria-hidden="true">
            <span class="pwd-strength-fill" :class="`is-${strength.level}`" :style="{ width: `${strength.percent}%` }" />
          </div>
        </div>

        <ul class="pwd-rules" aria-label="密码复杂度要求" data-testid="password-rules">
          <li v-for="rule in ruleChecks" :key="rule.key" :class="{ passed: rule.passed }">
            <span class="pwd-rule-dot" aria-hidden="true">{{ rule.passed ? '✓' : '•' }}</span>
            <span>{{ rule.label }}</span>
          </li>
        </ul>
      </el-form-item>

      <el-form-item label="确认新密码" :class="{ 'is-error': Boolean(fieldErrors.confirmPassword) }" required>
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="请再次输入新密码"
          :disabled="submitting"
          data-testid="confirm-password-input"
          @input="fieldErrors.confirmPassword = undefined"
          @keyup.enter="handleSubmit"
        />
        <p v-if="fieldErrors.confirmPassword" class="pwd-field-error" role="alert" data-testid="confirm-password-error">
          {{ fieldErrors.confirmPassword }}
        </p>
        <p v-else-if="confirmState === 'match'" class="pwd-confirm-hint is-match">
          两次输入的新密码一致
        </p>
        <p v-else-if="confirmState === 'mismatch'" class="pwd-confirm-hint is-mismatch">
          两次输入的新密码不一致
        </p>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="handleClose">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        data-testid="submit-change-password"
        @click="handleSubmit"
      >
        {{ submitting ? '正在提交…' : '确认修改' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pwd-account-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px solid #d5ece7;
  border-radius: 10px;
  color: #116f67;
  background: #f2fbf9;
}

.pwd-account-banner strong {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #183446;
  font-size: 13px;
  font-weight: 700;
}

.pwd-username {
  color: #6f8998;
  font-size: 11px;
  font-weight: 500;
}

.pwd-account-banner p {
  margin: 4px 0 0;
  color: #4f7274;
  font-size: 11px;
  line-height: 1.6;
}

.pwd-alert {
  margin-bottom: 14px;
}

.pwd-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.pwd-strength {
  width: 100%;
  margin-top: 8px;
}

.pwd-strength-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
  color: #7c92a0;
  font-size: 11px;
  line-height: 1.4;
}

.pwd-strength-head strong.is-empty { color: #95a7b2; }
.pwd-strength-head strong.is-weak { color: #d04f5c; }
.pwd-strength-head strong.is-medium { color: #c48024; }
.pwd-strength-head strong.is-strong { color: #0f948b; }

.pwd-strength-track {
  height: 5px;
  overflow: hidden;
  border-radius: 999px;
  background: #e9eff3;
}

.pwd-strength-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width 0.2s ease, background-color 0.2s ease;
}

.pwd-strength-fill.is-empty { background: transparent; }
.pwd-strength-fill.is-weak { background: #dc5b67; }
.pwd-strength-fill.is-medium { background: #dfa03a; }
.pwd-strength-fill.is-strong { background: #0f948b; }

.pwd-rules {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px 12px;
  margin: 10px 0 0;
  padding: 10px 12px;
  list-style: none;
  border: 1px solid #ebf1f4;
  border-radius: 9px;
  background: #f9fbfc;
}

.pwd-rules li {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #7f95a3;
  font-size: 11px;
  line-height: 1.5;
}

.pwd-rules li.passed {
  color: #137f6c;
  font-weight: 600;
}

.pwd-rule-dot {
  display: inline-flex;
  width: 14px;
  justify-content: center;
  flex: none;
  font-weight: 700;
}

.pwd-field-error {
  margin: 5px 0 0;
  color: #d04f5c;
  font-size: 11px;
  line-height: 1.45;
}

.pwd-confirm-hint {
  margin: 5px 0 0;
  font-size: 11px;
  line-height: 1.4;
}

.pwd-confirm-hint.is-match {
  color: #137f6c;
}

.pwd-confirm-hint.is-mismatch {
  color: #d04f5c;
}

@media (max-width: 520px) {
  .pwd-rules {
    grid-template-columns: 1fr;
  }
}
</style>
