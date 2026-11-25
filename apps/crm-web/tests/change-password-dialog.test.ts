import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { changePassword, DEFAULT_PASSWORD_POLICY, getPasswordPolicy } from '../src/api/auth'
import ChangePasswordDialog from '../src/components/shell/ChangePasswordDialog.vue'
import { useAuthStore } from '../src/stores/auth'
import { ApiError } from '../src/types/api'

vi.mock('../src/api/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/auth')>()),
  getPasswordPolicy: vi.fn(),
  changePassword: vi.fn(),
}))

const wrappers: VueWrapper[] = []

async function mountDialog(modelValue = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = {
    userId: 9,
    username: 'manager1',
    displayName: '王丽',
    userType: 'EMPLOYEE',
    roles: ['MANAGER'],
    permissions: ['customer:read'],
  }
  auth.initialized = true

  const wrapper = mount(ChangePasswordDialog, {
    attachTo: document.body,
    props: {
      modelValue: false,
      username: 'manager1',
      displayName: '王丽',
    },
    global: { plugins: [pinia, ElementPlus] },
  })
  wrappers.push(wrapper)
  if (modelValue) {
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
  }
  return { wrapper, auth }
}

beforeEach(() => {
  vi.mocked(getPasswordPolicy).mockReset().mockResolvedValue({ ...DEFAULT_PASSWORD_POLICY })
  vi.mocked(changePassword).mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

describe('ChangePasswordDialog 个人修改密码弹窗', () => {
  it('打开时加载服务端复杂度策略并拦截不合规或两次输入不一致的提交', async () => {
    await mountDialog(true)

    expect(getPasswordPolicy).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('长度 8–64 位')
    expect(document.body.textContent).toContain('包含至少 1 个特殊字符')

    const currentInput = document.querySelector<HTMLInputElement>('[data-testid="current-password-input"]')!
    const newInput = document.querySelector<HTMLInputElement>('[data-testid="new-password-input"]')!
    const confirmInput = document.querySelector<HTMLInputElement>('[data-testid="confirm-password-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="submit-change-password"]')!

    currentInput.value = 'OldPass@2025'
    currentInput.dispatchEvent(new Event('input'))
    newInput.value = 'weakpass1'
    newInput.dispatchEvent(new Event('input'))
    confirmInput.value = 'weakpass2'
    confirmInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(changePassword).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('新密码必须包含至少 1 个大写字母')
    expect(document.body.textContent).toContain('两次输入的新密码不一致')
  })

  it('校验通过后提交修改密码接口、清空本地会话并发出 success 事件', async () => {
    const { wrapper, auth } = await mountDialog(true)

    const currentInput = document.querySelector<HTMLInputElement>('[data-testid="current-password-input"]')!
    const newInput = document.querySelector<HTMLInputElement>('[data-testid="new-password-input"]')!
    const confirmInput = document.querySelector<HTMLInputElement>('[data-testid="confirm-password-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="submit-change-password"]')!

    currentInput.value = 'OldPass@2025'
    currentInput.dispatchEvent(new Event('input'))
    newInput.value = 'NewStrong#2026'
    newInput.dispatchEvent(new Event('input'))
    confirmInput.value = 'NewStrong#2026'
    confirmInput.dispatchEvent(new Event('input'))
    await flushPromises()

    expect(document.querySelector('[data-testid="password-strength"]')?.textContent).toContain('强')
    expect(document.body.textContent).toContain('两次输入的新密码一致')

    submitBtn.click()
    await flushPromises()

    expect(changePassword).toHaveBeenCalledWith({
      currentPassword: 'OldPass@2025',
      newPassword: 'NewStrong#2026',
      confirmPassword: 'NewStrong#2026',
    })
    expect(auth.user).toBeNull()
    expect(wrapper.emitted('success')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false])
  })

  it('原密码错误（400）保留会话并在原密码输入框下展示服务端字段错误', async () => {
    vi.mocked(changePassword).mockRejectedValueOnce(new ApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      message: '原密码不正确',
      details: [{ field: 'currentPassword', reason: '原密码不正确' }],
    }))
    const { wrapper, auth } = await mountDialog(true)

    const currentInput = document.querySelector<HTMLInputElement>('[data-testid="current-password-input"]')!
    const newInput = document.querySelector<HTMLInputElement>('[data-testid="new-password-input"]')!
    const confirmInput = document.querySelector<HTMLInputElement>('[data-testid="confirm-password-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="submit-change-password"]')!

    currentInput.value = 'WrongOld@2025'
    currentInput.dispatchEvent(new Event('input'))
    newInput.value = 'NewStrong#2026'
    newInput.dispatchEvent(new Event('input'))
    confirmInput.value = 'NewStrong#2026'
    confirmInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(auth.user?.username).toBe('manager1')
    expect(wrapper.emitted('success')).toBeUndefined()
    expect(document.body.textContent).toContain('原密码不正确')
  })

  it('触发 429 限流时在弹窗内展示限流提示并保留会话', async () => {
    vi.mocked(changePassword).mockRejectedValueOnce(new ApiError({
      status: 429,
      code: 'RATE_LIMITED',
      message: '原密码校验失败次数过多，请15分钟后再试',
    }))
    const { auth } = await mountDialog(true)

    const currentInput = document.querySelector<HTMLInputElement>('[data-testid="current-password-input"]')!
    const newInput = document.querySelector<HTMLInputElement>('[data-testid="new-password-input"]')!
    const confirmInput = document.querySelector<HTMLInputElement>('[data-testid="confirm-password-input"]')!
    const submitBtn = document.querySelector<HTMLButtonElement>('[data-testid="submit-change-password"]')!

    currentInput.value = 'WrongOld@2025'
    currentInput.dispatchEvent(new Event('input'))
    newInput.value = 'NewStrong#2026'
    newInput.dispatchEvent(new Event('input'))
    confirmInput.value = 'NewStrong#2026'
    confirmInput.dispatchEvent(new Event('input'))
    await flushPromises()

    submitBtn.click()
    await flushPromises()

    expect(auth.user?.username).toBe('manager1')
    expect(document.body.textContent).toContain('原密码校验失败次数过多，请15分钟后再试')
  })
})
