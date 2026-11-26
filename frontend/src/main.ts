import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Ask from './views/Ask.vue'
import Library from './views/Library.vue'
import Mail from './views/Mail.vue'
import Tasks from './views/Tasks.vue'
import './style.css'

if (location.pathname === '/' && /^#\/(?![/\\])/.test(location.hash)) {
  history.replaceState(history.state, '', location.hash.slice(1))
}

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', component: Ask },
    { path: '/documents', component: Library },
    { path: '/reader/:id', component: () => import('./views/Reader.vue') },
    { path: '/citation/:id', component: () => import('./views/Reader.vue'), meta: { citation: true } },
    { path: '/mail', component: Mail },
    { path: '/threads/:id', component: () => import('./views/Thread.vue') },
    { path: '/draft/:id', component: () => import('./views/Draft.vue') },
    { path: '/send/:id', component: () => import('./views/Send.vue') },
    { path: '/tasks', component: Tasks },
    { path: '/task/:id', component: () => import('./views/Task.vue') },
    { path: '/answer/:id', component: () => import('./views/Answer.vue') },
    { path: '/:anything(.*)*', redirect: '/' },
  ],
})

createApp(App).use(router).mount('#app')
