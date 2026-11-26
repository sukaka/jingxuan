/**
 * 纸雕灯影（Paper-cut Lightbox）Canvas2D 实时渲染引擎（生产 TypeScript 版）
 * 遵循 Lemo-Opuscar styles/paper-lantern/STYLE.md 材质、色阶梯与暖背光规范。
 */

export interface PaperSceneTheme {
  id: string
  cssBg: string
  accent: string
  accentSoft: string
  cardBg: string
  cardBorder: string
  rim: string
  spill: string
  skyTop: string
  skyBottom: string
  lightPos: [number, number]
  lightColor: [number, number, number]
  layers: [string, string, string, string, string]
}

export const PAPER_SCENE_THEMES: PaperSceneTheme[] = [
  {
    id: 'dawn-whale',
    cssBg: '#091124',
    accent: '#ffb862',
    accentSoft: 'rgba(255, 184, 98, 0.24)',
    cardBg: 'rgba(8, 16, 36, 0.72)',
    cardBorder: 'rgba(255, 224, 174, 0.26)',
    rim: 'rgba(255, 224, 170, 0.5)',
    spill: 'rgba(255, 184, 98, 0.26)',
    skyTop: '#0b1836',
    skyBottom: '#2d4d80',
    lightPos: [0.76, 0.27],
    lightColor: [255, 198, 114],
    layers: ['#7fa1d4', '#4d73ab', '#2a4779', '#16294d', '#091124'],
  },
  {
    id: 'four-seasons',
    cssBg: '#190c08',
    accent: '#ffcf86',
    accentSoft: 'rgba(255, 207, 134, 0.24)',
    cardBg: 'rgba(28, 14, 9, 0.72)',
    cardBorder: 'rgba(255, 224, 178, 0.24)',
    rim: 'rgba(255, 232, 188, 0.46)',
    spill: 'rgba(255, 176, 92, 0.28)',
    skyTop: '#4a2315',
    skyBottom: '#e9b77c',
    lightPos: [0.8, 0.19],
    lightColor: [255, 218, 146],
    layers: ['#d49762', '#a35f39', '#6c3821', '#3d1d12', '#190c08'],
  },
  {
    id: 'midnight-window',
    cssBg: '#080e1f',
    accent: '#ffb862',
    accentSoft: 'rgba(255, 184, 98, 0.28)',
    cardBg: 'rgba(9, 15, 34, 0.74)',
    cardBorder: 'rgba(255, 216, 154, 0.26)',
    rim: 'rgba(255, 220, 158, 0.5)',
    spill: 'rgba(255, 170, 74, 0.25)',
    skyTop: '#070e24',
    skyBottom: '#2a4173',
    lightPos: [0.82, 0.17],
    lightColor: [255, 184, 98],
    layers: ['#6c88c2', '#415d94', '#243764', '#132040', '#080e1f'],
  },
  {
    id: 'beloved-ritual',
    cssBg: '#1a0a12',
    accent: '#ffd09c',
    accentSoft: 'rgba(255, 208, 156, 0.24)',
    cardBg: 'rgba(30, 12, 22, 0.72)',
    cardBorder: 'rgba(255, 218, 192, 0.26)',
    rim: 'rgba(255, 226, 200, 0.48)',
    spill: 'rgba(235, 134, 148, 0.26)',
    skyTop: '#4c1e31',
    skyBottom: '#eab3be',
    lightPos: [0.76, 0.44],
    lightColor: [255, 214, 174],
    layers: ['#cc8698', '#9a5268', '#662f43', '#3b1826', '#1a0a12'],
  },
  {
    id: 'fulfillment-vault',
    cssBg: '#051619',
    accent: '#82e6dc',
    accentSoft: 'rgba(130, 230, 220, 0.22)',
    cardBg: 'rgba(6, 26, 29, 0.74)',
    cardBorder: 'rgba(154, 234, 224, 0.26)',
    rim: 'rgba(202, 248, 242, 0.46)',
    spill: 'rgba(32, 186, 174, 0.26)',
    skyTop: '#072a2e',
    skyBottom: '#5db7af',
    lightPos: [0.5, 0.3],
    lightColor: [255, 226, 162],
    layers: ['#62b8b0', '#2b837d', '#155252', '#0b2f32', '#051619'],
  },
  {
    id: 'keepsake-box',
    cssBg: '#0c1222',
    accent: '#ffcf86',
    accentSoft: 'rgba(255, 207, 134, 0.28)',
    cardBg: 'rgba(14, 16, 26, 0.8)',
    cardBorder: 'rgba(255, 218, 158, 0.34)',
    rim: 'rgba(255, 232, 188, 0.56)',
    spill: 'rgba(255, 186, 98, 0.36)',
    skyTop: '#121c38',
    skyBottom: '#566b96',
    lightPos: [0.76, 0.61],
    lightColor: [255, 204, 122],
    layers: ['#8ba2cc', '#576f9e', '#30436b', '#192542', '#0b1020'],
  },
]

