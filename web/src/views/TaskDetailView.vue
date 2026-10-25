<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Material, Page } from '../materials'
import { goalLabels, stageLabels, statusLabels, stepStatusLabels, sourceLocator, taskErrorMessage, generationPresentation, externalProcessingConsent, type AiTask, type TaskStep, type CandidateResult, type CandidateSource } from '../tasks'

const route = useRoute()
const router = useRouter()
const task = ref<AiTask | null>(null)
const steps = ref<TaskStep[]>([])
const candidate = ref<CandidateResult | null>(null)
const generationView = computed(() => generationPresentation(candidate.value?.generationMode, candidate.value?.generationInfo))
const sources = ref<CandidateSource[]>([])
const error = ref('')
const loading = ref(false)
const busy = ref(false)
const consent = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

function taskPath() { return `/ai-tasks/${encodeURIComponent(String(route.params.id))}` }
function handleError(cause: unknown, fallback: string) {
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
  } else error.value = cause instanceof Error ? cause.message : fallback
}
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try {
    const [latest, history] = await Promise.all([
      api<AiTask>(taskPath()), api<Page<TaskStep>>(`${taskPath()}/steps?page=1&pageSize=100`),
    ])
    task.value = latest
    steps.value = history.items
    if (latest.status === 'WAIT_CONFIRM' || latest.status === 'SUCCEEDED') {
      const [result, sourceList] = await Promise.all([
        api<CandidateResult>(`${taskPath()}/result`),
        api<CandidateSource[]>(`${taskPath()}/sources`),
      ])
      candidate.value = result
      sources.value = sourceList
    } else { candidate.value = null; sources.value = [] }
  } catch (cause) { handleError(cause, '任务读取失败') }
  finally { loading.value = false }
}
async function cancel() {
  if (!task.value?.canCancel || busy.value || !window.confirm('确定取消这个整理任务吗？已发出的外部请求可能仍产生费用。')) return
  busy.value = true; error.value = ''
  try { task.value = await api<AiTask>(`${taskPath()}/cancellations`, 'POST'); await load() }
  catch (cause) { handleError(cause, '取消失败') }
  finally { busy.value = false }
}
async function retry() {
  if (!task.value?.canRetry || busy.value || !consent.value) return
  busy.value = true; error.value = ''
  try {
    const material = await api<Material>(`/materials/${encodeURIComponent(task.value.materialId)}`)
    const storageKey = `lms.task-retry.${task.value.id}.${material.version}`
    let requestKey = sessionStorage.getItem(storageKey)
    if (!requestKey) { requestKey = crypto.randomUUID(); sessionStorage.setItem(storageKey, requestKey) }
    const next = await api<AiTask>(`${taskPath()}/retries`, 'POST', {
      requestKey, materialVersion: material.version, externalProcessingConsent: true,
    })
    sessionStorage.removeItem(storageKey)
    await router.push(`/ai-tasks/${next.id}`)
    task.value = next; steps.value = []; consent.value = false
    candidate.value = null; sources.value = []
  } catch (cause) { handleError(cause, '重试失败') }
  finally { busy.value = false }
}
onMounted(() => { load(); timer = setInterval(() => { if (task.value && ['QUEUED', 'RUNNING'].includes(task.value.status)) load() }, 3000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="materials-layout">
    <router-link class="back-link" to="/ai-tasks">← 返回整理任务</router-link>
    <p v-if="loading && !task" class="list-state">正在读取任务…</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }} <button type="button" @click="load">刷新</button></p>
    <article v-if="task" class="materials-panel detail-panel">
      <span class="note-badge">{{ statusLabels[task.status] }}</span>
      <h1>{{ goalLabels[task.goalType] }}</h1>
      <div class="detail-meta"><span>任务 #{{ task.id }}</span><span>资料 #{{ task.materialId }} · 版本 {{ task.materialVersion }}</span><span v-if="task.parentTaskId">重试自任务 #{{ task.parentTaskId }}</span></div>
      <p class="task-state">当前阶段：{{ task.stage ? (stageLabels[task.stage] || task.stage) : '等待更新' }} · 已记录 {{ task.stepCount }} 个步骤</p>
      <p v-if="task.status === 'QUEUED'" class="task-hint">任务已排队。服务端处理器启用后会按配置模式生成候选，结果仍需人工确认。</p>
      <p v-if="task.status === 'RUNNING'" class="task-hint">worker 正在执行；页面会自动刷新。取消后，迟到的结果不会写入任务。</p>
      <p v-if="task.errorCode" class="inline-error">错误码：{{ task.errorCode }}</p>
      <p v-if="task.errorCode && taskErrorMessage(task.errorCode)" class="task-hint">{{ taskErrorMessage(task.errorCode) }}</p>
      <section v-if="candidate" class="candidate-preview" aria-label="候选预览">
        <div class="candidate-heading"><h2>候选预览</h2><span class="candidate-badge">{{ generationView.label }}</span></div>
        <p class="task-state">候选版本 {{ candidate.resultVersion }} · 来源版本 {{ candidate.sourceVersion }}<span v-if="candidate.totalPages"> · 原资料 {{ candidate.totalPages }} 页</span> · 实际处理 {{ candidate.processedPages.length }} 页</p>
        <p v-if="generationView.modelLabel" class="task-state">{{ generationView.modelLabel }}</p>
        <p class="candidate-warning">{{ generationView.warning }}</p>
        <p v-for="warning in candidate.warnings" :key="warning" class="candidate-warning">{{ warning }}</p>
        <h3>候选摘要</h3><p class="candidate-copy">{{ candidate.summary }}</p>
        <p v-if="candidate.tags.length" class="task-state">候选标签：{{ candidate.tags.join('、') }}</p>
        <div v-for="card in candidate.cards" :key="card.candidateKey" class="candidate-card">
          <h3>{{ card.title }}</h3><p class="candidate-copy">{{ card.content }}</p>
          <div v-for="ref in card.sources" :key="ref.chunkId" class="candidate-source">
            <strong>来源片段 #{{ ref.chunkId }}</strong>
            <span> · {{ sourceLocator(sources.find(source => source.chunkId === ref.chunkId)) }}</span>
            <p v-if="sources.find(source => source.chunkId === ref.chunkId)?.qualityNote" class="task-state">{{ sources.find(source => source.chunkId === ref.chunkId)?.qualityNote }}</p>
            <blockquote>{{ ref.quoteText }}</blockquote>
            <details><summary>查看完整来源片段</summary><p class="candidate-copy">{{ sources.find(source => source.chunkId === ref.chunkId)?.content }}</p></details>
          </div>
        </div>
        <p v-if="task.status === 'WAIT_CONFIRM'" class="task-hint">候选尚未保存。请核对来源，再编辑并确认需要的卡片。</p>
        <div class="detail-actions"><router-link v-if="task.status === 'WAIT_CONFIRM'" :to="`/ai-tasks/${task.id}/confirm`">编辑并确认保存</router-link><router-link v-if="task.status === 'SUCCEEDED'" to="/cards">查看已保存卡片</router-link></div>
      </section>
      <section class="task-steps"><h2>执行记录</h2><p v-if="!steps.length" class="task-state">暂无执行步骤。</p><ol v-else><li v-for="step in steps" :key="`${step.stepNo}-${step.attemptNo}`"><strong>{{ step.toolName }}</strong> · {{ stepStatusLabels[step.status] }}<span v-if="step.outputSummary"> · {{ step.outputSummary }}</span><span v-if="step.errorCode"> · {{ step.errorCode }}</span></li></ol></section>
      <label v-if="task.canRetry" class="task-retry-consent"><input v-model="consent" type="checkbox">{{ externalProcessingConsent }}</label>
      <div class="detail-actions"><router-link :to="`/materials/${task.materialId}`">查看原资料</router-link><button type="button" :disabled="loading" @click="load">刷新状态</button><button v-if="task.canCancel" type="button" class="danger-action" :disabled="busy" @click="cancel">取消任务</button><button v-if="task.canRetry" type="button" :disabled="busy || !consent" @click="retry">创建重试任务</button></div>
    </article>
  </div>
</template>
