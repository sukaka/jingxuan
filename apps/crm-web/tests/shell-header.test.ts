import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ShellHeader from '../src/components/shell/ShellHeader.vue'

function mountHeader(canExport: boolean) {
  return mount(ShellHeader, {
    attachTo: document.body,
    props: {
      section: '运营管理', title: '工作台', displayName: '测试管理员', username: 'admin', roleLabel: '管理员',
      navigationOpen: false, isAdmin: true, canExport,
    },
  })
}

describe('顶栏导出与个人账号菜单', () => {
  it('授权管理员可以进入真实导出中心', async () => {
    const wrapper = mountHeader(true)
    const button = wrapper.get('button[title="数据导出中心"]')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('export')).toHaveLength(1)
    wrapper.unmount()
  })

  it('没有导出权限时不展示入口', () => {
    const wrapper = mountHeader(false)
    expect(wrapper.find('button[title="数据导出中心"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('点击右上角用户名打开菜单，可选择修改密码或切换身份并支持 Escape 关闭', async () => {
    const wrapper = mountHeader(true)
    const trigger = wrapper.get('.role-switch')

    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('#header-account-menu').exists()).toBe(false)

    await trigger.trigger('click')
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('#header-account-menu').exists()).toBe(true)
    expect(wrapper.emitted('logout')).toBeUndefined()

    await wrapper.get('[data-testid="account-change-password"]').trigger('click')
    expect(wrapper.emitted('changePassword')).toHaveLength(1)
    expect(wrapper.find('#header-account-menu').exists()).toBe(false)

    await trigger.trigger('click')
    await wrapper.get('[data-testid="account-switch-identity"]').trigger('click')
    expect(wrapper.emitted('logout')).toHaveLength(1)
    expect(wrapper.find('#header-account-menu').exists()).toBe(false)

    await trigger.trigger('click')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('#header-account-menu').exists()).toBe(false)
    wrapper.unmount()
  })
})
