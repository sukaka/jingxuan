<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string | null | undefined
  customerNo?: string | null | undefined
  size?: number
}>()

const initial = computed(() => {
  const name = props.name?.trim()
  return name ? name.slice(0, 1).toUpperCase() : '客'
})
</script>

<template>
  <span class="crm-avatar" :style="size ? { width: `${size}px`, height: `${size}px` } : undefined">
    {{ initial }}
  </span>
</template>
