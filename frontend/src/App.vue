<script setup lang="ts">
import { computed, markRaw, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Collection, Message, Timer } from '@element-plus/icons-vue'
import { errorMessage, get, onSessionExpired, reportClientError, signIn, signOut } from './api'
import PageErrorBoundary from './PageErrorBoundary.vue'

const route = useRoute()
const router = useRouter()
const links = [
  { path: '/', label: '问我的资料', icon: markRaw(ChatDotRound), matches: ['', 'answer'] },
  { path: '/documents', label: '资料库', icon: markRaw(Collection), matches: ['documents', 'reader', 'citation'] },
  { path: '/mail', label: '邮件', icon: markRaw(Message), matches: ['mail', 'threads', 'draft', 'send'] },
  { path: '/tasks', label: '任务记录', icon: markRaw(Timer), matches: ['task', 'tasks'] },
]
const section = computed(() => route.path.split('/')[1] ?? '')
const user = ref('')
const name = ref('')
const password = ref('')
const error = ref('')
const loading = ref(true)
const busy = ref(false)
const navigationFailed = ref(false)
onUnmounted(router.onError(() => { navigationFailed.value = true }))
onUnmounted(router.afterEach((_to, _from, failure) => {
  if (!failure) navigationFailed.value = false
}))
onSessionExpired(() => { user.value = '' })

function reload() {
  window.location.reload()
}

onMounted(async () => {
  try {
    user.value = (await get<{ username: string }>('/session')).username
  } catch {
    user.value = ''
  } finally {
    loading.value = false
  }
})

async function login() {
  error.value = ''
  busy.value = true
  try {
    await signIn(name.value, password.value)
    user.value = (await get<{ username: string }>('/session')).username
    password.value = ''
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await signOut()
    user.value = ''
    error.value = ''
  } catch (failure) {
    error.value = errorMessage(failure)
  }
}
</script>

<template>
  <div v-if="loading" class="login-frame"><p role="status">正在连接学习助手…</p></div>
  <div v-else-if="!user" class="login-frame">
    <form class="login-card panel" @submit.prevent="login">
      <div class="brand-mark"><Collection /></div>
      <h1>个人学习助手</h1>
      <label>用户名<input v-model="name" autocomplete="username" required></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required></label>
      <p v-if="error" class="problem" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
    </form>
  </div>
  <div v-else class="shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/">
        <span class="brand-mark"><Collection /></span>
        <span>个人学习助手</span>
      </RouterLink>
      <nav aria-label="主导航">
        <RouterLink v-for="link in links" :key="link.path" :to="link.path"
                    :class="{ selected: link.matches.includes(section) }">
          <component :is="link.icon" /><span>{{ link.label }}</span>
        </RouterLink>
      </nav>
      <div class="account">
        <span class="avatar">{{ user.slice(0, 1).toUpperCase() }}</span>
        <span class="account-name">{{ user }}</span>
        <button class="text-button" @click="logout">退出</button>
      </div>
    </aside>
    <main class="main">
      <p v-if="error" class="problem" role="alert">{{ error }}</p>
      <PageErrorBoundary :navigation-failed="navigationFailed" :route-key="route.path"
                         @failure="reportClientError" @reload="reload">
        <RouterView :key="route.path" />
      </PageErrorBoundary>
    </main>
  </div>
</template>
