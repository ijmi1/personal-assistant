<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, get, key, mutate, statusLabels, time, type Accepted, type DocumentView } from '../api'

const router = useRouter()
const documents = ref<DocumentView[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')

onMounted(async () => {
  try { documents.value = await get<DocumentView[]>('/documents') }
  catch (failure) { error.value = errorMessage(failure) }
  finally { loading.value = false }
})

async function sync() {
  busy.value = true
  error.value = ''
  try {
    const accepted = await mutate<Accepted>('POST', '/index/sync', undefined, key())
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
      <div><h1>资料库</h1><p>查看收录状态，接着上次的位置读。</p></div>
      <button class="primary" :disabled="busy" @click="sync">{{ busy ? '正在提交…' : '同步资料' }}</button>
    </header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <div class="panel rows">
      <p v-if="loading" class="empty" role="status">正在读取资料…</p>
      <p v-else-if="!documents.length" class="empty">暂无资料。将 Markdown 或 PDF 放入配置的目录，再同步资料。</p>
      <article v-for="doc in documents" :key="doc.id" class="row">
        <span class="file-type">{{ doc.kind === 'PDF' ? 'PDF' : 'MD' }}</span>
        <div class="row-fill">
          <strong>{{ doc.name }}</strong>
          <small>{{ doc.reason || time(doc.updatedAt) }}</small>
        </div>
        <span class="status" :data-state="doc.status">{{ statusLabels[doc.status] }}</span>
        <RouterLink v-if="doc.version" :to="'/reader/' + doc.id" class="button secondary slim">继续阅读</RouterLink>
      </article>
    </div>
  </section>
</template>
