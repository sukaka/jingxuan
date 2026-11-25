import { getWecomSidebarSignature } from '../api/wecomSidebar'
import type { WecomSdkSignature, WecomSdkSignatureBundle } from '../api/wecomSidebar'

interface WecomResult {
  errMsg?: string
  err_msg?: string
  entry?: string
  userId?: string
  chatId?: string
}

interface WecomSdk {
  register(options: Record<string, unknown>): void
  getContext(): Promise<WecomResult>
  getCurExternalContact(): Promise<WecomResult>
  getCurExternalChat?(): Promise<WecomResult>
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
  | { kind: 'GROUP_CHAT'; entry: string; chatId?: string }
  | { kind: 'CUSTOMER'; entry: string; externalUserid: string }

export interface WecomSendTarget {
  expectedExternalUserid?: string
  expectedChatId?: string
}

export interface WecomSendResult {
  status: 'SDK_SUCCESS' | 'SDK_FAILED' | 'SDK_UNKNOWN'
  clientResult: string
  /** 发送前实时校验发现当前企微会话与预期客户/群聊不一致时为 true */
  mismatch?: boolean
}

const SDK_URL = 'https://wwcdn.weixin.qq.com/node/wework/wwopen/js/wecom-jssdk-2.4.0.js'
const MOBILE_UA_RE = /Android|iPhone|iPad|iPod|Mobile/i
let sdkLoader: Promise<WecomSdk> | null = null

export function isWecomRuntime(): boolean {
  return /wxwork/i.test(window.navigator.userAgent)
}

export function isMobileWecomRuntime(): boolean {
  return MOBILE_UA_RE.test(window.navigator.userAgent)
}

export function redirectToWecomOAuth(): void {
  window.location.assign('/api/v1/wecom/oauth/authorize')
}

function normalizeSignatureUrl(url: string): string {
  return url.split('#')[0] ?? url
}

async function readContextFromSdk(ww: WecomSdk): Promise<WecomChatContext> {
  const context = await ww.getContext()
  const entry = context.entry ?? ''
  if (!isSuccessful(context)) throw new Error('企业微信聊天上下文读取失败，请稍后重试')
  if (entry === 'group_chat_tools') {
    if (typeof ww.getCurExternalChat === 'function') {
      try {
        const groupChat = await ww.getCurExternalChat()
        if (isSuccessful(groupChat) && groupChat.chatId) {
          return { kind: 'GROUP_CHAT', entry, chatId: groupChat.chatId }
        }
      } catch {
        // Fallback gracefully when group chatId is unavailable
      }
    }
    return { kind: 'GROUP_CHAT', entry }
  }
  if (!['single_chat_tools', 'contact_profile'].includes(entry)) {
    return { kind: 'NOT_CHAT', entry: entry || 'UNKNOWN' }
  }
  const current = await ww.getCurExternalContact()
  if (!isSuccessful(current) || !current.userId) {
    throw new Error('当前聊天没有可识别的企微客户，或应用尚未获得客户联系权限')
  }
  return { kind: 'CUSTOMER', entry, externalUserid: current.userId }
}

export async function getWecomChatContext(): Promise<WecomChatContext> {
  if (!isWecomRuntime()) return { kind: 'BROWSER' }
  const ww = await loadSdk()
  const initialUrl = normalizeSignatureUrl(window.location.href)
  const initialBundle = await getWecomSidebarSignature(initialUrl)

  let cachedUrl = initialUrl
  let cachedBundle: WecomSdkSignatureBundle = initialBundle
  let configServed = false
  let agentConfigServed = false
  let inflightUrl = ''
  let inflightRequest: Promise<WecomSdkSignatureBundle> | null = null

  async function resolveSignature(
    requestedUrl: string | undefined,
    kind: 'config' | 'agentConfig',
  ): Promise<WecomSdkSignature> {
    const targetUrl = normalizeSignatureUrl(requestedUrl || window.location.href)
    const alreadyServed = kind === 'config' ? configServed : agentConfigServed
    if (targetUrl === cachedUrl && !alreadyServed) {
      if (kind === 'config') configServed = true
      else agentConfigServed = true
      return kind === 'config' ? cachedBundle.config : cachedBundle.agentConfig
    }
    if (!inflightRequest || inflightUrl !== targetUrl) {
      inflightUrl = targetUrl
      inflightRequest = getWecomSidebarSignature(targetUrl)
        .then((bundle) => {
          cachedUrl = targetUrl
          cachedBundle = bundle
          configServed = false
          agentConfigServed = false
          return bundle
        })
        .finally(() => {
          inflightRequest = null
          inflightUrl = ''
        })
    }
    const freshBundle = await inflightRequest
    if (kind === 'config') configServed = true
    else agentConfigServed = true
    return kind === 'config' ? freshBundle.config : freshBundle.agentConfig
  }

  ww.register({
    corpId: initialBundle.corpId,
    agentId: initialBundle.agentId,
    jsApiList: ['getContext', 'getCurExternalContact', 'getCurExternalChat', 'sendChatMessage'],
    getConfigSignature: (url?: string) => resolveSignature(url, 'config'),
    getAgentConfigSignature: (url?: string) => resolveSignature(url, 'agentConfig'),
  })

  return readContextFromSdk(ww)
}

/**
 * 静默读取当前企微聊天上下文（用于窗口聚焦/可见性恢复时检测会话切换）。
 * 若 SDK 已初始化则直接读取上下文，避免重复请求签名；否则走完整初始化流程。
 */
export async function peekWecomChatContext(): Promise<WecomChatContext> {
  if (!isWecomRuntime()) return { kind: 'BROWSER' }
  if (window.ww) {
    return readContextFromSdk(window.ww)
  }
  return getWecomChatContext()
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
      reject(new Error('企业微信连接组件加载超时，请稍后重试'))
    }, 10000)
    script.onload = () => {
      window.clearTimeout(timeout)
      if (window.ww) resolve(window.ww)
      else {
        sdkLoader = null
        reject(new Error('企业微信连接组件初始化失败，请刷新重试'))
      }
    }
    script.onerror = () => {
      window.clearTimeout(timeout)
      script.remove()
      sdkLoader = null
      reject(new Error('企业微信连接组件加载失败，请检查网络后重试'))
    }
    document.head.appendChild(script)
  })
  return sdkLoader
}

