<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getPublicH5Card, isValidShareKey } from '../api/h5Card'
import type { PublicH5Card } from '../api/h5Card'
import { ApiError } from '../types/api'

const route = useRoute()
const card = ref<PublicH5Card | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const robots = document.createElement('meta')
robots.name = 'robots'
robots.content = 'noindex,nofollow,noarchive'
document.head.appendChild(robots)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const shareKey = String(route.params.shareKey ?? '')
    if (!isValidShareKey(shareKey)) {
      errorMessage.value = '分享链接无效或格式不正确'
      return
    }
    card.value = await getPublicH5Card(shareKey)
    document.title = `${card.value.title} - 鲸选美学`
  } catch (error) {
    errorMessage.value = error instanceof ApiError || error instanceof Error ? error.message : '页面暂时无法打开'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
onUnmounted(() => { robots.remove() })
</script>

<template>
  <main class="public-h5-page" data-testid="public-h5-card">
    <header class="public-brand"><span>鲸</span><div><strong>鲸选美学</strong><small>服务项目介绍</small></div></header>
    <section v-if="loading" class="public-state" aria-live="polite"><span class="loading-line" /><h1>正在打开项目介绍</h1><p>正在读取已分享的公开内容…</p></section>
    <section v-else-if="errorMessage" class="public-state" data-testid="public-h5-error"><span class="state-mark">暂</span><h1>介绍暂时无法查看</h1><p role="alert">{{ errorMessage }}</p><small>链接可能已停用或失效，请联系分享此页面的专属管家。</small></section>
    <article v-else-if="card" class="public-card-content">
      <img class="public-cover" :src="card.imageUrl" alt="项目介绍封面">
      <div class="public-copy"><span class="project-label">{{ card.projectName }}</span><h1>{{ card.title }}</h1><p class="public-summary">{{ card.summary }}</p></div>
      <section class="public-detail"><h2>服务介绍</h2><p>{{ card.content }}</p></section>
      <aside class="public-notice"><strong>沟通提示</strong><p>本页为服务项目介绍，不构成医疗诊断、疗效承诺或订单凭证。如需咨询，请联系分享此页面的专属管家。</p></aside>
    </article>
    <footer>鲸选美学 · 仅展示本次分享的公开内容</footer>
  </main>
</template>

<style scoped>
.public-h5-page { box-sizing: border-box; width: min(100%, 560px); min-height: 100vh; min-height: 100dvh; margin: 0 auto; padding: 24px 22px 28px; color: #2c4b3d; background: #f4f7f4; font-size: 14px; }
.public-brand { display: flex; align-items: center; gap: 11px; margin-bottom: 24px; }
.public-brand > span { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 12px; color: #eff7f1; background: #295d48; font-size: 19px; font-weight: 700; }
.public-brand > div { display: grid; gap: 3px; }
.public-brand strong { color: #315642; font-size: 14px; letter-spacing: 1px; }
.public-brand small { color: #819488; font-size: 10px; letter-spacing: 2px; }
.public-cover { display: block; width: 100%; aspect-ratio: 8 / 5; object-fit: cover; border-radius: 15px 15px 0 0; }
.public-copy { padding: 25px 23px 23px; border-inline: 1px solid #e0e9e2; background: #fcfefc; }
.project-label { color: #6f8e7c; font-size: 11px; font-weight: 650; letter-spacing: 1px; }
.public-copy h1 { margin: 13px 0 0; color: #294c3a; font-size: clamp(24px, 6vw, 32px); font-weight: 700; letter-spacing: -.6px; line-height: 1.4; overflow-wrap: anywhere; }
.public-summary { margin: 14px 0 0; color: #718b7c; font-size: 13px; line-height: 1.9; white-space: pre-wrap; overflow-wrap: anywhere; }
.public-detail { padding: 25px 23px; border: 1px solid #e0e9e2; border-top: 1px solid #edf2ee; background: #fcfefc; }
.public-detail h2 { margin: 0 0 13px; color: #3d614d; font-size: 14px; }
.public-detail p { margin: 0; color: #526f5e; font-size: 14px; line-height: 2; white-space: pre-wrap; overflow-wrap: anywhere; }
.public-notice { margin-top: 18px; padding: 16px 17px; border: 1px solid #e2e9df; border-radius: 10px; background: #edf3ed; }
.public-notice strong { color: #5a765f; font-size: 12px; }
.public-notice p { margin: 7px 0 0; color: #789080; font-size: 11px; line-height: 1.9; }
.public-h5-page footer { margin-top: 26px; color: #96a69b; font-size: 10px; text-align: center; }
.public-state { display: flex; min-height: 65vh; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.public-state h1 { margin: 19px 0 10px; color: #365a47; font-size: 24px; }
.public-state p { max-width: 350px; margin: 0; color: #788f80; line-height: 1.8; }
.public-state small { max-width: 350px; margin-top: 14px; color: #94a499; font-size: 11px; line-height: 1.8; }
.state-mark { display: grid; width: 44px; height: 44px; place-items: center; border: 1px solid #d5e2d8; border-radius: 50%; color: #799383; font-size: 16px; }
.loading-line { width: 44px; height: 3px; border-radius: 2px; background: #70967e; animation: public-pulse 1s ease-in-out infinite alternate; }
@keyframes public-pulse { from { opacity: .35; } to { opacity: 1; } }
@media (max-width: 360px) { .public-h5-page { padding-inline: 16px; } .public-copy, .public-detail { padding-inline: 18px; } }
@media (prefers-reduced-motion: reduce) { .loading-line { animation: none; } }
</style>
