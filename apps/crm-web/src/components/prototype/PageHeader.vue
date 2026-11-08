<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <header class="proto-page-head">
    <div class="proto-page-heading">
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="proto-head-actions"><slot name="actions" /></div>
  </header>
</template>
