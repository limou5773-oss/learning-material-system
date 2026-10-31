<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Page } from '../materials'
import { goalLabels, statusLabels, taskErrorMessage, type AiTask, type TaskStatus } from '../tasks'
import UiIcon from '../components/UiIcon.vue'

const route = useRoute()
const router = useRouter()
const initial = route.query
const status = ref(typeof initial.status === 'string' ? initial.status : '')
const page = ref(Math.max(1, Number(initial.page) || 1))
const results = ref<Page<AiTask> | null>(null)
const loading = ref(false)
const error = ref('')
const pageSize = 20
const statusOptions: TaskStatus[] = ['QUEUED', 'RUNNING', 'WAIT_CONFIRM', 'SUCCEEDED', 'FAILED', 'CANCELLED']
const tagTypes: Record<TaskStatus, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  QUEUED: 'info', RUNNING: 'primary', WAIT_CONFIRM: 'warning', SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'info',
}
const activeStatuses: TaskStatus[] = ['QUEUED', 'RUNNING']
const hasActive = computed(() => results.value?.items.some(task => activeStatuses.includes(task.status)) ?? false)
let timer: ReturnType<typeof setInterval> | undefined

function syncQuery() {
  const query: Record<string, string> = {}
  if (status.value) query.status = status.value
  if (page.value > 1) query.page = String(page.value)
  router.replace({ path: '/ai-tasks', query })
}

async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize) })
  if (status.value) query.set('status', status.value)
  try {
    results.value = await api<Page<AiTask>>(`/ai-tasks?${query}`)
    // 页码越界：任务被清理或筛选变化后回到最后一页，避免停在空页。
    if (results.value.items.length === 0 && page.value > 1 && results.value.total > 0) {
      page.value = Math.max(1, Math.ceil(results.value.total / pageSize))
      syncQuery()
      loading.value = false
      return load()
    }
  }
  catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '任务列表读取失败'
  } finally { loading.value = false }
}

function changeFilter() { page.value = 1; syncQuery(); load() }
function changePage(next: number) { page.value = next; syncQuery(); load() }
function resetFilter() { status.value = ''; changeFilter() }
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }

// 浏览器前进/后退恢复筛选：值相同则不重复请求，避免与 changeFilter 互触发。
watch(() => route.query, (q) => {
  const nextStatus = typeof q.status === 'string' ? q.status : ''
  const nextPage = Math.max(1, Number(q.page) || 1)
  if (nextStatus === status.value && nextPage === page.value) return
  status.value = nextStatus; page.value = nextPage; load()
})

onMounted(() => {
  load()
  // 有排队中或执行中的任务时每 4 秒自动刷新，其余状态不轮询。
  timer = setInterval(() => { if (hasActive.value) load() }, 4000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="materials-layout tasks-layout">
    <div class="section-heading"><div><h1>整理任务</h1><p>跟进资料整理进度，核对来源后，将有用的知识保存下来。</p></div><router-link class="new-note-link" to="/materials"><UiIcon name="plus" />选择资料开始整理</router-link></div>
    <section class="materials-panel">
      <div class="filter-bar">
        <el-select v-model="status" class="filter-fixed" placeholder="全部状态" aria-label="按状态筛选" @change="changeFilter">
          <el-option label="全部状态" value="" />
          <el-option v-for="value in statusOptions" :key="value" :label="statusLabels[value]" :value="value" />
        </el-select>
        <div class="filter-actions">
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button v-if="status" @click="resetFilter">清除筛选</el-button>
        </div>
      </div>
      <p v-if="hasActive" class="task-polling" role="status">有任务正在处理中，列表每 4 秒自动刷新。</p>
      <el-alert v-if="error" class="list-alert" type="error" show-icon :closable="false" role="alert">
        <template #title>{{ error }} <el-button link type="primary" @click="load">重试</el-button></template>
      </el-alert>
      <div v-if="loading && !results" class="loading-panel"><el-skeleton :rows="4" animated /></div>
      <el-empty v-else-if="results && !results.items.length" :description="page > 1 ? '本页已经没有任务了' : '暂无符合条件的整理任务'">
        <p class="empty-hint">{{ page > 1 ? '页码超出了当前结果范围，回到第一页即可继续查看。' : '从资料详情发起 AI 整理，结果会在这里等待你确认。' }}</p>
        <el-button v-if="page > 1" type="primary" @click="changePage(1)">回到第一页</el-button>
        <el-button v-else type="primary" @click="router.push('/materials')">去选择一份学习资料</el-button>
      </el-empty>
      <div v-else-if="results" class="material-list task-list">
        <router-link v-for="task in results.items" :key="task.id" class="material-item" :to="`/ai-tasks/${task.id}`">
          <div class="material-item-main">
            <el-tag size="small" :type="tagTypes[task.status]" effect="light">{{ statusLabels[task.status] }}</el-tag>
            <h2>{{ goalLabels[task.goalType] }}</h2>
            <p>资料 #{{ task.materialId }} · 版本 {{ task.materialVersion }}</p>
            <p v-if="task.errorCode" class="task-error">{{ taskErrorMessage(task.errorCode) || task.errorCode }}</p>
          </div>
          <div class="material-item-meta"><span>任务 #{{ task.id }}</span><time>{{ formatDate(task.createdAt) }}</time></div>
        </router-link>
      </div>
      <el-pagination v-if="results && results.total > pageSize" class="list-pager" background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="results.total" @current-change="changePage" />
    </section>
  </div>
</template>

<style scoped>
.list-alert { margin-bottom: 16px; }
.empty-hint { margin: 0 0 18px; color: var(--muted); font-size: 13px; }
.list-pager { margin-top: 20px; }
.task-polling { margin: -4px 0 14px; color: var(--brand-hover); font-size: 12px; }
.task-list .material-item { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 24px; padding: 15px 14px; border-top: 1px solid var(--line); border-radius: var(--r-md); transition: background .16s; }
.task-list .material-item:first-child { border-top: 0; }
.task-list .material-item:hover { background: var(--surface-2); }
.task-list .material-item-main { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; column-gap: 10px; row-gap: 5px; min-width: 0; }
.task-list .el-tag { grid-row: 1; margin-bottom: 0; }
.task-list .material-item-main h2 { grid-column: 2; grid-row: 1; margin: 0; font-size: 15px; font-weight: 600; line-height: 1.5; }
.task-list .material-item-main p { grid-column: 1 / -1; margin: 0; color: var(--muted); font-size: 13px; line-height: 1.65; }
.task-list .material-item-meta { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; margin-top: 0; text-align: right; white-space: nowrap; }
.task-list .material-item-meta span { color: #7d8ea5; font-size: 11px; }
.task-error { color: var(--el-color-danger) !important; }
</style>
