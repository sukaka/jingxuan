<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getFileDownload, getFileMetadata } from '../../api/file'
import { tradingErrorText } from '../trading/tradingUi'

const props = defineProps<{ fileId: number }>()
const loading = ref(false)

async function openFile() {
  loading.value = true
  try {
    const metadata = await getFileMetadata(props.fileId)
    if (metadata.scanStatus !== 'CLEAN') {
      ElMessage.warning('文件尚未通过安全扫描，不能预览或下载')
      return
    }
    const download = await getFileDownload(props.fileId)
    window.open(download.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error(tradingErrorText(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-button text size="small" :loading="loading" @click="openFile">查看文件</el-button>
</template>
