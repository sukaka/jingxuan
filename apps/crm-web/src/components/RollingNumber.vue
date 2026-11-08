<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  value: number | null | undefined
  formatter?: (value: number) => string
  label?: string
  duration?: number
}>(), {
  formatter: (value: number) => String(value),
  label: '',
  duration: 560,
})

interface DigitChange {
  before: string
  after: string
  changed: boolean
}

const root = ref<HTMLElement>()
const motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
const currentText = ref('')
const rolling = ref(false)
const changes = ref<DigitChange[]>([])
const animations: Animation[] = []
let sequence = 0

const formatted = computed(() => props.value === null || props.value === undefined ? '—' : props.formatter(props.value))
const accessibleText = computed(() => props.label ? `${props.label} ${currentText.value}` : currentText.value)

function cancelAnimations() {
  animations.splice(0).forEach((animation) => animation.cancel())
}

function stopForReducedMotion() {
  if (!motionQuery?.matches) return
  sequence += 1
  cancelAnimations()
  rolling.value = false
  currentText.value = formatted.value
}

watch(formatted, async (next, previous) => {
  const currentSequence = ++sequence
  cancelAnimations()
  if (previous === undefined || motionQuery?.matches || next.length !== previous.length || next === '—' || previous === '—') {
    rolling.value = false
    currentText.value = next
    return
  }
  if (next === previous) {
    rolling.value = false
    currentText.value = next
    return
  }
  changes.value = [...next].map((digit, index) => ({
    before: previous[index] ?? '', after: digit, changed: digit !== previous[index],
  }))
  currentText.value = next
  rolling.value = true
  await nextTick()
  if (currentSequence !== sequence) return
  const active = Array.from(root.value?.querySelectorAll<HTMLElement>('.digit-track') ?? []).map((track) => track.animate([
    { transform: 'translateY(0)' },
    { transform: 'translateY(-50%)' },
  ], {
    duration: props.duration,
    easing: 'cubic-bezier(.16, 1, .3, 1)',
    fill: 'forwards',
  }))
  animations.push(...active)
  try {
    await Promise.all(active.map((animation) => animation.finished))
  } catch {
    return
  }
  if (currentSequence === sequence) {
    rolling.value = false
    animations.splice(0)
  }
}, { immediate: true })

motionQuery?.addEventListener?.('change', stopForReducedMotion)
onBeforeUnmount(() => {
  sequence += 1
  cancelAnimations()
  motionQuery?.removeEventListener?.('change', stopForReducedMotion)
})
</script>

<template>
  <span ref="root" class="rolling-number" :aria-label="accessibleText">
    <span class="sr-only">{{ currentText }}</span>
    <template v-if="rolling">
      <template v-for="(change, index) in changes" :key="index">
        <span v-if="change.changed" class="digit-reel" aria-hidden="true">
          <span class="digit-track"><span>{{ change.before }}</span><span>{{ change.after }}</span></span>
        </span>
        <span v-else class="digit-static" aria-hidden="true">{{ change.after }}</span>
      </template>
    </template>
    <span v-else aria-hidden="true">{{ currentText }}</span>
  </span>
</template>

<style scoped>
.rolling-number { display: inline-flex; align-items: baseline; color: inherit; font-family: 'Outfit', 'Avenir Next', 'Source Han Sans SC', sans-serif; font-weight: 550; font-feature-settings: 'tnum' 1, 'lnum' 1; letter-spacing: .015em; white-space: nowrap; }
.digit-static { display: inline-block; min-width: .27em; text-align: center; }
.digit-reel { display: inline-block; height: 1.15em; overflow: hidden; vertical-align: -.15em; line-height: 1.15; contain: layout paint; }
.digit-track { display: flex; flex-direction: column; align-items: center; will-change: transform; }
.digit-track > span { display: block; min-width: .62em; height: 1.15em; line-height: 1.15; text-align: center; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (prefers-reduced-motion: reduce) {
  .digit-track { will-change: auto; }
}
</style>