function createPaperGrainCanvas(): HTMLCanvasElement | null {
  const c = document.createElement('canvas')
  c.width = 160
  c.height = 160
  const g = c.getContext('2d')
  if (!g) {
    return null
  }
  const img = g.createImageData(160, 160)
  let seed = 4219
  const rand = () => {
    seed = (seed * 16807) % 2147483647
    return (seed - 1) / 2147483646
  }
  for (let i = 0; i < img.data.length; i += 4) {
    const n = 128 + (rand() - 0.5) * 44
    img.data[i] = n
    img.data[i + 1] = n - 2
    img.data[i + 2] = n - 6
    img.data[i + 3] = 22
  }
  g.putImageData(img, 0, 0)
  g.strokeStyle = 'rgba(255, 244, 224, 0.08)'
  g.lineWidth = 0.65
  for (let i = 0; i < 65; i++) {
    const x = rand() * 160
    const y = rand() * 160
    const len = 4 + rand() * 10
    const ang = rand() * Math.PI * 2
    g.beginPath()
    g.moveTo(x, y)
    g.quadraticCurveTo(
      x + Math.cos(ang + 0.4) * len * 0.5,
      y + Math.sin(ang + 0.4) * len * 0.5,
      x + Math.cos(ang) * len,
      y + Math.sin(ang) * len,
    )
    g.stroke()
  }
  return c
}

function hexToRgb(hex: string): [number, number, number] {
  const clean = hex.replace('#', '')
  return [
    parseInt(clean.slice(0, 2), 16),
    parseInt(clean.slice(2, 4), 16),
    parseInt(clean.slice(4, 6), 16),
  ]
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}

function lerpColor(hexA: string, hexB: string, t: number): string {
  const a = hexToRgb(hexA)
  const b = hexToRgb(hexB)
  return `rgb(${Math.round(lerp(a[0], b[0], t))},${Math.round(lerp(a[1], b[1], t))},${Math.round(lerp(a[2], b[2], t))})`
}

function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}

