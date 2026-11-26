<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  getPublicAnnualReport,
  isValidAnnualShareKey,
  sendPublicAnnualTelemetry,
  submitPublicAnnualBookingIntent,
} from '../api/annualReport'
import type { AnnualTelemetryEventType, PublicAnnualReport } from '../api/annualReport'
import { ApiError } from '../types/api'
import { formatAnnualYuan, renderAnnualKeepsakePoster } from '../utils/annualPosterExporter'
import { PAPER_SCENE_THEMES, PaperLightboxEngine } from '../utils/paperLightboxEngine'

const TOTAL_SCENES = 6
const HEARTBEAT_INTERVAL_MS = 12_000
const MAX_DELTA_MS = 30_000

const route = useRoute()
const shareKey = computed(() => String(route.params.shareKey ?? ''))
const report = ref<PublicAnnualReport | null>(null)
const loading = ref(true)
const errorMessage = ref('')

const canvasRef = ref<HTMLCanvasElement | null>(null)
let engine: PaperLightboxEngine | null = null

const sceneIndex = ref(0)
const ignited = ref(false)
const hideAmount = ref(false)
const seasonFilter = ref<'ALL' | '春' | '夏' | '秋' | '冬'>('ALL')
const favView = ref<'dimensions' | 'top3'>('dimensions')
const selectedOrder = ref<PublicAnnualReport['orders'][number] | null>(null)
const bookingModalOpen = ref(false)
const bookingSubmitting = ref(false)
const bookingFeedback = ref('')
const posterModalOpen = ref(false)
const posterDataUrl = ref('')

// ---- 真实前台可见停留计时器与埋点会话 ----
const sessionKey = `ses_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`
let isVisible = typeof document !== 'undefined' ? document.visibilityState !== 'hidden' : true
let lastTickMs = typeof performance !== 'undefined' ? performance.now() : Date.now()
let heartbeatTimer: number | null = null
let touchStartX = 0
let touchStartY = 0
let touchInScrollable = false

const robots = document.createElement('meta')
robots.name = 'robots'
robots.content = 'noindex,nofollow,noarchive'
document.head.appendChild(robots)

const currentTheme = computed(() => PAPER_SCENE_THEMES[sceneIndex.value] ?? PAPER_SCENE_THEMES[0]!)

const themeStyle = computed(() => ({
  '--scene-bg': currentTheme.value.cssBg,
  '--scene-accent': currentTheme.value.accent,
  '--scene-accent-soft': currentTheme.value.accentSoft,
  '--scene-card-bg': currentTheme.value.cardBg,
  '--scene-card-border': currentTheme.value.cardBorder,
  '--scene-rim': currentTheme.value.rim,
  '--scene-spill': currentTheme.value.spill,
}))

const filteredOrders = computed(() => {
  if (!report.value) {
    return []
  }
  if (seasonFilter.value === 'ALL') {
    return report.value.orders
  }
  return report.value.orders.filter((item) => item.season === seasonFilter.value)
})

const clockTicks = computed(() => {
  return Array.from({ length: 24 }, (_, h) => {
    const ang = (h / 24) * Math.PI * 2 - Math.PI / 2
    const r1 = h % 6 === 0 ? 73 : 77
    const r2 = 82
    return {
      h,
      x1: (110 + Math.cos(ang) * r1).toFixed(1),
      y1: (110 + Math.sin(ang) * r1).toFixed(1),
      x2: (110 + Math.cos(ang) * r2).toFixed(1),
      y2: (110 + Math.sin(ang) * r2).toFixed(1),
      night: h >= 21 || h <= 2,
      major: h % 6 === 0,
    }
  })
})

const clockDots = computed(() => {
  if (!report.value) {
    return []
  }
  return report.value.midnight.hourDistribution.map((pt, idx) => {
    const ang = (pt.hour / 24) * Math.PI * 2 - Math.PI / 2
    const r = 72
    return {
      id: idx,
      cx: (110 + Math.cos(ang) * r).toFixed(1),
      cy: (110 + Math.sin(ang) * r).toFixed(1),
      r: pt.latest ? 5 : Number((2.8 + pt.weight * 1.8).toFixed(1)),
      latest: pt.latest,
    }
  })
})

function detectClientDevice(): 'MOBILE' | 'DESKTOP' | 'WECHAT' | 'UNKNOWN' {
  if (typeof navigator === 'undefined') {
    return 'UNKNOWN'
  }
  const ua = navigator.userAgent.toLowerCase()
  if (ua.includes('micromessenger')) {
    return 'WECHAT'
  }
  if (/iphone|ipad|ipod|android|mobile/.test(ua)) {
    return 'MOBILE'
  }
  return 'DESKTOP'
}

function consumeVisibleDelta(): number {
  const now = typeof performance !== 'undefined' ? performance.now() : Date.now()
  if (!isVisible) {
    lastTickMs = now
    return 0
  }
  const raw = Math.max(0, Math.round(now - lastTickMs))
  lastTickMs = now
  return Math.min(raw, MAX_DELTA_MS)
}

function flushTelemetry(eventType: AnnualTelemetryEventType, detail?: string, useBeacon = false): void {
  if (!report.value) {
    return
  }
  const deltaDurationMs = consumeVisibleDelta()
  void sendPublicAnnualTelemetry(
    shareKey.value,
    {
      sessionKey,
      eventType,
      sceneIndex: sceneIndex.value + 1,
      deltaDurationMs,
      clientDevice: detectClientDevice(),
      detail,
    },
    useBeacon,
  ).catch(() => {
    // 埋点上报失败不阻断客户正常浏览体验
  })
}

function igniteCover(): void {
  ignited.value = true
  engine?.igniteLamp()
  flushTelemetry('IGNITE_LAMP')
}

function goToScene(index: number): void {
  const target = Math.max(0, Math.min(TOTAL_SCENES - 1, index))
  if (target === sceneIndex.value) {
    return
  }
  if (target > 0 && !ignited.value) {
    ignited.value = true
    engine?.igniteLamp()
  }
  // 切幕前结算上一幕停留时长
  flushTelemetry('SCENE_CHANGE', `to_${target + 1}`)
  sceneIndex.value = target
  engine?.setScene(target)
}

function openOrderDetail(order: PublicAnnualReport['orders'][number]): void {
  selectedOrder.value = order
  flushTelemetry('VIEW_ORDER', order.maskedOrderNo)
}

function togglePrivacy(): void {
  hideAmount.value = !hideAmount.value
  flushTelemetry('TOGGLE_PRIVACY', hideAmount.value ? 'hidden' : 'visible')
  if (posterModalOpen.value && report.value) {
    posterDataUrl.value = renderAnnualKeepsakePoster(report.value, hideAmount.value)
  }
}

function openPosterModal(): void {
  if (!report.value) {
    return
  }
  posterDataUrl.value = renderAnnualKeepsakePoster(report.value, hideAmount.value)
  posterModalOpen.value = true
  flushTelemetry('GENERATE_POSTER', hideAmount.value ? 'private' : 'normal')
}

