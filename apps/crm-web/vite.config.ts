import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 默认后端为 127.0.0.1:8080，可通过 CRM_API_ORIGIN 指定本地验证实例。
      '/api': {
        target: process.env.CRM_API_ORIGIN ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.CRM_API_ORIGIN ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
    setupFiles: ['tests/setup.ts'],
    maxWorkers: 4,
  },
})
