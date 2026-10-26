<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import type { CardSource, KnowledgeCard } from '../knowledge'
import { cardId, graphPath, relationDescription, relationPayload, relationTypeLabels,
  type KnowledgeGraph, type GraphFilters, type RelationForm } from '../relations'
import KnowledgeGraphCanvas from './KnowledgeGraphCanvas.vue'
import KnowledgeGraphSources from './KnowledgeGraphSources.vue'

const route = useRoute(), router = useRouter(), ownerId = session.value?.user.id
const filters = reactive<GraphFilters>({ subject: String(route.query.subject || ''), rootCardId: String(route.query.rootCardId || '') })
const form = reactive<RelationForm>({ sourceCardId: '', targetCardId: '', type: 'RELATED', evidenceText: '' })
const graph = ref<KnowledgeGraph | null>(null), graphError = ref(''), notice = ref('')
const loading = ref(false), detailLoading = ref(false), endpointLoading = ref(false), saving = ref(false), deleting = ref(false)
const sourceSnapshot = ref<KnowledgeCard | null>(null), targetSnapshot = ref<KnowledgeCard | null>(null)
const createError = ref(''), deleteError = ref(''), detailError = ref(''), conflict = ref(false)
type Selection = { kind: 'card' | 'relation'; id: string }
const selection = ref<Selection | null>(null)
const details = ref<{ card: KnowledgeCard; sources: CardSource[] }[]>([])
const selectedRelation = computed(() => selection.value?.kind === 'relation'
  ? graph.value?.edges.find(edge => edge.id === selection.value?.id) : undefined)
const busy = computed(() => loading.value || detailLoading.value || endpointLoading.value || saving.value || deleting.value)
const dirty = computed(() => Boolean(form.sourceCardId || form.targetCardId || form.evidenceText || form.type !== 'RELATED'))
let active = true, authExpired = false, selectionVersion = 0
const current = () => active && session.value?.user.id === ownerId

function leave() {
  if (authExpired || !current()) return true
  if (busy.value) return false
  return !dirty.value || window.confirm('关联说明尚未保存，离开将放弃当前输入。确定继续吗？')
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !busy.value) return
  event.preventDefault(); event.returnValue = ''
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); loadGraph() })
onUnmounted(() => { active = false; window.removeEventListener('beforeunload', beforeUnload) })
watch(() => session.value?.user.id, () => {
  active = false; graph.value = null; selection.value = null; details.value = []
  sourceSnapshot.value = null; targetSnapshot.value = null
  Object.assign(form, { sourceCardId: '', targetCardId: '', type: 'RELATED', evidenceText: '' })
}, { flush: 'sync' })
watch(() => form.sourceCardId, () => { sourceSnapshot.value = null }, { flush: 'sync' })
watch(() => form.targetCardId, () => { targetSnapshot.value = null }, { flush: 'sync' })