export async function sendTextToCurrentChat(
  content: string,
  target?: WecomSendTarget,
): Promise<WecomSendResult> {
  if (!content.trim()) throw new Error('发送内容不能为空')
  if (content.length > 4000) throw new Error('发送内容不能超过 4000 字')
  return sendPayloadToCurrentChat({ msgtype: 'text', text: { content } }, target)
}

export async function sendH5CardToCurrentChat(
  card: { link: string; title: string; summary: string; imageUrl: string },
  target?: WecomSendTarget,
): Promise<WecomSendResult> {
  if (!card.title.trim() || !card.summary.trim()) throw new Error('卡片标题和摘要不能为空')
  if (![card.link, card.imageUrl].every((value) => /^https?:\/\//i.test(value))) {
    throw new Error('卡片链接和封面必须使用 HTTP 或 HTTPS 地址')
  }
  return sendPayloadToCurrentChat({
    msgtype: 'news',
    news: { link: card.link, title: card.title, desc: card.summary, imgUrl: card.imageUrl },
  }, target)
}

export async function sendImageToCurrentChat(
  mediaId: string,
  target?: WecomSendTarget,
): Promise<WecomSendResult> {
  const trimmed = mediaId.trim()
  if (!trimmed) throw new Error('缺少图片素材标识，暂无法发送')
  return sendPayloadToCurrentChat({
    msgtype: 'image',
    image: { mediaid: trimmed },
  }, target)
}

export async function sendFileToCurrentChat(
  mediaId: string,
  target?: WecomSendTarget,
): Promise<WecomSendResult> {
  const trimmed = mediaId.trim()
  if (!trimmed) throw new Error('缺少文件素材标识，暂无法发送')
  return sendPayloadToCurrentChat({
    msgtype: 'file',
    file: { mediaid: trimmed },
  }, target)
}

async function verifyTargetContext(
  ww: WecomSdk,
  entry: string,
  target?: WecomSendTarget,
): Promise<WecomSendResult | null> {
  const expectedExternalUserid = target?.expectedExternalUserid?.trim() || ''
  const expectedChatId = target?.expectedChatId?.trim() || ''

  if (expectedExternalUserid && !expectedChatId) {
    if (entry !== 'single_chat_tools') {
      return {
        status: 'SDK_FAILED',
        mismatch: true,
        clientResult: 'sendChatMessage:fail context_mismatch: 当前已离开客户单聊会话，已阻止误发',
      }
    }
    const current = await ww.getCurExternalContact()
    if (!isSuccessful(current) || !current.userId || current.userId !== expectedExternalUserid) {
      return {
        status: 'SDK_FAILED',
        mismatch: true,
        clientResult: 'sendChatMessage:fail context_mismatch: 当前聊天客户已切换，已阻止误发',
      }
    }
    return null
  }

  if (expectedChatId && !expectedExternalUserid) {
    if (entry !== 'group_chat_tools') {
      return {
        status: 'SDK_FAILED',
        mismatch: true,
        clientResult: 'sendChatMessage:fail context_mismatch: 当前已离开目标群聊会话，已阻止误发',
      }
    }
    if (typeof ww.getCurExternalChat !== 'function') {
      return {
        status: 'SDK_FAILED',
        mismatch: true,
        clientResult: 'sendChatMessage:fail context_mismatch: 当前企微版本不支持群聊身份校验，已阻止误发',
      }
    }
    const currentChat = await ww.getCurExternalChat()
    if (!isSuccessful(currentChat) || !currentChat.chatId || currentChat.chatId !== expectedChatId) {
      return {
        status: 'SDK_FAILED',
        mismatch: true,
        clientResult: 'sendChatMessage:fail context_mismatch: 当前企微群聊已切换，已阻止误发',
      }
    }
    return null
  }

  if (expectedExternalUserid && expectedChatId) {
    if (entry === 'single_chat_tools') {
      const current = await ww.getCurExternalContact()
      if (!isSuccessful(current) || !current.userId || current.userId !== expectedExternalUserid) {
        return {
          status: 'SDK_FAILED',
          mismatch: true,
          clientResult: 'sendChatMessage:fail context_mismatch: 当前聊天客户已切换，已阻止误发',
        }
      }
      return null
    }
    if (entry === 'group_chat_tools') {
      if (typeof ww.getCurExternalChat !== 'function') {
        return {
          status: 'SDK_FAILED',
          mismatch: true,
          clientResult: 'sendChatMessage:fail context_mismatch: 当前企微版本不支持群聊身份校验，已阻止误发',
        }
      }
      const currentChat = await ww.getCurExternalChat()
      if (!isSuccessful(currentChat) || !currentChat.chatId || currentChat.chatId !== expectedChatId) {
        return {
          status: 'SDK_FAILED',
          mismatch: true,
          clientResult: 'sendChatMessage:fail context_mismatch: 当前企微群聊已切换，已阻止误发',
        }
      }
      return null
    }
    return {
      status: 'SDK_FAILED',
      mismatch: true,
      clientResult: 'sendChatMessage:fail context_mismatch: 当前不在目标聊天会话中，已阻止误发',
    }
  }

  if (entry !== 'single_chat_tools' && entry !== 'group_chat_tools') {
    return { status: 'SDK_FAILED', clientResult: 'sendChatMessage:fail invalid current chat context' }
  }
  return null
}

async function sendPayloadToCurrentChat(
  payload: Record<string, unknown>,
  target?: WecomSendTarget,
): Promise<WecomSendResult> {
  try {
    const ww = await loadSdk()
    const context = await ww.getContext()
    if (!isSuccessful(context)) {
      return { status: 'SDK_FAILED', clientResult: 'sendChatMessage:fail invalid current chat context' }
    }
    const entry = context.entry ?? ''
    const mismatchError = await verifyTargetContext(ww, entry, target)
    if (mismatchError) return mismatchError

    if (!ww.sendChatMessage) {
      return { status: 'SDK_UNKNOWN', clientResult: 'sendChatMessage unavailable in current Wecom version' }
    }
    let timer: number | undefined
    try {
      const result = await Promise.race([
        ww.sendChatMessage({ ...payload, enterChat: isMobileWecomRuntime() }),
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
