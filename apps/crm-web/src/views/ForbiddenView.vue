<script setup lang="ts">
import { useRouter } from 'vue-router'
import ShellIcon from '../components/shell/ShellIcon.vue'

const router = useRouter()
</script>

<template>
  <section class="status-card" aria-labelledby="forbidden-title">
    <span class="status-icon"><ShellIcon name="shield" :size="34" /></span>
    <p class="status-code">403</p>
    <h1 id="forbidden-title">没有访问权限</h1>
    <p class="status-description">当前账号没有访问该页面的权限。如需使用，请联系管理员分配相应权限。</p>
    <el-button type="primary" @click="router.push('/')">返回工作台</el-button>
  </section>
</template>

<style scoped>
.status-card {
  display: flex;
  width: min(560px, 100%);
  min-height: 390px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  margin: 5vh auto 0;
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
  color: #ba7a35;
  background: #fff6e9;
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
