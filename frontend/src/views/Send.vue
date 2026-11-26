<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorMessage, get, statusLabels, time, type Send } from '../api'
import { poll } from '../poll'

const id = String(useRoute().params.id)
const send = ref<Send>()
const error = ref('')
poll(async () => {
  try {
    send.value = await get<Send>('/sends/' + id)
    error.value = ''
    return ['QUEUED', 'SENDING', 'VERIFY'].includes(send.value.status)
  } catch (failure) {
    error.value = errorMessage(failure)
    return false
  }
}, 2600)
</script>

<template>
  <section class="page narrow">
    <RouterLink class="back-link" to="/tasks">← 任务记录</RouterLink>
    <header class="page-head"><div><h1>发送状态</h1></div></header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <div v-if="send" class="panel detail" aria-live="polite">
      <span class="status" :data-state="send.status">{{ statusLabels[send.status] }}</span>
      <p v-if="send.error" class="notice">{{ send.error }}</p>
      <p class="muted">草稿修订 {{ send.revision }} · {{ time(send.updatedAt) }}</p>
      <RouterLink :to="'/draft/' + send.draftId" class="button secondary">查看草稿</RouterLink>
    </div>
    <p v-else-if="!error" role="status">正在读取发送记录…</p>
  </section>
</template>