async function handleBookButler(): Promise<void> {
  if (!report.value || bookingSubmitting.value) {
    return
  }
  bookingSubmitting.value = true
  bookingFeedback.value = ''
  try {
    const res = await submitPublicAnnualBookingIntent(shareKey.value, {
      sessionKey,
      note: `客户在 ${report.value.reportYear} 年度盘点 H5 点击预约专属管家`,
    })
    bookingFeedback.value = res.message
    bookingModalOpen.value = true
  } catch (err) {
    bookingFeedback.value =
      err instanceof ApiError || err instanceof Error ? err.message : '预约意向提交失败，请稍后重试'
    bookingModalOpen.value = true
  } finally {
    bookingSubmitting.value = false
  }
}

function handleVisibilityChange(): void {
  if (document.visibilityState === 'hidden') {
    flushTelemetry('VISIBILITY_HIDE', undefined, true)
    isVisible = false
  } else {
    isVisible = true
    lastTickMs = typeof performance !== 'undefined' ? performance.now() : Date.now()
  }
}

function handlePageHide(): void {
  flushTelemetry('PAGE_HIDE', undefined, true)
  isVisible = false
}

function handlePointerMove(e: PointerEvent): void {
  if (!engine || window.innerWidth <= 0 || window.innerHeight <= 0) {
    return
  }
  engine.setParallax((e.clientX / window.innerWidth) * 2 - 1, (e.clientY / window.innerHeight) * 2 - 1)
}

function handleTouchStart(e: TouchEvent): void {
  if (e.touches.length !== 1) {
    return
  }
  const touch = e.touches[0]!
  touchStartX = touch.clientX
  touchStartY = touch.clientY
  touchInScrollable = Boolean((e.target as HTMLElement | null)?.closest('.scrollable-zone'))
}

function handleTouchEnd(e: TouchEvent): void {
  if (!e.changedTouches.length) {
    return
  }
  const touch = e.changedTouches[0]!
  const dx = touch.clientX - touchStartX
  const dy = touch.clientY - touchStartY
  if (Math.abs(dx) > 54 && Math.abs(dx) > Math.abs(dy) * 1.2) {
    goToScene(sceneIndex.value + (dx < 0 ? 1 : -1))
  } else if (!touchInScrollable && Math.abs(dy) > 64 && Math.abs(dy) > Math.abs(dx) * 1.3) {
    goToScene(sceneIndex.value + (dy < 0 ? 1 : -1))
  }
}

function handleKeydown(e: KeyboardEvent): void {
  if (selectedOrder.value || bookingModalOpen.value || posterModalOpen.value) {
    if (e.key === 'Escape') {
      selectedOrder.value = null
      bookingModalOpen.value = false
      posterModalOpen.value = false
    }
    return
  }
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
    goToScene(sceneIndex.value + 1)
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
    goToScene(sceneIndex.value - 1)
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    if (!isValidAnnualShareKey(shareKey.value)) {
      errorMessage.value = '年度报告链接无效或格式不正确'
      return
    }
    const data = await getPublicAnnualReport(shareKey.value)
    report.value = data
    document.title = `${data.reportYear} 光采轨迹 · ${data.displayName} - 鲸选美学`
    await nextTick()
    if (canvasRef.value) {
      engine?.destroy()
      engine = new PaperLightboxEngine(canvasRef.value)
      engine.start()
    }
    lastTickMs = typeof performance !== 'undefined' ? performance.now() : Date.now()
    flushTelemetry('SESSION_OPEN')
    heartbeatTimer = window.setInterval(() => {
      if (isVisible) {
        flushTelemetry('HEARTBEAT')
      }
    }, HEARTBEAT_INTERVAL_MS)
  } catch (err) {
    errorMessage.value = err instanceof ApiError || err instanceof Error ? err.message : '年度报告暂时无法打开'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('pagehide', handlePageHide)
  window.addEventListener('pointermove', handlePointerMove, { passive: true })
  window.addEventListener('keydown', handleKeydown)
  void load()
})

onUnmounted(() => {
  robots.remove()
  if (heartbeatTimer !== null) {
    window.clearInterval(heartbeatTimer)
  }
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('pagehide', handlePageHide)
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('keydown', handleKeydown)
  engine?.destroy()
})
</script>

