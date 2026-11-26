import type { PublicAnnualReport } from '../api/annualReport'

export function formatAnnualYuan(fen: number, hideAmount: boolean): string {
  if (hideAmount) {
    return '私享保密'
  }
  const yuan = Math.round(fen / 100)
  return `¥${yuan.toLocaleString('zh-CN')}`
}

function wrapChineseText(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const lines: string[] = []
  let current = ''
  for (const ch of text) {
    const next = current + ch
    if (ctx.measureText(next).width > maxWidth && current) {
      lines.push(current)
      current = ch
    } else {
      current = next
    }
  }
  if (current) {
    lines.push(current)
  }
  return lines
}

/**
 * 离屏 Canvas2D 合成 750×1334 高清纸雕灯影 PNG 纪念海报。
 */
export function renderAnnualKeepsakePoster(report: PublicAnnualReport, hideAmount: boolean): string {
  const W = 750
  const H = 1334
  const canvas = document.createElement('canvas')
  canvas.width = W
  canvas.height = H
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return ''
  }

  const woodGrad = ctx.createLinearGradient(0, 0, W, H)
  woodGrad.addColorStop(0, '#2c1d14')
  woodGrad.addColorStop(0.5, '#1b110b')
  woodGrad.addColorStop(1, '#120a06')
  ctx.fillStyle = woodGrad
  ctx.fillRect(0, 0, W, H)

  const pad = 34
  const ix = pad
  const iy = pad
  const iw = W - pad * 2
  const ih = H - pad * 2

  ctx.save()
  ctx.beginPath()
  ctx.roundRect(ix, iy, iw, ih, 24)
  ctx.clip()

  const sky = ctx.createLinearGradient(0, iy, 0, iy + ih)
  sky.addColorStop(0, '#0c1835')
  sky.addColorStop(0.45, '#335488')
  sky.addColorStop(0.75, '#192a4d')
  sky.addColorStop(1, '#091021')
  ctx.fillStyle = sky
  ctx.fillRect(ix, iy, iw, ih)

  const mx = W * 0.5
  const my = 340
  const glow = ctx.createRadialGradient(mx, my, 16, mx, my, 430)
  glow.addColorStop(0, 'rgba(255, 224, 152, 0.92)')
  glow.addColorStop(0.32, 'rgba(255, 186, 98, 0.42)')
  glow.addColorStop(0.7, 'rgba(255, 174, 86, 0.1)')
  glow.addColorStop(1, 'rgba(0, 0, 0, 0)')
  ctx.fillStyle = glow
  ctx.fillRect(ix, iy, iw, ih)

  ctx.save()
  ctx.beginPath()
  ctx.arc(mx, my, 88, 0, Math.PI * 2)
  ctx.fillStyle = 'rgba(255, 244, 218, 0.92)'
  ctx.shadowColor = 'rgba(255, 198, 110, 0.85)'
  ctx.shadowBlur = 42
  ctx.fill()
  ctx.restore()

  const waveLayer = (yBase: number, amp: number, color: string, rimAlpha: number) => {
    ctx.save()
    ctx.shadowColor = 'rgba(3, 6, 16, 0.52)'
    ctx.shadowBlur = 24
    ctx.shadowOffsetY = -6
    ctx.beginPath()
    ctx.moveTo(ix - 10, iy + ih + 10)
    ctx.lineTo(ix - 10, yBase)
    ctx.bezierCurveTo(W * 0.25, yBase - amp, W * 0.55, yBase + amp, W + 10, yBase - amp * 0.35)
    ctx.lineTo(W + 10, iy + ih + 10)
    ctx.closePath()
    ctx.fillStyle = color
    ctx.fill()
    ctx.strokeStyle = `rgba(255, 228, 182, ${rimAlpha})`
    ctx.lineWidth = 2
    ctx.stroke()
    ctx.restore()
  }

  waveLayer(510, 74, '#5a7fb5', 0.45)
  waveLayer(595, 58, '#304e80', 0.38)

  ctx.save()
  ctx.translate(W * 0.5, 428)
  ctx.scale(1.55, 1.55)
  ctx.shadowColor = 'rgba(5, 10, 26, 0.58)'
  ctx.shadowBlur = 22
  ctx.beginPath()
  ctx.moveTo(-112, 22)
  ctx.bezierCurveTo(-92, -36, -18, -68, 56, -34)
  ctx.bezierCurveTo(96, -16, 122, 16, 134, 34)
  ctx.bezierCurveTo(154, 18, 174, -4, 186, -12)
  ctx.bezierCurveTo(176, 14, 164, 30, 150, 40)
  ctx.bezierCurveTo(166, 52, 178, 66, 182, 78)
  ctx.bezierCurveTo(162, 70, 142, 58, 126, 46)
  ctx.bezierCurveTo(86, 54, 36, 42, 6, 32)
  ctx.bezierCurveTo(-6, 52, -26, 66, -42, 68)
  ctx.bezierCurveTo(-36, 46, -32, 34, -34, 26)
  ctx.bezierCurveTo(-68, 32, -98, 34, -112, 22)
  ctx.closePath()
  ctx.fillStyle = '#1b315c'
  ctx.fill()
  ctx.strokeStyle = 'rgba(255, 232, 188, 0.6)'
  ctx.lineWidth = 1.6
  ctx.stroke()
  ;[
    [-56, -10, 4.5],
    [-20, -24, 5.5],
    [18, -20, 4],
    [52, -8, 4.5],
  ].forEach(([sx, sy, sr]) => {
    ctx.fillStyle = '#fff3d4'
    ctx.beginPath()
    ctx.arc(sx!, sy!, sr!, 0, Math.PI * 2)
    ctx.fill()
  })
  ctx.restore()

  waveLayer(675, 52, '#162749', 0.3)
  waveLayer(760, 40, '#0a1226', 0.22)

  ctx.fillStyle = '#c94635'
  ctx.beginPath()
  ctx.roundRect(78, 86, 44, 44, 8)
  ctx.fill()
  ctx.fillStyle = '#fff6e6'
  ctx.font = 'bold 24px "Songti SC", "Noto Serif SC", Georgia, serif'
  ctx.textAlign = 'center'
  ctx.fillText('鲸', 100, 117)

  ctx.textAlign = 'left'
  ctx.fillStyle = '#fdf4e3'
  ctx.font = '600 24px "Songti SC", "Noto Serif SC", Georgia, serif'
  ctx.fillText('鲸选美学', 136, 108)
  ctx.fillStyle = 'rgba(253, 244, 227, 0.65)'
  ctx.font = '14px Menlo, Consolas, monospace'
  ctx.fillText(`WHALE AESTHETICS · ${report.reportYear} ANNUAL KEEPSAKE`, 136, 129)

  ctx.fillStyle = 'rgba(201, 70, 53, 0.9)'
  ctx.beginPath()
  ctx.roundRect(W - 186, 90, 108, 36, 6)
  ctx.fill()
  ctx.fillStyle = '#fff6e8'
  ctx.textAlign = 'center'
  ctx.font = '600 18px "Songti SC", "Noto Serif SC", serif'
  ctx.fillText(report.sealText, W - 132, 115)

  const cardX = 68
  const cardY = 670
  const cardW = W - 136
  const cardH = 548
  ctx.save()
  ctx.fillStyle = 'rgba(11, 16, 30, 0.86)'
  ctx.strokeStyle = 'rgba(255, 218, 158, 0.45)'
  ctx.lineWidth = 2
  ctx.beginPath()
  ctx.roundRect(cardX, cardY, cardW, cardH, 22)
  ctx.fill()
  ctx.stroke()
  ctx.restore()

  ctx.textAlign = 'center'
  ctx.fillStyle = 'rgba(255, 228, 184, 0.78)'
  ctx.font = '22px "Songti SC", "Noto Serif SC", serif'
  ctx.fillText(report.copy.posterHeading, W / 2, cardY + 62)

  ctx.fillStyle = '#ffe3ab'
  ctx.font = 'bold 44px "Songti SC", "Noto Serif SC", serif'
  ctx.fillText(report.copy.personaTitle, W / 2, cardY + 124)

  const lineGrad = ctx.createLinearGradient(cardX + 60, 0, cardX + cardW - 60, 0)
  lineGrad.addColorStop(0, 'rgba(255, 214, 148, 0)')
  lineGrad.addColorStop(0.5, 'rgba(255, 214, 148, 0.65)')
  lineGrad.addColorStop(1, 'rgba(255, 214, 148, 0)')
  ctx.strokeStyle = lineGrad
  ctx.lineWidth = 1.5
  ctx.beginPath()
  ctx.moveTo(cardX + 60, cardY + 150)
  ctx.lineTo(cardX + cardW - 60, cardY + 150)
  ctx.stroke()

  ctx.fillStyle = 'rgba(253, 244, 228, 0.88)'
  ctx.font = '20px "Songti SC", "Noto Serif SC", serif'
  const quoteLines = wrapChineseText(ctx, report.copy.personaQuote, cardW - 60)
  quoteLines.slice(0, 3).forEach((line, idx) => {
    ctx.fillText(line, W / 2, cardY + 194 + idx * 34)
  })

  const metrics = [
    { label: '相伴鲸选', value: `${report.daysTogether} 天` },
    { label: '全年订单 / 到院', value: `${report.orderCount} 笔 · ${report.redeemCount} 次` },
    { label: '年度挚爱项目', value: report.favorite.projectName },
    { label: '年度焕美投入', value: formatAnnualYuan(report.netSpendFen, hideAmount) },
  ]
  const boxW = (cardW - 66) / 2
  const boxH = 88
  metrics.forEach((m, idx) => {
    const col = idx % 2
    const row = Math.floor(idx / 2)
    const bx = cardX + 24 + col * (boxW + 18)
    const by = cardY + 292 + row * (boxH + 14)

    ctx.fillStyle = 'rgba(255, 224, 178, 0.08)'
    ctx.strokeStyle = 'rgba(255, 218, 162, 0.22)'
    ctx.lineWidth = 1.2
    ctx.beginPath()
    ctx.roundRect(bx, by, boxW, boxH, 14)
    ctx.fill()
    ctx.stroke()

    ctx.textAlign = 'left'
    ctx.fillStyle = 'rgba(253, 236, 210, 0.65)'
    ctx.font = '18px -apple-system, "PingFang SC", sans-serif'
    ctx.fillText(m.label, bx + 18, by + 32)

    ctx.fillStyle = '#ffe5b0'
    ctx.font = 'bold 22px Menlo, "Songti SC", serif'
    const text = m.value.length > 12 ? `${m.value.slice(0, 11)}…` : m.value
    ctx.fillText(text, bx + 18, by + 68)
  })

  ctx.textAlign = 'left'
  ctx.fillStyle = '#ffd99c'
  ctx.font = '19px "Songti SC", "Noto Serif SC", serif'
  ctx.fillText(report.copy.butlerSign, cardX + 24, cardY + cardH - 22)

  ctx.textAlign = 'right'
  ctx.fillStyle = 'rgba(253, 240, 216, 0.56)'
  ctx.font = '15px Menlo, monospace'
  ctx.fillText(`最晚光影 ${report.midnight.time} · ${report.memberNo}`, cardX + cardW - 24, cardY + cardH - 22)

  ctx.restore()

  ctx.strokeStyle = 'rgba(255, 218, 162, 0.28)'
  ctx.lineWidth = 2
  ctx.beginPath()
  ctx.roundRect(ix, iy, iw, ih, 24)
  ctx.stroke()

  return canvas.toDataURL('image/png')
}
