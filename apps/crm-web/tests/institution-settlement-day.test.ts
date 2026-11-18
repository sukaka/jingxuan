import { flushPromises, mount } from '@vue/test-utils'
import type { AxiosAdapter } from 'axios'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it } from 'vitest'
import type { VueWrapper } from '@vue/test-utils'
import type { InstitutionSettlementDefault } from '../src/api/institution'
import { http } from '../src/api/http'
import { useAuthStore } from '../src/stores/auth'
import InstitutionSettlementDefaultCard from '../src/views/institution/InstitutionSettlementDefaultCard.vue'
import { institutionSettlementDayLabel, settlementDayLabel } from '../src/views/institution/institutionUi'

const originalAdapter = http.defaults.adapter
const wrappers: VueWrapper[] = []

afterEach(() => {
  http.defaults.adapter = originalAdapter
  while (wrappers.length) wrappers.pop()?.unmount()
  document.body.innerHTML = ''
})

const initial: InstitutionSettlementDefault = {
  defaultSettlementDay: 'LAST_DAY',
  version: 3,
  configured: true,
  updatedAt: '2026-11-01T02:00:00Z',
}

function capture(responses: InstitutionSettlementDefault[]) {
  const calls: { method: string; url: string; data: unknown; key: unknown }[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: config.method ?? 'get',
      url: config.url ?? '',
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      key: config.headers?.get('Idempotency-Key'),
    })
    const response = responses.shift() ?? initial
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: response, requestId: 'settlement-day-test', timestamp: '' },
    }
  }
  return { adapter, calls }
}

async function mountCard(userType: 'EMPLOYEE' | 'INSTITUTION' = 'EMPLOYEE') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    userId: 10,
    username: 'settlement-day-test',
    displayName: '结算日测试',
    userType,
    roles: userType === 'INSTITUTION' ? ['INSTITUTION_STAFF'] : ['ADMIN'],
    permissions: ['institution:read', 'institution:write'],
  }
  auth.initialized = true
  const wrapper = mount(InstitutionSettlementDefaultCard, {
    global: { plugins: [ElementPlus, pinia] },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

function component(wrapper: VueWrapper, testId: string): VueWrapper {
  return wrapper.getComponent(`[data-testid="${testId}"]`) as VueWrapper
}

describe('机构月结结算日', () => {
  it('使用中文明确区分系统默认继承和机构自定义', () => {
    expect(settlementDayLabel('LAST_DAY')).toBe('每月最后一天')
    expect(settlementDayLabel('8')).toBe('每月 8 日')
    expect(institutionSettlementDayLabel(null, 'LAST_DAY')).toBe('每月最后一天（系统默认）')
    expect(institutionSettlementDayLabel('8', 'LAST_DAY')).toBe('每月 8 日（机构自定义）')
  })

  it('系统默认配置读取后按乐观锁和幂等键保存，并提示只影响之后生成的月结批次', async () => {
    const { adapter, calls } = capture([
      initial,
      { ...initial, defaultSettlementDay: '15', version: 4 },
    ])
    http.defaults.adapter = adapter
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('系统默认结算日')
    expect(wrapper.text()).toContain('每月最后一天')
    expect(wrapper.text()).toContain('只影响之后生成的月结批次')
    expect(wrapper.text()).toContain('次卡次结不适用')

    component(wrapper, 'settlement-default-value').vm.$emit('update:modelValue', '15')
    await flushPromises()
    await wrapper.get('[data-testid="settlement-default-save"]').trigger('click')
    await flushPromises()

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ['get', '/institution-settlement-default'],
      ['put', '/institution-settlement-default'],
    ])
    expect(calls[1]?.data).toEqual({ defaultSettlementDay: '15', version: 3 })
    expect(calls[1]?.key).toEqual(expect.any(String))
    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({ defaultSettlementDay: '15', version: 4 })
  })

  it('机构账号可读取本机构所需默认值但不能修改系统配置', async () => {
    const { adapter, calls } = capture([initial])
    http.defaults.adapter = adapter
    const wrapper = await mountCard('INSTITUTION')

    expect(component(wrapper, 'settlement-default-value').props()).toMatchObject({ disabled: true })
    expect(wrapper.find('[data-testid="settlement-default-save"]').exists()).toBe(false)
    expect(calls.map((call) => call.method)).toEqual(['get'])
  })
})
