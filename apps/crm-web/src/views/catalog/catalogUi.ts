import { ApiError } from '../../types/api'

export function catalogErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return '配置已被修改或当前状态不允许操作，请刷新后重试'
    if (error.status === 422) return error.message
    return error.message
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

export function selectionModeLabel(mode: string | null | undefined): string {
  if (!mode) return '-'
  return mode === 'USE_N' ? '固定次数' : mode === 'CHOOSE_N' ? '选择子项目' : mode === 'FIXED' ? '固定组合' : '未知选择模式'
}

export function versionStatusLabel(status: string | null | undefined): string {
  if (!status) return '-'
  return status === 'DRAFT' ? '草稿' : status === 'ACTIVE' ? '已激活' : status === 'ARCHIVED' ? '已归档' : '未知状态'
}

export function skuStatusLabel(status: string | null | undefined): string {
  if (!status) return '-'
  return status === 'ACTIVE' ? '上架中' : status === 'INACTIVE' ? '已停用' : '未知状态'
}

export function optionGroupLabel(optionGroup: string | null | undefined): string {
  if (!optionGroup) return '-'
  return optionGroup === 'MAIN' ? '主选项组' : optionGroup
}
