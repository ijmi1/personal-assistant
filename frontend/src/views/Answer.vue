<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorMessage, get, type Answer } from '../api'

const id = String(useRoute().params.id)
const answer = ref<Answer>()
const error = ref('')
onMounted(async () => {
  try { answer.value = await get<Answer>('/answers/' + id) }
  catch (failure) { error.value = errorMessage(failure) }
})
</script>

<template>
  <section class="page narrow">
    <RouterLink class="back-link" to="/">← 问我的资料</RouterLink>
    <header class="page-head"><div><h1>{{ answer?.question ?? '资料回答' }}</h1></div></header>
    <p v-if="error" class="problem" role="alert">{{ error }}</p>
    <div v-if="answer" class="panel answer">
      <article v-for="(claim, index) in answer.claims" :key="index" class="claim">
        <p>{{ claim.text }}</p>
        <div class="actions">
          <RouterLink v-for="(citation, offset) in claim.citationIds" :key="citation" :to="'/citation/' + citation" class="citation-pill">
            查看引用 {{ offset + 1 }} ↗
          </RouterLink>
        </div>
      </article>
      <p v-if="answer.gap" class="notice">当前资料缺少足够依据来回答{{ answer.claims.length ? '其余问题' : '这个问题' }}。</p>
    </div>
    <p v-else-if="!error" role="status">正在打开回答…</p>
  </section>
</template>
