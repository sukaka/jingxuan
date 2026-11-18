import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getWecomSidebarSignature } from '../src/api/wecomSidebar'
import { getWecomChatContext, isWecomRuntime, sendH5CardToCurrentChat, sendTextToCurrentChat } from '../src/services/wecomSdk'

vi.mock('../src/api/wecomSidebar', () => ({ getWecomSidebarSignature: vi.fn() }))

const bundle = {
  corpId: 'ww-test',
  agentId: 10001,
  url: 'http://localhost/wecom/sidebar',
  config: { nonceStr: 'nonce-a', timestamp: 1, signature: 'signature-a' },
  agentConfig: { nonceStr: 'nonce-b', timestamp: 2, signature: 'signature-b' },
}

function useWecomRuntime() {
  Object.defineProperty(window.navigator, 'userAgent', {
    configurable: true,
    value: 'Mozilla/5.0 (iPhone) MicroMessenger wxwork/4.1.38',
  })
}

describe('企微 JS-SDK 聊天上下文', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/wecom/sidebar#customer')
    vi.mocked(getWecomSidebarSignature).mockResolvedValue(bundle)
    delete window.ww
  })

  it('使用新版 ww.register 注册双签名并获取当前单聊客户', async () => {
    useWecomRuntime()
    const register = vi.fn()
    const getCurExternalContact = vi.fn().mockResolvedValue({ errMsg: 'getCurExternalContact:ok', userId: 'external-1001' })
    window.ww = {
      register,
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact,
    }

    await expect(getWecomChatContext()).resolves.toEqual({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-1001',
    })
    expect(getWecomSidebarSignature).toHaveBeenCalledWith(window.location.href.split('#')[0])
    expect(register).toHaveBeenCalledWith(expect.objectContaining({
      corpId: 'ww-test', agentId: 10001, jsApiList: ['getContext', 'getCurExternalContact', 'sendChatMessage'],
    }))
    const options = register.mock.calls[0]?.[0]
    expect(options.getConfigSignature()).toEqual(bundle.config)
    expect(options.getAgentConfigSignature()).toEqual(bundle.agentConfig)
  })

  it('普通浏览器不加载 SDK 或请求签名', async () => {
    Object.defineProperty(window.navigator, 'userAgent', { configurable: true, value: 'Mozilla/5.0 Chrome' })

    await expect(getWecomChatContext()).resolves.toEqual({ kind: 'BROWSER' })
    expect(isWecomRuntime()).toBe(false)
    expect(getWecomSidebarSignature).not.toHaveBeenCalled()
  })

  it('群聊不读取当前外部联系人', async () => {
    useWecomRuntime()
    const getCurExternalContact = vi.fn()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'group_chat_tools' }),
      getCurExternalContact,
    }

    await expect(getWecomChatContext()).resolves.toEqual({ kind: 'GROUP_CHAT', entry: 'group_chat_tools' })
    expect(getCurExternalContact).not.toHaveBeenCalled()
  })

  it('SDK 错误与普通入口分开处理', async () => {
    useWecomRuntime()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValueOnce({ errMsg: 'getContext:fail' }),
      getCurExternalContact: vi.fn(),
    }
    await expect(getWecomChatContext()).rejects.toThrow('上下文读取失败')

    window.ww.getContext = vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'desktop_home' })
    await expect(getWecomChatContext()).resolves.toEqual({ kind: 'NOT_CHAT', entry: 'desktop_home' })
  })

  it('发送前重新检查单聊上下文并返回 SDK 结果', async () => {
    useWecomRuntime()
    const sendChatMessage = vi.fn().mockResolvedValue({ errMsg: 'sendChatMessage:ok' })
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact: vi.fn(),
      sendChatMessage,
    }

    await expect(sendTextToCurrentChat('你好')).resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenCalledWith(expect.objectContaining({ msgtype: 'text', text: { content: '你好' } }))
  })

  it('非单聊入口不发送，SDK 异常不冒充成功', async () => {
    useWecomRuntime()
    const sendChatMessage = vi.fn()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'group_chat_tools' }),
      getCurExternalContact: vi.fn(),
      sendChatMessage,
    }

    await expect(sendTextToCurrentChat('你好')).resolves.toMatchObject({ status: 'SDK_FAILED' })
    expect(sendChatMessage).not.toHaveBeenCalled()
    window.ww.getContext = vi.fn().mockRejectedValue(new Error('SDK timeout'))
    await expect(sendTextToCurrentChat('你好')).resolves.toMatchObject({ status: 'SDK_UNKNOWN' })
  })
})


describe('企微 H5 卡片消息', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    delete window.ww
  })

  it('使用官方 news 结构发送 link、title、desc 和 imgUrl', async () => {
    useWecomRuntime()
    const sendChatMessage = vi.fn().mockResolvedValue({ errMsg: 'sendChatMessage:ok' })
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact: vi.fn(),
      sendChatMessage,
    }

    await expect(sendH5CardToCurrentChat({
      link: 'https://crm.example.com/h5/cards/share-key', title: '项目介绍', summary: '服务流程与预约方式',
      imageUrl: 'https://crm.example.com/h5-card-cover.svg',
    })).resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenCalledWith({
      msgtype: 'news',
      news: {
        link: 'https://crm.example.com/h5/cards/share-key', title: '项目介绍', desc: '服务流程与预约方式',
        imgUrl: 'https://crm.example.com/h5-card-cover.svg',
      },
      enterChat: false,
    })
  })

  it('拒绝非法链接，不把客户端校验异常冒充已发送', async () => {
    await expect(sendH5CardToCurrentChat({ link: 'javascript:alert(1)', title: '项目', summary: '摘要', imageUrl: 'https://crm.example.com/cover.svg' }))
      .rejects.toThrow('HTTP 或 HTTPS')
    expect(window.ww).toBeUndefined()
  })
})
