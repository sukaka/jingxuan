import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RollingNumber from '../src/components/RollingNumber.vue'

function mockMotion(reduced = false) {
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: reduced,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })))
}

function mockAnimation(pending = false) {
  const cancel = vi.fn()
  const animate = vi.fn(() => ({
    cancel,
    finished: pending ? new Promise(() => {}) : Promise.resolve(),
  }))
  Object.defineProperty(Element.prototype, 'animate', { configurable: true, writable: true, value: animate })
  return { animate, cancel }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('RollingNumber 数字滚动', () => {
  it('只为变化的数位创建滚动轨道，完成后展示最新真实值', async () => {
    mockMotion()
    const { animate } = mockAnimation()
    const wrapper = mount(RollingNumber, {
      props: { value: 128680, formatter: (value: number) => new Intl.NumberFormat('zh-CN').format(value), label: '今日净支付' },
    })
    expect(animate).not.toHaveBeenCalled()

    await wrapper.setProps({ value: 131480 })
    expect(wrapper.findAll('.digit-track')).toHaveLength(3)
    await flushPromises()
    expect(wrapper.get('.sr-only').text()).toBe('131,480')
    expect(wrapper.attributes('aria-label')).toBe('今日净支付 131,480')
    expect(wrapper.findAll('.digit-track')).toHaveLength(0)
    wrapper.unmount()
  })

  it('位数变化时平滑替换，不沿用旧数字位数', async () => {
    mockMotion()
    mockAnimation()
    const wrapper = mount(RollingNumber, { props: { value: 9999 } })
    await wrapper.setProps({ value: 10000 })
    await flushPromises()
    expect(wrapper.get('.sr-only').text()).toBe('10000')
    expect(wrapper.findAll('.digit-track')).toHaveLength(0)
    wrapper.unmount()
  })

  it('减少动态效果时直接显示真实值，不创建滚动动画', async () => {
    mockMotion(true)
    const { animate } = mockAnimation()
    const wrapper = mount(RollingNumber, { props: { value: 26 } })
    await wrapper.setProps({ value: 27 })
    await flushPromises()
    expect(animate).not.toHaveBeenCalled()
    expect(wrapper.get('.sr-only').text()).toBe('27')
    wrapper.unmount()
  })

  it('组件卸载时取消未完成的动画', async () => {
    mockMotion()
    const { cancel } = mockAnimation(true)
    const wrapper = mount(RollingNumber, { props: { value: 26 } })
    await wrapper.setProps({ value: 27 })
    await flushPromises()
    wrapper.unmount()
    expect(cancel).toHaveBeenCalled()
  })
})
