<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import type { Material, Page, Tag } from '../materials'

const router = useRouter()
const route = useRoute()
const ownerToken = session.value?.accessToken, routePath = route.fullPath
const keyword = ref('')
const subject = ref('')
const tagId = ref('')
const status = ref('ACTIVE')
const favoriteOnly = ref(false)
const favoriteBusy = ref<string[]>([])
const tags = ref<Tag[]>([])
const results = ref<Page<Material> | null>(null)
const page = ref(1)
const loading = ref(false)
const error = ref('')

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

async function load() {
  loading.value = true
  error.value = ''
  results.value = null
  try {
    const query = new URLSearchParams({ page: String(page.value), pageSize: '20', status: status.value })
    if (keyword.value.trim()) query.set('keyword', keyword.value.trim())
    if (subject.value.trim()) query.set('subject', subject.value.trim())
    if (tagId.value) query.set('tagId', tagId.value)
    if (favoriteOnly.value) query.set('favorite', 'true')
    results.value = await api<Page<Material>>(`/materials?${query}`)
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

function search() { page.value = 1; load() }
function changePage(next: number) { page.value = next; load() }
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

onMounted(() => { loadTags(); load() })
</script>

<template>
  <div class="materials-layout">
    <div class="section-heading">
      <div><span class="intro-eyebrow">MY MATERIALS</span><h1>我的资料</h1><p>把零散的笔记收在一处，随时找回。</p></div>
      <div class="heading-actions"><router-link class="new-note-link" to="/materials/new">＋ 新建笔记</router-link><router-link class="new-note-link secondary-link" to="/materials/image/new">上传图片</router-link><router-link class="new-note-link secondary-link" to="/materials/pdf/new">上传 PDF</router-link></div>
    </div>

    <section class="materials-panel">
      <form class="search-row" @submit.prevent="search">
        <input v-model="keyword" aria-label="搜索标题或正文" placeholder="搜索标题或正文" maxlength="100" />
        <input v-model="subject" aria-label="按学科筛选" placeholder="学科" maxlength="64" class="subject-filter" />
        <select v-model="tagId" aria-label="按标签筛选" @change="search">
          <option value="">全部标签</option>
          <option v-for="tag in tags" :key="tag.id" :value="tag.id">{{ tag.name }}</option>
        </select>
        <select v-model="status" aria-label="按状态筛选" @change="search">
          <option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option><option value="ALL">全部</option>
        </select>
        <button type="submit">搜索</button>
      </form>
      <label class="favorite-filter"><input v-model="favoriteOnly" type="checkbox" @change="search" /> 只看收藏</label>
      <p v-if="error" class="inline-error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
      <p v-if="loading" class="list-state">正在加载资料…</p>
      <p v-else-if="results && results.items.length === 0" class="list-state">这里还没有符合条件的笔记。</p>
      <div v-else-if="results" class="material-list">
        <div v-for="item in results.items" :key="item.id" class="material-item-wrap">
          <router-link :to="`/materials/${item.id}`" class="material-item">
            <div class="material-item-main"><span class="note-badge">{{ item.type === 'IMAGE' ? '图片资料' : item.type === 'PDF' ? 'PDF 资料' : '文字笔记' }}{{ item.status === 'ARCHIVED' ? ' · 已归档' : '' }}</span><h2>{{ item.title }}</h2><p>{{ item.type === 'IMAGE' ? '打开查看原图' : item.type === 'PDF' ? `打开预览 · ${item.pageCount ?? '?'} 页` : (item.noteText?.slice(0, 110) || '打开查看笔记正文') }}</p></div>
            <div class="material-item-meta"><span>{{ item.subject || '未分类' }}</span><span v-for="tag in item.tags" :key="tag.id" class="mini-tag">#{{ tag.name }}</span><time>{{ formatDate(item.updatedAt) }}</time></div>
          </router-link>
          <button type="button" class="favorite-action" :disabled="favoriteBusy.includes(item.id)" :aria-label="item.favorite ? `取消收藏${item.title}` : `收藏${item.title}`" @click="toggleFavorite(item)">{{ item.favorite ? '★ 已收藏' : '☆ 收藏' }}</button>
        </div>
      </div>
      <div v-if="results && results.total > results.pageSize" class="pager">
        <button type="button" :disabled="page <= 1 || loading" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} 页 · 共 {{ results.total }} 条</span>
        <button type="button" :disabled="page * results.pageSize >= results.total || loading" @click="changePage(page + 1)">下一页</button>
      </div>
    </section>
  </div>
</template>