function message(cause: unknown, fallback: string) {
  if (cause instanceof ApiRequestError && cause.status === 401) {
    authExpired = true; clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
  }
  return cause instanceof Error ? cause.message : fallback
}
function title(id: string) { return graph.value?.nodes.find(card => card.id === id)?.title || `卡片 #${id}` }
function edgeLabel(edge: NonNullable<typeof selectedRelation.value>) {
  return `${relationTypeLabels[edge.type]} · ${relationDescription(edge.type, title(edge.sourceCardId), title(edge.targetCardId))}`
}
function changeForm(field: keyof RelationForm, value: string) {
  if (!current() || busy.value) return
  if (field === 'type') {
    if (Object.hasOwn(relationTypeLabels, value)) form.type = value as RelationForm['type']
  } else form[field] = value
}
async function applyFilters() {
  if (!current() || busy.value) return
  try {
    graphPath(filters)
    const query = { subject: filters.subject.trim(), rootCardId: filters.rootCardId.trim() }
    const target = router.resolve({ path: '/knowledge-graph', query })
    if (target.fullPath === route.fullPath) await loadGraph()
    else await router.push(target.fullPath)
  } catch (cause) { if (current()) graphError.value = message(cause, '筛选无效') }
}
async function refreshGraph() {
  loading.value = true; graphError.value = ''; graph.value = null
  selection.value = null; details.value = []; detailError.value = ''; selectionVersion++
  try {
    const result = await api<KnowledgeGraph>(graphPath({ subject: String(route.query.subject || ''), rootCardId: String(route.query.rootCardId || '') }))
    if (current()) graph.value = result
  } catch (cause) { if (current()) graphError.value = message(cause, '图读取失败') }
  finally { if (current()) loading.value = false }
}
async function loadGraph() {
  if (!current() || busy.value) return
  await refreshGraph()
}
async function selectCard(id: string) {
  if (!current() || busy.value || !graph.value?.nodes.some(card => card.id === id)) return
  selection.value = { kind: 'card', id }; deleteError.value = ''
  await loadSelection()
}
async function selectRelation(id: string) {
  if (!current() || busy.value || !graph.value?.edges.some(edge => edge.id === id)) return
  selection.value = { kind: 'relation', id }; deleteError.value = ''
  await loadSelection()
}
async function loadSelection() {
  if (!current() || busy.value || !selection.value) return
  const selected = { ...selection.value }, edge = selectedRelation.value
  const ids = selected.kind === 'card' ? [selected.id] : edge ? [edge.sourceCardId, edge.targetCardId] : []
  if (!ids.length) return
  const version = ++selectionVersion
  detailLoading.value = true; detailError.value = ''; details.value = []
  try {
    const result = await Promise.all(ids.map(async id => {
      const path = `/cards/${encodeURIComponent(id)}`
      const [card, sourceList] = await Promise.all([api<KnowledgeCard>(path), api<{ items: CardSource[] }>(`${path}/sources`)])
      return { card, sources: sourceList.items }
    }))
    if (current() && version === selectionVersion) details.value = result
  } catch (cause) { if (current() && version === selectionVersion) detailError.value = message(cause, '卡片或来源读取失败') }
  finally { if (current() && version === selectionVersion) detailLoading.value = false }
}
async function readEndpoints() {
  if (!current() || busy.value) return
  let sourceId: string, targetId: string
  try {
    sourceId = cardId(form.sourceCardId); targetId = cardId(form.targetCardId)
    if (sourceId === targetId) throw new Error('不能将卡片与自身关联')
  } catch (cause) { createError.value = message(cause, '请填写两端卡片 ID'); return }
  sourceSnapshot.value = null; targetSnapshot.value = null; createError.value = ''; endpointLoading.value = true
  try {
    const [source, target] = await Promise.all([api<KnowledgeCard>(`/cards/${sourceId}`), api<KnowledgeCard>(`/cards/${targetId}`)])
    if (!current()) return
    if (sourceId !== form.sourceCardId.trim() || targetId !== form.targetCardId.trim()) return
    sourceSnapshot.value = source; targetSnapshot.value = target; conflict.value = false
  } catch (cause) { if (current()) createError.value = message(cause, '两端卡片读取失败，请重试') }
  finally { if (current()) endpointLoading.value = false }
}
async function createRelation() {
  if (!current() || busy.value || conflict.value) return
  let payload
  try { payload = relationPayload(form, sourceSnapshot.value, targetSnapshot.value) }
  catch (cause) { createError.value = message(cause, '请核对关联输入'); return }
  if (!window.confirm(`确认保存此关联吗？${relationDescription(payload.type, sourceSnapshot.value!.title, targetSnapshot.value!.title)}`)) return
  saving.value = true; createError.value = ''; notice.value = ''
  try {
    await api('/card-relations', 'POST', payload)
    if (!current()) return
    notice.value = '关联已保存。若当前筛选未包含两端卡片，请调整范围后查看。'
    Object.assign(form, { sourceCardId: '', targetCardId: '', type: 'RELATED', evidenceText: '' })
    sourceSnapshot.value = null; targetSnapshot.value = null
  } catch (cause) {
    if (!current()) return
    if (cause instanceof ApiRequestError && cause.status === 409) {
      conflict.value = true; sourceSnapshot.value = null; targetSnapshot.value = null
      createError.value = `请求 409：${cause.message}${cause.currentVersion ? `（当前版本 ${cause.currentVersion}）` : ''}。所有输入已保留，请重新读取两端卡片后核对再确认；已有关系不会被覆盖。`
    } else createError.value = message(cause, '关联保存失败，输入已保留')
    return
  } finally { if (current()) saving.value = false }
  if (current()) await refreshGraph()
}
async function deleteRelation() {
  const edge = selectedRelation.value
  if (!current() || busy.value || !edge || !window.confirm(`确定删除此关联吗？${edgeLabel(edge)}。仅删除关联。`)) return
  deleting.value = true; deleteError.value = ''; notice.value = ''
  try {
    await api(`/card-relations/${encodeURIComponent(edge.id)}`, 'DELETE')
    if (!current()) return
    notice.value = '关联已删除。'
    if (graph.value) graph.value.edges = graph.value.edges.filter(item => item.id !== edge.id)
    selection.value = null; details.value = []
  } catch (cause) { if (current()) deleteError.value = message(cause, '关联删除失败'); return }
  finally { if (current()) deleting.value = false }
  if (current()) await refreshGraph()
}
</script>

