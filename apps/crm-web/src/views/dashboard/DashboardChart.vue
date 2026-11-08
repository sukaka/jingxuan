<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([BarChart, LineChart, AriaComponent, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ option: EChartsOption; label: string }>()
const chartElement = ref<HTMLDivElement | null>(null)
let chart: ReturnType<typeof echarts.init> | undefined
let observer: ResizeObserver | undefined

function render() {
  if (!chart) return
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  chart.setOption({ ...props.option, animation: !reducedMotion }, { notMerge: true })
}

watch(() => props.option, render)

onMounted(() => {
  if (!chartElement.value) return
  chart = echarts.init(chartElement.value, undefined, { renderer: 'canvas' })
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartElement.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="chartElement" class="dashboard-chart" role="img" :aria-label="label" data-testid="dashboard-chart" />
</template>
