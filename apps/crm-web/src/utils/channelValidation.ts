export function isValidMainlandMobile(value: string): boolean {
  return /^1[3-9]\d{9}$/.test(value.trim())
}

export function normalizeChineseIdCard(value: string): string {
  return value.trim().toUpperCase()
}

export function isValidChineseIdCard(value: string): boolean {
  const id = normalizeChineseIdCard(value)
  if (!/^\d{17}[0-9X]$/.test(id)) return false

  const year = Number(id.slice(6, 10))
  const month = Number(id.slice(10, 12))
  const day = Number(id.slice(12, 14))
  const birth = new Date(Date.UTC(year, month - 1, day))
  if (birth.getUTCFullYear() !== year || birth.getUTCMonth() !== month - 1 || birth.getUTCDate() !== day) return false
  const today = new Date()
  if (birth.getTime() > Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())) return false

  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
  const checkCodes = '10X98765432'
  const sum = weights.reduce((total, weight, index) => total + Number(id[index]) * weight, 0)
  return checkCodes[sum % 11] === id[17]
}
