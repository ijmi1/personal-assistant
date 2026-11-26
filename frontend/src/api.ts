import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 30_000 })
let csrf: { headerName: string; token: string } | undefined
let onUnauthorized: () => void = () => undefined

export function onSessionExpired(callback: () => void) {
  onUnauthorized = callback
}

client.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401 && error.config?.url !== '/session/login') {
    csrf = undefined
    onUnauthorized()
  }
  return Promise.reject(error)
})

async function token() {
  csrf ??= (await client.get('/session/csrf')).data
  if (!csrf) throw new Error('无法读取会话令牌')
  return csrf
}

export async function prepareKeepalive() {
  await token()
}

export function keepalivePut(url: string, data: unknown, requestKey: string) {
  if (csrf) void fetch('/api' + url, {
    method: 'PUT', keepalive: true, body: JSON.stringify(data),
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'Idempotency-Key': requestKey },
  }).catch(() => undefined)
}

export async function get<T>(url: string, options?: { params?: object; signal?: AbortSignal }) {
  return (await client.get<T>(url, options)).data
}

export async function mutate<T>(method: 'POST' | 'PATCH' | 'DELETE' | 'PUT', url: string, data?: unknown, key?: string) {
  const security = await token()
  return (await client.request<T>({
    method, url, data,
    headers: { [security.headerName]: security.token, ...(key ? { 'Idempotency-Key': key } : {}) },
  })).data
}

export async function signIn(username: string, password: string) {
  const form = new URLSearchParams({ username, password })
  await mutate('POST', '/session/login', form)
  csrf = undefined
}

export async function signOut() {
  await mutate('POST', '/session/logout')
  csrf = undefined
}

export function key() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
}

export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    return typeof error.response?.data?.message === 'string' ? error.response.data.message : '无法连接服务，请稍后重试'
  }
  return error instanceof Error ? error.message : '操作失败，请重试'
}

export function currentVersion<T>(error: unknown) {
  return axios.isAxiosError(error) && error.response?.status === 409 ? error.response.data.current as T | undefined : undefined
}

export function routeBack(fallback: string, router: { back(): void; push(path: string): unknown }) {
  if (window.history.state?.back) router.back()
  else void router.push(fallback)
}

export function time(date: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(date))
}

export type Accepted = { taskId: string }
export type Task = {
  id: string; type: string; step: string; status: string; updatedAt: string;
  resultType: string | null; resultId: string | null; error: string | null;
}
export type DocumentView = {
  id: string; name: string; version: string | null; kind: 'PDF' | 'MARKDOWN';
  status: string; reason: string | null; updatedAt: string;
}
export type Anchor = { page: number | null; heading: string[] | null; paragraph: number | null }
export type Citation = { document: DocumentView; anchor: Anchor | null; excerpt: string; changed: boolean; reason: string | null }
export type Bookmark = { version: string; anchor: Anchor; ratio: number; revision: number; notice: string | null }
export type Answer = { id: string; question: string; claims: { text: string; citationIds: string[] }[]; gap: boolean }
export type Thread = { id: string; subject: string; updatedAt: string; messageCount: number; latestMessageId: string }
export type Message = { id: string; sender: string; subject: string; body: string; sentAt: string; direction: 'IN' | 'OUT' }
export type History = { id: string; subject: string; messages: Message[] }
export type Content = { to: string[]; cc: string[]; bcc: string[]; subject: string; body: string; attachments: { name: string; size: number }[] }
export type Draft = {
  id: string; threadId: string; messageId: string; revision: number; status: string;
  content: Content; citationIds: string[]; evidenceGap: boolean; newIncoming: boolean; updatedAt: string;
}
export type Send = { id: string; draftId: string; revision: number; status: string; error: string | null; updatedAt: string }

export const taskLabels: Record<string, string> = { QUESTION: '资料问答', INDEX: '资料同步', MAIL: '收信', DRAFT: '起草回复' }
export const statusLabels: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '处理中', DONE: '已完成', FAILED: '失败', READY: '可检索',
  UNSEARCHABLE: '可阅读', INDEXING: '更新中', SENDING: '发送中', SENT: '已发送',
  VERIFY: '发送结果待核对', EDITABLE: '可编辑', LOCKED: '已确认', DISCARDED: '已放弃', REVISABLE: '可保存新修订',
}
