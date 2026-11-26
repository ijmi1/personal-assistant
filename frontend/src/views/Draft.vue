<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import { currentVersion, errorMessage, get, key, mutate, statusLabels, type Draft, type Send } from '../api'
import { poll } from '../poll'

type Attachment = { name: string; size: number; index?: number; base64?: string }
const id = String(useRoute().params.id)
const router = useRouter()
const draft = ref<Draft>()
const preview = ref<Draft>()
const conflict = ref<Draft>()
const sends = ref<Send[]>([])
const error = ref('')
const saved = ref('')
const busy = ref(false)
const readingFile = ref(false)
const confirmOpen = ref(false)
const discardOpen = ref(false)
const draftForm = reactive({ to: '', cc: '', bcc: '', subject: '', body: '', attachments: [] as Attachment[] })
const baseline = ref('')
const dirty = computed(() => baseline.value !== '' && JSON.stringify(draftForm) !== baseline.value)
const editable = computed(() => ['EDITABLE', 'REVISABLE'].includes(draft.value?.status ?? ''))

function fill(value: Draft) {
  draft.value = value
  draftForm.to = value.content.to.join(', ')
  draftForm.cc = value.content.cc.join(', ')
  draftForm.bcc = value.content.bcc.join(', ')
  draftForm.subject = value.content.subject
  draftForm.body = value.content.body
  draftForm.attachments = value.content.attachments.map((item, index) => ({ ...item, index }))
  baseline.value = JSON.stringify(draftForm)
}

onMounted(async () => {
  try {
    const value = await get<Draft>('/drafts/' + id)
    fill(value)
    sends.value = await get<Send[]>('/sends', { params: { draftId: id } })
  } catch (failure) {
    error.value = errorMessage(failure)
  }
})

poll(async () => {
  if (!draft.value || busy.value) return true
  try {
    const latest = await get<Draft>('/drafts/' + id)
    draft.value = { ...draft.value, newIncoming: latest.newIncoming }
  } catch {
    return true
  }
  return true
}, 4500)

function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault()
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
onBeforeRouteLeave(() => !dirty.value || window.confirm('这次编辑还未保存，离开草稿吗？'))

function handle(failure: unknown) {
  error.value = errorMessage(failure)
  const latest = currentVersion<Draft>(failure)
  if (latest) conflict.value = latest
}

function loadConflict() {
  if (!conflict.value) return
  fill(conflict.value)
  conflict.value = undefined
  saved.value = ''
  error.value = ''
}

function addresses(value: string) {
  return value.split(/[,;，；\n]/).map(address => address.trim()).filter(Boolean)
}

async function save() {
  if (!draft.value || !editable.value || busy.value) return
  busy.value = true
  error.value = ''
  saved.value = ''
  try {
    const content = {
      to: addresses(draftForm.to), cc: addresses(draftForm.cc), bcc: addresses(draftForm.bcc),
      subject: draftForm.subject, body: draftForm.body,
      attachments: draftForm.attachments.map(({ name, base64, index }) => ({ name, base64, index })),
    }
    const updated = await mutate<Draft>('PATCH', '/drafts/' + id, { revision: draft.value.revision, content }, key())
    fill(updated)
    conflict.value = undefined
    saved.value = '已保存修订 ' + updated.revision
  } catch (failure) { handle(failure) }
  finally { busy.value = false }
}

async function attach(event: Event) {
  const input = event.target as HTMLInputElement
  const chosen = Array.from(input.files ?? [])
  input.value = ''
  if (draftForm.attachments.length + chosen.length > 10
      || [...draftForm.attachments, ...chosen].reduce((bytes, file) => bytes + file.size, 0) > 8 * 1024 * 1024) {
    error.value = '最多添加 10 个附件，合计不能超过 8 MiB'
    return
  }
  error.value = ''
  readingFile.value = true
  try {
    const added = await Promise.all(chosen.map(async file => {
      const content = new Uint8Array(await file.arrayBuffer())
      let raw = ''
      for (let index = 0; index < content.length; index += 8192) {
        raw += String.fromCharCode(...content.subarray(index, index + 8192))
      }
      return { name: file.name, size: file.size, base64: btoa(raw) }
    }))
    draftForm.attachments.push(...added)
  } catch {
    error.value = '附件读取失败，请重新选择'
  } finally {
    readingFile.value = false
  }
}

async function openPreview() {
  if (!draft.value || dirty.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const latest = await get<Draft>('/drafts/' + id)
    if (latest.revision !== draft.value.revision || latest.status !== 'EDITABLE') {
      conflict.value = latest
      error.value = '草稿状态已更新，请加载最新版本后核对。'
      return
    }
    preview.value = latest
    confirmOpen.value = true
  } catch (failure) { handle(failure) }
  finally { busy.value = false }
}

async function send() {
  if (!preview.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await mutate<Send>('POST', '/drafts/' + id + '/send', { revision: preview.value.revision })
    confirmOpen.value = false
    await router.push('/send/' + result.id)
  } catch (failure) {
    confirmOpen.value = false
    handle(failure)
  } finally { busy.value = false }
}

