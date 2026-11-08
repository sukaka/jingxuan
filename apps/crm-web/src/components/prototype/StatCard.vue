<script setup lang="ts">
import ShellIcon from '../shell/ShellIcon.vue'

defineProps<{ label: string; value: string | number; sub?: string; icon?: string }>()
</script>

<template>
  <article class="proto-stat-card">
    <div class="proto-stat-label">
      <span>{{ label }}</span>
      <span v-if="icon" class="proto-mini-icon"><ShellIcon :name="icon" :size="18" /></span>
    </div>
    <h2 class="number">{{ value }}</h2>
    <div v-if="sub" class="proto-stat-sub">{{ sub }}</div>
  </article>
</template>
