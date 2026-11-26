<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage, get, key, mutate, time, type Accepted, type History } from '../api'
import { poll } from '../poll'

const router = useRouter()
const id = String(useRoute().params.id)
const history = ref<History>()
const selected = ref('')
const error = ref('')
const busy = ref(false)
const messages = computed(() => history.value?.messages ?? [])

poll(async () => {
  try {
    history.value = await get<History>('/threads/' + id)
    if (!selected.value) selected.value = messages.value.filter(message => message.direction === 'IN').at(-1)?.id ?? ''
  } catch (failure) {
    error.value = errorMessage(failure)
  }
  return true
}, 4500)

async function draft() {
  if (busy.value || !selected.value) return
  busy.value = true
  error.value = ''
  try {
    const request = await mutate<Accepted>('POST', '/threads/' + id + '/drafts', { messageId: selected.value }, key())
    await router.push('/task/' + request.taskId)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="page narrow">
    <RouterLink class="back-link" to="/mail">← 邮件往来</RouterLink>
    <header class="page-head">
      <div><h1>{{ history?.subject || '邮件往来' }}</h1><p>选择来信后，草稿会参考截至该封来信的往来。</p></div>
      <button class="primary" :disabled="busy || !selected" @click="draft">{{ busy ? '正在提交…' : '拟回复' }}</button>
    </header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <p v-else-if="!history" role="status">正在打开往来…</p>
    <article v-for="message in messages" :key="message.id" class="panel mail-card" :class="{ 'mail-selected': selected === message.id }">
      <div class="section-head">
        <div><h2>{{ message.subject || '无主题' }}</h2><p class="muted">{{ message.sender }} · {{ time(message.sentAt) }}</p></div>
        <label v-if="message.direction === 'IN'" class="select-message">
          <input v-model="selected" type="radio" name="message" :value="message.id" :aria-label="'选择来信 ' + message.subject">
          {{ selected === message.id ? '已选' : '选择' }}
        </label>
        <span v-else class="status" data-state="SENT">已发出</span>
      </div>
      <p class="preformatted">{{ message.body || '（正文为空）' }}</p>
    </article>
  </section>
</template>
