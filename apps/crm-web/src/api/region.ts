import type { ApiResponse } from '../types/api'
import { http } from './http'

/** 全国标准行政区划树节点（level: 1=省/直辖市/自治区/特别行政区，2=地级市/市级，3=区/县/镇街） */
export interface RegionNode {
  code: string
  name: string
  level: number
  children?: RegionNode[]
}

export interface CustomerRegionFields {
  provinceCode?: string | null
  provinceName?: string | null
  regionCityCode?: string | null
  regionCityName?: string | null
  districtCode?: string | null
  districtName?: string | null
  regionText?: string | null
}

let cachedTree: RegionNode[] | null = null
let inflightRequest: Promise<RegionNode[]> | null = null

export async function getRegionTree(): Promise<RegionNode[]> {
  const res = await http.get<ApiResponse<RegionNode[]>>('/regions/tree')
  return res.data.data ?? []
}

/** 会话级缓存行政区划树，避免多个弹窗/筛选器重复请求；失败时不锁死缓存以便重试。 */
export async function loadRegionTree(): Promise<RegionNode[]> {
  if (cachedTree) return cachedTree
  if (!inflightRequest) {
    inflightRequest = getRegionTree()
      .then((nodes) => {
        cachedTree = nodes
        return nodes
      })
      .finally(() => {
        inflightRequest = null
      })
  }
  return inflightRequest
}

export function resetRegionTreeCache(): void {
  cachedTree = null
  inflightRequest = null
}

export function buildRegionPath(item: CustomerRegionFields | null | undefined): string[] {
  if (!item) return []
  const path: string[] = []
  if (item.provinceCode) path.push(item.provinceCode)
  if (item.regionCityCode) path.push(item.regionCityCode)
  if (item.districtCode) path.push(item.districtCode)
  return path
}

export function splitRegionPath(path: readonly string[] | null | undefined): {
  provinceCode?: string
  regionCityCode?: string
  districtCode?: string
} {
  if (!path || path.length === 0) return {}
  return {
    provinceCode: path[0] || undefined,
    regionCityCode: path[1] || undefined,
    districtCode: path[2] || undefined,
  }
}

const GENERIC_CITY_PLACEHOLDERS = new Set([
  '市辖区',
  '县',
  '省直辖县级行政区划',
  '自治区直辖县级行政区划',
  '直辖市',
])

export function formatRegionText(
  provinceName?: string | null,
  regionCityName?: string | null,
  districtName?: string | null,
): string | null {
  const parts: string[] = []
  const p = provinceName?.trim() || ''
  const c = regionCityName?.trim() || ''
  const d = districtName?.trim() || ''
  if (p) parts.push(p)
  if (c && c !== p && !GENERIC_CITY_PLACEHOLDERS.has(c)) parts.push(c)
  if (d && d !== c && d !== p) parts.push(d)
  return parts.length ? parts.join(' / ') : null
}

export function formatCustomerRegion(
  item: CustomerRegionFields | null | undefined,
  fallbackCityLabel?: string | null,
): string {
  if (item?.regionText?.trim()) return item.regionText.trim()
  const formatted = formatRegionText(item?.provinceName, item?.regionCityName, item?.districtName)
  if (formatted) return formatted
  if (fallbackCityLabel && fallbackCityLabel !== '-') return fallbackCityLabel
  return '—'
}
