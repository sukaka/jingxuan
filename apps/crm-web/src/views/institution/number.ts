interface DecimalParts {
  negative: boolean
  digits: string
}

function parseDecimal(value: string, maxFractionDigits: number): DecimalParts {
  const normalized = value.trim().replace(/%$/, '').trim()
  const match = /^([+-])?(\d+)(?:\.(\d+))?$/.exec(normalized)
  if (!match) throw new Error('请输入有效的十进制数字')
  const fraction = match[3] ?? ''
  if (fraction.length > maxFractionDigits) throw new Error(`最多支持 ${maxFractionDigits} 位小数`)
  const negative = match[1] === '-'
  const digits = `${match[2]}${fraction.padEnd(maxFractionDigits, '0')}`.replace(/^0+(?=\d)/, '')
  return { negative, digits }
}

function toSafeNumber(value: bigint): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER) || value < BigInt(Number.MIN_SAFE_INTEGER)) {
    throw new Error('数值超过安全整数范围')
  }
  return Number(value)
}

export function yuanToFenExact(value: string): number {
  const { negative, digits } = parseDecimal(value, 2)
  const fen = BigInt(digits)
  return toSafeNumber(negative ? -fen : fen)
}

export function fenToYuanExact(fen: number | null | undefined): string {
  if (fen === null || fen === undefined) return '-'
  if (!Number.isSafeInteger(fen)) throw new Error('金额分必须是安全整数')
  const negative = fen < 0
  const absolute = Math.abs(fen)
  const yuan = Math.floor(absolute / 100)
  const cents = String(absolute % 100).padStart(2, '0')
  return `${negative ? '-' : ''}${yuan}.${cents}`
}

export function percentToDecimalRate(value: string): string {
  const { negative, digits } = parseDecimal(value, 4)
  const scaled = BigInt(digits)
  const integer = scaled / 1000000n
  const fraction = String(scaled % 1000000n).padStart(6, '0')
  return `${negative ? '-' : ''}${integer}.${fraction}`
}

export function decimalRateToPercent(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '-'
  const normalized = typeof value === 'number' ? String(value) : value
  if (normalized.trim() === '') return '-'
  const { negative, digits } = parseDecimal(normalized, 6)
  const scaled = BigInt(digits)
  const integer = scaled / 10000n
  const fraction = String(scaled % 10000n).padStart(4, '0').replace(/0+$/, '')
  return `${negative ? '-' : ''}${integer}${fraction ? `.${fraction}` : ''}`
}
