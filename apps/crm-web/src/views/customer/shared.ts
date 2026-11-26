import { ref } from 'vue'
import type { DictionaryOption } from '../../api/dictionary'
import { getDictionaryOptions } from '../../api/dictionary'

export function formatDateTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : '-'
}

export function formatDate(value: string | null | undefined): string {
  return value || '-'
}

/** 整数分 → 元展示(两位小数);仅展示/输入边界做转换,不使用浮点传金额 */
export function fenToYuan(fen: number | null | undefined): string {
  if (fen === null || fen === undefined) return '-'
  return (fen / 100).toFixed(2)
}

export function yuanToFen(yuan: number): number {
  return Math.round(yuan * 100)
}

/** 意向/消费能力级别 code → 中文展示(HIGH/MEDIUM/LOW → 高/中/低) */
export function levelLabel(value: string | null | undefined): string {
  if (!value) return '—'
  return { HIGH: '高', MEDIUM: '中', LOW: '低' }[value] ?? '未知'
}

const CUSTOMER_TAG_LABELS: Record<string, string> = {
  VIP: '贵宾客户',
  HIGH_INTENT: '高意向',
  NEW_7D: '近 7 天新增',
  REFERRAL_GOOD: '优质转介绍',
}

export function customerTagLabel(value: string): string {
  return CUSTOMER_TAG_LABELS[value] ?? (/[一-龥]/.test(value) ? value : '其他标签')
}

export interface DictionarySelectOption extends DictionaryOption {
  disabled: boolean
}

/** 批量加载启用字典；历史 code 保留原值，不用当前字典或猜测值覆盖创建时快照。 */
export function useDictionaries() {
  const optionsMap = ref<Record<string, DictionaryOption[]>>({})
  const loadedMap = ref<Record<string, boolean>>({})

  async function load(...dictCodes: string[]) {
    const results = await Promise.allSettled(dictCodes.map((code) => getDictionaryOptions(code)))
    const next = { ...optionsMap.value }
    const loaded = { ...loadedMap.value }
    dictCodes.forEach((code, index) => {
      const result = results[index]
      if (result?.status === 'fulfilled') {
        next[code] = result.value
        loaded[code] = true
      }
    })
    optionsMap.value = next
    loadedMap.value = loaded
  }

  function options(dictCode: string): DictionaryOption[] {
    return optionsMap.value[dictCode] ?? []
  }

  function optionsWithHistory(dictCode: string, currentCode: string | null | undefined): DictionarySelectOption[] {
    const active = options(dictCode).map((option) => ({ ...option, disabled: false }))
    if (!currentCode || !loadedMap.value[dictCode] || active.some((option) => option.code === currentCode)) {
      return active
    }
    return [...active, { code: currentCode, label: `${currentCode}（已停用）`, sortNo: -1, disabled: true }]
  }

  function optionsWithHistoryList(
    dictCode: string,
    currentCodes: readonly string[] | null | undefined,
  ): DictionarySelectOption[] {
    const active = options(dictCode).map((option) => ({ ...option, disabled: false }))
    if (!currentCodes?.length) {
      return active
    }
    const activeSet = new Set(active.map((option) => option.code))
    const extras: DictionarySelectOption[] = []
    for (const code of currentCodes) {
      if (!code || activeSet.has(code)) continue
      activeSet.add(code)
      const fallback = dictCode === 'customerTags' ? customerTagLabel(code) : null
      const historicalLabel = loadedMap.value[dictCode]
        ? (fallback && fallback !== '其他标签' ? `${fallback}（已停用）` : `${code}（已停用）`)
        : (fallback && fallback !== '其他标签' ? fallback : code)
      extras.push({ code, label: historicalLabel, sortNo: -1, disabled: true })
    }
    return [...active, ...extras]
  }

  function label(dictCode: string, optionCode: string | null | undefined): string {
    if (!optionCode) return '-'
    const hit = optionsMap.value[dictCode]?.find((option) => option.code === optionCode)
    if (hit) return hit.label
    return loadedMap.value[dictCode] ? `${optionCode}（已停用）` : optionCode
  }

  function tagLabel(optionCode: string | null | undefined): string {
    if (!optionCode) return '-'
    const hit = optionsMap.value.customerTags?.find((option) => option.code === optionCode)
    if (hit) return hit.label
    const fallback = customerTagLabel(optionCode)
    if (fallback !== '其他标签') return fallback
    return loadedMap.value.customerTags ? `${optionCode}（已停用）` : fallback
  }

  return { optionsMap, load, options, optionsWithHistory, optionsWithHistoryList, label, tagLabel }
}
