<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import { buildConfirmationInput, createConfirmationDraft, isConfirmationDraft, type ConfirmationDraft } from '../confirmation'
import type { AiTask, CandidateResult, CandidateSource, ConfirmationResult } from '../tasks'
import { sourceLocator, generationPresentation, relationEndpointLabel } from '../tasks'
import { relationDescription, relationTypeLabels } from '../relations'

const route = useRoute()
const router = useRouter()
const taskId = String(route.params.id)
const draftKey = `lms.confirmDraft.v1.${session.value?.user.id}.${taskId}`
const task = ref<AiTask | null>(null)
const draft = ref<ConfirmationDraft | null>(null)
const candidate = ref<CandidateResult | null>(null)
const generationView = computed(() => generationPresentation(candidate.value?.generationMode, candidate.value?.generationInfo))
const sources = ref<CandidateSource[]>([])
const receipt = ref<ConfirmationResult | null>(null)
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const restored = ref(false)
const changed = ref(false)
const error = ref('')
const selectedCount = computed(() => draft.value?.cards.filter(card => card.selected).length || 0)

function persist() {
  if (!draft.value || receipt.value) return
  try { sessionStorage.setItem(draftKey, JSON.stringify(draft.value)) } catch { /* 页面仍保留当前编辑内容 */ }
}
watch(draft, () => { if (!loading.value && !receipt.value) { changed.value = true; persist() } }, { deep: true })
onBeforeRouteLeave(() => !changed.value || !!receipt.value || window.confirm('确认内容尚未保存，草稿将保留在当前标签页。确定离开吗？'))
function handleError(cause: unknown) {
  if (cause instanceof ApiRequestError && cause.status === 401) {
    persist(); clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
  } else error.value = cause instanceof Error ? cause.message : '请求失败，草稿已保留'
}
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try {
    if (!draft.value) {
      try {
        const stored: unknown = JSON.parse(sessionStorage.getItem(draftKey) || 'null')
        if (isConfirmationDraft(stored) && stored.taskId === taskId) { draft.value = stored; restored.value = true }
      } catch { /* 不使用损坏的草稿 */ }
    }
    task.value = await api<AiTask>(`/ai-tasks/${encodeURIComponent(taskId)}`)
    if (!['WAIT_CONFIRM', 'SUCCEEDED'].includes(task.value.status)) {
      conflict.value = true
      error.value = '任务已不可确认。下方草稿仍可复制，请返回任务查看状态。'
      return
    }
    const [result, sourceList] = await Promise.all([
      api<CandidateResult>(`/ai-tasks/${encodeURIComponent(taskId)}/result`),
      api<CandidateSource[]>(`/ai-tasks/${encodeURIComponent(taskId)}/sources`),
    ])
    candidate.value = result; sources.value = sourceList
    if (!draft.value) draft.value = createConfirmationDraft(result)
    else if (draft.value.resultVersion !== result.resultVersion || draft.value.sourceVersion !== result.sourceVersion) {
      conflict.value = true; error.value = '候选版本已变化，原草稿已保留。请复制需要的内容后重新整理。'
    }
  } catch (cause) { handleError(cause) }
  finally { loading.value = false }
}
async function submit() {
  if (!draft.value || saving.value || conflict.value || receipt.value) return
  let input
  try { input = buildConfirmationInput(draft.value) } catch (cause) { handleError(cause); return }
  const alreadySaved = task.value?.status === 'SUCCEEDED'
  if (!alreadySaved && !window.confirm(`确认保存 ${input.cards.length} 张卡片，并更新资料摘要与标签吗？`)) return
  saving.value = true; error.value = ''; persist()
  try {
    receipt.value = await api<ConfirmationResult>(`/ai-tasks/${encodeURIComponent(taskId)}/confirmation`, 'PUT', input)
    changed.value = false
    try { sessionStorage.removeItem(draftKey) } catch { /* 已保存结果仍可查看 */ }
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409) {
      conflict.value = true
      error.value = `${cause.message}。编辑草稿已保留，请返回任务重新整理，勿覆盖新版本。`
    } else handleError(cause)
    persist()
  } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div class="confirmation-layout">
    <router-link class="back-link" :to="`/ai-tasks/${taskId}`">← 返回任务详情</router-link>
    <section class="materials-panel editor-panel">
      <div class="candidate-heading"><h1>确认整理结果</h1><span v-if="candidate" class="candidate-badge">{{ generationView.label }}</span></div>
      <p class="task-state">核对来源后，选择需要的卡片并修改文字。确认保存后，两端均可查阅。</p>
      <p v-if="candidate && generationView.modelLabel" class="task-state">{{ generationView.modelLabel }}</p>
      <p v-if="candidate" class="candidate-warning">{{ generationView.warning }}</p>
      <p v-if="loading" role="status">正在读取候选结果…</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }} <button v-if="!conflict" type="button" @click="load">重试读取</button></p>
      <p v-if="restored && !receipt" class="task-hint">已恢复当前账号的确认草稿，来源版本保持不变。</p>
      <section v-if="receipt" class="confirmation-success" role="status">
        <h2>已保存 {{ receipt.cardIds.length }} 张知识卡片</h2>
        <p>资料已更新为版本 {{ receipt.resultingMaterialVersion }}。重复提交只会返回本次保存结果。</p>
        <div class="detail-actions"><router-link v-for="id in receipt.cardIds" :key="id" :to="`/cards/${id}`">查看卡片 #{{ id }}</router-link><router-link to="/cards">全部知识卡片</router-link></div>
      </section>
      <template v-else-if="draft">
        <p v-if="task?.status === 'SUCCEEDED'" class="task-hint">此任务已完成，可以读取上次保存结果，不会再次创建卡片。</p>
        <p v-for="warning in candidate?.warnings || []" :key="warning" class="candidate-warning">{{ warning }}</p>
        <p class="task-state">候选版本 {{ draft.resultVersion }} · 来源版本 {{ draft.sourceVersion }}</p>
        <form @submit.prevent="submit">
          <fieldset :disabled="saving || task?.status === 'SUCCEEDED'" class="confirmation-fields">
            <label for="confirm-summary">资料摘要</label>
            <textarea id="confirm-summary" v-model="draft.summary" maxlength="4000" rows="4" />
            <label for="confirm-tags">资料标签 <small>用逗号分隔，最多10个</small></label>
            <input id="confirm-tags" v-model="draft.tagsText" maxlength="650">
            <label for="confirm-tag-mode">标签保存方式</label>
            <select id="confirm-tag-mode" v-model="draft.tagMode"><option value="MERGE">与原标签合并</option><option value="REPLACE">替换原标签</option></select>
            <h2>选择知识卡片</h2>
            <p class="task-state">已选择 {{ selectedCount }} 张。未选择父卡片时，子卡片会保存为根节点。</p>
            <div v-for="(card, index) in draft.cards" :key="card.candidateKey" class="candidate-card">
              <label class="task-check"><input v-model="card.selected" type="checkbox">保存第 {{ index + 1 }} 张卡片</label>
              <label :for="`card-title-${index}`">标题</label><input :id="`card-title-${index}`" v-model="card.title" maxlength="255" :disabled="!card.selected">
              <label :for="`card-content-${index}`">正文</label><textarea :id="`card-content-${index}`" v-model="card.content" rows="5" maxlength="8000" :disabled="!card.selected" />
              <label :for="`card-tags-${index}`">卡片标签</label><input :id="`card-tags-${index}`" v-model="card.tagsText" maxlength="650" :disabled="!card.selected">
              <details class="candidate-source"><summary>对照本卡片来源</summary><div v-for="source in card.sources" :key="source.chunkId"><p>来源片段 #{{ source.chunkId }}</p><blockquote>{{ source.quoteText }}</blockquote></div></details>
            </div>
            <section v-if="draft.relations.length"><h2>关联建议</h2><p class="task-state">关联默认不保存，请逐条核对后勾选。</p>
              <label v-for="relation in draft.relations" :key="relation.relationKey" class="task-check"><input v-model="relation.selected" type="checkbox">{{ relationTypeLabels[relation.type] }} · {{ relationDescription(relation.type, relationEndpointLabel(relation.source, draft.cards), relationEndpointLabel(relation.target, draft.cards)) }}：{{ relation.evidenceText }}</label>
            </section>
          </fieldset>
          <div class="editor-actions"><span>输入将保留为当前账号的标签页草稿</span><button type="submit" class="primary-button" :disabled="saving || conflict || loading || !selectedCount">{{ saving ? '正在保存…' : task?.status === 'SUCCEEDED' ? '读取上次保存结果' : `确认保存 ${selectedCount} 张卡片` }}</button></div>
        </form>
      </template>
    </section>
    <aside v-if="sources.length && !receipt" class="materials-panel confirmation-source-panel"><h2>原始来源</h2><section v-for="source in sources" :key="source.chunkId" class="candidate-source"><h3>片段 #{{ source.chunkId }}</h3><p class="task-state">{{ sourceLocator(source) }}</p><p v-if="source.qualityNote" class="candidate-warning">{{ source.qualityNote }}</p><p class="candidate-copy">{{ source.content }}</p></section></aside>
  </div>
</template>
