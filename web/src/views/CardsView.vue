<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { savedGenerationLabel } from '../tasks'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Page } from '../materials'
import { cardListPath, type CardFilters, type KnowledgeCard } from '../knowledge'

const route = useRoute(), router = useRouter()
const results = ref<Page<KnowledgeCard> | null>(null)
const page = ref(1), loading = ref(false), error = ref('')
const filters = reactive<CardFilters>({ keyword: '', subject: '', tag: '', favoriteOnly: false })
let applied = { ...filters }
async function load(next = page.value) {
  if (loading.value) return
  loading.value = true; error.value = ''; results.value = null
  try {
    results.value = await api<Page<KnowledgeCard>>(cardListPath(applied, next))
    page.value = next
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '卡片读取失败'
  } finally { loading.value = false }
}
function search() {
  if (loading.value) return
  applied = { ...filters }; page.value = 1; load(1)
}
onMounted(() => load())
</script>

<template>
  <div class="materials-layout">
    <div class="section-heading"><div><h1>知识卡片</h1><p>管理自己的学习要点与创建时的来源。</p></div><div class="heading-actions"><router-link class="new-note-link" to="/cards/new">＋ 人工创建卡片</router-link><router-link class="new-note-link secondary-link" :to="{ path: '/cards/organize', query: { keyword: filters.keyword, subject: filters.subject, tag: filters.tag, favoriteOnly: String(filters.favoriteOnly) } }">组织卡片</router-link><router-link class="new-note-link secondary-link" :to="{ path: '/knowledge-graph', query: { subject: filters.subject } }">知识关系图</router-link><router-link class="back-link" to="/ai-tasks">前往整理任务</router-link></div></div>
    <section class="materials-panel">
      <form class="search-row" @submit.prevent="search"><input v-model="filters.keyword" aria-label="搜索卡片标题或正文" placeholder="搜索标题或正文" maxlength="100" /><input v-model="filters.subject" aria-label="按学科筛选" placeholder="学科（精确匹配）" maxlength="64" class="subject-filter" /><input v-model="filters.tag" aria-label="按标签筛选" placeholder="标签（精确匹配）" maxlength="64" class="subject-filter" /><button type="submit" :disabled="loading">搜索</button></form>
      <label class="favorite-filter"><input v-model="filters.favoriteOnly" type="checkbox" /> 仅收藏（点击搜索应用）</label>
      <p v-if="error" class="inline-error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></p>
      <p v-if="loading" class="list-state" role="status">正在读取卡片…</p>
      <div v-else-if="results && !results.items.length" class="list-state">没有符合当前条件的卡片。可调整筛选，或人工创建卡片。</div>
      <template v-if="results"><router-link v-for="card in results.items" :key="card.id" :to="`/cards/${card.id}`" class="material-item">
        <div class="material-item-main"><span class="note-badge">{{ savedGenerationLabel(card.generationMode, card.isEdited) }}{{ card.favorite ? ' · 已收藏' : '' }}</span><h2>{{ card.title }}</h2><p>{{ card.summary || card.content }}</p></div>
        <div class="material-item-meta"><span v-if="card.subject">{{ card.subject }}</span><span v-for="tag in card.tags" :key="tag" class="mini-tag">{{ tag }}</span><span v-if="card.isEdited">已人工修改</span><span>版本 {{ card.version }}</span></div>
      </router-link>
      <nav v-if="results.total > 20" class="pager" aria-label="卡片分页"><button :disabled="loading || page === 1" @click="load(page - 1)">上一页</button><span>第 {{ page }} 页 · {{ results.total }} 张</span><button :disabled="loading || page * 20 >= results.total" @click="load(page + 1)">下一页</button></nav></template>
    </section>
  </div>
</template>