<template>
  <div class="materials-layout relation-layout">
    <router-link class="back-link" to="/cards">← 返回知识卡片</router-link>
    <div class="section-heading"><div><h1>知识关系图</h1><p>查看已确认关联，核对说明与两端卡片来源。</p></div></div>
    <section class="materials-panel relation-panel">
      <h2>查看范围</h2>
      <form class="search-row" @submit.prevent="applyFilters">
        <input v-model="filters.subject" :disabled="busy" aria-label="关系图学科" placeholder="学科（精确匹配）" maxlength="64" />
        <input v-model="filters.rootCardId" :disabled="busy" aria-label="中心卡片 ID" placeholder="中心卡片 ID（可选）" inputmode="numeric" maxlength="19" />
        <button type="submit" :disabled="busy">应用范围</button>
        <button type="button" :disabled="busy" @click="loadGraph">重新读取图</button>
      </form>
      <p class="task-state">中心筛选包含中心卡片及直接相邻卡片（入边与出边）；与学科筛选同时生效。关联图与父子层级分开。</p>
      <p v-if="notice" class="relation-success" role="status">{{ notice }}</p>
      <p v-if="loading" class="list-state" role="status">正在读取关系图…</p>
      <p v-if="graphError" class="inline-error" role="alert">图读取失败：{{ graphError }} <button type="button" :disabled="busy" @click="loadGraph">重试读取图</button></p>
      <template v-if="graph">
        <p v-if="graph.hasMore" class="candidate-warning" role="status">当前仅显示部分结果：最多 200 张卡片、600 条关联。请缩小学科或中心卡片范围后查看，当前图不代表全部关联。</p>
        <p v-if="!graph.nodes.length" class="list-state">当前范围没有卡片。请检查学科与中心卡片是否匹配，或调整范围。</p>
        <template v-else>
          <p class="task-state">{{ graph.nodes.length }} 张卡片 · {{ graph.edges.length }} 条关联。相关 ↔ 为无向；前置知识 → 起点是终点的前置；示例 → 终点是起点的示例。点击节点或下方关联核对来源。</p>
          <KnowledgeGraphCanvas :graph="graph" :disabled="busy" :selected-card-id="selection?.kind === 'card' ? selection.id : undefined" :selected-relation-id="selectedRelation?.id" @select-card="selectCard" @select-relation="selectRelation" />
          <h3>已确认关联</h3>
          <p v-if="!graph.edges.length" class="task-state">当前范围没有已确认关联，可在下方人工添加。</p>
          <ul v-else class="relation-list" aria-label="已确认关系列表"><li v-for="edge in graph.edges" :key="edge.id"><button type="button" :disabled="busy" :aria-pressed="selectedRelation?.id === edge.id" @click="selectRelation(edge.id)">{{ edgeLabel(edge) }}</button></li></ul>
        </template>
      </template>
      <section v-if="selection" class="relation-inspector" aria-label="选取内容与来源">
        <h2>{{ selectedRelation ? '关联说明与依据' : '卡片与来源' }}</h2>
        <template v-if="selectedRelation"><p>{{ edgeLabel(selectedRelation) }}</p><p class="candidate-copy">{{ selectedRelation.evidenceText }}</p><p class="task-state">人工确认说明；请核对其与当前卡片正文、历史来源的对应关系。</p></template>
        <p v-if="detailLoading" role="status">正在读取卡片与双方来源…</p>
        <p v-if="detailError" class="inline-error" role="alert">{{ detailError }} <button type="button" :disabled="busy" @click="loadSelection">重试卡片与来源</button></p>
        <div v-if="!detailError && !detailLoading" class="relation-details"><KnowledgeGraphSources v-for="detail in details" :key="detail.card.id" :card="detail.card" :sources="detail.sources" /></div>
        <p v-if="deleteError" class="inline-error" role="alert">{{ deleteError }}</p>
        <button v-if="selectedRelation" type="button" class="danger-button" :disabled="busy" @click="deleteRelation">{{ deleting ? '正在删除…' : '删除此关联' }}</button>
      </section>
    </section>
    <section class="materials-panel relation-panel">
      <h2>人工确认关联</h2><p class="task-state">填写两端卡片 ID，明确读取并核对当前版本后再保存。卡片来源已失效时仍可人工关联，来源正文将继续受访问权限限制。</p>
      <form @submit.prevent="createRelation">
        <div class="relation-form-row">
          <label>起点卡片 ID<input :value="form.sourceCardId" :disabled="busy" inputmode="numeric" maxlength="19" @input="changeForm('sourceCardId', ($event.target as HTMLInputElement).value)" /></label>
          <label>终点卡片 ID<input :value="form.targetCardId" :disabled="busy" inputmode="numeric" maxlength="19" @input="changeForm('targetCardId', ($event.target as HTMLInputElement).value)" /></label>
          <button type="button" :disabled="busy" @click="readEndpoints">{{ endpointLoading ? '正在读取…' : '读取并核对两端卡片' }}</button>
        </div>
        <div class="relation-details"><article v-if="sourceSnapshot" class="endpoint-snapshot"><h3>起点：{{ sourceSnapshot.title }} · #{{ sourceSnapshot.id }} · 版本 {{ sourceSnapshot.version }}</h3><p class="candidate-copy">{{ sourceSnapshot.content }}</p></article><article v-if="targetSnapshot" class="endpoint-snapshot"><h3>终点：{{ targetSnapshot.title }} · #{{ targetSnapshot.id }} · 版本 {{ targetSnapshot.version }}</h3><p class="candidate-copy">{{ targetSnapshot.content }}</p></article></div>
        <label class="relation-field">关系类型<select :value="form.type" :disabled="busy" @change="changeForm('type', ($event.target as HTMLSelectElement).value)"><option v-for="(label, type) in relationTypeLabels" :key="type" :value="type">{{ label }}</option></select></label>
        <p class="task-state">{{ relationDescription(form.type, sourceSnapshot?.title || '起点卡片', targetSnapshot?.title || '终点卡片') }}</p>
        <label class="relation-field">人工确认说明<textarea :value="form.evidenceText" :disabled="busy" rows="4" maxlength="2000" @input="changeForm('evidenceText', ($event.target as HTMLTextAreaElement).value)" /></label>
        <p v-if="createError" class="inline-error" role="alert">{{ createError }}</p>
        <button type="submit" class="save-relation" :disabled="busy || conflict || !sourceSnapshot || !targetSnapshot">{{ saving ? '正在保存…' : '确认保存关联' }}</button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.relation-panel { margin-bottom: 24px; }
