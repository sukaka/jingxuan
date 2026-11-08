import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardChart from '../src/views/dashboard/DashboardChart.vue'

const chart = vi.hoisted(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }))
const echarts = vi.hoisted(() => ({ init: vi.fn(() => chart), use: vi.fn() }))
vi.mock('echarts/core', () => echarts)

const observer = vi.hoisted(() => ({ observe: vi.fn(), disconnect: vi.fn() }))
let resizeCallback: (() => void) | undefined

beforeEach(() => {
  vi.clearAllMocks()
  resizeCallback = undefined
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { resizeCallback = callback }
    observe = observer.observe
    disconnect = observer.disconnect
  })
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn().mockReturnValue({ matches: false }) })
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('DashboardChart 生命周期', () => {
  it('初始化、更新、响应尺寸并在卸载时销毁', async () => {
    const wrapper = mount(DashboardChart, {
      props: { option: { series: [] }, label: '测试图表' },
      attachTo: document.body,
    })
    await flushPromises()

    expect(echarts.init).toHaveBeenCalledTimes(1)
    expect(chart.setOption).toHaveBeenCalledWith(expect.objectContaining({ animation: true }), { notMerge: true })
    expect(observer.observe).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ option: { series: [{ type: 'bar', data: [123] }] } })
    expect(chart.setOption).toHaveBeenLastCalledWith(expect.objectContaining({
      series: [{ type: 'bar', data: [123] }],
    }), { notMerge: true })

    resizeCallback?.()
    expect(chart.resize).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    expect(observer.disconnect).toHaveBeenCalledTimes(1)
    expect(chart.dispose).toHaveBeenCalledTimes(1)
  })
})
