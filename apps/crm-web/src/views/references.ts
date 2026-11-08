import { ref } from 'vue'
import { listInstitutions, listProjects } from '../api/catalog'
import { getCustomer } from '../api/customer'

/** 名称解析：机构/项目/客户的真实名称映射，权限不足或不存在时回退为真实 ID 标签，不伪造名称。 */
const projectNames = ref<Record<number, string>>({})
const projectCodeNames = ref<Record<string, string>>({})
const institutionNames = ref<Record<number, string>>({})
const customerNames = ref<Record<number, string>>({})
let referenceLoading: Promise<void> | null = null

function loadReferences() {
  referenceLoading ??= (async () => {
    const [institutions, projects] = await Promise.allSettled([
      listInstitutions({ page: 1, size: 100 }),
      listProjects({ page: 1, size: 100 }),
    ])
    if (institutions.status === 'fulfilled') {
      const map: Record<number, string> = {}
      ;(institutions.value?.items ?? []).forEach((item) => { map[item.id] = item.shortName || item.fullName })
      institutionNames.value = map
    }
    if (projects.status === 'fulfilled') {
      const map: Record<number, string> = {}
      const codeMap: Record<string, string> = {}
      ;(projects.value?.items ?? []).forEach((item) => { map[item.id] = item.projectName; codeMap[item.projectCode] = item.projectName })
      projectNames.value = map
      projectCodeNames.value = codeMap
    }
  })()
  return referenceLoading
}

async function loadCustomerNames(ids: number[]) {
  const missing = [...new Set(ids)].filter((id) => !(id in customerNames.value))
  if (!missing.length) return
  const results = await Promise.allSettled(missing.map((id) => getCustomer(id)))
  const map = { ...customerNames.value }
  results.forEach((result, index) => {
    map[missing[index]] = result.status === 'fulfilled'
      ? (result.value.displayName || result.value.customerNo)
      : `客户 #${missing[index]}`
  })
  customerNames.value = map
}

export function useReferenceNames() {
  void loadReferences()
  return {
    loadCustomerNames,
    customerLabel: (id: number) => customerNames.value[id] || `客户 #${id}`,
    institutionLabel: (id: number) => institutionNames.value[id] || `机构 #${id}`,
    projectLabel: (id: number) => projectNames.value[id] || `项目 #${id}`,
    projectCodeLabel: (code: string) => projectCodeNames.value[code] || code,
  }
}
