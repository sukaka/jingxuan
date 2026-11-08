<script setup lang="ts">
import ShellIcon from '../components/shell/ShellIcon.vue'
</script>

<template>
  <main class="not-found-page">
    <section class="status-card" aria-labelledby="not-found-title">
      <span class="status-icon"><ShellIcon name="file" :size="34" /></span>
      <p class="status-code">404</p>
      <h1 id="not-found-title">页面不存在</h1>
      <p class="status-description">你访问的页面不存在或已被移除，请返回工作台继续操作。</p>
      <el-button type="primary" @click="$router.push('/')">返回工作台</el-button>
    </section>
  </main>
</template>

<style scoped>
.not-found-page {
  display: grid;
  min-height: 100vh;
  min-height: 100dvh;
  place-items: center;
  padding: 24px;
  background: var(--app-page-bg);
}

.status-card {
  display: flex;
  width: min(520px, 100%);
  min-height: 390px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 42px 28px;
  border: 1px solid var(--app-line);
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--app-shadow);
  text-align: center;
}

.status-icon {
  display: grid;
  width: 68px;
  height: 68px;
  place-items: center;
  border-radius: 20px;
  color: #168f85;
  background: #eaf7f4;
}

.status-code {
  margin: 22px 0 0;
  color: #a0afb7;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 2px;
}

.status-card h1 {
  margin: 7px 0 0;
  color: #294455;
  font-size: 23px;
}

.status-description {
  max-width: 390px;
  margin: 13px 0 24px;
  color: #8498a4;
  font-size: 13px;
  line-height: 1.8;
}
</style>
