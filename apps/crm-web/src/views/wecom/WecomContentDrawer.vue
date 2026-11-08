<script setup lang="ts">
defineProps<{
  modelValue: boolean
  title: string
  loading: boolean
  content: string | null
  error: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="min(680px, 96vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="warning"
      :closable="false"
      title="私有内容仅在后端本次授权、数据范围与有效同意校验成功后展示；关闭后应清除。"
      class="content-compliance-tip"
    />
    <div v-loading="loading" class="content-body" aria-live="polite">
      <el-alert v-if="error" type="error" :closable="false" :title="`内容读取失败：${error}`" data-testid="wecom-content-error" />
      <el-empty v-else-if="!loading && content === null" description="尚未读取授权内容；元数据不包含聊天全文" />
      <pre v-else-if="!loading" class="content-text" data-testid="wecom-content-text">{{ content }}</pre>
    </div>
    <template #footer><el-button @click="emit('update:modelValue', false)">关闭并清除</el-button></template>
  </el-drawer>
</template>

<style scoped>
.content-compliance-tip { margin-bottom: 16px; }
.content-body { min-height: 180px; }
.content-text { margin: 0; padding: 18px; border: 1px solid #dce7e1; border-radius: 10px; background: #f4f8f5; color: #183d32; font-family: inherit; font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
