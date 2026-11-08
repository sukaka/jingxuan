/** 读取浏览器 Cookie(HttpOnly 的 CRM_TOKEN 不可读,这里只用于 CRM_CSRF 等可读 Cookie) */
export function getCookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim()
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length))
    }
  }
  return null
}
