<script setup lang="ts">
import { computed, ref } from 'vue'
import { errorMessage, get, statusLabels, taskLabels, time, type Send, type Task } from '../api'
import { poll } from '../poll'

const tasks = ref<Task[]>([])
const sends = ref<Send[]>([])
const filter = ref('study')
const error = ref('')
const loading = ref(true)
const displayed = computed(() => tasks.value.filter(item => filter.value === 'all'
  || item.type === 'QUESTION' || item.type === 'DRAFT'))

poll(async () => {
  try {
    [tasks.value, sends.value] = await Promise.all([get<Task[]>('/tasks'), get<Send[]>('/sends')])
    error.value = ''
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
  return true
}, 3500)
</script>

<template>
  <section class="page">
    <header class="page-head"><div><h1>任务记录</h1><p>查看后台进度，打开回答和已保存的草稿。</p></div></header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <div class="section-head">
      <h2>后台任务</h2>
      <select v-model="filter" aria-label="任务类型"><option value="study">问答和拟稿</option><option value="all">全部任务</option></select>
    </div>
    <div class="panel rows">
      <p v-if="loading" class="empty" role="status">正在读取任务…</p>
      <p v-else-if="!displayed.length" class="empty">暂无这类任务。</p>
      <RouterLink v-for="task in displayed" :key="task.id" :to="'/task/' + task.id" class="row">
        <span class="row-fill"><strong>{{ taskLabels[task.type] }}</strong><small>{{ task.error || task.step }} · {{ time(task.updatedAt) }}</small></span>
        <span class="status" :data-state="task.status">{{ statusLabels[task.status] }}</span>
      </RouterLink>
    </div>
    <section class="section-block">
      <h2>发送记录</h2>
      <div class="panel rows">
        <p v-if="!sends.length" class="empty">暂无发送记录。</p>
        <RouterLink v-for="send in sends" :key="send.id" :to="'/send/' + send.id" class="row">
          <span class="row-fill"><strong>草稿修订 {{ send.revision }}</strong><small>{{ time(send.updatedAt) }}</small></span>
          <span class="status" :data-state="send.status">{{ statusLabels[send.status] }}</span>
        </RouterLink>
      </div>
    </section>
  </section>
</template>
