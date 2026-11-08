import { readdirSync, readFileSync } from 'node:fs'
import { extname, join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = resolve(process.cwd(), 'src')

function vueFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return vueFiles(path)
    return entry.isFile() && extname(entry.name) === '.vue' ? [path] : []
  })
}

describe('运营界面版本标记', () => {
  it('页面标题、品牌区和提示标签不展示装饰性版本号', () => {
    const patterns = [
      { label: '页面标题版本号', regex: /title="[^"]*\bV\d+(?:\.\d+)*/ },
      { label: 'CRM 品牌版本号', regex: /CRM\s+V\d+(?:\.\d+)*/ },
      { label: '提示标签版本号', regex: />\s*V\d+(?:\.\d+)+\s+(?:分阶段建设|可配置化)\s*</ },
    ]
    const offenders = vueFiles(sourceRoot).flatMap((file) => {
      const source = readFileSync(file, 'utf8')
      return patterns.filter((pattern) => pattern.regex.test(source))
        .map((pattern) => `${relative(sourceRoot, file)}: ${pattern.label}`)
    })

    expect(offenders).toEqual([])
  })
})
