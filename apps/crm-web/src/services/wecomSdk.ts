import { getWecomSidebarSignature } from '../api/wecomSidebar'

interface WecomResult {
  errMsg?: string
  err_msg?: string
  entry?: string
  userId?: string
}

interface WecomSdk {
  register(options: Record<string, unknown>): void
  getContext(): Promise<WecomResult>
  getCurExternalContact(): Promise<WecomResult>
  sendChatMessage?(options: Record<string, unknown>): Promise<WecomResult>
}

declare global {
  interface Window {
    ww?: WecomSdk
  }
}

export type WecomChatContext =
  | { kind: 'BROWSER' }
  | { kind: 'NOT_CHAT'; entry: string }
  | { kind: 'GROUP_CHAT'; entry: string }
  | { kind: 'CUSTOMER'; entry: string; externalUserid: string }

const SDK_URL = 'https://wwcdn.weixin.qq.com/node/wework/wwopen/js/wecom-jssdk-2.4.0.js'
let sdkLoader: Promise<WecomSdk> | null = null

export function isWecomRuntime(): boolean {
  return /wxwork/i.test(window.navigator.userAgent)
}

export function redirectToWecomOAuth(): void {
  window.location.assign('/api/v1/wecom/oauth/authorize')
}

export async function getWecomChatContext(): Promise<WecomChatContext> {
  if (!isWecomRuntime()) return { kind: 'BROWSER' }
  const ww = await loadSdk()
  const signatures = await getWecomSidebarSignature(window.location.href.split('#')[0])
  ww.register({
    corpId: signatures.corpId,
    agentId: signatures.agentId,
    jsApiList: ['getContext', 'getCurExternalContact', 'sendChatMessage'],
    getConfigSignature: () => signatures.config,
    getAgentConfigSignature: () => signatures.agentConfig,
  })

  const context = await ww.getContext()
  const entry = context.entry ?? ''
  if (!isSuccessful(context)) throw new Error('企业微信聊天上下文读取失败，请稍后重试')
  if (entry === 'group_chat_tools') return { kind: 'GROUP_CHAT', entry }
  if (!['single_chat_tools', 'contact_profile'].includes(entry)) {
    return { kind: 'NOT_CHAT', entry: entry || 'UNKNOWN' }
  }
  const current = await ww.getCurExternalContact()
  if (!isSuccessful(current) || !current.userId) {
    throw new Error('当前聊天没有可识别的企微客户，或应用尚未获得客户联系权限')
  }
  return { kind: 'CUSTOMER', entry, externalUserid: current.userId }
}

function isSuccessful(result: WecomResult): boolean {
  const message = result.errMsg ?? result.err_msg ?? ''
  return message.endsWith(':ok') || message === 'ok' || message === ''
}

function loadSdk(): Promise<WecomSdk> {
  if (window.ww) return Promise.resolve(window.ww)
  if (sdkLoader) return sdkLoader
  sdkLoader = new Promise<WecomSdk>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SDK_URL
    script.dataset.wecomSdk = 'true'
    script.async = true
    const timeout = window.setTimeout(() => {
      script.remove()
      sdkLoader = null
      reject(new Error('企业微信 JS-SDK 加载超时'))
    }, 10000)
    script.onload = () => {
      window.clearTimeout(timeout)
      if (window.ww) resolve(window.ww)
      else {
        sdkLoader = null
        reject(new Error('企业微信 JS-SDK 初始化失败'))
      }
    }
    script.onerror = () => {
      window.clearTimeout(timeout)
      script.remove()
      sdkLoader = null
      reject(new Error('企业微信 JS-SDK 加载失败，请检查网络'))
    }
    document.head.appendChild(script)
  })
  return sdkLoader
}

export interface WecomSendResult {
  status: 'SDK_SUCCESS' | 'SDK_FAILED' | 'SDK_UNKNOWN'
  clientResult: string
}

export async function sendTextToCurrentChat(content: string): Promise<WecomSendResult> {
  if (!content.trim()) throw new Error('发送内容不能为空')
  if (content.length > 4000) throw new Error('发送内容不能超过 4000 字')
  return sendPayloadToCurrentChat({ msgtype: 'text', text: { content } })
}

export async function sendH5CardToCurrentChat(card: { link: string; title: string; summary: string; imageUrl: string }): Promise<WecomSendResult> {
  if (!card.title.trim() || !card.summary.trim()) throw new Error('卡片标题和摘要不能为空')
  if (![card.link, card.imageUrl].every((value) => /^https?:\/\//i.test(value))) {
    throw new Error('卡片链接和封面必须使用 HTTP 或 HTTPS 地址')
  }
  return sendPayloadToCurrentChat({
    msgtype: 'news',
    news: { link: card.link, title: card.title, desc: card.summary, imgUrl: card.imageUrl },
  })
}

async function sendPayloadToCurrentChat(payload: Record<string, unknown>): Promise<WecomSendResult> {
  try {
    const ww = await loadSdk()
    const context = await ww.getContext()
    if (!isSuccessful(context) || context.entry !== 'single_chat_tools') {
      return { status: 'SDK_FAILED', clientResult: 'sendChatMessage:fail invalid current chat context' }
    }
    if (!ww.sendChatMessage) {
      return { status: 'SDK_UNKNOWN', clientResult: 'sendChatMessage unavailable in current Wecom version' }
    }
    let timer: number | undefined
    try {
      const result = await Promise.race([
        ww.sendChatMessage({ ...payload, enterChat: false }),
        new Promise<never>((_, reject) => {
          timer = window.setTimeout(() => reject(new Error('企微发送结果确认超时')), 10000)
        }),
      ])
      const message = result.errMsg ?? result.err_msg ?? ''
      if (isSuccessful(result)) return { status: 'SDK_SUCCESS', clientResult: message || 'sendChatMessage:ok' }
      return {
        status: message.includes('fail') ? 'SDK_FAILED' : 'SDK_UNKNOWN',
        clientResult: message || 'sendChatMessage returned no result',
      }
    } finally {
      if (timer !== undefined) window.clearTimeout(timer)
    }
  } catch (error) {
    return { status: 'SDK_UNKNOWN', clientResult: error instanceof Error ? error.message : 'sendChatMessage unknown error' }
  }
}
