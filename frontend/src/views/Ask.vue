<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, get, key, mutate, statusLabels, taskLabels, time, type Accepted, type Task } from '../api'

const router = useRouter()
const question = ref('')
const busy = ref(false)
const error = ref('')
const recent = ref<Task[]>([])
onMounted(async () => {
  try {
    recent.value = (await get<Task[]>('/tasks')).filter(task => task.type === 'QUESTION' || task.type === 'DRAFT').slice(0, 4)
  } catch (failure) {
    error.value = errorMessage(failure)
  }
})

async function ask() {
  if (busy.value || !question.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    const accepted = await mutate<Accepted>('POST', '/questions', { question: question.value }, key())
    await router.push('/task/' + accepted.taskId)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div><h1>问我的资料</h1><p>按自己的理解提问，打开引用核对原文。</p></div>
    </header>
    <form class="panel composer" @submit.prevent="ask">
      <label for="question">你的问题</label>
      <textarea id="question" v-model="question" rows="5" maxlength="4000"
                placeholder="例如，滑动窗口为什么要用 while？" @keydown.ctrl.enter.prevent="ask" />
      <div class="composer-footer">
        <RouterLink to="/documents" class="quiet-link">查看资料库</RouterLink>
        <button class="primary" :disabled="busy || !question.trim()">{{ busy ? '正在提交…' : '提问 →' }}</button>
      </div>
    </form>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <section class="section-block">
      <div class="section-head"><h2>最近任务</h2><RouterLink to="/tasks">全部任务 →</RouterLink></div>
      <div class="panel rows">
        <p v-if="!recent.length" class="empty">提问或拟稿后，可以在这里继续查看。</p>
        <RouterLink v-for="task in recent" :key="task.id" :to="'/task/' + task.id" class="row">
          <span><strong>{{ taskLabels[task.type] }}</strong><small>{{ time(task.updatedAt) }}</small></span>
          <span class="status" :data-state="task.status">{{ statusLabels[task.status] }}</span>
        </RouterLink>
      </div>
    </section>
  </section>
</template>
