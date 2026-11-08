<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ShellIcon from '../components/shell/ShellIcon.vue'
import { getCaptcha } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const form = reactive({ username: '', password: '', captchaId: '', captchaAnswer: '' })
const captchaImage = ref('')
const captchaLoading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const showDevelopmentBadge = import.meta.env.DEV

async function loadCaptcha() {
  if (captchaLoading.value) return
  captchaLoading.value = true
  try {
    const result = await getCaptcha()
    form.captchaId = result.captchaId
    form.captchaAnswer = ''
    captchaImage.value = result.imageBase64
  } catch (error) {
    form.captchaId = ''
    captchaImage.value = ''
    errorMessage.value = error instanceof Error ? `${error.message}。请点击换一张重试` : '验证码加载失败，请稍后重试'
  } finally {
    captchaLoading.value = false
  }
}

onMounted(() => { void loadCaptcha() })

async function submit() {
  errorMessage.value = ''
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  if (!form.captchaId || !form.captchaAnswer.trim()) {
    errorMessage.value = '请输入验证码'
    return
  }
  submitting.value = true
  try {
    await auth.login(form.username.trim(), form.password, form.captchaId, form.captchaAnswer.trim())
  } catch (error) {
    if (error instanceof ApiError && error.isUnauthorized) {
      errorMessage.value = error.message || '用户名或密码错误'
    } else if (error instanceof ApiError && error.isNetworkError) {
      errorMessage.value = '无法连接后端服务，请确认后端已启动后重试'
    } else {
      errorMessage.value = error instanceof Error ? error.message : '登录失败，请稍后重试'
    }
    submitting.value = false
    await loadCaptcha()
    return
  }

  const redirect = route.query.redirect
  const target =
    typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
      ? redirect
      : '/'
  try {
    await router.push(target)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-panel">
      <section class="login-brand" aria-label="鲸选美学 CRM 介绍">
        <div>
          <div class="login-brand-mark" aria-hidden="true">鲸</div>
          <p class="login-edition">鲸选美学 · 医疗会员运营</p>
          <h1>让每一次客户服务<br>都有迹可循</h1>
          <p class="login-intro">围绕客户档案、跟进任务与团队协同，为医疗会员运营提供清晰、可靠的工作空间。</p>
          <div class="login-flow">
            <div><span><ShellIcon name="users" :size="18" /></span><p><strong>客户档案</strong><small>统一维护客户身份与服务脉络</small></p></div>
            <div><span><ShellIcon name="chat" :size="18" /></span><p><strong>持续跟进</strong><small>每次沟通独立留痕，待办清晰可追踪</small></p></div>
            <div><span><ShellIcon name="shield" :size="18" /></span><p><strong>权限与审计</strong><small>按角色与数据范围访问真实业务数据</small></p></div>
          </div>
        </div>
        <p class="login-brand-foot">客户唯一身份不由手机号或昵称替代，业务操作以服务端权限与审计为准。</p>
      </section>

      <section class="login-form-wrap">
        <div class="login-form-heading">
          <span v-if="showDevelopmentBadge" class="development-badge"><span aria-hidden="true" />开发环境</span>
          <h2>登录工作台</h2>
          <p>使用系统分配的用户名和密码登录</p>
        </div>
        <el-alert
          v-if="errorMessage"
          type="error"
          :closable="false"
          :title="errorMessage"
          class="login-alert"
        />
        <el-form class="login-form" :model="form" @submit.prevent="submit">
          <div class="login-field">
            <label for="login-username">用户名</label>
            <el-input
              id="login-username"
              v-model="form.username"
              placeholder="请输入用户名"
              autocomplete="username"
              :disabled="submitting"
              required
            />
          </div>
          <div class="login-field">
            <label for="login-password">密码</label>
            <el-input
              id="login-password"
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
              :disabled="submitting"
              required
            />
          </div>
          <div class="login-field">
            <label for="login-captcha">验证码</label>
            <div class="login-captcha-row">
              <el-input
                id="login-captcha"
                v-model="form.captchaAnswer"
                placeholder="请输入图中计算结果"
                inputmode="numeric"
                autocomplete="off"
                :disabled="submitting"
                required
              />
              <el-button
                class="login-captcha-image"
                native-type="button"
                :loading="captchaLoading"
                :disabled="captchaLoading || submitting"
                title="点击换一张"
                @click="loadCaptcha"
              >
                <img v-if="captchaImage" :src="`data:image/png;base64,${captchaImage}`" alt="算术验证码，点击换一张">
                <span v-else>换一张</span>
              </el-button>
            </div>
            <p class="login-captcha-help">输入计算结果，看不清可点击图片换一张</p>
          </div>
          <el-button
            type="primary"
            native-type="submit"
            :loading="submitting"
            :disabled="submitting"
            class="login-submit"
          >
            {{ submitting ? '正在登录…' : '登录' }}
          </el-button>
        </el-form>
        <div class="login-security">
          <ShellIcon name="lock" :size="15" />
          <p>登录后仅显示当前账号获授权的菜单与数据。请勿共享账号或密码。</p>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  min-height: 100dvh;
  place-items: center;
  padding: 40px;
  background:
    radial-gradient(circle at 8% 12%, rgb(44 190 170 / 12%), transparent 33%),
    radial-gradient(circle at 92% 8%, rgb(77 139 205 / 9%), transparent 30%),
    linear-gradient(145deg, #f5fbfa, #f8fafc 52%, #f3f7f9);
}

.login-panel {
  display: grid;
  width: min(1060px, 100%);
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
  overflow: hidden;
  border: 1px solid #e0eaed;
  border-radius: 24px;
  background: rgb(255 255 255 / 97%);
  box-shadow: 0 25px 80px rgb(18 54 72 / 10%);
}

.login-brand {
  display: flex;
  min-height: 610px;
  justify-content: space-between;
  flex-direction: column;
  padding: 48px 45px 36px;
  color: #fff;
  background:
    radial-gradient(circle at 90% 5%, rgb(60 199 174 / 13%), transparent 33%),
    linear-gradient(155deg, #0c2939, #103e48);
}

.login-brand-mark {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 17px;
  background: linear-gradient(140deg, #59d3ba, #0a9a91);
  box-shadow: 0 12px 34px rgb(26 185 164 / 25%);
  font-size: 30px;
  font-weight: 780;
}

.login-edition {
  margin: 30px 0 0;
  color: #75cbbd;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 1.6px;
}

.login-brand h1 {
  margin: 13px 0 15px;
  font-size: 31px;
  font-weight: 740;
  line-height: 1.45;
  letter-spacing: -0.7px;
}

.login-intro {
  margin: 0;
  color: #a9c4cd;
  font-size: 13px;
  line-height: 1.9;
}

.login-flow {
  display: grid;
  gap: 11px;
  margin-top: 31px;
}

.login-flow > div {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 13px;
  border: 1px solid rgb(255 255 255 / 9%);
  border-radius: 11px;
  background: rgb(255 255 255 / 3.5%);
}

.login-flow > div > span {
  display: grid;
  width: 34px;
  height: 34px;
  flex: none;
  place-items: center;
  border-radius: 9px;
  color: #7bd4c3;
  background: rgb(86 201 180 / 9%);
}

.login-flow p {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin: 0;
}

.login-flow strong {
  color: #e2f1f3;
  font-size: 12px;
  font-weight: 650;
}

.login-flow small {
  color: #88aab6;
  font-size: 10px;
}

.login-brand-foot {
  margin: 28px 0 0;
  color: #749ba8;
  font-size: 10px;
  line-height: 1.8;
}

.login-form-wrap {
  display: flex;
  justify-content: center;
  flex-direction: column;
  padding: 48px 52px;
}

.development-badge {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 6px;
  padding: 5px 9px;
  border: 1px solid #d9eae6;
  border-radius: 999px;
  color: #397c72;
  background: #f0f9f7;
  font-size: 10px;
  font-weight: 650;
}

.development-badge > span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #20a48e;
}

.login-form-heading h2 {
  margin: 19px 0 7px;
  color: #203b4d;
  font-size: 26px;
  font-weight: 740;
  letter-spacing: -0.5px;
}

.login-form-heading > p {
  margin: 0 0 26px;
  color: #8a9ca7;
  font-size: 12px;
}

.login-alert {
  margin-bottom: 18px;
  border-radius: 9px;
}

.login-field {
  margin-bottom: 18px;
}

.login-field label {
  display: block;
  margin-bottom: 8px;
  color: #465f6e;
  font-size: 12px;
  font-weight: 650;
}

.login-field :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 4px 13px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px #dfe8ec inset;
}

.login-field :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #36ad9f inset, 0 0 0 3px #e7f7f3;
}

.login-captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 142px;
  gap: 10px;
}

