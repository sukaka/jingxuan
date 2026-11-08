<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './components/shell/AppShell.vue'

const route = useRoute()
const isStandalonePage = computed(() => Boolean(route.meta.public || route.meta.display))
</script>

<template>
  <RouterView v-if="isStandalonePage" />
  <AppShell v-else>
    <RouterView />
  </AppShell>
</template>
