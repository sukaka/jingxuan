export function shanghaiInputToInstant(value: string | null): string | null {
  if (!value) return null
  const parsed = new Date(`${value}+08:00`)
  if (Number.isNaN(parsed.getTime())) throw new Error('活动时间格式无效')
  return parsed.toISOString()
}

export function instantToShanghaiInput(value: string | null): string | null {
  if (!value) return null
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) return null
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}T${values.hour}:${values.minute}:${values.second}`
}
