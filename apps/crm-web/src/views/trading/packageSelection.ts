import type { PackageSelectionGroup, PackageVersionSnapshot } from '../../api/catalog'
import type { OrderItemSelectionInput } from '../../api/trading'

export type PackageSelections = Record<string, number[]>

export interface SelectionTotals {
  referenceAmountFen: number
  packageAmountFen: number
  discountAmountFen: number
}

function orderedGroups(snapshot: PackageVersionSnapshot): PackageSelectionGroup[] {
  return [...(snapshot.groups ?? [])].sort((left, right) =>
    left.sortOrder - right.sortOrder || (left.id ?? 0) - (right.id ?? 0) || left.groupCode.localeCompare(right.groupCode))
}

export function groupOptions(snapshot: PackageVersionSnapshot, groupCode: string) {
  return (snapshot.projects ?? []).filter((project) => project.optionGroup === groupCode)
}

export function selectionError(snapshot: PackageVersionSnapshot, selections: PackageSelections): string {
  if (snapshot.selectionMode !== 'CHOOSE_N') return ''
  const groups = orderedGroups(snapshot)
  if (!groups.length) return '该套餐尚未配置可选项目分组，暂不能下单'
  const selectedProjects = new Set<number>()
  for (const group of groups) {
    const selected = selections[group.groupCode] ?? []
    if (new Set(selected).size !== selected.length) return `${group.groupName}存在重复选择`
    for (const projectId of selected) {
      if (!groupOptions(snapshot, group.groupCode).some((project) => project.projectId === projectId)) {
        return `${group.groupName}包含不属于该组的子项目`
      }
      if (selectedProjects.has(projectId)) return `子项目 #${projectId} 不能重复选择`
      selectedProjects.add(projectId)
    }
    if (selected.length < group.minSelections) return `${group.groupName}至少选择 ${group.minSelections} 项`
    if (selected.length > group.maxSelections) return `${group.groupName}最多选择 ${group.maxSelections} 项`
  }
  return ''
}

export function toSelectionInputs(
  snapshot: PackageVersionSnapshot,
  selections: PackageSelections,
): OrderItemSelectionInput[] {
  const result: OrderItemSelectionInput[] = []
  for (const group of orderedGroups(snapshot)) {
    const selected = new Set(selections[group.groupCode] ?? [])
    for (const project of groupOptions(snapshot, group.groupCode)) {
      if (selected.has(project.projectId)) result.push({ groupCode: group.groupCode, projectId: project.projectId })
    }
  }
  return result
}

export function selectionTotals(
  snapshot: PackageVersionSnapshot,
  selections: PackageSelections,
  packageSalePriceFen: number,
  quantity = 1,
): SelectionTotals {
  const selected = toSelectionInputs(snapshot, selections)
  const selectedKeys = new Set(selected.map((item) => `${item.groupCode}|${item.projectId}`))
  const referencePerPackage = (snapshot.projects ?? [])
    .filter((project) => selectedKeys.has(`${project.optionGroup}|${project.projectId}`))
    .reduce((total, project) => total + project.referencePriceFen * project.grantCount, 0)
  const referenceAmountFen = referencePerPackage * quantity
  const packageAmountFen = packageSalePriceFen * quantity
  return {
    referenceAmountFen,
    packageAmountFen,
    discountAmountFen: Math.max(0, referenceAmountFen - packageAmountFen),
  }
}
