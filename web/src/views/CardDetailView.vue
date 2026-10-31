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
    <div v-if="loading" class="loading-panel"><el-skeleton :rows="5" animated /></div>
    <el-alert v-if="error" class="form-alert" type="error" show-icon :closable="false" role="alert">
      <template #title>{{ error }} <el-button link type="primary" @click="load">重试</el-button></template>
    </el-alert>
    <article v-if="card" class="materials-panel detail-panel">
      <el-tag size="small" type="warning" effect="plain">{{ savedGenerationLabel(card.generationMode, card.isEdited) }}</el-tag>
      <h1>{{ card.title }}</h1>
      <div class="detail-meta"><span>卡片 #{{ card.id }} · 版本 {{ card.version }}</span><span v-if="card.isEdited">已人工修改</span><span v-if="card.subject">{{ card.subject }}</span><el-tag v-for="tag in card.tags" :key="tag" size="small" type="info" effect="plain">{{ tag }}</el-tag></div>
      <div class="card-actions">
        <el-button @click="router.push(`/cards/${card.id}/edit`)">编辑卡片</el-button>
        <el-button @click="router.push({ path: '/knowledge-graph', query: { rootCardId: card.id } })">查看知识关联</el-button>
        <el-button :loading="favoriteBusy" :disabled="deleteBusy" :aria-pressed="card.favorite" @click="toggleFavorite">{{ favoriteBusy ? '正在更新…' : card.favorite ? '★ 取消收藏' : '☆ 收藏卡片' }}</el-button>
        <el-button type="danger" plain :disabled="deleteBusy || favoriteBusy || loading || deleteConflict" @click="deleteCard">{{ deleteBusy ? '正在删除…' : '删除卡片' }}</el-button>
      </div>
      <el-alert v-if="actionError || deleteConflict" class="form-alert" type="error" show-icon :closable="false" role="alert">
        <template #title>{{ actionError }} <el-button v-if="deleteConflict" link type="primary" :disabled="loading" @click="load">重新读取卡片</el-button></template>
      </el-alert>
      <el-alert class="form-alert" type="info" :title="savedGenerationWarning(card.generationMode, card.isEdited)" :closable="false" show-icon />
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
.card-actions { display: flex; flex-wrap: wrap; gap: 10px; margin: 18px 0 4px; }
.card-actions .el-button { margin: 0; }
.form-alert { margin: 14px 0; }
.detail-panel h1 { margin: 12px 0; }
</style>