export class PaperLightboxEngine {
  private readonly canvas: HTMLCanvasElement
  private readonly ctx: CanvasRenderingContext2D | null
  private readonly grainCanvas: HTMLCanvasElement | null
  private grainPattern: CanvasPattern | null = null
  private width = 390
  private height = 790
  private dpr = 1
  private sceneIndex = 0
  private prevSceneIndex = 0
  private sceneMix = 1
  private sceneEnterAt = performance.now()
  private lampLit = 0.28
  private lampTarget = 0.28
  private pxTarget = 0
  private pyTarget = 0
  private px = 0
  private py = 0
  private rafId: number | null = null
  private readonly motes = Array.from({ length: 24 }, (_, i) => ({
    x: ((i * 37) % 100) / 100,
    y: ((i * 53 + 17) % 100) / 100,
    r: 1 + (i % 3) * 0.75,
    speed: 0.008 + (i % 5) * 0.004,
    phase: i * 1.13,
  }))
  private readonly onResize: () => void

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas
    this.ctx = canvas.getContext('2d')
    this.grainCanvas = createPaperGrainCanvas()
    this.onResize = () => this.resize()
    this.resize()
    window.addEventListener('resize', this.onResize)
  }

  resize(): void {
    if (!this.ctx) {
      return
    }
    const rect = this.canvas.getBoundingClientRect()
    this.width = Math.max(300, Math.round(rect.width || 390))
    this.height = Math.max(500, Math.round(rect.height || 790))
    this.dpr = Math.min(window.devicePixelRatio || 1, 2)
    this.canvas.width = Math.round(this.width * this.dpr)
    this.canvas.height = Math.round(this.height * this.dpr)
    this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0)
    this.grainPattern = this.grainCanvas ? this.ctx.createPattern(this.grainCanvas, 'repeat') : null
  }

  setScene(index: number): void {
    const safeIdx = Math.max(0, Math.min(PAPER_SCENE_THEMES.length - 1, index))
    if (safeIdx === this.sceneIndex) {
      return
    }
    this.prevSceneIndex = this.sceneIndex
    this.sceneIndex = safeIdx
    this.sceneMix = 0
    this.sceneEnterAt = performance.now()
    if (safeIdx > 0) {
      this.lampTarget = 1
    }
  }

  igniteLamp(): void {
    this.lampTarget = 1
    this.sceneEnterAt = performance.now()
  }

  setParallax(nx: number, ny: number): void {
    this.pxTarget = Math.max(-1, Math.min(1, nx))
    this.pyTarget = Math.max(-1, Math.min(1, ny))
  }

  start(): void {
    if (this.rafId !== null || !this.ctx) {
      return
    }
    const tick = (now: number) => {
      this.render(now)
      this.rafId = requestAnimationFrame(tick)
    }
    this.rafId = requestAnimationFrame(tick)
  }

  destroy(): void {
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId)
      this.rafId = null
    }
    window.removeEventListener('resize', this.onResize)
  }

  private drawPaperLayer(
    pathFn: (c: CanvasRenderingContext2D, w: number, h: number) => void,
    fillColor: string,
    rimColor: string,
    shadowAlpha: number,
    transFactor: number,
    lx: number,
    ly: number,
    lit: number,
  ): void {
    const ctx = this.ctx
    if (!ctx) {
      return
    }
    const w = this.width
    const h = this.height

    ctx.save()
    ctx.shadowColor = `rgba(3, 5, 12, ${shadowAlpha})`
    ctx.shadowBlur = 22
    ctx.shadowOffsetY = -4
    pathFn(ctx, w, h)
    ctx.fillStyle = fillColor
    ctx.fill()
    ctx.restore()

    ctx.save()
    pathFn(ctx, w, h)
    ctx.clip()
    if (transFactor > 0.02 && lit > 0.05) {
      const radius = w * (0.65 + transFactor * 0.45)
      const grad = ctx.createRadialGradient(lx, ly, 6, lx, ly, radius)
      grad.addColorStop(0, `rgba(255, 224, 162, ${0.42 * transFactor * lit})`)
      grad.addColorStop(0.55, `rgba(255, 194, 118, ${0.14 * transFactor * lit})`)
      grad.addColorStop(1, 'rgba(255, 184, 98, 0)')
      ctx.fillStyle = grad
      ctx.fillRect(0, 0, w, h)
    }
    const shadeGrad = ctx.createLinearGradient(0, h * 0.45, 0, h)
    shadeGrad.addColorStop(0, 'rgba(0,0,0,0)')
    shadeGrad.addColorStop(1, 'rgba(3, 5, 12, 0.34)')
    ctx.fillStyle = shadeGrad
    ctx.fillRect(0, 0, w, h)
    if (this.grainPattern) {
      ctx.fillStyle = this.grainPattern
      ctx.fillRect(0, 0, w, h)
    }
    ctx.restore()

    ctx.save()
    ctx.translate(0, -1.2)
    pathFn(ctx, w, h)
    ctx.strokeStyle = rimColor
    ctx.lineWidth = 1.4
    ctx.stroke()
    ctx.restore()
  }

  private drawPaperWhale(
    cx: number,
    cy: number,
    scale: number,
    fillColor: string,
    rimColor: string,
    lit: number,
    t: number,
  ): void {
    const ctx = this.ctx
    if (!ctx) {
      return
    }
    const bob = Math.sin(t * 1.1) * 5
    ctx.save()
    ctx.translate(cx, cy + bob)
    ctx.scale(scale, scale)

    const whalePath = () => {
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
    }

    ctx.save()
    ctx.shadowColor = 'rgba(4, 8, 22, 0.56)'
    ctx.shadowBlur = 18
    ctx.shadowOffsetY = 8
    whalePath()
    ctx.fillStyle = fillColor
    ctx.fill()
    ctx.restore()

    ctx.save()
    ctx.translate(0, -1.2)
    whalePath()
    ctx.strokeStyle = rimColor
    ctx.lineWidth = 1.6
    ctx.stroke()
    ctx.restore()

    const stars: Array<[number, number, number]> = [
      [-58, -10, 4.2],
      [-24, -24, 5.4],
      [14, -22, 3.8],
      [48, -10, 4.6],
      [82, 8, 3.2],
    ]
    stars.forEach(([sx, sy, sr], idx) => {
      const pulse = 0.65 + 0.35 * Math.sin(t * 2.4 + idx * 1.1)
      const alpha = lit * pulse
      ctx.save()
      const glow = ctx.createRadialGradient(sx, sy, 0.5, sx, sy, sr * 4.5)
      glow.addColorStop(0, `rgba(255, 240, 200, ${0.95 * alpha})`)
      glow.addColorStop(0.35, `rgba(255, 194, 102, ${0.55 * alpha})`)
      glow.addColorStop(1, 'rgba(255, 184, 98, 0)')
      ctx.fillStyle = glow
      ctx.beginPath()
      ctx.arc(sx, sy, sr * 4.5, 0, Math.PI * 2)
      ctx.fill()

      ctx.fillStyle = `rgba(255, 246, 220, ${alpha})`
      ctx.beginPath()
      ctx.moveTo(sx, sy - sr)
      ctx.quadraticCurveTo(sx, sy, sx + sr, sy)
      ctx.quadraticCurveTo(sx, sy, sx, sy + sr)
      ctx.quadraticCurveTo(sx, sy, sx - sr, sy)
      ctx.quadraticCurveTo(sx, sy, sx, sy - sr)
      ctx.fill()
      ctx.restore()
    })
    ctx.restore()
  }

  private drawMidnightCity(
    ox: number,
    oy: number,
    layerColor: string,
    rimColor: string,
    lit: number,
    elapsed: number,
  ): void {
    const ctx = this.ctx
    if (!ctx) {
      return
    }
    const w = this.width
    const h = this.height
    const baseY = h * 0.72 + oy
    const buildings = [
      { x: -16, bw: 62, bh: 155, arch: false },
      { x: 42, bw: 54, bh: 118, arch: true },
      { x: 92, bw: 68, bh: 182, arch: false },
      { x: 158, bw: 56, bh: 136, arch: false },
      { x: 210, bw: 72, bh: 168, arch: true },
      { x: 278, bw: 60, bh: 128, arch: false },
      { x: 334, bw: 78, bh: 170, arch: false },
    ]
    this.drawPaperLayer(
      (c) => {
        c.beginPath()
        c.moveTo(-24, h + 20)
        buildings.forEach((b) => {
          const bx = b.x + ox
          const topY = baseY - b.bh
          c.lineTo(bx, topY + (b.arch ? 16 : 0))
          if (b.arch) {
            c.quadraticCurveTo(bx + b.bw * 0.5, topY - 12, bx + b.bw, topY + 16)
          } else {
            c.lineTo(bx + b.bw, topY)
          }
          c.lineTo(bx + b.bw, baseY)
        })
        c.lineTo(w + 24, h + 20)
        c.closePath()
      },
      layerColor,
      rimColor,
      0.55,
      0.16,
      w * 0.5,
      h * 0.34,
      lit,
    )

    const windows = [
      { x: 116, y: baseY - 148, ww: 18, wh: 26, delay: 0.15, hero: true },
      { x: 14, y: baseY - 102, ww: 10, wh: 14, delay: 0.45, hero: false },
      { x: 238, y: baseY - 122, ww: 11, wh: 16, delay: 0.68, hero: false },
      { x: 300, y: baseY - 88, ww: 9, wh: 13, delay: 0.9, hero: false },
    ]
    windows.forEach((win) => {
      const on = smoothstep(win.delay, win.delay + 0.5, elapsed) * lit
      if (on <= 0.01) {
        return
      }
      const wx = win.x + ox
      const wy = win.y
      ctx.save()
      const glowR = win.hero ? 58 : 26
      const g = ctx.createRadialGradient(wx + win.ww / 2, wy + win.wh / 2, 2, wx + win.ww / 2, wy + win.wh / 2, glowR)
      g.addColorStop(0, `rgba(255, 224, 152, ${(win.hero ? 0.72 : 0.34) * on})`)
      g.addColorStop(0.5, `rgba(255, 178, 86, ${(win.hero ? 0.28 : 0.12) * on})`)
      g.addColorStop(1, 'rgba(255, 170, 74, 0)')
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(wx + win.ww / 2, wy + win.wh / 2, glowR, 0, Math.PI * 2)
      ctx.fill()

      ctx.fillStyle = win.hero ? `rgba(255, 236, 186, ${0.95 * on})` : `rgba(255, 206, 128, ${0.65 * on})`
      ctx.beginPath()
      ctx.roundRect(wx, wy, win.ww, win.wh, [win.ww / 2, win.ww / 2, 2, 2])
      ctx.fill()
      ctx.restore()
    })
  }

  private render(now: number): void {
    const ctx = this.ctx
    if (!ctx) {
      return
    }
    const w = this.width
    const h = this.height
    const t = now * 0.001
    const elapsed = (now - this.sceneEnterAt) * 0.001

    this.sceneMix = Math.min(1, this.sceneMix + 0.045)
    const mix = smoothstep(0, 1, this.sceneMix)
    this.lampLit += (this.lampTarget - this.lampLit) * 0.055
    this.px += (this.pxTarget - this.px) * 0.08
    this.py += (this.pyTarget - this.py) * 0.08

    const prev = PAPER_SCENE_THEMES[this.prevSceneIndex] ?? PAPER_SCENE_THEMES[0]!
    const curr = PAPER_SCENE_THEMES[this.sceneIndex] ?? PAPER_SCENE_THEMES[0]!
    const flicker = 1 + 0.045 * Math.sin(5.1 * t) * Math.sin(2.3 * t)
    const lit = this.lampLit * flicker

    const lx = w * lerp(prev.lightPos[0], curr.lightPos[0], mix) + this.px * 8
    const ly = h * lerp(prev.lightPos[1], curr.lightPos[1], mix) + this.py * 6

    const skyGrad = ctx.createLinearGradient(0, 0, 0, h)
    skyGrad.addColorStop(0, lerpColor(prev.skyTop, curr.skyTop, mix))
    skyGrad.addColorStop(0.68, lerpColor(prev.skyBottom, curr.skyBottom, mix))
    skyGrad.addColorStop(1, lerpColor(prev.cssBg, curr.cssBg, mix))
    ctx.fillStyle = skyGrad
    ctx.fillRect(0, 0, w, h)

    const lr = Math.round(lerp(prev.lightColor[0], curr.lightColor[0], mix))
    const lg = Math.round(lerp(prev.lightColor[1], curr.lightColor[1], mix))
    const lb = Math.round(lerp(prev.lightColor[2], curr.lightColor[2], mix))

    const backGlow = ctx.createRadialGradient(lx, ly, 8, lx, ly, w * 0.92)
    backGlow.addColorStop(0, `rgba(${lr}, ${lg}, ${lb}, ${0.88 * lit})`)
    backGlow.addColorStop(0.32, `rgba(${lr}, ${lg}, ${lb}, ${0.38 * lit})`)
    backGlow.addColorStop(0.7, `rgba(${lr}, ${lg}, ${lb}, ${0.1 * lit})`)
    backGlow.addColorStop(1, 'rgba(0, 0, 0, 0)')
    ctx.fillStyle = backGlow
    ctx.fillRect(0, 0, w, h)

    const moonR = this.sceneIndex === 2 ? 22 : this.sceneIndex === 1 || this.sceneIndex === 3 ? 28 : 42
    ctx.save()
    ctx.beginPath()
    ctx.arc(lx, ly, moonR * (0.85 + 0.15 * lit), 0, Math.PI * 2)
    ctx.fillStyle = `rgba(255, 245, 222, ${0.85 * lit})`
    ctx.shadowColor = `rgba(${lr}, ${lg}, ${lb}, ${0.9 * lit})`
    ctx.shadowBlur = 32
    ctx.fill()
    ctx.restore()

    const c0 = lerpColor(prev.layers[0], curr.layers[0], mix)
    const c1 = lerpColor(prev.layers[1], curr.layers[1], mix)
    const c2 = lerpColor(prev.layers[2], curr.layers[2], mix)
    const c3 = lerpColor(prev.layers[3], curr.layers[3], mix)
    const c4 = lerpColor(prev.layers[4], curr.layers[4], mix)

    const ox0 = this.px * 4
    const oy0 = this.py * 3
    this.drawPaperLayer(
      (c) => {
        c.beginPath()
        c.moveTo(-20, h + 20)
        c.lineTo(-20, h * 0.44 + oy0)
        c.bezierCurveTo(w * 0.22 + ox0, h * 0.35 + oy0, w * 0.46 + ox0, h * 0.48 + oy0, w * 0.72 + ox0, h * 0.38 + oy0)
        c.bezierCurveTo(w * 0.88 + ox0, h * 0.33 + oy0, w + 10, h * 0.41 + oy0, w + 20, h * 0.44 + oy0)
        c.lineTo(w + 20, h + 20)
        c.closePath()
      },
      c0,
      `rgba(255, 230, 184, ${0.42 * lit})`,
      0.28,
      0.82,
      lx,
      ly,
      lit,
    )

    const ox1 = this.px * 9
    const oy1 = this.py * 5
    this.drawPaperLayer(
      (c) => {
        c.beginPath()
        c.moveTo(-24, h + 20)
        c.lineTo(-24, h * 0.54 + oy1)
        c.bezierCurveTo(w * 0.18 + ox1, h * 0.46 + oy1, w * 0.36 + ox1, h * 0.58 + oy1, w * 0.62 + ox1, h * 0.49 + oy1)
        c.bezierCurveTo(w * 0.82 + ox1, h * 0.44 + oy1, w * 0.94 + ox1, h * 0.52 + oy1, w + 24, h * 0.54 + oy1)
        c.lineTo(w + 24, h + 20)
        c.closePath()
      },
      c1,
      `rgba(255, 224, 172, ${0.38 * lit})`,
      0.36,
      0.58,
      lx,
      ly,
      lit,
    )

    if (this.sceneIndex === 0 || this.sceneIndex === 4 || this.sceneIndex === 5) {
      const whaleScale = this.sceneIndex === 0 ? 0.92 : this.sceneIndex === 5 ? 0.8 : 0.78
      const whaleY = this.sceneIndex === 0 ? h * 0.44 : this.sceneIndex === 5 ? h * 0.64 : h * 0.39
      const whaleX = this.sceneIndex === 5 ? w * 0.48 : w * 0.52
      this.drawPaperWhale(whaleX + this.px * 14, whaleY + this.py * 8, whaleScale, c2, `rgba(255, 232, 186, ${0.58 * lit})`, lit, t)
    } else if (this.sceneIndex === 2) {
      this.drawMidnightCity(this.px * 13, this.py * 7, c3, `rgba(255, 212, 148, ${0.32 * lit})`, lit, elapsed)
    }

    const ox2 = this.px * 15
    const oy2 = this.py * 8
    this.drawPaperLayer(
      (c) => {
        c.beginPath()
        c.moveTo(-30, h + 20)
        c.lineTo(-30, h * 0.66 + oy2)
        c.bezierCurveTo(w * 0.25 + ox2, h * 0.58 + oy2, w * 0.52 + ox2, h * 0.72 + oy2, w * 0.8 + ox2, h * 0.62 + oy2)
        c.bezierCurveTo(w * 0.92 + ox2, h * 0.58 + oy2, w + 16, h * 0.64 + oy2, w + 30, h * 0.66 + oy2)
        c.lineTo(w + 30, h + 20)
        c.closePath()
      },
      c2,
      `rgba(255, 220, 162, ${0.34 * lit})`,
      0.44,
      0.36,
      lx,
      ly,
      lit,
    )

    const ox3 = this.px * 21
    const oy3 = this.py * 11
    this.drawPaperLayer(
      (c) => {
        c.beginPath()
        c.moveTo(-32, h + 24)
        c.lineTo(-32, h * 0.77 + oy3)
        c.bezierCurveTo(w * 0.2 + ox3, h * 0.72 + oy3, w * 0.48 + ox3, h * 0.83 + oy3, w * 0.74 + ox3, h * 0.75 + oy3)
        c.bezierCurveTo(w * 0.9 + ox3, h * 0.71 + oy3, w + 18, h * 0.75 + oy3, w + 32, h * 0.77 + oy3)
        c.lineTo(w + 32, h + 24)
        c.closePath()
      },
      c3,
      `rgba(255, 214, 152, ${0.28 * lit})`,
      0.52,
      0.16,
      lx,
      ly,
      lit,
    )

    const ox4 = this.px * 26
    const oy4 = this.py * 12
    this.drawPaperLayer(
      (c) => {
        c.beginPath()
        c.rect(-36, -36, w + 72, h + 72)
        const padX = 16
        const archTop = 76 + oy4 * 0.3
        const archBottom = h - 72 + oy4 * 0.3
        c.moveTo(padX + ox4 * 0.25, archBottom)
        c.lineTo(w - padX + ox4 * 0.25, archBottom)
        c.lineTo(w - padX + ox4 * 0.25, archTop + 88)
        c.bezierCurveTo(w - padX + ox4 * 0.25, archTop, padX + ox4 * 0.25, archTop, padX + ox4 * 0.25, archTop + 88)
        c.closePath()
      },
      c4,
      `rgba(255, 218, 160, ${0.22 * lit})`,
      0.62,
      0.04,
      lx,
      ly,
      lit,
    )

    ctx.save()
    this.motes.forEach((m) => {
      const y = (((m.y - t * m.speed) % 1) + 1) % 1
      const x = m.x + Math.sin(t * 0.7 + m.phase) * 0.03
      const alpha = (0.2 + 0.55 * Math.abs(Math.sin(t * 1.4 + m.phase))) * lit
      ctx.fillStyle = `rgba(255, 232, 178, ${alpha})`
      ctx.beginPath()
      ctx.arc(x * w + this.px * 12, y * h * 0.78 + h * 0.06, m.r, 0, Math.PI * 2)
      ctx.fill()
    })
    ctx.restore()
  }
}
