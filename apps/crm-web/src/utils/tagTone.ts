export type TagTone = 'success' | 'warning' | 'danger' | 'info' | 'dark' | 'teal' | 'purple' | 'orange' | 'pink'

export function orderTypeTone(code: string): TagTone {
  return ({ REGULAR: 'teal', FLASH: 'orange', PROMOTION: 'purple' } as Record<string, TagTone>)[code] ?? 'dark'
}

export function orderStatusTone(status: string | null | undefined): TagTone {
  if (status === 'PAID' || status === 'FULFILLED') return 'success'
  if (status === 'DRAFT' || status === 'PENDING_PAYMENT') return 'warning'
  if (status === 'PARTIALLY_REDEEMED') return 'info'
  return 'dark'
}

export function materialCategoryTone(code: string): TagTone {
  return ({
    GUIDE: 'info',
    CASE: 'purple',
    INSTITUTION: 'teal',
    DOCTOR: 'orange',
    DEVICE: 'pink',
    INSTRUMENT: 'pink',
    PRODUCT: 'success',
  } as Record<string, TagTone>)[code] ?? 'dark'
}

export function customerTierTone(tier: string | null | undefined): TagTone {
  return ({ S: 'purple', A: 'info', B: 'teal', C: 'dark' } as Record<string, TagTone>)[tier?.toUpperCase() ?? ''] ?? 'dark'
}

export function customerStatusTone(status: string | null | undefined): TagTone {
  return ({
    ACTIVE: 'success',
    PENDING_FOLLOWUP: 'warning',
    APPOINTMENT: 'info',
    LEAD: 'info',
    REFUNDING: 'danger',
    INACTIVE: 'dark',
  } as Record<string, TagTone>)[status ?? ''] ?? 'dark'
}

export function roleTone(roleCode: string): TagTone {
  return ({
    ADMIN: 'purple',
    OWNER: 'orange',
    MANAGER_LEAD: 'info',
    MANAGER: 'teal',
    BUSINESS: 'info',
    FINANCE: 'warning',
    INSTITUTION_STAFF: 'dark',
    INSTITUTION_FINANCE: 'warning',
  } as Record<string, TagTone>)[roleCode] ?? 'dark'
}

export function userStatusTone(status: string): TagTone {
  return status === 'ACTIVE' ? 'success' : 'dark'
}

export function priorityTone(priority: string): TagTone {
  return ({ URGENT: 'danger', HIGH: 'warning', MEDIUM: 'info', LOW: 'dark' } as Record<string, TagTone>)[priority] ?? 'dark'
}

export function knowledgeDocumentTone(status: string): TagTone {
  return ({
    WAITING: 'warning',
    SYNCING: 'info',
    ACTIVE: 'success',
    FAILED: 'danger',
    DISABLED: 'dark',
  } as Record<string, TagTone>)[status] ?? 'dark'
}

export function knowledgeSyncTone(status: string | null): TagTone {
  return ({
    WAITING: 'warning',
    PENDING: 'warning',
    RUNNING: 'info',
    INDEXING: 'info',
    RETRY: 'warning',
    SUCCEEDED: 'success',
    FAILED: 'danger',
  } as Record<string, TagTone>)[status ?? ''] ?? 'dark'
}
