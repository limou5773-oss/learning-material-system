<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Page } from '../materials'
import { goalLabels, statusLabels, type AiTask, type TaskStatus } from '../tasks'

const route = useRoute()
const router = useRouter()
const status = ref('')
const page = ref(1)
const results = ref<Page<AiTask> | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page.value), pageSize: '20' })
  if (status.value) query.set('status', status.value)
  try { results.value = await api<Page<AiTask>>(`/ai-tasks?${query}`) }
  catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '任务列表读取失败'
  } finally { loading.value = false }
}

function changeFilter() { page.value = 1; load() }
function changePage(next: number) { page.value = next; load() }
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
onMounted(load)
</script>

<template>
  <div class="materials-layout narrow">
    <div class="section-heading"><div><span class="intro-eyebrow">ORGANIZE QUEUE</span><h1>整理任务</h1><p>查看本人提交的任务与实际状态。</p></div></div>
    <section class="materials-panel">
      <div class="task-toolbar"><label for="task-status">状态</label><select id="task-status" v-model="status" @change="changeFilter"><option value="">全部</option><option v-for="value in (['QUEUED','RUNNING','WAIT_CONFIRM','SUCCEEDED','FAILED','CANCELLED'] as TaskStatus[])" :key="value" :value="value">{{ statusLabels[value] }}</option></select><button type="button" @click="load">刷新</button></div>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading && !results" class="list-state">正在读取任务…</p>
      <p v-else-if="results && !results.items.length" class="list-state">暂无符合条件的任务。</p>
      <div v-else-if="results" class="material-list">
        <router-link v-for="task in results.items" :key="task.id" class="material-item" :to="`/ai-tasks/${task.id}`"><div class="material-item-main"><span class="note-badge">{{ statusLabels[task.status] }}</span><h2>{{ goalLabels[task.goalType] }}</h2><p>资料 #{{ task.materialId }} · 版本 {{ task.materialVersion }}</p></div><div class="material-item-meta"><span>任务 #{{ task.id }}</span><time>{{ formatDate(task.createdAt) }}</time></div></router-link>
      </div>
      <div v-if="results && results.total > results.pageSize" class="pager"><button type="button" :disabled="page <= 1 || loading" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} 页 · 共 {{ results.total }} 条</span><button type="button" :disabled="page * results.pageSize >= results.total || loading" @click="changePage(page + 1)">下一页</button></div>
    </section>
  </div>
</template>
