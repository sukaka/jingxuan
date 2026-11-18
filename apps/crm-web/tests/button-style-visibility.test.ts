import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/prototype.css', 'utf8')
const appCss = readFileSync('src/style.css', 'utf8')

describe('全局按钮文字可见性', () => {
  it('实色主按钮样式不得覆盖 plain、文字链、链接和虚线变体', () => {
    const rule = css.split('}').find((item) => item.includes('.proto-page .el-button--primary'))

    expect(rule).toBeDefined()
    for (const variant of ['is-plain', 'is-text', 'is-link', 'is-dashed']) {
      expect(rule).toContain(`:not(.${variant})`)
    }
  })

  it('文字按钮继续使用透明背景', () => {
    expect(css).toMatch(/\.proto-page \.el-button\.is-text\s*\{[^}]*background:\s*transparent/)
  })

  it('应用层主色变量同样不得覆盖按钮变体', () => {
    const rule = appCss.split('}').find((item) => item.includes('.app-content .el-button--primary'))

    expect(rule).toBeDefined()
    for (const variant of ['is-plain', 'is-text', 'is-link', 'is-dashed']) {
      expect(rule).toContain(`:not(.${variant})`)
    }
  })
})
