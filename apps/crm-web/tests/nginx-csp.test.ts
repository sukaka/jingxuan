import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('nginx-default.conf CSP 与企微 JS-SDK 配置', () => {
  const nginxConf = readFileSync(resolve(__dirname, '../nginx-default.conf'), 'utf8')
  const wecomSdkSource = readFileSync(resolve(__dirname, '../src/services/wecomSdk.ts'), 'utf8')

  it('CSP 位于 server 块并允许企微官方 JS-SDK，同时禁止 connect-src 明文 http:', () => {
    const sdkMatch = wecomSdkSource.match(/const SDK_URL = '([^']+)'/)
    expect(sdkMatch).not.toBeNull()
    const sdkUrl = sdkMatch![1]

    const cspMatch = nginxConf.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;/)
    expect(cspMatch).not.toBeNull()
    const csp = cspMatch![1]

    const firstLocationIndex = nginxConf.indexOf('location ')
    const cspIndex = nginxConf.indexOf('Content-Security-Policy')
    expect(cspIndex).toBeGreaterThan(-1)
    expect(cspIndex).toBeLessThan(firstLocationIndex)

    const directives = Object.fromEntries(
      csp.split(';').map((part) => part.trim()).filter(Boolean).map((part) => {
        const [name, ...values] = part.split(/\s+/)
        return [name, values]
      }),
    )

    expect(directives['script-src']).toContain("'self'")
    expect(directives['script-src']).toContain(sdkUrl)
    expect(directives['connect-src']).toContain("'self'")
    expect(directives['connect-src']).toContain('https:')
    expect(directives['connect-src']).not.toContain('http:')
  })
})
