<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { savedGenerationLabel, savedGenerationWarning } from '../tasks'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import { sourceUnavailableLabels, type CardSource, type KnowledgeCard } from '../knowledge'
import { sourceLocator } from '../tasks'

const route = useRoute(), router = useRouter()
const card = ref<KnowledgeCard | null>(null), sources = ref<CardSource[]>([])
const loading = ref(false), error = ref('')
const favoriteBusy = ref(false), actionError = ref('')
const deleteBusy = ref(false), deleteConflict = ref(false)
let pageAlive = true
onBeforeUnmount(() => { pageAlive = false })
async function load() {
  loading.value = true; error.value = ''
  try {
    const path = `/cards/${encodeURIComponent(String(route.params.id))}`
    const [saved, refs] = await Promise.all([api<KnowledgeCard>(path), api<{ items: CardSource[] }>(`${path}/sources`)])
    card.value = saved; sources.value = refs.items; deleteConflict.value = false; actionError.value = ''
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '卡片读取失败'
  } finally { loading.value = false }
}
async function deleteCard() {
  const saved = card.value
  if (!saved || deleteBusy.value || deleteConflict.value) return
  if (!window.confirm(`确定删除“${saved.title}”吗？此操作不可撤销。相关关联、收藏及画布位置将清理；直接子卡片会成为根卡片并保留，原始资料不受影响。`)) return
  const token = session.value?.accessToken
  const isCurrent = () => pageAlive && session.value?.accessToken === token
    && String(route.params.id) === saved.id && card.value?.id === saved.id
  deleteBusy.value = true; actionError.value = ''
  try {
    await api<void>(`/cards/${encodeURIComponent(saved.id)}?version=${saved.version}`, 'DELETE')
    if (!isCurrent()) return
    await router.replace('/cards')
  } catch (cause) {
    if (!isCurrent()) return
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else if (cause instanceof ApiRequestError && cause.status === 409) {
      deleteConflict.value = true
      actionError.value = `卡片已被修改${cause.currentVersion === undefined ? '' : `，当前版本 ${cause.currentVersion}`}。请重新读取并核对后再删除。`
    } else actionError.value = cause instanceof Error ? cause.message : '卡片删除失败'
  } finally { if (pageAlive) deleteBusy.value = false }
}
async function toggleFavorite() {
  const saved = card.value
  if (!saved || favoriteBusy.value) return
  favoriteBusy.value = true; actionError.value = ''
  try {
    await api<void>(`/favorites/CARD/${encodeURIComponent(saved.id)}`, saved.favorite ? 'DELETE' : 'PUT')
    saved.favorite = !saved.favorite
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else actionError.value = cause instanceof Error ? cause.message : '收藏操作失败'
  } finally { favoriteBusy.value = false }
}
onMounted(load)
</script>

<template>
  <div class="materials-layout"><router-link class="back-link" to="/cards">← 返回知识卡片</router-link>
    <p v-if="loading" class="list-state" role="status">正在读取卡片…</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <article v-if="card" class="materials-panel detail-panel">
      <span class="candidate-badge">{{ savedGenerationLabel(card.generationMode, card.isEdited) }}</span>
      <h1>{{ card.title }}</h1><div class="detail-meta"><span>卡片 #{{ card.id }} · 版本 {{ card.version }}</span><span v-if="card.isEdited">已人工修改</span><span v-if="card.subject">{{ card.subject }}</span><span v-for="tag in card.tags" :key="tag" class="mini-tag">{{ tag }}</span></div>
      <div class="heading-actions"><router-link class="new-note-link secondary-link" :to="`/cards/${card.id}/edit`">编辑卡片</router-link><router-link class="new-note-link secondary-link" :to="{ path: '/knowledge-graph', query: { rootCardId: card.id } }">查看知识关联</router-link><button class="favorite-action" type="button" :disabled="favoriteBusy || deleteBusy" :aria-pressed="card.favorite" @click="toggleFavorite">{{ favoriteBusy ? '正在更新…' : card.favorite ? '★ 取消收藏' : '☆ 收藏卡片' }}</button><button class="delete-action" type="button" :disabled="deleteBusy || favoriteBusy || loading || deleteConflict" @click="deleteCard">{{ deleteBusy ? '正在删除…' : '删除卡片' }}</button></div><p v-if="actionError || deleteConflict" class="inline-error" role="alert">{{ actionError }} <button v-if="deleteConflict" :disabled="loading" @click="load">重新读取卡片</button></p>
      <p class="candidate-warning">{{ savedGenerationWarning(card.generationMode, card.isEdited) }}</p>
      <p v-if="card.summary" class="candidate-copy">{{ card.summary }}</p><div class="detail-text">{{ card.content }}</div>
      <section class="task-steps"><h2>来源与依据</h2><p v-if="card.origin === 'MANUAL'" class="candidate-warning">人工创建的卡片无自动来源。</p><div v-for="source in sources" :key="source.chunkId" class="candidate-source">
        <h3>{{ source.materialTitle }} · 来源版本 {{ source.materialVersion }}<span v-if="source.pageNo"> · 第 {{ source.pageNo }} 页</span></h3>
        <template v-if="source.isAvailable"><p class="task-state">{{ sourceLocator(source) }}</p><p v-if="source.qualityNote" class="candidate-warning">{{ source.qualityNote }}</p><blockquote>{{ source.quoteText }}</blockquote><details><summary>查看完整片段</summary><p class="candidate-copy">{{ source.content }}</p></details><router-link :to="`/materials/${source.materialId}`">查看原资料</router-link></template>
        <p v-else class="candidate-warning">{{ sourceUnavailableLabels[source.unavailableReason || ''] || '来源暂不可用' }}</p>
      </div></section>
    </article>
  </div>
</template>

<style scoped>
.heading-actions .favorite-action { position: static; }
.delete-action { color: #a3362d; border: 1px solid currentColor; background: transparent; border-radius: 6px; padding: 8px 14px; cursor: pointer; }
.delete-action:disabled { opacity: .55; cursor: default; }
</style>
