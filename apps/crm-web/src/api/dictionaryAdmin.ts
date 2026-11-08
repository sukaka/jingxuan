import type { ApiResponse } from '../types/api'
import { http } from './http'

/** 字典类型(系统预置,管理页只做选项维护) */
export interface DictionaryTypeItem {
  id: number
  dictCode: string
  dictName: string
  status: string
}

/** 字典选项(管理视图,含 INACTIVE 历史项;只读 options 接口只返回 ACTIVE) */
export interface DictionaryItemDetail {
  id: number
  dictTypeId: number
  optionCode: string
  optionLabel: string
  sortNo: number
  status: string
  version: number
}

export interface CreateDictionaryItemRequest {
  dictCode: string
  optionCode: string
  optionLabel: string
  sortNo?: number
}

/** PATCH /dictionary-items/{id}:body.version 乐观锁;optionCode 为不可变业务代码,只允许改展示名/排序 */
export interface UpdateDictionaryItemRequest {
  optionLabel: string
  sortNo: number
  version: number
}

/** GET /dictionary-types(dictionary:read) */
export async function listDictionaryTypes(): Promise<DictionaryTypeItem[]> {
  const res = await http.get<ApiResponse<DictionaryTypeItem[]>>('/dictionary-types')
  return res.data.data
}

/** GET /dictionary-types/{id}/items(dictionary:read),管理用全量含停用项 */
export async function listDictionaryItems(dictTypeId: number): Promise<DictionaryItemDetail[]> {
  const res = await http.get<ApiResponse<DictionaryItemDetail[]>>(`/dictionary-types/${dictTypeId}/items`)
  return res.data.data
}

/** POST /dictionary-items(dictionary:write) */
export async function createDictionaryItem(
  request: CreateDictionaryItemRequest,
): Promise<DictionaryItemDetail> {
  const res = await http.post<ApiResponse<DictionaryItemDetail>>('/dictionary-items', request)
  return res.data.data
}

/** PATCH /dictionary-items/{id}(dictionary:write),携带 version 乐观锁 */
export async function updateDictionaryItem(
  id: number,
  request: UpdateDictionaryItemRequest,
): Promise<DictionaryItemDetail> {
  const res = await http.patch<ApiResponse<DictionaryItemDetail>>(`/dictionary-items/${id}`, request)
  return res.data.data
}

/** POST /dictionary-items/{id}/disable(dictionary:write)。停用后 options 不再返回,历史数据仍可展示 */
export async function disableDictionaryItem(id: number): Promise<DictionaryItemDetail> {
  const res = await http.post<ApiResponse<DictionaryItemDetail>>(`/dictionary-items/${id}/disable`)
  return res.data.data
}
