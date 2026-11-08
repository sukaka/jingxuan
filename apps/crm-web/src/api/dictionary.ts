import type { ApiResponse } from '../types/api'
import { http } from './http'

/** 字典选项(前端统一字段) */
export interface DictionaryOption {
  code: string
  label: string
  sortNo: number
}

/** 后端原始字段 */
interface DictionaryOptionRaw {
  optionCode: string
  optionLabel: string
  sortNo: number
}

/** GET /dictionaries/{dictCode}/options */
export async function getDictionaryOptions(dictCode: string): Promise<DictionaryOption[]> {
  const res = await http.get<ApiResponse<DictionaryOptionRaw[]>>(
    `/dictionaries/${encodeURIComponent(dictCode)}/options`,
  )
  const list = res.data.data ?? []
  return list.map((raw) => ({
    code: raw.optionCode,
    label: raw.optionLabel,
    sortNo: raw.sortNo,
  }))
}
