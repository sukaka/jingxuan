import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getWecomSidebarSignature } from '../src/api/wecomSidebar'
import {
  getWecomChatContext,
  isMobileWecomRuntime,
  isWecomRuntime,
  peekWecomChatContext,
  sendFileToCurrentChat,
  sendH5CardToCurrentChat,
  sendImageToCurrentChat,
  sendTextToCurrentChat,
} from '../src/services/wecomSdk'

vi.mock('../src/api/wecomSidebar', () => ({ getWecomSidebarSignature: vi.fn() }))

const bundle = {
  corpId: 'ww-test',
  agentId: 10001,
  url: 'http://localhost/wecom/sidebar',
  config: { nonceStr: 'nonce-a', timestamp: 1, signature: 'signature-a' },
  agentConfig: { nonceStr: 'nonce-b', timestamp: 2, signature: 'signature-b' },
}

const refreshedBundle = {
  corpId: 'ww-test',
  agentId: 10001,
  url: 'http://localhost/wecom/sidebar?tab=tools',
  config: { nonceStr: 'nonce-a2', timestamp: 3, signature: 'signature-a2' },
  agentConfig: { nonceStr: 'nonce-b2', timestamp: 4, signature: 'signature-b2' },
}

function useWecomMobileRuntime() {
  Object.defineProperty(window.navigator, 'userAgent', {
    configurable: true,
    value: 'Mozilla/5.0 (iPhone) MicroMessenger wxwork/4.1.38',
  })
}

function useWecomDesktopRuntime() {
  Object.defineProperty(window.navigator, 'userAgent', {
    configurable: true,
    value: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) wxwork/4.1.38',
  })
}

describe('企微 JS-SDK 聊天上下文与动态签名', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/wecom/sidebar#customer')
    vi.mocked(getWecomSidebarSignature).mockResolvedValue(bundle)
    delete window.ww
  })

  it('使用新版 ww.register 注册动态双签名回调并获取当前单聊客户', async () => {
    useWecomMobileRuntime()
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
    expect(getWecomSidebarSignature).toHaveBeenCalledTimes(1)
    expect(getWecomSidebarSignature).toHaveBeenCalledWith(window.location.href.split('#')[0])
    expect(register).toHaveBeenCalledWith(expect.objectContaining({
      corpId: 'ww-test',
      agentId: 10001,
      jsApiList: ['getContext', 'getCurExternalContact', 'getCurExternalChat', 'sendChatMessage'],
    }))

    const options = register.mock.calls[0]?.[0]
    // Initial calls for same URL reuse cached bundle without extra network requests
    await expect(options.getConfigSignature(window.location.href)).resolves.toEqual(bundle.config)
    await expect(options.getAgentConfigSignature(window.location.href)).resolves.toEqual(bundle.agentConfig)
    expect(getWecomSidebarSignature).toHaveBeenCalledTimes(1)

    // Retry on same URL refreshes signature from backend
    vi.mocked(getWecomSidebarSignature).mockResolvedValueOnce(refreshedBundle)
    await expect(options.getConfigSignature(window.location.href)).resolves.toEqual(refreshedBundle.config)
    expect(getWecomSidebarSignature).toHaveBeenCalledTimes(2)

    // Different URL refreshes signature with the requested URL (hash stripped)
    vi.mocked(getWecomSidebarSignature).mockResolvedValueOnce(refreshedBundle)
    await expect(options.getAgentConfigSignature('http://localhost/wecom/sidebar?tab=tools#hash')).resolves.toEqual(refreshedBundle.agentConfig)
    expect(getWecomSidebarSignature).toHaveBeenLastCalledWith('http://localhost/wecom/sidebar?tab=tools')
  })

  it('普通浏览器不加载 SDK 或请求签名', async () => {
    Object.defineProperty(window.navigator, 'userAgent', { configurable: true, value: 'Mozilla/5.0 Chrome' })

    await expect(getWecomChatContext()).resolves.toEqual({ kind: 'BROWSER' })
    expect(isWecomRuntime()).toBe(false)
    expect(getWecomSidebarSignature).not.toHaveBeenCalled()
  })

  it('群聊调用 getCurExternalChat 获取 chatId，不可用时优雅降级', async () => {
    useWecomDesktopRuntime()
    const getCurExternalContact = vi.fn()
    const getCurExternalChat = vi.fn().mockResolvedValue({ errMsg: 'getCurExternalChat:ok', chatId: 'wr-group-01' })
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'group_chat_tools' }),
      getCurExternalContact,
      getCurExternalChat,
    }

    await expect(getWecomChatContext()).resolves.toEqual({
      kind: 'GROUP_CHAT', entry: 'group_chat_tools', chatId: 'wr-group-01',
    })
    expect(getCurExternalContact).not.toHaveBeenCalled()

    // Fallback gracefully when getCurExternalChat fails
    getCurExternalChat.mockRejectedValueOnce(new Error('no permission'))
    await expect(getWecomChatContext()).resolves.toEqual({ kind: 'GROUP_CHAT', entry: 'group_chat_tools' })
  })

  it('peekWecomChatContext 在 SDK 已就绪时不重复请求签名', async () => {
    useWecomDesktopRuntime()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact: vi.fn().mockResolvedValue({ errMsg: 'getCurExternalContact:ok', userId: 'external-2002' }),
    }

    await expect(peekWecomChatContext()).resolves.toEqual({
      kind: 'CUSTOMER', entry: 'single_chat_tools', externalUserid: 'external-2002',
    })
    expect(getWecomSidebarSignature).not.toHaveBeenCalled()
  })

  it('SDK 错误与普通入口分开处理', async () => {
    useWecomDesktopRuntime()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValueOnce({ errMsg: 'getContext:fail' }),
      getCurExternalContact: vi.fn(),
    }
    await expect(getWecomChatContext()).rejects.toThrow('上下文读取失败')

    window.ww.getContext = vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'desktop_home' })
    await expect(getWecomChatContext()).resolves.toEqual({ kind: 'NOT_CHAT', entry: 'desktop_home' })
  })
})

