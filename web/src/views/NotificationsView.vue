<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import type { Page } from '../materials'
import { notificationTaskPath, type NotificationView } from '../notifications'

const route = useRoute(), router = useRouter()
const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, routePath = route.fullPath
const unreadOnly = ref(false), page = ref(1), results = ref<Page<NotificationView> | null>(null)
const loading = ref(false), saving = ref(false), error = ref(''), actionError = ref(''), notice = ref('')
const busy = computed(() => loading.value || saving.value)
let active = true
const current = () => active && Boolean(ownerId && ownerToken) && session.value?.user.id === ownerId && session.value?.accessToken === ownerToken && route.fullPath === routePath

function invalidate() {
  active = false; results.value = null; error.value = ''; actionError.value = ''; notice.value = ''
}
watch(() => [session.value?.user.id, session.value?.accessToken, route.fullPath], invalidate, { flush: 'sync' })
onUnmounted(invalidate)
onMounted(load)

async function message(cause: unknown, fallback: string) {
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession(); await router.replace({ name: 'login', query: { redirect: routePath } }); return ''
  }
  return cause instanceof Error ? cause.message : fallback
}
async function refreshList() {
  loading.value = true; error.value = ''; results.value = null
  const query = new URLSearchParams({ unreadOnly: String(unreadOnly.value), page: String(page.value), pageSize: '20' })
  try {
    const value = await api<Page<NotificationView>>(`/notifications?${query}`)
    if (current()) results.value = value
  } catch (cause) { if (current()) error.value = await message(cause, '通知列表读取失败，请刷新重试') }
  finally { if (current()) loading.value = false }
}
async function load() {
  if (!current() || busy.value) return
  await refreshList()
}
async function changeFilter(value: boolean) {
  if (!current() || busy.value) return
  unreadOnly.value = value; page.value = 1; notice.value = ''; actionError.value = ''
  await refreshList()
}
async function changePage(next: number) {
  if (!current() || busy.value || next < 1) return
  page.value = next; notice.value = ''; actionError.value = ''
  await refreshList()
}
async function saveRead(item: NotificationView, isRead: boolean, open: boolean) {
  if (!current() || busy.value) return
  const destination = notificationTaskPath(item.target)
  if (open && !destination) { actionError.value = '此通知的任务入口不可用'; return }
  saving.value = true; actionError.value = ''; notice.value = ''
  try {
    let saved: NotificationView
    try { saved = await api<NotificationView>(`/notifications/${encodeURIComponent(item.id)}/read-state`, 'PUT', { isRead }) }
    catch (cause) { if (current()) actionError.value = await message(cause, '读状态保存失败，请重试'); return }
    if (!current()) return
    notice.value = saved.isRead ? '已标记为已读。' : '已标记为未读。'
    if (results.value) results.value.items = results.value.items.map(value => value.id === saved.id ? saved : value)
    if (open && destination) {
      try { await router.push(destination) }
      catch (cause) { if (current()) actionError.value = `读状态已保存，但任务页面打开失败：${cause instanceof Error ? cause.message : '请重试'}` }
    } else await refreshList()
  } finally { if (current()) saving.value = false }
}
async function setRead(item: NotificationView, isRead: boolean) { await saveRead(item, isRead, false) }
async function openTask(item: NotificationView) { await saveRead(item, true, true) }
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <div class="materials-layout narrow">
    <div class="section-heading"><div><span class="intro-eyebrow">NOTIFICATIONS</span><h1>通知</h1><p>整理结果待确认或任务失败时，可在这里查看最新消息。</p></div></div>
    <section class="materials-panel" :aria-busy="busy">
      <p class="notification-channel">微信订阅消息尚未开通，站内通知正常</p>
      <div class="task-toolbar">
        <label for="notification-filter">范围</label>
        <select id="notification-filter" :value="String(unreadOnly)" :disabled="busy" @change="changeFilter(($event.target as HTMLSelectElement).value === 'true')"><option value="false">全部通知</option><option value="true">仅未读</option></select>
        <button type="button" :disabled="busy" @click="load">刷新</button>
      </div>
      <p v-if="notice" class="notification-notice" role="status">{{ notice }}</p>
      <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="list-state" role="status">正在读取通知…</p>
      <template v-else-if="results">
        <p v-if="!results.items.length" class="list-state">{{ page > 1 ? '本页暂无通知，请返回上一页查看。' : unreadOnly ? '暂无未读通知。' : '暂无通知。' }}</p>
        <div v-else class="material-list" role="list">
          <article v-for="item in results.items" :key="item.id" class="material-item" role="listitem">
            <div class="material-item-main">
              <span class="note-badge">{{ item.isRead ? '已读' : '未读' }}</span>
              <h2>{{ item.type === 'RESULT_READY' ? '整理结果待确认' : '整理任务失败' }}</h2>
              <p class="notification-content">{{ item.content }}</p>
            </div>
            <div class="material-item-meta"><span v-if="notificationTaskPath(item.target)">任务 #{{ item.target.taskId }}</span><span v-else>任务入口不可用</span><time :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time></div>
            <div class="notification-actions">
              <button type="button" :disabled="busy" @click="setRead(item, !item.isRead)">{{ item.isRead ? '标为未读' : '标为已读' }}</button>
              <button type="button" :disabled="busy || !notificationTaskPath(item.target)" @click="openTask(item)">查看任务</button>
            </div>
          </article>
        </div>
      </template>
      <div v-if="page > 1 || (results && results.total > results.pageSize)" class="pager">
        <button type="button" :disabled="page <= 1 || busy" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} 页<span v-if="results"> · 共 {{ results.total }} 条</span></span>
        <button type="button" :disabled="busy || !results || page * results.pageSize >= results.total" @click="changePage(page + 1)">下一页</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.notification-channel { margin: 0 0 20px; color: #75839a; font-size: 14px; }
.notification-notice { color: #286448; font-size: 14px; }
.material-item-main .notification-content { white-space: normal; overflow-wrap: anywhere; line-height: 1.7; }
.notification-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 16px; }
.notification-actions button { padding: 9px 15px; border: 1px solid #dce5f2; border-radius: 8px; background: #fff; color: #315bc2; cursor: pointer; }
.notification-actions button:disabled, .task-toolbar :disabled { opacity: .5; cursor: default; }
</style>
