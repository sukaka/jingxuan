import { ref } from 'vue'
import { listInstitutions, listProjects } from '../api/catalog'
import { getCustomer } from '../api/customer'

/** 名称解析：机构/项目/客户的真实名称映射，权限不足或不存在时回退为真实 ID 标签，不伪造名称。 */
const projectNames = ref<Record<number, string>>({})
const projectCodeNames = ref<Record<string, string>>({})
const institutionNames = ref<Record<number, string>>({})
const customerNames = ref<Record<number, string>>({})
let referenceLoading: Promise<void> | null = null
let referenceEpoch = 0

export function resetReferenceNames(): void {
  referenceEpoch += 1
  referenceLoading = null
  projectNames.value = {}
  projectCodeNames.value = {}
  institutionNames.value = {}
  customerNames.value = {}
}

function loadReferences() {
  if (referenceLoading) return referenceLoading
  const epoch = referenceEpoch
  const promise = (async () => {
    const [institutions, projects] = await Promise.allSettled([
      listInstitutions({ page: 1, size: 100 }),
      listProjects({ page: 1, size: 100 }),
    ])
    if (epoch !== referenceEpoch) return
    let allFulfilled = true
    if (institutions.status === 'fulfilled') {
      const map: Record<number, string> = {}
      ;(institutions.value?.items ?? []).forEach((item) => { map[item.id] = item.shortName || item.fullName })
      institutionNames.value = map
    } else {
      allFulfilled = false
    }
    if (projects.status === 'fulfilled') {
      const map: Record<number, string> = {}
      const codeMap: Record<string, string> = {}
      ;(projects.value?.items ?? []).forEach((item) => { map[item.id] = item.projectName; codeMap[item.projectCode] = item.projectName })
      projectNames.value = map
      projectCodeNames.value = codeMap
    } else {
      allFulfilled = false
    }
    if (!allFulfilled && epoch === referenceEpoch) {
      referenceLoading = null
    }
  })().catch((error) => {
    if (epoch === referenceEpoch) {
      referenceLoading = null
    }
    throw error
  })
  referenceLoading = promise
  return promise
}

async function loadCustomerNames(ids: number[]) {
  const missing = [...new Set(ids)].filter((id) => !(id in customerNames.value))
  if (!missing.length) return
  const epoch = referenceEpoch
  const results = await Promise.allSettled(missing.map((id) => getCustomer(id)))
  if (epoch !== referenceEpoch) return
  const map = { ...customerNames.value }
  let changed = false
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      map[missing[index]] = result.value.displayName || result.value.customerNo
      changed = true
    }
  })
  if (changed) {
    customerNames.value = map
  }
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
