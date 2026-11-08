<script setup lang="ts">
defineProps<{ title?: string; subtitle?: string; padded?: boolean }>()
</script>

<template>
  <section class="proto-card" :class="{ 'proto-card-padded': padded }">
    <header v-if="title || $slots.actions" class="proto-card-head">
      <div>
        <h2 v-if="title">{{ title }}</h2>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="proto-head-actions"><slot name="actions" /></div>
    </header>
    <slot />
  </section>
</template>
