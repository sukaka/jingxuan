<script setup lang="ts">
import type { TagTone } from '../../utils/tagTone'

defineProps<{
  text: string
  tone?: TagTone
}>()
</script>

<template>
  <span class="crm-pill" :class="tone ? `is-${tone}` : ''">{{ text }}</span>
</template>
