<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { savedGenerationLabel } from '../tasks'
import UiIcon from '../components/UiIcon.vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Page } from '../materials'
import { cardListPath, type CardFilters, type KnowledgeCard } from '../knowledge'

const route = useRoute(), router = useRouter()
const results = ref<Page<KnowledgeCard> | null>(null)
const page = ref(1), loading = ref(false), error = ref('')
const filters = reactive<CardFilters>({ keyword: '', subject: '', tag: '', favoriteOnly: false })
const pageSize = 20
const filtersOpen = ref(false)
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
function resetFilters() {
  Object.assign(filters, { keyword: '', subject: '', tag: '', favoriteOnly: false })
  search()
}
onMounted(() => load())
</script>

<template>
  <div class="materials-layout library-workspace">
    <div class="section-heading"><div><h1>知识卡片</h1><p>把学习要点整理成卡片，让每一次回顾更清晰。</p></div><div class="heading-actions"><router-link class="new-note-link" to="/cards/new"><UiIcon name="plus" />新建卡片</router-link></div></div>

    <div class="library-layout">
    <section class="library-results" aria-label="知识卡片列表">
      <div class="filter-bar library-filters">
        <el-input v-model="filters.keyword" class="filter-grow" placeholder="搜索标题或正文" maxlength="100" clearable aria-label="搜索卡片标题或正文" @keyup.enter="search" @clear="search" />
        <div id="cards-filters" class="filter-options" :class="{ 'is-open': filtersOpen }">
        <el-input v-model="filters.subject" class="filter-narrow" placeholder="学科（精确匹配）" maxlength="64" clearable aria-label="按学科筛选" @keyup.enter="search" @clear="search" />
        <el-input v-model="filters.tag" class="filter-narrow" placeholder="标签（精确匹配）" maxlength="64" clearable aria-label="按标签筛选" @keyup.enter="search" @clear="search" />
        <el-checkbox v-model="filters.favoriteOnly" @change="search">仅收藏</el-checkbox>
        </div>
        <div class="filter-actions">
          <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
        <el-button class="filter-toggle" :aria-expanded="filtersOpen" aria-controls="cards-filters" @click="filtersOpen = !filtersOpen">{{ filtersOpen ? '收起筛选' : '筛选条件' }}</el-button>
      </div>

      <el-alert v-if="error" class="list-alert" type="error" show-icon :closable="false" role="alert">
        <template #title>{{ error }} <el-button link type="primary" @click="load()">重试</el-button></template>
      </el-alert>

      <div v-if="loading" class="loading-panel"><el-skeleton :rows="5" animated /></div>
      <el-empty v-else-if="results && !results.items.length" description="知识，从一张卡片开始">
        <p class="empty-hint">调整筛选条件，或把刚学到的要点记下来。</p>
        <el-button type="primary" @click="router.push('/cards/new')">创建一张知识卡片</el-button>
      </el-empty>
      <template v-else-if="results">
        <div v-if="results.items.length" class="list-summary"><strong>筛选结果 {{ results.total }} 张卡片</strong><span class="summary-divider"></span><span>第 {{ page }} 页</span><nav class="library-tools" aria-label="卡片组织工具">
      <router-link :to="{ path: '/cards/organize', query: { keyword: filters.keyword, subject: filters.subject, tag: filters.tag, favoriteOnly: String(filters.favoriteOnly) } }"><UiIcon name="layout" />编排卡片</router-link>
      <router-link :to="{ path: '/knowledge-graph', query: { subject: filters.subject } }"><UiIcon name="graph" />知识关系图</router-link>
    </nav></div>
        <div class="knowledge-grid">
          <el-card v-for="card in results.items" :key="card.id" class="card-tile" shadow="hover">
            <router-link :to="`/cards/${card.id}`" class="material-item">
              <div class="material-item-main"><span class="note-badge">{{ savedGenerationLabel(card.generationMode, card.isEdited) }}{{ card.favorite ? ' · 已收藏' : '' }}</span><h2 :title="card.title">{{ card.title }}</h2><p>{{ card.summary || card.content }}</p></div>
              <div class="material-item-meta"><span v-if="card.subject">{{ card.subject }}</span><el-tag v-for="tag in card.tags" :key="tag" size="small" type="info" effect="plain">{{ tag }}</el-tag><span v-if="card.isEdited">已人工修改</span><span>版本 {{ card.version }}</span></div>
            </router-link>
          </el-card>
        </div>
        <el-pagination v-if="results.total > pageSize" class="list-pager" background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="results.total" @current-change="load" />
      </template>
    </section>
    <details class="library-help"><summary>卡片编排与知识关联说明</summary><aside class="workspace-rail" aria-label="卡片组织工具"><section class="materials-panel rail-panel"><div class="rail-heading"><UiIcon name="layout" /><h2>组织你的知识</h2></div><p class="rail-description">从单个知识点到完整的知识结构，按自己的理解编排。</p><router-link class="rail-tool" :to="{ path: '/cards/organize', query: { keyword: filters.keyword, subject: filters.subject, tag: filters.tag, favoriteOnly: String(filters.favoriteOnly) } }"><UiIcon name="layout" /><span>编排卡片<small>调整卡片位置与父子层级</small></span><UiIcon name="arrow" /></router-link><router-link class="rail-tool" :to="{ path: '/knowledge-graph', query: { subject: filters.subject } }"><UiIcon name="graph" /><span>知识关系图<small>查看已确认的知识关联</small></span><UiIcon name="arrow" /></router-link></section><section class="materials-panel rail-panel"><h2>知识从资料中来</h2><p class="rail-description">回到原始笔记和课件，补充细节，也可以发起新的智能整理任务。</p><router-link class="rail-action" to="/materials">返回我的资料<UiIcon name="arrow" /></router-link></section></aside></details>
    </div>
  </div>
</template>

<style scoped>
.list-alert { margin-bottom: 16px; }
.empty-hint { margin: 0 0 18px; color: var(--muted); font-size: 14px; }
.list-pager { margin-top: 24px; }
.card-tile { transition: transform .18s, box-shadow .18s; }
.card-tile:hover { transform: translateY(-2px); }
.card-tile :deep(.el-card__body) { padding: 24px; }
.knowledge-grid .material-item-main p { display: -webkit-box; min-height: 44px; -webkit-box-orient: vertical; -webkit-line-clamp: 2; white-space: normal; overflow: hidden; }
</style>
