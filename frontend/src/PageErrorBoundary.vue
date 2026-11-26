<script setup lang="ts">
import { onErrorCaptured, ref, watch } from 'vue'
import type { ClientErrorKind } from './api'

const props = defineProps<{ navigationFailed: boolean; routeKey: string }>()
const emit = defineEmits<{ failure: [kind: ClientErrorKind]; reload: [] }>()
const renderFailed = ref(false)

watch(() => props.routeKey, () => { renderFailed.value = false })
watch(() => props.navigationFailed, failed => {
  if (failed) emit('failure', 'navigation')
}, { immediate: true })

onErrorCaptured(() => {
  if (!renderFailed.value) emit('failure', 'render')
  renderFailed.value = true
  return false
})
</script>

<template>
  <section v-if="navigationFailed || renderFailed" class="panel detail" role="alert">
    <h2>页面暂时无法打开</h2>
    <button class="secondary" @click="emit('reload')">重新加载页面</button>
  </section>
  <slot v-else />
</template>