.login-captcha-image {
  width: 142px;
  height: 48px;
  padding: 0;
  overflow: hidden;
  border: 1px solid #dfe8ec;
  border-radius: 10px;
  background: #f6f9f8;
}

.login-captcha-image img {
  display: block;
  width: 100%;
  height: 46px;
  object-fit: cover;
}

.login-captcha-help {
  margin: 7px 0 0;
  color: #91a2ac;
  font-size: 11px;
  line-height: 1.6;
}

.login-submit {
  width: 100%;
  min-height: 46px;
  margin-top: 4px;
  border-radius: 10px;
  font-weight: 680;
  box-shadow: 0 7px 18px rgb(15 148 139 / 16%);
}

.login-security {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 22px;
  color: #96a6af;
}

.login-security .shell-icon {
  margin-top: 2px;
}

.login-security p {
  margin: 0;
  font-size: 10px;
  line-height: 1.8;
}

@media (max-width: 900px) {
  .login-panel {
    grid-template-columns: minmax(0, 1fr);
  }

  .login-brand {
    min-height: auto;
    padding: 32px 36px;
  }

  .login-brand h1 {
    font-size: 27px;
  }

  .login-flow,
  .login-brand-foot {
    display: none;
  }

  .login-form-wrap {
    padding: 36px;
  }
}

@media (max-width: 560px) {
  .login-page {
    padding: 0;
  }

  .login-panel {
    min-height: 100dvh;
    border: 0;
    border-radius: 0;
  }

  .login-brand {
    padding: 26px 22px;
  }

  .login-brand-mark {
    width: 45px;
    height: 45px;
    border-radius: 14px;
    font-size: 25px;
  }

  .login-edition {
    margin-top: 20px;
  }

  .login-brand h1 {
    margin-bottom: 0;
    font-size: 24px;
  }

  .login-intro {
    display: none;
  }

  .login-form-wrap {
    padding: 28px 22px 34px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-submit {
    box-shadow: none;
  }
}
</style>
