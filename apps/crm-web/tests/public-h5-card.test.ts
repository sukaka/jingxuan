import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getPublicH5Card } from '../src/api/h5Card'
import { routes } from '../src/router'
import PublicH5CardView from '../src/views/PublicH5CardView.vue'

vi.mock('../src/api/h5Card', () => ({ getPublicH5Card: vi.fn() }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ params: { shareKey: 'high-entropy-share-key' } }) }
})

afterEach(() => {
  document.head.querySelectorAll('meta[name="robots"]').forEach((item) => item.remove())
})

describe('H5 公开项目介绍', () => {
  it('免登录路由仅展示公开快照，并添加 noindex', async () => {
    vi.mocked(getPublicH5Card).mockResolvedValue({
      projectName: '测试护理项目', title: '项目介绍', summary: '服务流程与预约方式', content: '公开介绍第一行\n公开介绍第二行',
      imageUrl: 'https://crm.example.com/h5-card-cover.svg',
    })
    const wrapper = mount(PublicH5CardView)
    await flushPromises()

    expect(getPublicH5Card).toHaveBeenCalledWith('high-entropy-share-key')
    expect(wrapper.text()).toContain('测试护理项目')
    expect(wrapper.text()).toContain('公开介绍第一行')
    expect(wrapper.text()).not.toContain('customerId')
    expect(document.head.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex,nofollow,noarchive')
    const route = routes.find((item) => item.name === 'public-h5-card')
    expect(route?.meta).toMatchObject({ public: true, display: true })
    wrapper.unmount()
    expect(document.head.querySelector('meta[name="robots"]')).toBeNull()
  })

  it('链接失效时展示明确错误而不是伪造内容', async () => {
    vi.mocked(getPublicH5Card).mockRejectedValue(new Error('卡片不存在或不可见'))
    const wrapper = mount(PublicH5CardView)
    await flushPromises()
    expect(wrapper.get('[data-testid="public-h5-error"]').text()).toContain('卡片不存在或不可见')
    expect(wrapper.find('.public-card-content').exists()).toBe(false)
    wrapper.unmount()
  })
})
