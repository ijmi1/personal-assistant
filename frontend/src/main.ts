import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Ask from './views/Ask.vue'
import Library from './views/Library.vue'
import Mail from './views/Mail.vue'
import Tasks from './views/Tasks.vue'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
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
