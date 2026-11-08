<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ name?: string; size?: number }>(), { name: 'grid', size: 18 })
const paths: Record<string, readonly string[]> = {
  grid:['M4 4h6v6H4z','M14 4h6v6H14z','M4 14h6v6H4z','M14 14h6v6H14z'],
  users:['M16 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2','M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z','M22 20v-2a4 4 0 0 0-3-3.87','M16 3.13a4 4 0 0 1 0 7.75'],
  heart:['M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l7.8-7.5a5.5 5.5 0 0 0 1-9Z'],
  bag:['M4 8h16l-1 13H5L4 8Z','M9 9V6a3 3 0 0 1 6 0v3'],
  calendar:['M4 5h16v16H4z','M8 3v4M16 3v4M4 11h16'],
  check:['m5 12 4 4L19 6'],
  chat:['M21 11.5a8.4 8.4 0 0 1-9 8.5 9.2 9.2 0 0 1-4-.9L3 21l1.9-5a9.2 9.2 0 0 1-.9-4A8.4 8.4 0 0 1 12.5 3 8.4 8.4 0 0 1 21 11.5Z'],
  spark:['m12 3 1.8 6.2L20 11l-6.2 1.8L12 19l-1.8-6.2L4 11l6.2-1.8L12 3ZM19 18l.6 1.4L21 20l-1.4.6L19 22l-.6-1.4L17 20l1.4-.6L19 18Z'],
  hospital:['M4 5h16v16H4z','M9 5V3h6v2M12 9v8M8 13h8M7 21v-4M17 21v-4'],
  megaphone:['m3 10 15-6v16L3 14v-4Zm15-1a4 4 0 0 1 0 6M7 15l2 6h4l-3-5'],
  chart:['M3 3v18h18M7 16l4-5 3 2 6-8'],
  wallet:['M3 6h18v15H3zM3 10h18M7 3h13'],
  search:['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm9 2-4-4'],
  bell:['M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4'],
  plus:['M12 5v14M5 12h14'],
  refresh:['M20 11a8 8 0 1 0-3 7M20 4v7h-7'],
  settings:['M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z','M19 12a7 7 0 0 0-.2-1.8L21 8l-2-3-3 1a7 7 0 0 0-3-1L12 2 9 5a7 7 0 0 0-3 1L3 5 1 8l2.2 2.2a7 7 0 0 0 0 3.6L1 16l2 3 3-1a7 7 0 0 0 3 1l3 3 1-3a7 7 0 0 0 3-1l3 1 2-3-2.2-2.2c.1-.6.2-1.2.2-1.8Z'],
  menu:['M3 6h18M3 12h18M3 18h18'],
  download:['M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4'],
  shield:['M12 2 4 5v6c0 5 3.5 8.5 8 11 4.5-2.5 8-6 8-11V5l-8-3Z'],
  link:['M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-2 2M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l2-2'],
  activity:['M3 12h4l3-8 4 16 3-8h4'],
  clipboard:['M4 5h16v17H4z','M8 3h8v5H8z','m8 14 2 2 5-5'],
  file:['M6 3h9l4 4v14H6zM14 3v5h5M9 12h6M9 16h6'],
  close:['M18 6 6 18M6 6l12 12'],
  chevron:['m6 9 6 6 6-6'],
  logout:['M10 17l5-5-5-5M15 12H3M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5'],
  'arrow-right':['M5 12h14m-6-6 6 6-6 6'],
  lock:['M5 10h14v11H5zM8 10V7a4 4 0 0 1 8 0v3'],
}
const iconPaths = computed(() => paths[props.name] ?? paths.grid ?? [])
</script>

<template><svg class="shell-icon" :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path v-for="path in iconPaths" :key="path" :d="path" /></svg></template>
<style scoped>.shell-icon{display:block;flex:none}</style>
