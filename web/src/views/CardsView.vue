<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { savedGenerationLabel } from '../tasks'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Page } from '../materials'
import type { KnowledgeCard } from '../knowledge'

const route = useRoute(), router = useRouter()
const results = ref<Page<KnowledgeCard> | null>(null)
const page = ref(1), loading = ref(false), error = ref('')
async function load(next = page.value) {
  if (loading.value) return
  loading.value = true; error.value = ''
  try {
    results.value = await api<Page<KnowledgeCard>>(`/cards?page=${next}&pageSize=20`)
    page.value = next
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '卡片读取失败'
  } finally { loading.value = false }
}
onMounted(() => load())
</script>

<template>
  <div class="materials-layout">
    <div class="section-heading"><div><h1>知识卡片</h1><p>查看经你确认保存的学习要点与来源。</p></div><router-link class="back-link" to="/ai-tasks">前往整理任务</router-link></div>
    <section class="materials-panel">
      <p v-if="error" class="inline-error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></p>
      <p v-if="loading" class="list-state" role="status">正在读取卡片…</p>
      <div v-else-if="results && !results.items.length" class="list-state">还没有保存的卡片。先在整理任务中核对并确认候选结果。</div>
      <template v-if="results"><router-link v-for="card in results.items" :key="card.id" :to="`/cards/${card.id}`" class="material-item">
        <div class="material-item-main"><span class="note-badge">{{ savedGenerationLabel(card.generationMode) }}</span><h2>{{ card.title }}</h2><p>{{ card.summary || card.content }}</p></div>
        <div class="material-item-meta"><span v-if="card.subject">{{ card.subject }}</span><span v-for="tag in card.tags" :key="tag" class="mini-tag">{{ tag }}</span><span v-if="card.isEdited">已人工修改</span><span>版本 {{ card.version }}</span></div>
      </router-link>
      <nav v-if="results.total > 20" class="pager" aria-label="卡片分页"><button :disabled="loading || page === 1" @click="load(page - 1)">上一页</button><span>第 {{ page }} 页 · {{ results.total }} 张</span><button :disabled="loading || page * 20 >= results.total" @click="load(page + 1)">下一页</button></nav></template>
    </section>
  </div>
</template>