async function discard() {
  if (!draft.value || busy.value) return
  busy.value = true
  try {
    fill(await mutate<Draft>('DELETE', '/drafts/' + id, { revision: draft.value.revision }, key()))
    saved.value = ''
  } catch (failure) { handle(failure) }
  finally {
    busy.value = false
    discardOpen.value = false
  }
}
</script>

<template>
  <section class="page narrow">
    <RouterLink class="back-link" :to="draft ? '/threads/' + draft.threadId : '/tasks'">← 返回往来</RouterLink>
    <header class="page-head">
      <div><h1>回复草稿</h1><p v-if="draft">修订 {{ draft.revision }} · {{ statusLabels[draft.status] }}</p></div>
    </header>
    <p v-if="error" class="problem" role="alert">{{ error }}
      <button v-if="conflict" class="text-button" @click="loadConflict">加载最新版本</button>
    </p>
    <p v-if="draft?.newIncoming" class="notice">这组往来有新来信。<RouterLink :to="'/threads/' + draft.threadId">查看往来 →</RouterLink></p>
    <template v-if="draft">
      <form class="panel draft-form" @submit.prevent="save">
        <fieldset :disabled="!editable || busy || readingFile">
          <label>收件人<input v-model="draftForm.to" autocomplete="off" placeholder="多个地址用逗号分隔"></label>
          <div class="form-pair">
            <label>抄送<input v-model="draftForm.cc" autocomplete="off"></label>
            <label>密送<input v-model="draftForm.bcc" autocomplete="off"></label>
          </div>
          <label>主题<input v-model="draftForm.subject" maxlength="998"></label>
          <label>正文<textarea v-model="draftForm.body" rows="11" maxlength="100000" /></label>
          <div class="attachment-area">
            <div class="section-head"><h3>附件</h3><label v-if="editable" class="file-pick button secondary slim">添加附件<input type="file" multiple @change="attach"></label></div>
            <p v-if="!draftForm.attachments.length" class="muted">无附件</p>
            <ul v-else><li v-for="(item, index) in draftForm.attachments" :key="index">
              {{ item.name }} · {{ item.size }} B
              <button v-if="editable" class="text-button" type="button" @click="draftForm.attachments.splice(index, 1)">移除</button>
            </li></ul>
          </div>
        </fieldset>
        <div v-if="editable" class="form-actions">
          <button type="button" class="text-button danger" :disabled="busy" @click="discardOpen = true">放弃草稿</button>
          <div class="actions">
            <button class="secondary" :disabled="busy || readingFile">保留草稿</button>
            <button type="button" class="primary" :disabled="busy || readingFile || dirty || draft.status !== 'EDITABLE'"
                    @click="openPreview">核对并发送</button>
          </div>
        </div>
        <p v-if="dirty" class="muted" role="status">有未保存的编辑</p>
        <p v-else-if="saved" class="success-text" role="status">{{ saved }}</p>
        <RouterLink v-if="sends.length" class="quiet-link" :to="'/send/' + sends[0]?.id">查看发送记录 →</RouterLink>
      </form>
      <section class="section-block">
        <h2>核对资料</h2>
        <div class="panel detail compact">
          <p v-if="draft.evidenceGap" class="muted">相关个人资料不足，请核对回复内容。</p>
          <div class="actions">
            <RouterLink v-for="(id, index) in draft.citationIds" :key="id" :to="'/citation/' + id" class="citation-pill">
              查看引用 {{ index + 1 }} ↗
            </RouterLink>
          </div>
        </div>
      </section>
    </template>
    <p v-else-if="!error" role="status">正在打开草稿…</p>
    <ElDialog v-model="confirmOpen" title="确认发送" width="min(92vw, 580px)" append-to-body
              :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy">
      <div v-if="preview" class="send-preview">
        <p>请核对修订 {{ preview.revision }} 的外发内容。</p>
        <dl>
          <dt>收件人</dt><dd>{{ preview.content.to.join(', ') || '无' }}</dd>
          <dt>抄送</dt><dd>{{ preview.content.cc.join(', ') || '无' }}</dd>
          <dt>密送</dt><dd>{{ preview.content.bcc.join(', ') || '无' }}</dd>
          <dt>主题</dt><dd>{{ preview.content.subject || '无主题' }}</dd>
          <dt>附件</dt><dd>{{ preview.content.attachments.map(item => item.name).join('、') || '无附件' }}</dd>
        </dl>
        <div class="preformatted preview-body" aria-label="确认正文">{{ preview.content.body }}</div>
      </div>
      <template #footer><div class="actions right">
        <button class="secondary" :disabled="busy" @click="confirmOpen = false">返回草稿</button>
        <button class="primary" :disabled="busy" @click="send">{{ busy ? '正在提交…' : '确认发送此修订' }}</button>
      </div></template>
    </ElDialog>
    <ElDialog v-model="discardOpen" title="放弃草稿" width="min(92vw, 440px)" append-to-body>
      <p>确认放弃当前草稿？</p>
      <template #footer><div class="actions right">
        <button class="secondary" :disabled="busy" @click="discardOpen = false">保留</button>
        <button class="primary" :disabled="busy" @click="discard">确认放弃</button>
      </div></template>
    </ElDialog>
  </section>
</template>
