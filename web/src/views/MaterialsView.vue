<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import type { Material, Page, Tag } from '../materials'
import UiIcon from '../components/UiIcon.vue'

const router = useRouter()
const route = useRoute()
const ownerToken = session.value?.accessToken, routePath = route.fullPath
const initial = route.query
const keyword = ref(typeof initial.keyword === 'string' ? initial.keyword : '')
const subject = ref(typeof initial.subject === 'string' ? initial.subject : '')
const tagId = ref(typeof initial.tagId === 'string' ? initial.tagId : '')
const status = ref(typeof initial.status === 'string' ? initial.status : 'ACTIVE')
const favoriteOnly = ref(initial.favorite === 'true')
const favoriteBusy = ref<string[]>([])
const tags = ref<Tag[]>([])
const results = ref<Page<Material> | null>(null)
const page = ref(Math.max(1, Number(initial.page) || 1))
const loading = ref(false)
const error = ref('')
const pageSize = 20
const filtersOpen = ref(false)

async function loadTags() {
  try {
    let current = 1
    const collected: Tag[] = []
    while (true) {
      const batch = await api<Page<Tag>>(`/tags?page=${current}&pageSize=100`)
      collected.push(...batch.items)
      if (collected.length >= batch.total) break
      current += 1
    }
    tags.value = collected
  } catch (cause) { handleError(cause) }
}

function syncQuery() {
  const query: Record<string, string> = {}
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  if (subject.value.trim()) query.subject = subject.value.trim()
  if (tagId.value) query.tagId = tagId.value
  if (status.value !== 'ACTIVE') query.status = status.value
  if (favoriteOnly.value) query.favorite = 'true'
  if (page.value > 1) query.page = String(page.value)
  router.replace({ path: '/materials', query })
}

async function load() {
  loading.value = true
  error.value = ''
  results.value = null
  try {
    const query = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize), status: status.value })
    if (keyword.value.trim()) query.set('keyword', keyword.value.trim())
    if (subject.value.trim()) query.set('subject', subject.value.trim())
    if (tagId.value) query.set('tagId', tagId.value)
    if (favoriteOnly.value) query.set('favorite', 'true')
    results.value = await api<Page<Material>>(`/materials?${query}`)
    // 页码越界：删除或筛选变化后当前页没有数据时，回到最后一页而不是显示误导性空态。
    if (results.value.items.length === 0 && page.value > 1 && results.value.total > 0) {
      page.value = Math.max(1, Math.ceil(results.value.total / pageSize))
      syncQuery()
      loading.value = false
      return load()
    }
  } catch (cause) { handleError(cause) }
  finally { loading.value = false }
}

function handleError(cause: unknown) {
  if (session.value?.accessToken !== ownerToken) return
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession()
    router.replace({ name: 'login', query: { redirect: routePath } })
  } else {
    error.value = cause instanceof Error ? cause.message : '资料加载失败'
  }
}

function search() {
  keyword.value = keyword.value.trim()
  subject.value = subject.value.trim()
  page.value = 1
  syncQuery()
  load()
}
function changePage(next: number) { page.value = next; syncQuery(); load() }
function showArchived() { status.value = 'ARCHIVED'; search() }
function resetFilters() {
  keyword.value = ''; subject.value = ''; tagId.value = ''; status.value = 'ACTIVE'; favoriteOnly.value = false
  search()
}
async function toggleFavorite(item: Material) {
  if (favoriteBusy.value.includes(item.id)) return
  favoriteBusy.value = [...favoriteBusy.value, item.id]
  try {
    await api<void>(`/favorites/MATERIAL/${item.id}`, item.favorite ? 'DELETE' : 'PUT')
    item.favorite = !item.favorite
    if (favoriteOnly.value && !item.favorite) await load()
  } catch (cause) { handleError(cause) }
  finally { favoriteBusy.value = favoriteBusy.value.filter(id => id !== item.id) }
}
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium' }).format(new Date(value))
}

