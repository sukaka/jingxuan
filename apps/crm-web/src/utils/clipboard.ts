/**
 * 企微 WebView / 桌面浏览器通用剪贴板复制工具。
 * 优先使用 navigator.clipboard.writeText；在 iOS/Android 企微 WebView 等受限环境下
 * 自动降级为离屏只读 textarea + document.execCommand('copy')。
 */
export async function copyTextWithFallback(text: string): Promise<void> {
  if (
    typeof navigator !== 'undefined'
    && navigator.clipboard
    && typeof navigator.clipboard.writeText === 'function'
  ) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // Fall through to execCommand fallback for restricted WebViews (e.g. iOS/Android WeCom)
    }
  }

  if (typeof document === 'undefined' || !document.body) {
    throw new Error('当前环境不支持复制，请手动选中内容复制')
  }

  const previousActive = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', 'true')
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '-9999px'
  textarea.style.width = '1px'
  textarea.style.height = '1px'
  textarea.style.padding = '0'
  textarea.style.border = '0'
  textarea.style.opacity = '0'
  textarea.style.fontSize = '16px'
  textarea.style.pointerEvents = 'none'

  document.body.appendChild(textarea)
  try {
    textarea.focus({ preventScroll: true })
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    const copied = typeof document.execCommand === 'function' && document.execCommand('copy')
    if (!copied) {
      throw new Error('复制失败，请手动长按选中内容复制')
    }
  } finally {
    textarea.remove()
    if (previousActive && typeof previousActive.focus === 'function') {
      previousActive.focus({ preventScroll: true })
    }
  }
}
