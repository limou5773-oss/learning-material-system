<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Material } from '../materials'
import { externalProcessingConsent, type AiTask, type GoalType } from '../tasks'

const route = useRoute()
const router = useRouter()
const material = ref<Material | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const form = reactive({ goalType: 'REVIEW_CARDS' as GoalType, instruction: '',
  from: 1, to: 1, allowRelatedSearch: false, consent: false })

async function load() {
  loading.value = true; error.value = ''
  try {
    material.value = await api<Material>(`/materials/${encodeURIComponent(String(route.params.id))}`)
    if (material.value.type === 'PDF') form.to = Math.min(material.value.pageCount || 1, 20)
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '资料读取失败'
  } finally { loading.value = false }
}

async function submit() {
  if (!material.value || saving.value) return
  error.value = ''
  if (!form.consent) { error.value = '请先同意资料用于外部处理'; return }
  if (form.instruction.length > 1000) { error.value = '补充说明不能超过 1000 字'; return }
  if (material.value.type === 'PDF' && (!Number.isInteger(form.from) || !Number.isInteger(form.to) ||
      form.from < 1 || form.to < form.from || form.to - form.from + 1 > 20 || form.to > (material.value.pageCount || 0))) {
    error.value = '请选择 PDF 内连续且不超过 20 页的页段'; return
  }
  const input = {
    materialId: material.value.id, materialVersion: material.value.version,
    goalType: form.goalType, instruction: form.instruction.trim(),
    pageRange: material.value.type === 'PDF' ? { from: form.from, to: form.to } : undefined,
    allowRelatedSearch: form.allowRelatedSearch, externalProcessingConsent: true,
  }
  const storageKey = `lms.task-submit.${material.value.id}`
  const fingerprint = JSON.stringify(input)
  let stored: { fingerprint: string; requestKey: string } | null = null
  try { stored = JSON.parse(sessionStorage.getItem(storageKey) || 'null') }
  catch { /* Invalid draft is replaced. */ }
  const requestKey = stored?.fingerprint === fingerprint ? stored.requestKey : crypto.randomUUID()
  sessionStorage.setItem(storageKey, JSON.stringify({ fingerprint, requestKey }))
  saving.value = true
  try {
    const task = await api<AiTask>('/ai-tasks', 'POST', { ...input, requestKey })
    sessionStorage.removeItem(storageKey)
    await router.replace(`/ai-tasks/${task.id}`)
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '提交任务失败'
  } finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" :to="`/materials/${route.params.id}`">← 返回资料</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">ORGANIZE</span><h1>提交整理任务</h1><p>任务先进入队列；处理模式由服务端配置决定，候选均需人工确认。</p></div></div>
    <p v-if="loading" class="list-state">正在读取资料…</p>
    <section v-if="material" class="materials-panel editor-panel">
      <p class="task-source">资料：{{ material.title }} · 版本 {{ material.version }}</p>
      <form @submit.prevent="submit">
        <label for="task-goal">整理目标</label>
        <select id="task-goal" v-model="form.goalType"><option value="REVIEW_CARDS">生成复习卡片</option><option value="EXTRACT_KEY_POINTS">提炼课件重点</option><option value="LINK_NOTES">整理并关联已有笔记</option></select>
        <label for="task-instruction">补充说明</label><textarea id="task-instruction" v-model="form.instruction" maxlength="1000" rows="4" placeholder="可选，说明希望关注的内容" />
        <div v-if="material.type === 'PDF'" class="task-range"><label for="task-from">起始页<input id="task-from" v-model.number="form.from" type="number" min="1" :max="material.pageCount || 1" /></label><label for="task-to">结束页<input id="task-to" v-model.number="form.to" type="number" min="1" :max="material.pageCount || 1" /></label><small>一次最多整理 20 页，共 {{ material.pageCount }} 页</small></div>
        <label class="task-check"><input v-model="form.allowRelatedSearch" type="checkbox" /> 允许检索本人相关笔记</label>
        <label class="task-check"><input v-model="form.consent" type="checkbox" /> {{ externalProcessingConsent }}</label>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
        <div class="editor-actions"><span>提交后可在“整理任务”查看状态</span><button type="submit" :disabled="saving">{{ saving ? '正在提交…' : '提交任务' }}</button></div>
      </form>
    </section>
    <p v-else-if="error" class="inline-error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
  </div>
</template>
