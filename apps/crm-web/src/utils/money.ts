export function fenToYuan(fen: number | null | undefined): string {
  return fen === null || fen === undefined ? '-' : (fen / 100).toFixed(2)
}

export function yuanToFen(yuan: number): number {
  return Math.round(yuan * 100)
}
