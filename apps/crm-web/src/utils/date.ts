export const BUSINESS_TIME_ZONE = 'Asia/Shanghai'

const NAIVE_DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const NAIVE_DATETIME_RE = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?$/

const shanghaiFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: BUSINESS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function extractShanghaiParts(date: Date): {
  year: string
  month: string
  day: string
  hour: string
  minute: string
} | null {
  if (Number.isNaN(date.getTime())) return null
  const parts = shanghaiFormatter.formatToParts(date)
  const map: Record<string, string> = {}
  for (const part of parts) {
    if (part.type !== 'literal') map[part.type] = part.value
  }
  if (!map.year || !map.month || !map.day || !map.hour || !map.minute) return null
  return {
    year: map.year,
    month: map.month,
    day: map.day,
    hour: map.hour === '24' ? '00' : map.hour,
    minute: map.minute,
  }
}

export function todayInShanghai(now: Date = new Date()): string {
  const parts = extractShanghaiParts(now)
  if (!parts) return ''
  return `${parts.year}-${parts.month}-${parts.day}`
}

export function addDaysInShanghai(days: number, now: Date = new Date()): string {
  const today = todayInShanghai(now)
  if (!today) return ''
  const [y, m, d] = today.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10)
}

export function shanghaiDateString(value: string | Date | number | null | undefined): string {
  if (value == null || value === '') return ''
  if (typeof value === 'string') {
    const raw = value.trim()
    if (!raw) return ''
    if (NAIVE_DATE_RE.test(raw)) return raw
    const naiveMatch = raw.match(NAIVE_DATETIME_RE)
    if (naiveMatch) return naiveMatch[1]
    const parsed = new Date(raw)
    const parts = extractShanghaiParts(parsed)
    return parts ? `${parts.year}-${parts.month}-${parts.day}` : raw.slice(0, 10)
  }
  const parsed = value instanceof Date ? value : new Date(value)
  const parts = extractShanghaiParts(parsed)
  return parts ? `${parts.year}-${parts.month}-${parts.day}` : ''
}

export function shanghaiTimeString(value: string | Date | number | null | undefined): string {
  if (value == null || value === '') return ''
  if (typeof value === 'string') {
    const raw = value.trim()
    if (!raw) return ''
    const naiveMatch = raw.match(NAIVE_DATETIME_RE)
    if (naiveMatch) return naiveMatch[2]
    const parsed = new Date(raw)
    const parts = extractShanghaiParts(parsed)
    return parts ? `${parts.hour}:${parts.minute}` : raw.slice(11, 16)
  }
  const parsed = value instanceof Date ? value : new Date(value)
  const parts = extractShanghaiParts(parsed)
  return parts ? `${parts.hour}:${parts.minute}` : ''
}

export function shanghaiDateTimeMinute(value: string | Date | number | null | undefined): string {
  if (value == null || value === '') return ''
  if (typeof value === 'string') {
    const raw = value.trim()
    if (!raw) return ''
    if (NAIVE_DATE_RE.test(raw)) return raw
    const naiveMatch = raw.match(NAIVE_DATETIME_RE)
    if (naiveMatch) return `${naiveMatch[1]} ${naiveMatch[2]}`
    const parsed = new Date(raw)
    const parts = extractShanghaiParts(parsed)
    return parts ? `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}` : raw.replace('T', ' ').slice(0, 16)
  }
  const parsed = value instanceof Date ? value : new Date(value)
  const parts = extractShanghaiParts(parsed)
  return parts ? `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}` : ''
}

export function endOfTodayInShanghaiIso(now: Date = new Date()): string {
  const today = todayInShanghai(now)
  return new Date(`${today}T23:59:59.999+08:00`).toISOString()
}
