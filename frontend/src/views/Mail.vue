<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, get, key, mutate, time, type Accepted, type Thread } from '../api'
import { poll } from '../poll'

const router = useRouter()
const threads = ref<Thread[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const refreshError = ref('')

poll(async () => {
  try {
    threads.value = await get<Thread[]>('/threads')
    refreshError.value = ''
  } catch (failure) { refreshError.value = errorMessage(failure) }
  finally { loading.value = false }
  return true
}, 15000)

async function sync() {
  busy.value = true
  error.value = ''
  try {
    const task = await mutate<Accepted>('POST', '/mail/sync', undefined, key())
    await router.push('/task/' + task.taskId)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally { busy.value = false }
}
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div><h1>邮件往来</h1><p>打开一组往来，选择要回复的来信。</p></div>
      <button class="secondary" :disabled="busy" @click="sync">收取新邮件</button>
    </header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <div class="panel rows">
      <p v-if="loading" class="empty" role="status">正在读取往来…</p>
      <p v-else-if="!threads.length" class="empty">暂无来信。</p>
      <RouterLink v-for="thread in threads" :key="thread.id" class="row" :to="'/threads/' + thread.id">
        <span class="row-fill"><strong>{{ thread.subject || '无主题' }}</strong><small>{{ time(thread.updatedAt) }}</small></span>
        <span class="muted">{{ thread.messageCount }} 封</span><span aria-hidden="true">→</span>
      </RouterLink>
    </div>
  </section>
</template>
