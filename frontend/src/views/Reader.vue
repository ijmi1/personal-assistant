<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import {
  currentVersion, errorMessage, get, keepalivePut, key, mutate, prepareKeepalive, routeBack,
  type Anchor, type Bookmark, type Citation, type DocumentView,
} from '../api'

const markdown = new MarkdownIt({ html: false, linkify: false }).disable('image')
const route = useRoute()
const router = useRouter()
const id = String(route.params.id)
const citationMode = Boolean(route.meta.citation)
const document = ref<DocumentView>()
const citation = ref<Citation>()
const bookmark = ref<Bookmark>()
const newPosition = ref<Bookmark>()
const units = ref<{ anchor: Anchor; html: string }[]>([])
const frame = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const pdf = shallowRef<PDFDocumentProxy>()
const page = ref(1)
const error = ref('')
const notice = ref('')
const loading = ref(true)
const rendering = ref(false)
const saved = ref('')
let suppressScroll = false
let pending: { anchor: Anchor; ratio: number } | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let resizeTimer: ReturnType<typeof setTimeout> | undefined
let saving: Promise<void> | undefined
let renderTask: RenderTask | undefined
let loadingTask: PDFDocumentLoadingTask | undefined
let resize: ResizeObserver | undefined
let disposed = false
const markdownReady = computed(() => document.value?.kind === 'MARKDOWN' && units.value.length > 0)
const pdfReady = computed(() => document.value?.kind === 'PDF' && Boolean(pdf.value))
const endpoint = computed(() => '/documents/' + document.value?.id + '/bookmark')

async function open() {
  try {
    if (citationMode) {
      citation.value = await get<Citation>('/citations/' + id)
      document.value = citation.value.document
      if (!citation.value.anchor) return
    } else {
      document.value = (await get<DocumentView[]>('/documents')).find(item => item.id === id)
      if (!document.value) throw new Error('资料不存在')
      bookmark.value = await get<Bookmark>(endpoint.value)
      notice.value = bookmark.value.notice ?? ''
      await prepareKeepalive()
    }
    if (disposed || !document.value?.version) return
    const doc = document.value
    const version = doc.version
    const anchor = citationMode ? citation.value?.anchor : bookmark.value?.anchor
    const ratio = citationMode ? 0 : bookmark.value?.ratio ?? 0
    if (doc.kind === 'PDF') {
      const { GlobalWorkerOptions, getDocument } = await import('pdfjs-dist')
      if (disposed) return
      GlobalWorkerOptions.workerSrc = workerUrl
      const url = '/api/documents/' + doc.id + '/content?' + new URLSearchParams({ version: version ?? '' })
      const assets = import.meta.env.DEV ? '/node_modules/pdfjs-dist/' : '/pdfjs/'
      loadingTask = getDocument({
        url, disableRange: true, disableStream: true, enableXfa: false, cMapPacked: true,
        cMapUrl: assets + 'cmaps/', standardFontDataUrl: assets + 'standard_fonts/',
        wasmUrl: assets + 'wasm/', iccUrl: assets + 'iccs/',
      })
      const opened = await loadingTask.promise
      if (disposed) return
      pdf.value = opened
      await render(anchor?.page ?? 1, ratio)
      if (frame.value && !disposed) {
        let width = frame.value.clientWidth
        resize = new ResizeObserver(() => {
          if (!frame.value || Math.abs(frame.value.clientWidth - width) < 4) return
          width = frame.value.clientWidth
          const ratio = ratioOnPage()
          clearTimeout(resizeTimer)
          resizeTimer = setTimeout(() => render(page.value, ratio), 160)
        })
        resize.observe(frame.value)
      }
    } else {
      const outline = await get<{ anchor: Anchor; markdown: string }[]>('/documents/' + doc.id + '/outline', { params: { version } })
      if (disposed) return
      units.value = outline.map(unit => ({ anchor: unit.anchor, html: markdown.render(unit.markdown) }))
      await scrollToAnchor(anchor?.paragraph ?? 1, ratio)
    }
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function render(number: number, ratio: number) {
  if (!pdf.value || number < 1 || number > pdf.value.numPages) return
  rendering.value = true
  suppressScroll = true
  page.value = number
  await nextTick()
  if (!canvas.value || !frame.value || disposed) return
  renderTask?.cancel()
  try {
    const pdfPage = await pdf.value.getPage(number)
    if (disposed || page.value !== number || !frame.value || !canvas.value) return
    const base = pdfPage.getViewport({ scale: 1 })
    const scale = Math.min(1.8, Math.max(0.2, (frame.value.clientWidth - 16) / base.width))
    const viewport = pdfPage.getViewport({ scale })
    const output = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(12_000_000 / (viewport.width * viewport.height)))
    canvas.value.width = Math.floor(viewport.width * output)
    canvas.value.height = Math.floor(viewport.height * output)
    canvas.value.style.width = viewport.width + 'px'
    canvas.value.style.height = viewport.height + 'px'
    renderTask = pdfPage.render({ canvas: canvas.value, viewport, transform: [output, 0, 0, output, 0, 0] })
    await renderTask.promise
    await nextTick()
    if (frame.value) frame.value.scrollTop = ratio * Math.max(0, frame.value.scrollHeight - frame.value.clientHeight)
  } catch (failure) {
    if (!(failure instanceof Error && failure.name === 'RenderingCancelledException')) error.value = errorMessage(failure)
  } finally {
    rendering.value = false
    requestAnimationFrame(() => { suppressScroll = false })
  }
}

async function scrollToAnchor(paragraph: number, ratio: number) {
  suppressScroll = true
  await nextTick()
  const block = frame.value?.querySelector<HTMLElement>('[data-paragraph="' + paragraph + '"]')
  if (block && frame.value) frame.value.scrollTop = block.offsetTop + ratio * block.offsetHeight
  requestAnimationFrame(() => { suppressScroll = false })
}

function ratioOnPage() {
  return frame.value ? frame.value.scrollTop / Math.max(1, frame.value.scrollHeight - frame.value.clientHeight) : 0
}

function position() {
  if (!frame.value) return undefined
  if (pdfReady.value) return { anchor: { page: page.value, heading: null, paragraph: null }, ratio: ratioOnPage() }
  const blocks = Array.from(frame.value.querySelectorAll<HTMLElement>('[data-paragraph]'))
  const y = frame.value.scrollTop + 1
  const index = blocks.findIndex(block => block.offsetTop + block.offsetHeight > y)
  const block = blocks[index]
  const unit = units.value[index]
  if (block && unit) {
    return { anchor: unit.anchor, ratio: Math.max(0, Math.min(1, (y - block.offsetTop) / Math.max(1, block.offsetHeight))) }
  }
  return undefined
}

function changed() {
  if (citationMode || suppressScroll || !bookmark.value || newPosition.value) return
  pending = position()
  if (!pending) return
  saved.value = ''
  clearTimeout(timer)
  timer = setTimeout(() => { void persist() }, 650)
}

async function go(delta: number) {
  if (rendering.value || !pdf.value) return
  const target = page.value + delta
  if (target < 1 || target > pdf.value.numPages) return
  await render(target, 0)
  if (!citationMode && !newPosition.value) {
    pending = position()
    await persist()
  }
}

async function persist() {
  clearTimeout(timer)
  if (saving) await saving
  if (!pending || !bookmark.value || newPosition.value || citationMode) return
  const update = { ...bookmark.value, ...pending }
  pending = undefined
  saving = mutate<Bookmark>('PUT', endpoint.value, update, key()).then(value => {
    bookmark.value = value
    saved.value = '进度已保存'
    error.value = ''
  }).catch(failure => {
    error.value = errorMessage(failure)
    newPosition.value = currentVersion<Bookmark>(failure)
  })
  await saving
  saving = undefined
}

async function savePosition() {
  pending = position()
  await persist()
}

async function loadRemote() {
  if (!newPosition.value) return
  const remote = newPosition.value
  if (remote.version !== document.value?.version) {
    window.location.reload()
    return
  }
  bookmark.value = remote
  newPosition.value = undefined
  pending = undefined
  error.value = ''
  if (pdfReady.value) await render(remote.anchor.page ?? 1, remote.ratio)
  else await scrollToAnchor(remote.anchor.paragraph ?? 1, remote.ratio)
}

function closePage() {
  if (pending && bookmark.value && !saving && !citationMode && !newPosition.value) {
    keepalivePut(endpoint.value, { ...bookmark.value, ...pending }, key())
  }
}

onMounted(() => {
  void open()
  window.addEventListener('pagehide', closePage)
})
onBeforeRouteLeave(async () => { await persist() })
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
  clearTimeout(resizeTimer)
  resize?.disconnect()
  renderTask?.cancel()
  void loadingTask?.destroy().catch(() => undefined)
  window.removeEventListener('pagehide', closePage)
})
</script>