describe('企微消息发送、防串客校验与移动端 enterChat', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    delete window.ww
  })

  it('移动端设置 enterChat: true，桌面端设置 enterChat: false', async () => {
    const sendChatMessage = vi.fn().mockResolvedValue({ errMsg: 'sendChatMessage:ok' })
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact: vi.fn().mockResolvedValue({ errMsg: 'getCurExternalContact:ok', userId: 'external-1001' }),
      sendChatMessage,
    }

    useWecomMobileRuntime()
    expect(isMobileWecomRuntime()).toBe(true)
    await expect(sendTextToCurrentChat('您好', { expectedExternalUserid: 'external-1001' }))
      .resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenLastCalledWith({
      msgtype: 'text',
      text: { content: '您好' },
      enterChat: true,
    })

    useWecomDesktopRuntime()
    expect(isMobileWecomRuntime()).toBe(false)
    await expect(sendTextToCurrentChat('您好', { expectedExternalUserid: 'external-1001' }))
      .resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenLastCalledWith({
      msgtype: 'text',
      text: { content: '您好' },
      enterChat: false,
    })
  })

  it('防串客守卫：单聊客户切换或入口切换时阻止发送并返回 mismatch: true', async () => {
    useWecomDesktopRuntime()
    const sendChatMessage = vi.fn()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact: vi.fn().mockResolvedValue({ errMsg: 'getCurExternalContact:ok', userId: 'external-OTHER' }),
      sendChatMessage,
    }

    const res = await sendTextToCurrentChat('你好', { expectedExternalUserid: 'external-1001' })
    expect(res).toMatchObject({ status: 'SDK_FAILED', mismatch: true })
    expect(res.clientResult).toContain('context_mismatch')
    expect(sendChatMessage).not.toHaveBeenCalled()

    // Switched from single chat to group chat while expecting externalUserid
    window.ww.getContext = vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'group_chat_tools' })
    const switchedRes = await sendTextToCurrentChat('你好', { expectedExternalUserid: 'external-1001' })
    expect(switchedRes).toMatchObject({ status: 'SDK_FAILED', mismatch: true })
    expect(sendChatMessage).not.toHaveBeenCalled()
  })

  it('防串群守卫：群聊 chatId 匹配可发送，不一致时阻止发送', async () => {
    useWecomDesktopRuntime()
    const sendChatMessage = vi.fn().mockResolvedValue({ errMsg: 'sendChatMessage:ok' })
    const getCurExternalChat = vi.fn()
      .mockResolvedValueOnce({ errMsg: 'getCurExternalChat:ok', chatId: 'wr-group-01' })
      .mockResolvedValueOnce({ errMsg: 'getCurExternalChat:ok', chatId: 'wr-group-99' })
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'group_chat_tools' }),
      getCurExternalContact: vi.fn(),
      getCurExternalChat,
      sendChatMessage,
    }

    await expect(sendTextToCurrentChat('群公告', { expectedChatId: 'wr-group-01' }))
      .resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenCalledTimes(1)

    const mismatch = await sendTextToCurrentChat('群公告', { expectedChatId: 'wr-group-01' })
    expect(mismatch).toMatchObject({ status: 'SDK_FAILED', mismatch: true })
    expect(sendChatMessage).toHaveBeenCalledTimes(1)
  })

  it('非聊天入口不发送，SDK 异常返回 SDK_UNKNOWN 不冒充成功', async () => {
    useWecomDesktopRuntime()
    const sendChatMessage = vi.fn()
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'contact_profile' }),
      getCurExternalContact: vi.fn(),
      sendChatMessage,
    }

    await expect(sendTextToCurrentChat('你好')).resolves.toMatchObject({ status: 'SDK_FAILED' })
    expect(sendChatMessage).not.toHaveBeenCalled()

    window.ww.getContext = vi.fn().mockRejectedValue(new Error('SDK timeout'))
    await expect(sendTextToCurrentChat('你好')).resolves.toMatchObject({ status: 'SDK_UNKNOWN' })
  })

  it('H5 卡片使用官方 news 结构并支持图片与文件素材发送', async () => {
    useWecomDesktopRuntime()
    const sendChatMessage = vi.fn().mockResolvedValue({ errMsg: 'sendChatMessage:ok' })
    window.ww = {
      register: vi.fn(),
      getContext: vi.fn().mockResolvedValue({ errMsg: 'getContext:ok', entry: 'single_chat_tools' }),
      getCurExternalContact: vi.fn().mockResolvedValue({ errMsg: 'getCurExternalContact:ok', userId: 'external-1001' }),
      sendChatMessage,
    }

    await expect(sendH5CardToCurrentChat({
      link: 'https://crm.example.com/h5/cards/share-key',
      title: '项目介绍',
      summary: '服务流程与预约方式',
      imageUrl: 'https://crm.example.com/h5-card-cover.png',
    }, { expectedExternalUserid: 'external-1001' })).resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })

    expect(sendChatMessage).toHaveBeenLastCalledWith({
      msgtype: 'news',
      news: {
        link: 'https://crm.example.com/h5/cards/share-key',
        title: '项目介绍',
        desc: '服务流程与预约方式',
        imgUrl: 'https://crm.example.com/h5-card-cover.png',
      },
      enterChat: false,
    })

    await expect(sendImageToCurrentChat('media-img-01', { expectedExternalUserid: 'external-1001' }))
      .resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenLastCalledWith({
      msgtype: 'image',
      image: { mediaid: 'media-img-01' },
      enterChat: false,
    })

    await expect(sendFileToCurrentChat('media-file-01', { expectedExternalUserid: 'external-1001' }))
      .resolves.toEqual({ status: 'SDK_SUCCESS', clientResult: 'sendChatMessage:ok' })
    expect(sendChatMessage).toHaveBeenLastCalledWith({
      msgtype: 'file',
      file: { mediaid: 'media-file-01' },
      enterChat: false,
    })
  })

  it('拒绝非法 H5 链接和空 mediaId', async () => {
    await expect(sendH5CardToCurrentChat({
      link: 'javascript:alert(1)', title: '项目', summary: '摘要', imageUrl: 'https://crm.example.com/h5-card-cover.png',
    })).rejects.toThrow('HTTP 或 HTTPS')
    await expect(sendImageToCurrentChat('   ')).rejects.toThrow('mediaId')
    await expect(sendFileToCurrentChat('')).rejects.toThrow('mediaId')
    expect(window.ww).toBeUndefined()
  })
})
