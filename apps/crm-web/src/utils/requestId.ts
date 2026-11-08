const ENCODING = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const MAX_TIMESTAMP = 281474976710655

export function createRequestId(now = Date.now(), randomBytes?: Uint8Array): string {
  if (!Number.isSafeInteger(now) || now < 0 || now > MAX_TIMESTAMP) {
    throw new RangeError('ULID 时间戳超出有效范围')
  }
  const random = randomBytes ?? crypto.getRandomValues(new Uint8Array(16))
  if (random.length !== 16) throw new RangeError('ULID 需要 16 个随机字节')
  let remaining = now
  let timestamp = ''
  for (let index = 0; index < 10; index += 1) {
    timestamp = ENCODING[remaining % 32] + timestamp
    remaining = Math.floor(remaining / 32)
  }
  return timestamp + Array.from(random, (value) => ENCODING[value & 31]).join('')
}