<template>
  <section class="page reader">
    <button class="back-link text-button" @click="routeBack('/documents', router)">← {{ citationMode ? '返回' : '资料库' }}</button>
    <header class="page-head">
      <div><h1>{{ document?.name || '原文阅读' }}</h1><p v-if="citationMode">引用预览 · 原阅读进度会保留</p></div>
      <button v-if="!citationMode && (markdownReady || pdfReady)" class="secondary slim"
              :disabled="rendering || Boolean(newPosition)" @click="savePosition">保存阅读位置</button>
    </header>
    <p v-if="error" class="problem" role="alert">{{ error }}
      <button v-if="newPosition" class="text-button" @click="loadRemote">恢复已保存位置</button>
    </p>
    <p v-if="notice" class="notice">{{ notice }}</p>
    <div v-if="citation" class="panel excerpt">
      <p v-if="citation.changed && citation.anchor" class="notice">资料已更新，已定位到当前版本。</p>
      <p v-if="citation.reason" class="notice">{{ citation.reason }}</p>
      <blockquote>{{ citation.excerpt }}</blockquote>
    </div>
    <p v-if="loading" class="muted" role="status">正在打开原文…</p>
    <div v-if="pdfReady" class="reader-controls">
      <button class="secondary slim" :disabled="page <= 1 || rendering" @click="go(-1)">上一页</button>
      <span aria-live="polite">第 {{ page }} / {{ pdf?.numPages }} 页</span>
      <button class="secondary slim" :disabled="page >= (pdf?.numPages ?? 1) || rendering" @click="go(1)">下一页</button>
    </div>
    <div v-if="pdfReady || markdownReady" ref="frame" class="reader-frame" :class="{ 'pdf-frame': pdfReady }"
         tabindex="0" aria-label="原文" @scroll.passive="changed">
      <canvas v-if="pdfReady" ref="canvas" :aria-label="'PDF 第 ' + page + ' 页'"></canvas>
      <template v-else>
        <article v-for="unit in units" :key="unit.anchor.paragraph ?? 0" class="markdown-block"
                 :class="{ 'source-hit': citation?.anchor?.paragraph === unit.anchor.paragraph }"
                 :data-paragraph="unit.anchor.paragraph" v-html="unit.html"></article>
        <div class="reader-spacer" aria-hidden="true"></div>
      </template>
    </div>
    <p v-if="saved" class="success-text" role="status">{{ saved }}</p>
  </section>
</template>
