<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorMessage, get, statusLabels, taskLabels, time, type Task } from '../api'
import { poll } from '../poll'

const id = String(useRoute().params.id)
const task = ref<Task>()
const error = ref('')
const labels: Record<string, [string, string]> = {
  answer: ['/answer/', '打开回答'], draft: ['/draft/', '打开草稿'],
  documents: ['/documents', '返回资料库'], threads: ['/mail', '返回邮件'],
}
const next = computed(() => task.value?.resultType ? labels[task.value.resultType] : undefined)

poll(async () => {
  try {
    task.value = await get<Task>('/tasks/' + id)
    error.value = ''
    return task.value.status === 'QUEUED' || task.value.status === 'RUNNING'
  } catch (failure) {
    error.value = errorMessage(failure)
    return false
  }
})
</script>

<template>
  <section class="page narrow">
    <RouterLink class="back-link" to="/tasks">← 任务记录</RouterLink>
    <header class="page-head"><div><h1>{{ task ? taskLabels[task.type] : '任务进度' }}</h1></div></header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <div v-if="task" class="panel detail" aria-live="polite">
      <span class="status" :data-state="task.status">{{ statusLabels[task.status] }}</span>
      <h2>{{ task.step }}</h2>
      <p v-if="task.error" class="problem">{{ task.error }}</p>
      <p class="muted">{{ time(task.updatedAt) }}</p>
      <RouterLink v-if="next" class="button primary" :to="next[0] + (task.resultId ?? '')">{{ next[1] }} →</RouterLink>
      <RouterLink v-else-if="task.status === 'FAILED' && task.type === 'INDEX'" class="button secondary" to="/documents">查看资料状态</RouterLink>
    </div>
    <p v-else-if="!error" role="status">正在查看任务…</p>
  </section>
</template>
