import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ShellHeader from '../src/components/shell/ShellHeader.vue'

function mountHeader(canExport: boolean) {
  return mount(ShellHeader, {
    props: {
      section: '运营管理', title: '工作台', displayName: '测试管理员', roleLabel: '管理员',
      navigationOpen: false, isAdmin: true, canExport,
    },
  })
}

describe('顶栏导出入口', () => {
  it('授权管理员可以进入真实导出中心', async () => {
    const wrapper = mountHeader(true)
    const button = wrapper.get('button[title="数据导出中心"]')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('export')).toHaveLength(1)
  })

  it('没有导出权限时不展示入口', () => {
    const wrapper = mountHeader(false)
    expect(wrapper.find('button[title="数据导出中心"]').exists()).toBe(false)
  })
})
