<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import { cardDraftKey, cardForm, cardPayload, readCardDraft, type KnowledgeCard } from '../knowledge'

const route = useRoute(), router = useRouter()
const id = String(route.params.id || ''), userId = session.value!.user.id
const draftKey = cardDraftKey(userId, id || 'new')
const form = reactive(cardForm())
const card = ref<KnowledgeCard | null>(null), serverCopy = ref<KnowledgeCard | null>(null)
const editingVersion = ref(0), currentVersion = ref<number | null>(null)
const baseline = ref(''), loading = ref(true), ready = ref(false), saving = ref(false), saved = ref(false), restored = ref(false)
const conflict = ref(false), serverLoading = ref(false), error = ref(''), serverError = ref(''), draftError = ref('')
const snapshot = () => JSON.stringify(form)
const dirty = computed(() => ready.value && !saved.value && snapshot() !== baseline.value)

function persistDraft() {
  if (!ready.value || saved.value) return
  try {
    if (dirty.value) sessionStorage.setItem(draftKey, JSON.stringify({ version: editingVersion.value, form: { ...form } }))
    else sessionStorage.removeItem(draftKey)
  } catch { draftError.value = '本机草稿保存失败，请保留页面并复制未保存内容。' }
}
watch(form, persistDraft)
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault(); event.returnValue = ''
}
function confirmLeave() {
  if (saving.value && !saved.value) return false
  if (!dirty.value) return true
  persistDraft()
  return window.confirm('卡片有未保存的修改，确定离开吗？')
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => to.params.id === from.params.id || confirmLeave())
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
function handleError(cause: unknown, fallback: string) {
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
  } else error.value = cause instanceof Error ? cause.message : fallback
}
async function load() {
  loading.value = true; error.value = ''; ready.value = false
  try {
    const data = id ? await api<KnowledgeCard>(`/cards/${encodeURIComponent(id)}`) : null
    card.value = data; editingVersion.value = data?.version || 0
    Object.assign(form, cardForm(data || undefined)); baseline.value = snapshot()
    let draft = null
    try { draft = readCardDraft(sessionStorage.getItem(draftKey), !!id) }
    catch { draftError.value = '本机草稿读取失败，当前显示服务器内容。' }
    if (draft) {
      editingVersion.value = draft.version; Object.assign(form, draft.form); restored.value = true
      if (data && draft.version !== data.version) {
        conflict.value = true; serverCopy.value = data; currentVersion.value = data.version
        error.value = '服务器已有新版本，本机草稿已恢复。请先对照内容。'
      }
    }
    ready.value = true
  } catch (cause) { handleError(cause, '卡片加载失败') }
  finally { loading.value = false }
}
async function loadServerCopy() {
  if (serverLoading.value) return
  serverLoading.value = true; serverError.value = ''
  try {
    serverCopy.value = await api<KnowledgeCard>(`/cards/${encodeURIComponent(id)}`)
    currentVersion.value = serverCopy.value.version
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) handleError(cause, '登录已失效')
    serverError.value = cause instanceof Error ? cause.message : '读取服务器版本失败'
  } finally { serverLoading.value = false }
}
async function submit() {
  if (!ready.value || saving.value || conflict.value) return
  if (session.value?.user.id !== userId) { error.value = '登录账号已变更，请重新打开卡片后编辑。'; return }
  let payload
  try { payload = cardPayload(form, card.value?.tags) } catch (cause) { error.value = (cause as Error).message; return }
  saving.value = true; error.value = ''
  try {
    const updated = await api<KnowledgeCard>(id ? `/cards/${encodeURIComponent(id)}` : '/cards', id ? 'PATCH' : 'POST',
      id ? { version: editingVersion.value, ...payload } : payload)
    saved.value = true
    try { sessionStorage.removeItem(draftKey) } catch { draftError.value = '保存成功，但本机草稿清理失败。' }
    await router.replace(`/cards/${updated.id}`)
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409) {
      conflict.value = true; currentVersion.value = cause.currentVersion || null; serverCopy.value = null
      error.value = '服务器已有新版本，本机草稿已保留。请先对照内容。'
      persistDraft(); await loadServerCopy()
    } else { handleError(cause, '保存失败，当前输入已保留'); persistDraft() }
  } finally { saving.value = false }
}
function discardDraft() {
  if (!serverCopy.value || serverLoading.value || !window.confirm('确定放弃本机草稿，使用服务器版本替换当前五项编辑内容吗？')) return
  const data = serverCopy.value
  card.value = data; editingVersion.value = data.version
  Object.assign(form, cardForm(data)); baseline.value = snapshot()
  conflict.value = false; serverCopy.value = null; currentVersion.value = null; restored.value = false; error.value = ''; serverError.value = ''
  persistDraft()
}
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" :to="id ? `/cards/${id}` : '/cards'">← {{ id ? '返回卡片' : '返回知识卡片' }}</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">KNOWLEDGE CARD</span><h1>{{ id ? '编辑知识卡片' : '人工创建卡片' }}</h1><p>{{ id ? `正在编辑版本 ${editingVersion}，原来源仍为创建时的历史依据。` : '写下自己的学习要点，人工卡片无自动来源。' }}</p></div></div>
    <p v-if="loading" class="list-state" role="status">正在加载…</p>
    <section v-else-if="ready" class="materials-panel editor-panel">
      <p v-if="restored" class="candidate-warning">已恢复当前账号的本机草稿。</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p><p v-if="draftError" class="inline-error" role="alert">{{ draftError }}</p>
      <div v-if="conflict" class="server-copy">
        <strong>当前服务器版本 {{ currentVersion || '待读取' }}；下方草稿未被替换。</strong>
        <p v-if="serverError" class="inline-error" role="alert">{{ serverError }}</p>
        <button type="button" :disabled="serverLoading" @click="loadServerCopy">{{ serverLoading ? '正在读取…' : serverCopy ? '刷新服务器版本' : '重试读取服务器版本' }}</button>
        <template v-if="serverCopy"><h2>服务器版本 {{ serverCopy.version }}：{{ serverCopy.title }}</h2><p>{{ serverCopy.content }}</p><p>摘要：{{ serverCopy.summary || '无' }} · 学科：{{ serverCopy.subject || '无' }} · 标签：{{ serverCopy.tags.join('，') || '无' }}</p><button type="button" class="discard-button" :disabled="serverLoading" @click="discardDraft">放弃草稿，使用服务器版本</button></template>
      </div>
      <form @submit.prevent="submit">
        <label for="card-title">标题</label><input id="card-title" v-model="form.title" :disabled="saving" maxlength="255" required />
        <label for="card-subject">学科（可选）</label><input id="card-subject" v-model="form.subject" :disabled="saving" maxlength="64" />
        <label for="card-tags">标签（可选）</label><input id="card-tags" v-model="form.tagsText" :disabled="saving" placeholder="多个标签用逗号隔开，最多 10 个" />
        <p class="field-help">修改标签文本后将按中英文逗号重新分隔；保持文本不变会保留原有标签。</p>
        <label for="card-summary">摘要（可选）</label><textarea id="card-summary" v-model="form.summary" :disabled="saving" maxlength="2000" rows="3"></textarea>
        <label for="card-content">正文</label><textarea id="card-content" v-model="form.content" :disabled="saving" maxlength="8000" rows="13" required></textarea>
        <div class="editor-actions"><span>{{ form.content.length }} / 8000 字{{ dirty ? ' · 未保存' : '' }}</span><button type="submit" :disabled="saving || conflict">{{ saving ? '正在保存…' : id ? '保存修改' : '创建卡片' }}</button></div>
      </form>
    </section>
    <section v-else-if="error" class="materials-panel"><p class="inline-error" role="alert">{{ error }}</p><button class="retry-button" @click="load">重试加载</button></section>
  </div>
</template>
