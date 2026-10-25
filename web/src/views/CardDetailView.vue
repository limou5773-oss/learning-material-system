<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { savedGenerationLabel, savedGenerationWarning } from '../tasks'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import { sourceUnavailableLabels, type CardSource, type KnowledgeCard } from '../knowledge'
import { sourceLocator } from '../tasks'

const route = useRoute(), router = useRouter()
const card = ref<KnowledgeCard | null>(null), sources = ref<CardSource[]>([])
const loading = ref(false), error = ref('')
async function load() {
  loading.value = true; error.value = ''; card.value = null; sources.value = []
  try {
    const path = `/cards/${encodeURIComponent(String(route.params.id))}`
    const [saved, refs] = await Promise.all([api<KnowledgeCard>(path), api<{ items: CardSource[] }>(`${path}/sources`)])
    card.value = saved; sources.value = refs.items
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '卡片读取失败'
  } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="materials-layout"><router-link class="back-link" to="/cards">← 返回知识卡片</router-link>
    <p v-if="loading" class="list-state" role="status">正在读取卡片…</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <article v-if="card" class="materials-panel detail-panel">
      <span class="candidate-badge">{{ savedGenerationLabel(card.generationMode) }}</span>
      <h1>{{ card.title }}</h1><div class="detail-meta"><span>卡片 #{{ card.id }} · 版本 {{ card.version }}</span><span v-if="card.isEdited">已人工修改</span><span v-if="card.subject">{{ card.subject }}</span><span v-for="tag in card.tags" :key="tag" class="mini-tag">{{ tag }}</span></div>
      <p class="candidate-warning">{{ savedGenerationWarning(card.generationMode) }}</p>
      <p v-if="card.summary" class="candidate-copy">{{ card.summary }}</p><div class="detail-text">{{ card.content }}</div>
      <section class="task-steps"><h2>来源与依据</h2><div v-for="source in sources" :key="source.chunkId" class="candidate-source">
        <h3>{{ source.materialTitle }} · 来源版本 {{ source.materialVersion }}<span v-if="source.pageNo"> · 第 {{ source.pageNo }} 页</span></h3>
        <template v-if="source.isAvailable"><p class="task-state">{{ sourceLocator(source) }}</p><p v-if="source.qualityNote" class="candidate-warning">{{ source.qualityNote }}</p><blockquote>{{ source.quoteText }}</blockquote><details><summary>查看完整片段</summary><p class="candidate-copy">{{ source.content }}</p></details><router-link :to="`/materials/${source.materialId}`">查看原资料</router-link></template>
        <p v-else class="candidate-warning">{{ sourceUnavailableLabels[source.unavailableReason || ''] || '来源暂不可用' }}</p>
      </div></section>
    </article>
  </div>
</template>