.relation-panel h2 { margin-top: 0; font-size: 20px; }
.relation-panel h3 { font-size: 16px; }
.relation-panel button { padding: 10px 14px; border: 1px solid #dce5f2; border-radius: 8px; background: white; color: #315bc2; font: inherit; font-size: 13px; cursor: pointer; }
.relation-panel button:disabled, .relation-panel :is(input, textarea, select):disabled { opacity: .55; cursor: default; }
.relation-success { padding: 12px; border-radius: 8px; background: #edf7f0; color: #2e6549; }
.relation-list { display: grid; gap: 8px; padding-left: 20px; }
.relation-list button { text-align: left; overflow-wrap: anywhere; }
.relation-list button[aria-pressed="true"] { border-color: #315bc2; background: #eef3ff; }
.relation-inspector { margin-top: 24px; padding-top: 24px; border-top: 1px solid #e3e9f1; overflow-wrap: anywhere; }
.relation-details { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 16px 0; }
.relation-form-row { display: flex; align-items: end; gap: 16px; margin-top: 20px; }
.relation-form-row label { flex: 1; min-width: 0; }
.relation-field { display: block; margin: 16px 0 8px; }
.relation-panel label { font-size: 13px; color: #43516a; }
.relation-panel label :is(input, textarea, select) { display: block; width: 100%; margin-top: 8px; padding: 10px 12px; border: 1px solid #dfe6f0; border-radius: 8px; color: #34435a; background: white; font: inherit; }
.endpoint-snapshot { padding: 16px; border: 1px solid #e3e9f1; border-radius: 8px; overflow-wrap: anywhere; }
.relation-panel .save-relation { background: #315bc2; color: white; }
.relation-panel .danger-button { margin-top: 16px; color: #a13d32; }
.relation-panel :is(button, input, select, textarea):focus-visible { outline: 2px solid #315bc2; outline-offset: 2px; }
@media (max-width: 700px) { .relation-panel { padding: 20px; } .relation-details { grid-template-columns: 1fr; } .relation-form-row { flex-direction: column; align-items: stretch; } }
</style>
