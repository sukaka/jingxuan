import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { copyTextWithFallback } from '../src/utils/clipboard'

describe('copyTextWithFallback 剪贴板降级工具', () => {
  const originalClipboard = navigator.clipboard
  const originalExecCommand = document.execCommand

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: originalClipboard,
    })
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      writable: true,
      value: originalExecCommand,
    })
  })

  it('优先使用 navigator.clipboard.writeText', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const execCommand = vi.fn()
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      writable: true,
      value: execCommand,
    })

    await copyTextWithFallback('欢迎预约光子嫩肤')

    expect(writeText).toHaveBeenCalledWith('欢迎预约光子嫩肤')
    expect(execCommand).not.toHaveBeenCalled()
  })

  it('clipboard.writeText 失败或不可用时自动降级到 textarea + execCommand', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('NotAllowedError'))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    let copiedValue = ''
    const execCommand = vi.fn().mockImplementation((cmd: string) => {
      if (cmd === 'copy') {
        const active = document.activeElement as HTMLTextAreaElement | null
        copiedValue = active?.value ?? ''
        return true
      }
      return false
    })
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      writable: true,
      value: execCommand,
    })

    await copyTextWithFallback('降级复制内容')

    expect(writeText).toHaveBeenCalledWith('降级复制内容')
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(copiedValue).toBe('降级复制内容')
    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })

  it('无 navigator.clipboard 时直接走 execCommand，失败时抛出明确提示并清理 DOM', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    })
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      writable: true,
      value: vi.fn().mockReturnValue(false),
    })

    await expect(copyTextWithFallback('复制失败测试')).rejects.toThrow('复制失败')
    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })
})