// 浏览器前进/后退恢复筛选：仅在 query 与当前状态不一致时重载，避免与 search() 互触发。
watch(() => route.query, (q) => {
  const next = {
    keyword: typeof q.keyword === 'string' ? q.keyword : '',
    subject: typeof q.subject === 'string' ? q.subject : '',
    tagId: typeof q.tagId === 'string' ? q.tagId : '',
    status: typeof q.status === 'string' ? q.status : 'ACTIVE',
    favorite: q.favorite === 'true',
    page: Math.max(1, Number(q.page) || 1),
  }
  if (next.keyword === keyword.value && next.subject === subject.value && next.tagId === tagId.value
    && next.status === status.value && next.favorite === favoriteOnly.value && next.page === page.value) return
  keyword.value = next.keyword; subject.value = next.subject; tagId.value = next.tagId
  status.value = next.status; favoriteOnly.value = next.favorite; page.value = next.page
  load()
})

onMounted(() => { loadTags(); load() })
</script>

<template>
  <div class="materials-layout library-workspace">
    <div class="section-heading">
      <div><h1>我的资料</h1><p>收好每一份笔记、课件和灵感，慢慢建立自己的知识库。</p></div>
      <div class="heading-actions"><router-link class="new-note-link" to="/materials/new"><UiIcon name="plus" />新建笔记</router-link><router-link class="new-note-link secondary-link" to="/materials/image/new"><UiIcon name="image" />上传图片</router-link><router-link class="new-note-link secondary-link" to="/materials/pdf/new"><UiIcon name="pdf" />上传 PDF</router-link></div>
    </div>


    <div class="library-layout">
    <section class="library-results" aria-label="资料列表">
      <div class="filter-bar library-filters">
        <el-input v-model="keyword" class="filter-grow" placeholder="搜索标题或正文" maxlength="100" clearable aria-label="搜索标题或正文" @keyup.enter="search" @clear="search" />
        <div id="materials-filters" class="filter-options" :class="{ 'is-open': filtersOpen }">
        <el-input v-model="subject" class="filter-narrow" placeholder="学科" maxlength="64" clearable aria-label="按学科筛选" @keyup.enter="search" @clear="search" />
        <el-select v-model="tagId" class="filter-fixed" placeholder="全部标签" aria-label="按标签筛选" @change="search">
          <el-option label="全部标签" value="" />
          <el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.id" />
        </el-select>
        <el-select v-model="status" class="filter-narrow" aria-label="按状态筛选" @change="search">
          <el-option label="使用中" value="ACTIVE" />
          <el-option label="已归档" value="ARCHIVED" />
          <el-option label="全部" value="ALL" />
        </el-select>
        <el-checkbox v-model="favoriteOnly" @change="search">只看收藏</el-checkbox>
        </div>
        <div class="filter-actions">
          <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
        <el-button class="filter-toggle" :aria-expanded="filtersOpen" aria-controls="materials-filters" @click="filtersOpen = !filtersOpen">{{ filtersOpen ? '收起筛选' : '筛选条件' }}</el-button>
      </div>

      <el-alert v-if="error" class="list-alert" type="error" show-icon :closable="false" role="alert">
        <template #title>{{ error }} <el-button link type="primary" @click="load">重试</el-button></template>
      </el-alert>

      <div v-if="loading" class="loading-panel"><el-skeleton :rows="5" animated /></div>
      <el-empty v-else-if="results && results.items.length === 0" :description="page > 1 ? '本页已经没有资料了' : '这里还没有符合条件的资料'">
        <p class="empty-hint">{{ page > 1 ? '页码超出了当前结果范围，回到第一页即可继续浏览。' : '调整筛选条件，或记录一条笔记，开始积累。' }}</p>
        <el-button v-if="page > 1" type="primary" @click="changePage(1)">回到第一页</el-button>
        <el-button v-else type="primary" @click="router.push('/materials/new')">写下第一条笔记</el-button>
      </el-empty>
      <template v-else-if="results">
        <div class="list-summary">
          <strong>筛选结果 {{ results.total }} 份资料</strong><span class="summary-divider"></span><span>第 {{ page }} 页</span>
          <nav class="library-tools" aria-label="资料操作"><router-link to="/ai-tasks"><UiIcon name="tasks" />整理任务</router-link><router-link to="/cards"><UiIcon name="cards" />知识卡片</router-link></nav><span v-if="status === 'ACTIVE'" class="archived-hint"><el-button link type="primary" @click="showArchived">查看已归档</el-button></span>
        </div>
        <div class="material-list document-grid">
          <el-card v-for="item in results.items" :key="item.id" class="document-card" shadow="hover">
            <router-link :to="`/materials/${item.id}`" class="material-item">
              <div class="document-heading"><span class="document-icon" :class="item.type.toLowerCase()"><UiIcon :name="item.type === 'IMAGE' ? 'image' : item.type === 'PDF' ? 'pdf' : 'note'" /></span><div><span class="note-badge">{{ item.type === 'IMAGE' ? '图片资料' : item.type === 'PDF' ? 'PDF 文档' : '文字笔记' }}</span><small v-if="item.status === 'ARCHIVED'">已归档</small></div></div>
              <div class="material-item-main"><h2 :title="item.title">{{ item.title }}</h2><p>{{ item.type === 'IMAGE' ? '打开查看原图' : item.type === 'PDF' ? `打开预览 · ${item.pageCount ?? '?'} 页` : (item.noteText?.slice(0, 110) || '打开查看笔记正文') }}</p></div>
              <div class="material-item-meta"><span>{{ item.subject || '未分类' }}</span><el-tag v-for="tag in item.tags" :key="tag.id" size="small" type="info" effect="plain">#{{ tag.name }}</el-tag><time>{{ formatDate(item.updatedAt) }}</time></div>
            </router-link>
            <el-button class="favorite-action" text :title="item.favorite ? '取消收藏' : '收藏'" :loading="favoriteBusy.includes(item.id)" :aria-label="item.favorite ? `取消收藏${item.title}` : `收藏${item.title}`" @click="toggleFavorite(item)">{{ item.favorite ? '★' : '☆' }}</el-button>
          </el-card>
        </div>
        <el-pagination v-if="results.total > pageSize" class="list-pager" background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="results.total" @current-change="changePage" />
      </template>
    </section>
    <details class="library-help"><summary>如何把资料整理成知识卡片？</summary>
    <aside class="workspace-rail" aria-label="资料整理工具">
      <section class="materials-panel rail-panel"><div class="rail-heading"><UiIcon name="spark" /><h2>把资料变成知识</h2></div><p class="rail-description">从一份资料开始，提炼要点、核对来源，再沉淀为自己的知识卡片。</p><ol class="organize-flow"><li><span>1</span><div><strong>收集学习资料</strong><p>记录笔记，上传图片或 PDF。</p></div></li><li><span>2</span><div><strong>发起智能整理</strong><p>在资料详情选择整理目标。</p></div></li><li><span>3</span><div><strong>核对并保存</strong><p>确认摘要、卡片与关联建议。</p></div></li></ol><router-link class="rail-action" to="/ai-tasks">查看整理任务<UiIcon name="arrow" /></router-link></section>
      <section class="materials-panel rail-panel"><h2>继续学习</h2><router-link class="rail-tool" to="/cards"><UiIcon name="cards" /><span>回顾知识卡片<small>让提炼过的知识更容易找回</small></span><UiIcon name="arrow" /></router-link><router-link class="rail-tool" to="/notifications"><UiIcon name="bell" /><span>查看消息通知<small>跟进整理与审核结果</small></span><UiIcon name="arrow" /></router-link></section>
    </aside>
    </details>
    </div>
  </div>
</template>

<style scoped>
.list-alert { margin-bottom: 16px; }
.empty-hint { margin: 0 0 18px; color: var(--muted); font-size: 14px; }
.list-pager { margin-top: 24px; }
.list-summary { flex-wrap: wrap; }
.archived-hint { display: inline-flex; align-items: center; gap: 4px; margin-left: auto; color: var(--muted); font-size: 12px; font-weight: 400; }
.archived-hint .el-button { margin: 0; font-size: 12px; }
.document-card { transition: transform .18s, box-shadow .18s; }
.document-card:hover { transform: translateY(-2px); }
.document-card :deep(.el-card__body) { padding: 24px; }
.document-card .material-item-meta { margin-bottom: 0; }
.document-card .favorite-action { position: absolute; top: 16px; right: 16px; width: 40px; height: 40px; min-height: 40px; margin: 0; padding: 0; border: 0; border-radius: var(--r-md); color: var(--brand-hover); font-size: 22px; }
.document-card .favorite-action :deep(span) { justify-content: center; }
@media (max-width: 620px) { .document-card .favorite-action { top: 8px; right: 8px; width: 44px; height: 44px; } }
.document-grid .el-card { border-color: var(--line-strong); }
</style>