<template>
  <div class="annual-h5-root" :data-scene="sceneIndex" :style="themeStyle" data-testid="public-annual-report">
    <div class="room-ambient" aria-hidden="true" />

    <main class="lightbox-stage">
      <div class="lamp-spill" aria-hidden="true" />
      <div class="lightbox-frame" :class="{ 'is-keepsake': sceneIndex === 5 }">
        <div class="wood-bevel" aria-hidden="true" />

        <div
          class="lightbox-cavity"
          @touchstart.passive="handleTouchStart"
          @touchend.passive="handleTouchEnd"
        >
          <canvas ref="canvasRef" class="paper-canvas" aria-hidden="true" />
          <div class="cavity-vignette" aria-hidden="true" />

          <!-- 加载态 -->
          <section v-if="loading" class="state-overlay" aria-live="polite">
            <div class="brand-seal-lg">鲸</div>
            <h1>正在点亮你的年度光采灯箱…</h1>
            <p>正在安全读取鲸选美学专属档案</p>
          </section>

          <!-- 错误 / 停用态 -->
          <section v-else-if="errorMessage" class="state-overlay" data-testid="public-annual-error">
            <div class="brand-seal-lg is-muted">暂</div>
            <h1>年度报告暂时无法查看</h1>
            <p role="alert">{{ errorMessage }}</p>
            <small>链接可能已停用或失效，请联系分享此页面的专属管家。</small>
          </section>

          <!-- 正式 6 幕纸雕灯影内容 -->
          <template v-else-if="report">
            <header class="hud-top">
              <div class="story-progress" role="tablist" aria-label="章节进度">
                <button
                  v-for="idx in TOTAL_SCENES"
                  :key="idx"
                  type="button"
                  class="progress-seg"
                  :class="{ 'is-done': idx - 1 < sceneIndex, 'is-active': idx - 1 === sceneIndex }"
                  :aria-label="`前往第 ${idx} 幕`"
                  @click="goToScene(idx - 1)"
                >
                  <span />
                </button>
              </div>
              <div class="hud-row">
                <div class="brand-lockup">
                  <span class="brand-seal">鲸</span>
                  <div class="brand-text">
                    <span class="brand-cn">鲸选美学</span>
                    <span class="brand-en">WHALE AESTHETICS · {{ report.reportYear }}</span>
                  </div>
                </div>
                <button
                  type="button"
                  class="mini-privacy-btn"
                  data-testid="toggle-privacy-btn"
                  @click="togglePrivacy"
                >
                  {{ hideAmount ? '已隐金额' : '隐藏金额' }}
                </button>
              </div>
            </header>

            <div class="scenes-viewport">
              <!-- 第 1 幕 · 启幕灯箱 -->
              <section class="scene" :class="{ 'is-active': sceneIndex === 0 }" aria-label="启幕相识">
                <div class="scene-inner scene-cover">
                  <div class="cover-eyebrow">
                    <span class="seal-tag">{{ report.tierLabel }}</span>
                    <span class="mono-id">{{ report.memberNo }}</span>
                  </div>

                  <div class="cover-headline">
                    <p class="salutation">{{ report.copy.salutation }}</p>
                    <h1 class="main-title">
                      <span>光采轨迹</span>
                      <small>{{ report.copy.coverSubTitle }}</small>
                    </h1>
                  </div>

                  <div v-if="!ignited" class="cover-ignite-box">
                    <button
                      type="button"
                      class="ignite-btn"
                      data-testid="ignite-lamp-btn"
                      @click="igniteCover"
                    >
                      <span class="ignite-core">✦</span>
                      <span class="ignite-text">{{ report.copy.igniteBtnText }}</span>
                    </button>
                    <p class="ignite-hint">{{ report.copy.igniteHint }}</p>
                  </div>

                  <div v-else class="cover-revealed">
                    <div class="paper-plaque">
                      <div class="plaque-row">
                        <span class="plaque-label">初次与鲸选相遇</span>
                        <span class="plaque-value mono">{{ report.firstMeetDate }}</span>
                      </div>
                      <div class="plaque-divider" />
                      <div class="plaque-hero">
                        <span class="hero-prefix">相伴的第</span>
                        <strong class="hero-num mono">{{ report.daysTogether }}</strong>
                        <span class="hero-suffix">天</span>
                      </div>
                      <p class="plaque-note">{{ report.copy.firstMeetNote }}</p>
                    </div>
                    <button type="button" class="primary-paper-btn" @click="goToScene(1)">
                      <span>{{ report.copy.coverNextBtn }}</span>
                      <span>→</span>
                    </button>
                  </div>
                </div>
              </section>

              <!-- 第 2 幕 · 四季购买轨迹 -->
              <section class="scene" :class="{ 'is-active': sceneIndex === 1 }" aria-label="四季购买轨迹">
                <div class="scene-inner">
                  <div class="chapter-head">
                    <span class="chapter-kicker">CHAPTER 01 · ANNUAL LEDGER</span>
                    <h2 class="chapter-title">{{ report.copy.scene1Title }}</h2>
                  </div>

                  <div class="kpi-strip">
                    <div class="kpi-cell">
                      <span class="kpi-label">年度有效订单</span>
                      <strong class="kpi-val mono">{{ report.orderCount }}<em>笔</em></strong>
                    </div>
                    <div class="kpi-cell">
                      <span class="kpi-label">年度净投入</span>
                      <strong class="kpi-val mono" data-testid="annual-net-spend">
                        {{ formatAnnualYuan(report.netSpendFen, hideAmount) }}
                      </strong>
                    </div>
                    <div class="kpi-cell">
                      <span class="kpi-label">臻选品类</span>
                      <strong class="kpi-val mono">{{ report.categoryCount }}<em>类</em></strong>
                    </div>
                  </div>

                  <div class="season-tabs" role="tablist">
                    <button
                      v-for="tab in (['ALL', '春', '夏', '秋', '冬'] as const)"
                      :key="tab"
                      type="button"
                      class="season-tab"
                      :class="{ 'is-active': seasonFilter === tab }"
                      @click="seasonFilter = tab"
                    >
                      {{ tab === 'ALL' ? '全年' : tab }}
                    </button>
                  </div>

                  <div class="timeline-scroll scrollable-zone">
                    <div v-if="!filteredOrders.length" class="paper-plaque empty-note">
                      该季节暂无已确认下单记录，切换其他季节看看吧
                    </div>
                    <button
                      v-for="order in filteredOrders"
                      :key="order.maskedOrderNo + order.date"
                      type="button"
                      class="order-card"
                      @click="openOrderDetail(order)"
                    >
                      <div class="order-date-badge">
                        <span class="order-date-md mono">{{ order.md }}</span>
                        <span class="order-date-time mono">{{ order.time }}</span>
                      </div>
                      <div class="order-main">
                        <div class="order-title">{{ order.title }}</div>
                        <div class="order-sub">
                          <span class="order-tag">{{ order.season }} · {{ order.category }}</span>
                          <span>{{ order.channel }}</span>
                        </div>
                      </div>
                      <div class="order-right">
                        <span class="order-amount mono">{{ formatAnnualYuan(order.amountFen, hideAmount) }}</span>
                        <span class="order-status" :class="{ 'is-open': order.open }">{{ order.statusText }}</span>
                      </div>
                    </button>
                  </div>
                  <p class="tap-tip">轻点任意一张日期纸签，展开该笔订单与项目快照明细</p>
                </div>
              </section>

              <!-- 第 3 幕 · 深夜光影时刻 -->
              <section class="scene" :class="{ 'is-active': sceneIndex === 2 }" aria-label="深夜光影时刻">
                <div class="scene-inner scene-midnight">
                  <div class="chapter-head">
                    <span class="chapter-kicker">CHAPTER 02 · MIDNIGHT LAMP</span>
                    <h2 class="chapter-title">{{ report.copy.scene2Title }}</h2>
                  </div>

                  <div class="clock-dial-wrap">
                    <svg class="circadian-svg" viewBox="0 0 220 220" aria-label="24小时购美时刻分布">
                      <defs>
                        <radialGradient id="annualWindowGlowGrad" cx="50%" cy="50%" r="50%">
                          <stop offset="0%" stop-color="#ffd589" stop-opacity="0.45" />
                          <stop offset="100%" stop-color="#ffb862" stop-opacity="0" />
                        </radialGradient>
                      </defs>
                      <circle cx="110" cy="110" r="96" fill="rgba(7, 13, 32, 0.42)" />
                      <circle cx="110" cy="110" r="88" fill="url(#annualWindowGlowGrad)" />
                      <circle cx="110" cy="110" r="82" fill="none" stroke="rgba(255,214,150,0.22)" stroke-width="1.2" stroke-dasharray="2 5" />
                      <circle cx="110" cy="110" r="62" fill="none" stroke="rgba(255,214,150,0.16)" stroke-width="1" />
                      <line
                        v-for="tick in clockTicks"
                        :key="tick.h"
                        :x1="tick.x1"
                        :y1="tick.y1"
                        :x2="tick.x2"
                        :y2="tick.y2"
                        :stroke="tick.night ? 'rgba(255, 198, 112, 0.58)' : 'rgba(255, 224, 178, 0.22)'"
                        :stroke-width="tick.major ? '1.8' : '1'"
                      />
                      <template v-for="dot in clockDots" :key="dot.id">
                        <circle v-if="dot.latest" :cx="dot.cx" :cy="dot.cy" r="11" fill="rgba(255, 184, 98, 0.34)" />
                        <circle :cx="dot.cx" :cy="dot.cy" :r="dot.r" :fill="dot.latest ? '#fff3cf' : '#ffbf69'" />
                      </template>
                      <text x="110" y="20" text-anchor="middle" class="clock-axis">00:00</text>
                      <text x="204" y="114" text-anchor="middle" class="clock-axis">06</text>
                      <text x="110" y="210" text-anchor="middle" class="clock-axis">12:00</text>
                      <text x="16" y="114" text-anchor="middle" class="clock-axis">18</text>
                    </svg>
                    <div class="clock-center-readout">
                      <span class="clock-sub">最晚光影时刻</span>
                      <strong class="clock-time mono">{{ report.midnight.time }}</strong>
                      <span class="clock-date mono">{{ report.midnight.date }}</span>
                    </div>
                  </div>

                  <div class="paper-plaque midnight-card">
                    <div class="midnight-badge-row">
                      <span class="amber-dot" />
                      <span>{{ report.midnight.eventType }}</span>
                    </div>
                    <p class="midnight-story">{{ report.copy.midnightStory }}</p>
                    <div class="midnight-meta-row">
                      <span>{{ report.midnight.peakLabel }}</span>
                    </div>
                  </div>
                </div>
              </section>

              <!-- 第 4 幕 · 年度挚爱项目 -->
              <section class="scene" :class="{ 'is-active': sceneIndex === 3 }" aria-label="年度挚爱项目">
                <div class="scene-inner scene-favorite">
                  <div class="chapter-head">
                    <span class="chapter-kicker">CHAPTER 03 · SIGNATURE RITUAL</span>
                    <h2 class="chapter-title">{{ report.copy.scene3Title }}</h2>
                  </div>

                  <div class="paper-plaque">
                    <div class="fav-top-row">
                      <span class="seal-tag seal-rose">年度挚爱 TOP 1</span>
                      <span class="fav-beat">{{ report.favorite.beatPercent }}</span>
                    </div>
                    <h3 class="fav-name">{{ report.favorite.projectName }}</h3>
                    <p class="fav-subtitle">{{ report.copy.favDesc }}</p>
                    <div class="fav-chips">
                      <span v-for="tag in report.favorite.tags" :key="tag" class="fav-chip"># {{ tag }}</span>
                    </div>
                  </div>

                  <div class="paper-plaque radar-card">
                    <div class="radar-head">
                      <span>{{ report.reportYear }} 美学能量分布</span>
                      <div class="mini-seg" role="tablist">
                        <button
                          type="button"
                          class="seg-btn"
                          :class="{ 'is-active': favView === 'dimensions' }"
                          @click="favView = 'dimensions'"
                        >
                          四大维度
                        </button>
                        <button
                          type="button"
                          class="seg-btn"
                          :class="{ 'is-active': favView === 'top3' }"
                          @click="favView = 'top3'"
                        >
                          挚爱榜单
                        </button>
                      </div>
                    </div>

                    <div v-if="favView === 'dimensions'" class="dimensions-list">
                      <div v-for="dim in report.favorite.dimensions" :key="dim.name" class="dim-row">
                        <div class="dim-label-row">
                          <span>{{ dim.name }}</span>
                          <strong class="mono">{{ dim.percent }}%</strong>
                        </div>
                        <div class="dim-track">
                          <div class="dim-fill" :style="{ width: `${sceneIndex === 3 ? dim.percent : 12}%` }" />
                        </div>
                      </div>
                    </div>
                    <div v-else class="top3-list">
                      <div v-for="row in report.favorite.top3" :key="row.rank + row.name" class="top3-row">
                        <span class="top3-rank mono">{{ row.rank }}</span>
                        <span class="top3-name">{{ row.name }}</span>
                        <strong class="top3-count mono">{{ row.countText }}</strong>
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <!-- 第 5 幕 · 履约蜕变与权益宝库 -->
              <section class="scene" :class="{ 'is-active': sceneIndex === 4 }" aria-label="履约蜕变与权益宝库">
                <div class="scene-inner scene-vault">
                  <div class="chapter-head">
                    <span class="chapter-kicker">CHAPTER 04 · JOURNEY &amp; VAULT</span>
                    <h2 class="chapter-title">{{ report.copy.scene4Title }}</h2>
                  </div>

                  <div class="vault-stats-grid">
                    <div class="vault-tile">
                      <span class="vault-tile-label">正式到院核销</span>
                      <strong class="vault-tile-num mono">{{ report.redeemCount }}<em>次</em></strong>
                    </div>
                    <div class="vault-tile">
                      <span class="vault-tile-label">打卡臻选机构</span>
                      <strong class="vault-tile-num mono">{{ report.institutionCount }}<em>家</em></strong>
                    </div>
                    <div class="vault-tile">
                      <span class="vault-tile-label">专属管家跟进</span>
                      <strong class="vault-tile-num mono">{{ report.butlerResponseCount }}<em>次</em></strong>
                    </div>
                  </div>

                  <div class="paper-plaque vault-treasure">
                    <div class="vault-treasure-head">
                      <div>
                        <span class="teal-badge">{{ report.copy.vaultBadge }}</span>
                        <h3 class="vault-remaining-title">
                          剩余 <strong class="mono">{{ report.remainingEntitlements.totalCount }}</strong> 次焕美权益
                        </h3>
                      </div>
                      <span class="vault-expire mono">有效期至 {{ report.remainingEntitlements.expireDate }}</span>
                    </div>

                    <ul v-if="report.remainingEntitlements.items.length" class="vault-items">
                      <li v-for="item in report.remainingEntitlements.items" :key="item.name" class="vault-item">
                        <div>
                          <strong>{{ item.name }}</strong>
                          <span class="vault-item-sub">{{ item.subtitle }}</span>
                        </div>
                        <span class="vault-item-cnt mono">{{ item.countText }}</span>
                      </li>
                    </ul>
                    <p v-else class="empty-note">本年度已发放权益均已使用完毕，可随时联系管家规划新一年焕美计划。</p>

                    <button
                      type="button"
                      class="teal-cta-btn"
                      data-testid="book-butler-btn"
                      :disabled="bookingSubmitting"
                      @click="handleBookButler"
                    >
                      <span>{{ bookingSubmitting ? '正在通知专属管家…' : report.copy.vaultCta }}</span>
                      <span>→</span>
                    </button>
                    <p class="vault-compliance-note">点击仅向专属管家发送预约意向待办，正式核销以合作机构到院确认为准</p>
                  </div>
                </div>
              </section>

              <!-- 第 6 幕 · 年度美学人格 & 纪念海报 -->
              <section class="scene" :class="{ 'is-active': sceneIndex === 5 }" aria-label="年度私享灯箱海报">
                <div class="scene-inner scene-keepsake">
                  <div class="keepsake-box-card">
                    <div class="keepsake-top">
                      <span class="keepsake-year mono">{{ report.reportYear }} · WHALE AESTHETICS</span>
                      <span class="seal-stamp">{{ report.sealText }}</span>
                    </div>
                    <p class="keepsake-owner">{{ report.copy.posterHeading }}</p>
                    <h2 class="keepsake-persona">{{ report.copy.personaTitle }}</h2>
                    <p class="keepsake-quote">{{ report.copy.personaQuote }}</p>

                    <div class="keepsake-metrics">
                      <div class="km-item">
                        <span>相伴鲸选</span>
                        <strong class="mono">{{ report.daysTogether }} 天</strong>
                      </div>
                      <div class="km-item">
                        <span>全年订单</span>
                        <strong class="mono">{{ report.orderCount }} 笔</strong>
                      </div>
                      <div class="km-item">
                        <span>年度挚爱</span>
                        <strong>{{ report.favorite.projectName }}</strong>
                      </div>
                      <div class="km-item">
                        <span>年度投入</span>
                        <strong class="mono">{{ formatAnnualYuan(report.netSpendFen, hideAmount) }}</strong>
                      </div>
                    </div>

                    <div class="keepsake-butler-note">
                      <span class="butler-sign">{{ report.copy.butlerSign }}</span>
                      <span>{{ hideAmount ? '隐私晒图模式' : '已显示金额' }}</span>
                    </div>
                  </div>

                  <div class="keepsake-actions">
                    <button
                      type="button"
                      class="primary-paper-btn"
                      data-testid="generate-poster-btn"
                      @click="openPosterModal"
                    >
                      <span>✦ 生成纸雕灯影纪念海报（PNG）</span>
                    </button>
                    <div class="keepsake-secondary-row">
                      <button type="button" class="ghost-paper-btn" @click="togglePrivacy">
                        {{ hideAmount ? '显示消费金额' : '切换隐藏金额' }}
                      </button>
                      <button type="button" class="ghost-paper-btn" @click="goToScene(0)">
                        重温光采轨迹
                      </button>
                    </div>
                  </div>
                </div>
              </section>
            </div>

            <footer class="hud-bottom">
              <button
                type="button"
                class="nav-arrow"
                :disabled="sceneIndex === 0"
                @click="goToScene(sceneIndex - 1)"
              >
                ← 上一幕
              </button>
              <span class="scene-counter mono">0{{ sceneIndex + 1 }} / 0{{ TOTAL_SCENES }}</span>
              <button
                type="button"
                class="nav-arrow"
                :disabled="sceneIndex === TOTAL_SCENES - 1"
                @click="goToScene(sceneIndex + 1)"
              >
                {{ sceneIndex === TOTAL_SCENES - 1 ? '已至末页' : '下一幕 →' }}
              </button>
            </footer>

            <!-- 订单快照抽屉 -->
            <div v-if="selectedOrder" class="sheet-backdrop" @click.self="selectedOrder = null">
              <div class="paper-sheet">
                <div class="sheet-header">
                  <div>
                    <span class="sheet-kicker mono">订单号 {{ selectedOrder.maskedOrderNo }}</span>
                    <h3 class="sheet-title">{{ selectedOrder.title }}</h3>
                  </div>
                  <button type="button" class="sheet-close" @click="selectedOrder = null">✕</button>
                </div>
                <div class="sheet-meta-grid">
                  <div class="meta-box">
                    <span>下单支付时间</span>
                    <strong class="mono">{{ selectedOrder.date }} {{ selectedOrder.time }}</strong>
                  </div>
                  <div class="meta-box">
                    <span>有效实付金额</span>
                    <strong class="mono">{{ formatAnnualYuan(selectedOrder.amountFen, hideAmount) }}</strong>
                  </div>
                  <div class="meta-box">
                    <span>订购来源</span>
                    <strong>{{ selectedOrder.channel }}</strong>
                  </div>
                  <div class="meta-box">
                    <span>履约状态</span>
                    <strong>{{ selectedOrder.statusText }}</strong>
                  </div>
                </div>
                <ul class="sheet-sku-list">
                  <li v-for="sku in selectedOrder.skus" :key="sku.name" class="sheet-sku-item">
                    <span>{{ sku.name }}</span>
                    <strong class="mono">{{ sku.qtyText }}</strong>
                  </li>
                </ul>
                <p class="sheet-footnote">以上数据来自鲸选美学 CRM 真实冻结快照，历史交易事实不可篡改。</p>
              </div>
            </div>

            <!-- 预约管家确认弹窗 -->
            <div v-if="bookingModalOpen" class="sheet-backdrop" @click.self="bookingModalOpen = false">
              <div class="paper-sheet" data-testid="booking-feedback-sheet">
                <div class="sheet-header">
                  <div>
                    <span class="sheet-kicker">CONCIERGE REQUEST · 预约意向</span>
                    <h3 class="sheet-title">预约提醒状态</h3>
                  </div>
                  <button type="button" class="sheet-close" @click="bookingModalOpen = false">✕</button>
                </div>
                <p class="sheet-body-text">{{ bookingFeedback }}</p>
                <button type="button" class="primary-paper-btn" @click="bookingModalOpen = false">好的</button>
              </div>
            </div>

            <!-- 海报预览与下载弹窗 -->
            <div v-if="posterModalOpen" class="sheet-backdrop" @click.self="posterModalOpen = false">
              <div class="paper-sheet poster-sheet">
                <div class="sheet-header">
                  <div>
                    <span class="sheet-kicker">KEEPSAKE POSTER · 750×1334</span>
                    <h3 class="sheet-title">{{ report.reportYear }} 纸雕灯影纪念海报</h3>
                  </div>
                  <button type="button" class="sheet-close" @click="posterModalOpen = false">✕</button>
                </div>
                <div class="poster-preview-wrap">
                  <img :src="posterDataUrl" alt="鲸选美学年度纸雕灯影纪念海报">
                </div>
                <div class="poster-modal-actions">
                  <a
                    class="primary-paper-btn"
                    :href="posterDataUrl"
                    :download="`jingxuan-${report.reportYear}-keepsake.png`"
                  >
                    下载 PNG 海报
                  </a>
                  <button type="button" class="ghost-paper-btn" @click="togglePrivacy">
                    {{ hideAmount ? '海报显示消费金额' : '海报隐藏消费金额' }}
                  </button>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.annual-h5-root {
  --font-serif: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "STSong", Georgia, serif;
  --font-mono: "SF Mono", "JetBrains Mono", ui-monospace, Menlo, Consolas, monospace;
  position: relative;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: #0a090c;
  color: #fdf4e3;
  user-select: none;
}
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.room-ambient { position: fixed; inset: 0; background: radial-gradient(62% 52% at 50% 48%, var(--scene-spill) 0%, rgba(12, 11, 16, 0.92) 66%, #070609 100%); pointer-events: none; transition: background 0.8s ease; }
.lightbox-stage { position: relative; z-index: 2; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; padding: 20px 16px; }
.lamp-spill { position: absolute; width: min(440px, 88vw); height: 120px; bottom: max(4px, calc(50% - 430px)); background: radial-gradient(ellipse at center, var(--scene-spill) 0%, transparent 72%); filter: blur(22px); pointer-events: none; }
.lightbox-frame { position: relative; width: min(408px, 100%); height: min(812px, calc(100vh - 40px)); max-height: 844px; border-radius: 26px; padding: 10px; background: linear-gradient(145deg, #2d1e15 0%, #1d120c 46%, #130b07 100%); box-shadow: 0 28px 80px rgba(0, 0, 0, 0.78), 0 0 65px -14px var(--scene-spill); transition: transform 0.6s ease; }
.lightbox-frame.is-keepsake { transform: scale(0.975); }
.wood-bevel { position: absolute; inset: 5px; border-radius: 21px; border: 1px solid rgba(255, 210, 154, 0.13); pointer-events: none; z-index: 25; }
.lightbox-cavity { position: relative; width: 100%; height: 100%; border-radius: 17px; overflow: hidden; background: var(--scene-bg); box-shadow: inset 0 0 0 1px rgba(255, 226, 178, 0.14); }
.paper-canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; z-index: 1; }
.cavity-vignette { position: absolute; inset: 0; z-index: 2; pointer-events: none; background: radial-gradient(90% 72% at 50% 42%, transparent 48%, rgba(4, 6, 14, 0.52) 100%), linear-gradient(180deg, rgba(5, 8, 18, 0.52) 0%, transparent 18%, transparent 74%, rgba(5, 7, 15, 0.78) 100%); }
.state-overlay { position: relative; z-index: 12; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 28px; gap: 10px; }
.brand-seal-lg { width: 48px; height: 48px; border-radius: 12px; display: grid; place-items: center; font-family: var(--font-serif); font-size: 22px; font-weight: 700; background: #c94635; color: #fff6e6; box-shadow: 0 0 24px rgba(201, 70, 53, 0.45); }
.brand-seal-lg.is-muted { background: rgba(255, 224, 174, 0.14); border: 1px solid rgba(255, 224, 174, 0.35); box-shadow: none; }
.state-overlay h1 { margin: 4px 0 0; font-family: var(--font-serif); font-size: 20px; }
.state-overlay p { margin: 0; font-size: 13px; color: rgba(253, 244, 227, 0.76); line-height: 1.6; }
.state-overlay small { font-size: 11px; color: rgba(253, 244, 227, 0.52); }
.hud-top { position: absolute; top: 0; left: 0; right: 0; z-index: 15; padding: calc(12px + env(safe-area-inset-top, 0px)) 16px 8px; }
.story-progress { display: grid; grid-template-columns: repeat(6, 1fr); gap: 5px; margin-bottom: 10px; }
.progress-seg { border: 0; padding: 5px 0; background: transparent; cursor: pointer; }
.progress-seg span { display: block; height: 2.5px; border-radius: 999px; background: rgba(255, 238, 210, 0.24); }
.progress-seg.is-done span { background: rgba(255, 206, 134, 0.72); }
.progress-seg.is-active span { background: #ffd38e; box-shadow: 0 0 10px rgba(255, 194, 102, 0.9); }
.hud-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.brand-lockup { display: flex; align-items: center; gap: 8px; }
.brand-seal { width: 24px; height: 24px; border-radius: 5px; display: grid; place-items: center; font-family: var(--font-serif); font-size: 13px; font-weight: 700; color: #fff6e6; background: #c94635; }
.brand-text { display: flex; flex-direction: column; line-height: 1.15; }
.brand-cn { font-family: var(--font-serif); font-size: 13px; letter-spacing: 0.12em; }
.brand-en { font-family: var(--font-mono); font-size: 8px; letter-spacing: 0.1em; color: rgba(253, 244, 227, 0.58); }
.mini-privacy-btn { border: 1px solid rgba(255, 222, 174, 0.3); background: rgba(9, 13, 25, 0.62); color: #fdf4e3; font-size: 11px; padding: 4px 10px; border-radius: 999px; cursor: pointer; backdrop-filter: blur(8px); }
.scenes-viewport { position: relative; z-index: 10; width: 100%; height: 100%; }
.scene { position: absolute; inset: 0; padding: calc(68px + env(safe-area-inset-top, 0px)) 18px calc(62px + env(safe-area-inset-bottom, 0px)); display: flex; flex-direction: column; opacity: 0; transform: translateY(16px); pointer-events: none; transition: opacity 0.5s ease, transform 0.55s ease; }
.scene.is-active { opacity: 1; transform: translateY(0); pointer-events: auto; }
.scene-inner { width: 100%; height: 100%; display: flex; flex-direction: column; }
.paper-plaque { background: var(--scene-card-bg); border: 1px solid var(--scene-card-border); border-top-color: var(--scene-rim); border-radius: 16px; padding: 14px 16px; backdrop-filter: blur(12px); box-shadow: 0 14px 32px rgba(0, 0, 0, 0.42); }
.seal-tag { display: inline-flex; padding: 3px 9px; border-radius: 4px; font-size: 11px; letter-spacing: 0.08em; color: #fff6e9; background: rgba(201, 70, 53, 0.88); }
.seal-rose { background: rgba(196, 68, 96, 0.88); }
.chapter-head { margin-bottom: 11px; }
.chapter-kicker { display: block; font-family: var(--font-mono); font-size: 9.5px; letter-spacing: 0.18em; color: var(--scene-accent); margin-bottom: 4px; }
.chapter-title { margin: 0; font-family: var(--font-serif); font-size: 19px; font-weight: 600; line-height: 1.32; color: #fff7ea; }
.primary-paper-btn { width: 100%; border: 1px solid rgba(255, 232, 188, 0.58); background: linear-gradient(135deg, rgba(255, 194, 102, 0.92), rgba(230, 148, 66, 0.94)); color: #1c1008; font-family: var(--font-serif); font-weight: 700; font-size: 14px; letter-spacing: 0.08em; padding: 12px 16px; border-radius: 12px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; cursor: pointer; text-decoration: none; }
.ghost-paper-btn { flex: 1; border: 1px solid rgba(255, 224, 176, 0.28); background: rgba(14, 18, 32, 0.65); color: #fdf4e3; font-size: 12.5px; padding: 10px 12px; border-radius: 11px; cursor: pointer; }
.scene-cover { justify-content: space-between; padding-top: 6px; }
.cover-eyebrow { display: flex; justify-content: space-between; align-items: center; }
.mono-id { font-family: var(--font-mono); font-size: 10.5px; color: rgba(253, 244, 227, 0.66); }
.cover-headline { margin-top: 10px; }
.salutation { margin: 0 0 6px; font-family: var(--font-serif); font-size: 15px; color: #ffe2ad; }
.main-title { margin: 0; display: flex; flex-direction: column; gap: 6px; font-family: var(--font-serif); }
.main-title span { font-size: 34px; font-weight: 600; letter-spacing: 0.22em; color: #fff8eb; }
.main-title small { font-size: 13px; font-weight: 400; letter-spacing: 0.16em; color: rgba(255, 222, 170, 0.85); }
.cover-ignite-box { margin-top: auto; margin-bottom: 12px; display: flex; flex-direction: column; align-items: center; gap: 10px; }
.ignite-btn { width: 100%; border: 1px solid rgba(255, 214, 146, 0.45); background: rgba(11, 18, 38, 0.76); color: #fff6e4; border-radius: 16px; padding: 18px 16px; display: flex; flex-direction: column; align-items: center; gap: 9px; cursor: pointer; backdrop-filter: blur(10px); }
.ignite-core { width: 44px; height: 44px; border-radius: 50%; display: grid; place-items: center; font-size: 18px; color: #1f1208; background: radial-gradient(circle, #fff3d1 0%, #ffc46b 68%, #d98c3a 100%); box-shadow: 0 0 24px rgba(255, 198, 108, 0.85); }
.ignite-text { font-family: var(--font-serif); font-size: 15px; letter-spacing: 0.08em; }
.ignite-hint { margin: 0; font-size: 11px; color: rgba(246, 232, 209, 0.6); }
.cover-revealed { margin-top: auto; display: flex; flex-direction: column; gap: 12px; }
.plaque-row { display: flex; justify-content: space-between; font-size: 12.5px; color: rgba(246, 232, 209, 0.76); }
.plaque-value { color: #ffd896; font-weight: 600; }
.plaque-divider { height: 1px; margin: 10px 0; background: linear-gradient(90deg, transparent, rgba(255, 218, 158, 0.34), transparent); }
.plaque-hero { display: flex; align-items: baseline; justify-content: center; gap: 6px; margin: 4px 0 8px; }
.hero-num { font-size: 38px; line-height: 1; font-weight: 700; color: #ffe0a3; }
.plaque-note { margin: 0; font-size: 12.5px; line-height: 1.6; text-align: center; color: rgba(250, 238, 219, 0.84); }
.kpi-strip { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 10px; }
.kpi-cell { background: var(--scene-card-bg); border: 1px solid var(--scene-card-border); border-radius: 12px; padding: 9px 10px; }
.kpi-label { display: block; font-size: 10.5px; color: rgba(246, 232, 209, 0.72); margin-bottom: 3px; }
.kpi-val { font-size: 15px; color: #ffe2a8; }
.kpi-val em { font-style: normal; font-size: 11px; margin-left: 2px; }
.season-tabs { display: flex; gap: 6px; margin-bottom: 9px; }
.season-tab { flex: 1; border: 1px solid rgba(255, 218, 168, 0.22); background: rgba(24, 11, 7, 0.56); color: rgba(253, 240, 220, 0.72); font-size: 12px; padding: 5px 0; border-radius: 9px; cursor: pointer; }
.season-tab.is-active { background: rgba(255, 198, 118, 0.24); border-color: rgba(255, 214, 146, 0.65); color: #fff6e6; }
.timeline-scroll { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.empty-note { text-align: center; font-size: 12px; color: rgba(253, 240, 218, 0.76); margin: 0; }
.order-card { width: 100%; text-align: left; display: grid; grid-template-columns: 58px 1fr auto; align-items: center; gap: 10px; padding: 11px 12px; border-radius: 13px; border: 1px solid rgba(255, 224, 174, 0.22); background: rgba(26, 13, 9, 0.72); color: #fff6e6; cursor: pointer; }
.order-date-badge { display: flex; flex-direction: column; align-items: center; padding: 5px 4px; border-radius: 9px; background: rgba(255, 198, 118, 0.14); }
.order-date-md { font-size: 12px; font-weight: 700; color: #ffe0a3; }
.order-date-time { font-size: 10px; color: rgba(255, 226, 184, 0.76); }
.order-main { min-width: 0; }
.order-title { font-family: var(--font-serif); font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.order-sub { margin-top: 3px; display: flex; gap: 6px; font-size: 11px; color: rgba(246, 228, 202, 0.72); }
.order-tag { font-size: 10px; padding: 1px 6px; border-radius: 999px; background: rgba(255, 184, 98, 0.16); color: #ffd697; }
.order-right { text-align: right; display: flex; flex-direction: column; align-items: flex-end; gap: 3px; }
.order-amount { font-size: 13px; font-weight: 700; color: #ffe3ad; }
.order-status { font-size: 10px; color: rgba(154, 232, 220, 0.88); }
.order-status.is-open { color: #ffc878; }
.tap-tip { margin: 8px 0 0; text-align: center; font-size: 11px; color: rgba(255, 232, 194, 0.68); }
.scene-midnight { justify-content: space-between; }
.clock-dial-wrap { position: relative; width: 210px; height: 210px; margin: 4px auto 0; }
.circadian-svg { width: 100%; height: 100%; overflow: visible; }
.clock-axis { fill: rgba(255, 226, 178, 0.68); font-family: var(--font-mono); font-size: 9px; }
.clock-center-readout { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; pointer-events: none; }
.clock-sub { font-size: 10.5px; color: rgba(255, 224, 174, 0.76); }
.clock-time { font-size: 32px; font-weight: 700; color: #ffdfa0; line-height: 1.15; }
.clock-date { font-size: 11px; color: rgba(255, 232, 194, 0.76); }
.midnight-card { margin-top: auto; }
.midnight-badge-row { display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: #ffd591; margin-bottom: 8px; }
.amber-dot { width: 8px; height: 8px; border-radius: 50%; background: #ffb862; box-shadow: 0 0 12px #ffb862; }
.midnight-story { margin: 0 0 10px; font-family: var(--font-serif); font-size: 13.5px; line-height: 1.68; }
.midnight-meta-row { padding-top: 8px; border-top: 1px solid rgba(255, 220, 168, 0.16); font-size: 11px; color: rgba(246, 232, 209, 0.72); }
.scene-favorite { gap: 12px; }
.fav-top-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.fav-beat { font-size: 11px; color: #ffd4b0; }
.fav-name { margin: 0 0 5px; font-family: var(--font-serif); font-size: 21px; }
.fav-subtitle { margin: 0 0 10px; font-size: 12.5px; line-height: 1.5; color: rgba(252, 234, 226, 0.82); }
.fav-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.fav-chip { font-size: 11px; padding: 3px 9px; border-radius: 999px; background: rgba(255, 212, 184, 0.14); color: #ffe6d5; }
.radar-card { margin-top: auto; }
.radar-head { display: flex; justify-content: space-between; align-items: center; font-size: 12.5px; margin-bottom: 11px; }
.mini-seg { display: inline-flex; padding: 2px; border-radius: 8px; background: rgba(255, 255, 255, 0.06); }
.seg-btn { border: 0; background: transparent; color: rgba(255, 234, 220, 0.68); font-size: 11px; padding: 3px 8px; border-radius: 6px; cursor: pointer; }
.seg-btn.is-active { background: rgba(255, 198, 134, 0.25); color: #fff8ee; }
.dimensions-list, .top3-list { display: flex; flex-direction: column; gap: 9px; }
.dim-label-row { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px; }
.dim-track { height: 8px; border-radius: 999px; background: rgba(12, 6, 10, 0.65); overflow: hidden; }
.dim-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #e5838f 0%, #ffc982 100%); transition: width 0.7s ease; }
.top3-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 10px; border-radius: 10px; background: rgba(255, 224, 206, 0.07); }
.top3-rank { color: #ffcf8a; font-size: 12px; width: 24px; }
.top3-name { flex: 1; font-family: var(--font-serif); font-size: 13px; }
.top3-count { color: #ffd79f; font-size: 12px; }
.scene-vault { gap: 11px; }
.vault-stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.vault-tile { background: rgba(6, 26, 28, 0.74); border: 1px solid rgba(154, 228, 220, 0.24); border-radius: 13px; padding: 10px; text-align: center; }
.vault-tile-label { display: block; font-size: 10.5px; color: rgba(212, 242, 238, 0.76); margin-bottom: 4px; }
.vault-tile-num { font-size: 20px; color: #ffe3a6; }
.vault-tile-num em { font-style: normal; font-size: 11px; margin-left: 2px; }
.vault-treasure { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
.vault-treasure-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 8px; }
.teal-badge { display: inline-block; font-size: 10.5px; padding: 2px 8px; border-radius: 999px; background: rgba(15, 148, 139, 0.34); color: #b7f5ee; margin-bottom: 5px; }
.vault-remaining-title { margin: 0; font-family: var(--font-serif); font-size: 17px; }
.vault-remaining-title strong { color: #ffe09e; font-size: 22px; }
.vault-expire { font-size: 10px; color: rgba(208, 238, 234, 0.72); }
.vault-items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.vault-item { display: flex; justify-content: space-between; align-items: center; padding: 8px 10px; border-radius: 10px; background: rgba(255, 255, 255, 0.06); font-size: 12.5px; }
.vault-item-sub { display: block; font-size: 10.5px; color: rgba(212, 240, 236, 0.68); margin-top: 2px; }
.vault-item-cnt { font-weight: 700; color: #ffe1a0; }
.teal-cta-btn { width: 100%; border: 1px solid rgba(164, 244, 234, 0.52); background: linear-gradient(135deg, #13a49a, #0b7a73); color: #f6fffe; font-family: var(--font-serif); font-size: 13.5px; padding: 12px 14px; border-radius: 12px; display: flex; justify-content: space-between; cursor: pointer; }
.vault-compliance-note { margin: 0; font-size: 10.5px; color: rgba(212, 238, 234, 0.62); text-align: center; }
.scene-keepsake { justify-content: space-between; gap: 12px; }
.keepsake-box-card { padding: 18px 16px 14px; border-radius: 16px; background: linear-gradient(180deg, rgba(14, 18, 34, 0.78), rgba(16, 12, 10, 0.88)); border: 1px solid rgba(255, 214, 150, 0.38); }
.keepsake-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.keepsake-year { font-size: 10px; letter-spacing: 0.14em; color: rgba(255, 224, 174, 0.75); }
.seal-stamp { font-family: var(--font-serif); font-size: 11px; padding: 2px 7px; border-radius: 4px; background: #c94635; color: #fff6e8; }
.keepsake-owner { margin: 0 0 4px; font-size: 12px; color: rgba(255, 232, 198, 0.78); }
.keepsake-persona { margin: 0 0 8px; font-family: var(--font-serif); font-size: 24px; color: #ffe3ab; }
.keepsake-quote { margin: 0 0 12px; font-family: var(--font-serif); font-size: 12.5px; line-height: 1.6; color: rgba(253, 244, 228, 0.86); }
.keepsake-metrics { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; margin-bottom: 11px; }
.km-item { padding: 8px 10px; border-radius: 10px; background: rgba(255, 226, 182, 0.07); }
.km-item span { display: block; font-size: 10px; color: rgba(253, 236, 210, 0.66); margin-bottom: 2px; }
.km-item strong { font-size: 13px; }
.keepsake-butler-note { display: flex; justify-content: space-between; padding-top: 9px; border-top: 1px dashed rgba(255, 216, 158, 0.24); font-size: 10.5px; color: rgba(253, 236, 210, 0.72); }
.butler-sign { font-family: var(--font-serif); color: #ffd99c; }
.keepsake-actions { display: flex; flex-direction: column; gap: 8px; }
.keepsake-secondary-row { display: flex; gap: 8px; }
.hud-bottom { position: absolute; bottom: 0; left: 0; right: 0; z-index: 15; padding: 10px 18px calc(14px + env(safe-area-inset-bottom, 0px)); display: flex; justify-content: space-between; align-items: center; background: linear-gradient(180deg, transparent, rgba(5, 7, 15, 0.72) 55%); }
.nav-arrow { border: 1px solid rgba(255, 222, 174, 0.26); background: rgba(12, 16, 30, 0.62); color: #fdf4e3; font-size: 12px; padding: 6px 12px; border-radius: 999px; cursor: pointer; }
.nav-arrow:disabled { opacity: 0.28; cursor: not-allowed; }
.scene-counter { font-size: 11px; letter-spacing: 0.14em; color: rgba(255, 228, 186, 0.74); }
.sheet-backdrop { position: absolute; inset: 0; z-index: 30; background: rgba(4, 6, 12, 0.74); backdrop-filter: blur(6px); display: flex; align-items: flex-end; padding: 12px; }
.paper-sheet { width: 100%; max-height: 85%; display: flex; flex-direction: column; background: linear-gradient(180deg, #1b1720, #120f15); border: 1px solid rgba(255, 218, 162, 0.32); border-radius: 18px; padding: 14px 16px 16px; color: #fff6e8; }
.sheet-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; margin-bottom: 12px; }
.sheet-kicker { display: block; font-size: 10px; color: #ffcf86; margin-bottom: 3px; }
.sheet-title { margin: 0; font-family: var(--font-serif); font-size: 16px; }
.sheet-close { border: 1px solid rgba(255, 222, 174, 0.24); background: rgba(255, 255, 255, 0.06); color: #fff6e8; width: 28px; height: 28px; border-radius: 50%; cursor: pointer; }
.sheet-meta-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; margin-bottom: 12px; }
.meta-box { padding: 8px 10px; border-radius: 10px; background: rgba(255, 255, 255, 0.05); }
.meta-box span { display: block; font-size: 10px; color: rgba(246, 232, 209, 0.64); margin-bottom: 2px; }
.meta-box strong { font-size: 12.5px; color: #ffe4b0; }
.sheet-sku-list { list-style: none; margin: 0 0 12px; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.sheet-sku-item { display: flex; justify-content: space-between; font-size: 12.5px; padding: 8px 10px; border-radius: 9px; background: rgba(255, 198, 118, 0.08); }
.sheet-footnote, .sheet-body-text { margin: 0 0 12px; font-size: 12px; line-height: 1.6; color: rgba(246, 232, 209, 0.78); }
.poster-preview-wrap { flex: 1; min-height: 0; display: flex; justify-content: center; align-items: center; margin-bottom: 12px; background: rgba(0, 0, 0, 0.4); border-radius: 12px; padding: 8px; }
.poster-preview-wrap img { max-height: 410px; max-width: 100%; border-radius: 8px; }
.poster-modal-actions { display: flex; flex-direction: column; gap: 8px; }
@media (max-width: 540px) {
  .lamp-spill, .wood-bevel { display: none; }
  .lightbox-stage { padding: 0; }
  .lightbox-frame { width: 100%; height: 100%; max-height: none; border-radius: 0; padding: 0; box-shadow: none; background: var(--scene-bg); }
  .lightbox-frame.is-keepsake { transform: none; padding: 12px; background: linear-gradient(145deg, #2d1e15, #170e09); }
  .lightbox-cavity { border-radius: 0; }
  .lightbox-frame.is-keepsake .lightbox-cavity { border-radius: 14px; }
}
</style>
