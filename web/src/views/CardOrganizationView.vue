<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { CardFilters, KnowledgeCard } from '../knowledge'
import { layoutPayload, loadOrganizationRange, prepareLayout, type CardLayout, type LayoutItem } from '../organization'
import OrganizationCanvas from './OrganizationCanvas.vue'
import OrganizationHierarchy from './OrganizationHierarchy.vue'

const route = useRoute(), router = useRouter()
const filters = reactive<CardFilters>({ keyword: String(route.query.keyword || ''), subject: String(route.query.subject || ''),
  tag: String(route.query.tag || ''), favoriteOnly: route.query.favoriteOnly === 'true' })
const cards = ref<KnowledgeCard[]>([]), items = ref<LayoutItem[]>([]), unloadedIds = ref<string[]>([])
const layoutVersion = ref(0), ready = ref(false), loading = ref(false), saving = ref(false), parentSaving = ref(false)
const error = ref(''), notice = ref(''), baseline = ref(''), viewMode = ref('canvas')
const conflict = ref(false), currentVersion = ref<number | null>(null), serverCopy = ref<CardLayout | null>(null)
const serverLoading = ref(false), serverError = ref(''), selectedId = ref(''), parentChoice = ref(''), parentConflict = ref(false)
const selectedCard = computed(() => cards.value.find(card => card.id === selectedId.value))
const selectedItem = computed(() => items.value.find(item => item.cardId === selectedId.value))
const busy = computed(() => loading.value || saving.value || serverLoading.value || parentSaving.value)
const dirty = computed(() => ready.value && JSON.stringify(items.value) !== baseline.value)
const parentOutside = computed(() => selectedCard.value?.parentCardId && !cards.value.some(card => card.id === selectedCard.value?.parentCardId))
const validParent = computed(() => parentChoice.value === '' || cards.value.some(card => card.id === parentChoice.value && card.id !== selectedId.value))
let active = true, authExpired = false

function confirmDiscard(message: string) { return (!dirty.value && !conflict.value) || window.confirm(message) }
function confirmLeave() {
  if (authExpired) return true
  if (busy.value) return false
  return confirmDiscard('离开将放弃当前本地画布的位置和顺序，确定继续吗？')
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !busy.value) return
  event.preventDefault(); event.returnValue = ''
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate(confirmLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onUnmounted(() => { active = false; window.removeEventListener('beforeunload', beforeUnload) })
function handleError(cause: unknown, fallback: string) {
  if (!active) return
  if (cause instanceof ApiRequestError && cause.status === 401) {
    authExpired = true; clearSession(); router.replace({ name: 'login', query: { redirect: route.fullPath } })
  } else error.value = cause instanceof Error ? cause.message : fallback
}
function selectCard(id: string) {
  if (busy.value) return
  selectedId.value = id; parentChoice.value = cards.value.find(card => card.id === id)?.parentCardId || ''
  parentConflict.value = false
}
function applyLayout(layout: CardLayout) {
  const prepared = prepareLayout(cards.value, layout.items)
  items.value = layout.layoutVersion > 0 && !layout.items.length ? [] : prepared.items
  unloadedIds.value = prepared.unloadedIds; layoutVersion.value = layout.layoutVersion
  baseline.value = JSON.stringify(items.value); conflict.value = false; currentVersion.value = null; serverCopy.value = null
  serverError.value = ''; error.value = ''; notice.value = ''
}
async function load() {
  if (busy.value || !confirmDiscard('重新读取会放弃当前本地画布的位置和顺序，确定继续吗？')) return
  const applied = { ...filters }
  loading.value = true; error.value = ''; notice.value = ''
  try {
    const layout = await api<CardLayout>('/card-layouts/default')
    const loaded = await loadOrganizationRange(path => api(path), applied)
    if (!active) return
    cards.value = loaded; applyLayout(layout); ready.value = true
    selectedId.value = loaded[0]?.id || ''; parentChoice.value = loaded[0]?.parentCardId || ''; parentConflict.value = false
  } catch (cause) { ready.value = false; handleError(cause, '卡片范围加载失败') }
  finally { if (active) loading.value = false }
}
function moveItem(change: { cardId: string; x: number; y: number }) {
  if (busy.value || !ready.value) return
  const item = items.value.find(item => item.cardId === change.cardId)
  if (!item) return
  item.x = Math.max(-100000, Math.min(100000, change.x)); item.y = Math.max(-100000, Math.min(100000, change.y))
  notice.value = ''
}
function changeNumber(field: 'x' | 'y' | 'orderNo', event: Event) {
  if (busy.value || !selectedItem.value) return
  const input = event.target as HTMLInputElement, value = input.value === '' ? NaN : Number(input.value)
  const changed = items.value.map(item => item.cardId === selectedId.value ? { ...item, [field]: value } : item)
  try { layoutPayload(layoutVersion.value, changed); items.value = changed; error.value = ''; notice.value = '' }
  catch (cause) { error.value = (cause as Error).message; input.value = String(selectedItem.value[field]) }
}
function clearCanvas() {
  if (busy.value || !ready.value || !window.confirm('确定清空当前画布吗？保存后 default 布局为空，卡片正文和父子关系不受影响。')) return
  items.value = []; notice.value = ''
}
function includeRange() {
  if (busy.value || !ready.value) return
  items.value = prepareLayout(cards.value, items.value).items; notice.value = ''
}
async function saveLayout() {
  if (busy.value || !ready.value || conflict.value) return
  let payload
  try { payload = layoutPayload(layoutVersion.value, items.value) }
  catch (cause) { error.value = (cause as Error).message; return }
  if (unloadedIds.value.length && !window.confirm(`保存将完整替换 default 布局，并移除 ${unloadedIds.value.length} 张未载入卡片的已保存位置，确定继续吗？`)) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await api<CardLayout>('/card-layouts/default', 'PUT', payload)
    if (!active) return
    items.value = saved.items; layoutVersion.value = saved.layoutVersion; baseline.value = JSON.stringify(items.value)
    unloadedIds.value = []; notice.value = `当前画布已保存，版本 ${saved.layoutVersion}。`
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409) {
      conflict.value = true; currentVersion.value = cause.currentVersion ?? null; serverCopy.value = null
      error.value = '布局版本冲突，本地位置、顺序和原版本已保留。请读取服务器快照后决定是否放弃本地修改。'
    } else handleError(cause, '保存失败，本地位置已保留')
  } finally { if (active) saving.value = false }
}
async function loadServerCopy() {
  if (busy.value) return
  serverLoading.value = true; serverError.value = ''
  try {
    const layout = await api<CardLayout>('/card-layouts/default')
    if (!active) return
    serverCopy.value = layout; currentVersion.value = layout.layoutVersion
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) handleError(cause, '登录已失效')
    if (active) serverError.value = cause instanceof Error ? cause.message : '服务器快照读取失败'
  } finally { if (active) serverLoading.value = false }
}
function discardLocal() {
  if (busy.value || !serverCopy.value || !window.confirm('确定放弃本地位置和顺序，载入刚读取的服务器布局吗？')) return
  applyLayout(serverCopy.value)
}
async function saveParent() {
  const child = selectedCard.value
  if (busy.value || !ready.value || !child || !validParent.value || parentConflict.value) return
  const parentCardId = parentChoice.value || null
  parentSaving.value = true; error.value = ''; notice.value = ''
  try {
    const updated = await api<KnowledgeCard>(`/cards/${encodeURIComponent(child.id)}/parent`, 'PUT', { version: child.version, parentCardId })
    if (!active) return
    cards.value = cards.value.map(card => card.id === updated.id ? updated : card)
    parentChoice.value = updated.parentCardId || ''; notice.value = '父子层级已保存。画布位置需单独保存。'
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409) {
      parentConflict.value = true
      error.value = `卡片已有新版本 ${cause.currentVersion ?? '待读取'}，父级选择已保留。请重新读取当前范围后再设置父级。`
    } else handleError(cause, '层级保存失败，父级选择已保留')
  } finally { if (active) parentSaving.value = false }
}
</script>

<template>
  <div class="materials-layout organization-layout">
    <router-link class="back-link" to="/cards">← 返回知识卡片</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">CARD ORGANIZATION</span><h1>卡片组织</h1><p>保存位置与顺序，按父子层级查看当前范围。</p></div></div>
    <section class="materials-panel organization-panel">
      <form class="search-row" @submit.prevent="load">
        <input v-model="filters.keyword" :disabled="busy" aria-label="搜索组织范围标题或正文" placeholder="搜索标题或正文" maxlength="100" />
        <input v-model="filters.subject" :disabled="busy" aria-label="组织范围学科" placeholder="学科" maxlength="64" class="subject-filter" />
        <input v-model="filters.tag" :disabled="busy" aria-label="组织范围标签" placeholder="标签" maxlength="64" class="subject-filter" />
        <button type="submit" :disabled="busy">应用筛选并重新读取</button>
      </form>
      <label class="favorite-filter"><input v-model="filters.favoriteOnly" :disabled="busy" type="checkbox" /> 仅收藏（应用筛选后生效）</label>
      <p class="organization-hint">每次最多读取 200 张卡片。保存会用当前画布完整替换 default 布局；其他范围的已保存位置将被移除。清空画布只清除布局，不删除卡片。</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="organization-success" role="status">{{ notice }}</p>
      <p v-if="loading" class="list-state" role="status">正在读取布局和完整卡片范围…</p>
      <template v-if="ready">
        <p class="organization-status">当前范围 {{ cards.length }} 张 · 画布 {{ items.length }} 张 · 本地布局版本 {{ layoutVersion }}{{ dirty ? ' · 位置或顺序未保存' : '' }}</p>
        <p v-if="unloadedIds.length" class="candidate-warning">已保存但当前未载入的卡片（{{ unloadedIds.length }} 张）：{{ unloadedIds.join('、') }}。这些卡片可能在范围外或已失效，保存当前画布会移除其位置。</p>
        <div v-if="conflict" class="server-copy">
          <strong>服务器布局版本 {{ currentVersion ?? '待读取' }}；本地版本 {{ layoutVersion }} 与坐标已保留。</strong>
          <p v-if="serverError" class="inline-error" role="alert">{{ serverError }}</p>
          <button type="button" :disabled="busy" @click="loadServerCopy">{{ serverLoading ? '正在读取…' : '读取服务器快照' }}</button>
          <template v-if="serverCopy"><p>服务器快照：版本 {{ serverCopy.layoutVersion }}，{{ serverCopy.items.length }} 张卡片。只有下方按钮会替换本地画布。</p><button type="button" :disabled="busy" @click="discardLocal">放弃本地位置，载入服务器布局</button></template>
        </div>
        <div class="organization-toolbar">
          <button type="button" :disabled="busy" :aria-pressed="viewMode === 'canvas'" @click="viewMode = 'canvas'">位置画布</button>
          <button type="button" :disabled="busy" :aria-pressed="viewMode === 'hierarchy'" @click="viewMode = 'hierarchy'">父子层级图</button>
          <button type="button" :disabled="busy" @click="includeRange">加入当前范围卡片</button>
          <button type="button" :disabled="busy" @click="clearCanvas">清空画布</button>
          <button type="button" class="save-layout" :disabled="busy || conflict" @click="saveLayout">{{ saving ? '正在保存…' : `保存当前画布（${items.length} 张）` }}</button>
        </div>
        <p v-if="viewMode === 'canvas'" class="organization-hint">拖动卡片顶部移动；也可聚焦移动按钮后按方向键（10，Shift + 方向键为 50），或在下方输入坐标与顺序。负坐标通过显示偏移呈现，保存值不变。</p>
        <p v-else class="organization-hint">连线表示当前范围的父子层级。范围外父级会明确标注；这是层级图，未展示知识关联类型或依据。</p>
        <OrganizationCanvas v-if="viewMode === 'canvas'" :cards="cards" :items="items" :selected-id="selectedId" :disabled="busy" @select="selectCard" @move="moveItem" />
        <OrganizationHierarchy v-else :cards="cards" />
        <section v-if="cards.length" class="organization-inspector">
          <h2>位置与父子层级</h2>
          <label for="organization-card">当前卡片</label>
          <select id="organization-card" :value="selectedId" :disabled="busy" @change="selectCard(($event.target as HTMLSelectElement).value)"><option v-for="card in cards" :key="card.id" :value="card.id">{{ card.title }}</option></select>
          <div v-if="selectedItem" class="position-fields">
            <label>X 坐标<input type="number" :value="selectedItem.x" :disabled="busy" min="-100000" max="100000" step="any" @change="changeNumber('x', $event)" /></label>
            <label>Y 坐标<input type="number" :value="selectedItem.y" :disabled="busy" min="-100000" max="100000" step="any" @change="changeNumber('y', $event)" /></label>
            <label>堆叠顺序（越大越靠前）<input type="number" :value="selectedItem.orderNo" :disabled="busy" min="0" max="2147483647" step="1" @change="changeNumber('orderNo', $event)" /></label>
          </div>
          <p v-else class="organization-hint">当前卡片未加入画布，仍可设置父级。</p>
          <p v-if="parentOutside" class="candidate-warning">父卡片未载入 · {{ selectedCard?.parentCardId }}。当前卡片不是根节点。</p>
          <label for="organization-parent">父卡片（保存立即生效）</label>
          <div class="parent-fields"><select id="organization-parent" :value="parentChoice" :disabled="busy" @change="parentChoice = ($event.target as HTMLSelectElement).value">
            <option v-if="parentOutside" :value="selectedCard?.parentCardId" disabled>当前父卡片未载入 · {{ selectedCard?.parentCardId }}</option>
            <option value="">无父卡片（设为根）</option><option v-for="card in cards.filter(card => card.id !== selectedId)" :key="card.id" :value="card.id">{{ card.title }}</option>
          </select><button type="button" :disabled="busy || !validParent || parentConflict" @click="saveParent">{{ parentSaving ? '正在保存…' : '保存父级' }}</button></div>
          <p class="organization-hint">只可选择当前范围的其他卡片，或清为根。保存父级使用卡片版本 {{ selectedCard?.version }}，服务器会拒绝循环层级。</p>
        </section>
      </template>
    </section>
  </div>
</template>

<style scoped>
.organization-layout { width: min(100%, 1200px); }
.organization-panel { padding: 24px 26px; }
.organization-hint, .organization-status { max-width: 86ch; margin: 8px 0; color: #74869e; font-size: 12px; line-height: 1.8; }
.organization-status { font-weight: 600; color: #4d637f; }
.organization-success { padding: 11px 14px; border-radius: var(--r-md); background: #eef8f2; color: #226e4c; font-size: 12.5px; }
.organization-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0 12px; }
.organization-panel button { min-height: 36px; padding: 8px 14px; border: 1px solid var(--line-strong); border-radius: var(--r-md); background: #fff; color: #47617f; font: inherit; font-size: 12.5px; font-weight: 500; cursor: pointer; transition: background .16s, border-color .16s, color .16s; }
.organization-panel button:hover:not(:disabled) { border-color: #bcd3f1; background: #f4f8ff; color: var(--brand-hover); }
.organization-toolbar button[aria-pressed="true"] { border-color: #bcd3f1; background: var(--brand-soft); color: var(--brand-hover); }
.organization-panel .save-layout, .organization-panel .search-row button { border-color: var(--brand); background: var(--brand); color: #fff; box-shadow: 0 1px 2px rgba(35, 101, 207, .2); }
.organization-toolbar .save-layout { margin-left: auto; padding-inline: 18px; }
.organization-panel .save-layout:hover:not(:disabled) { border-color: var(--brand-hover); background: var(--brand-hover); color: #fff; }
.organization-panel button:disabled, .organization-panel input:disabled, .organization-panel select:disabled { opacity: .55; cursor: default; }
.organization-inspector { margin-top: 24px; padding-top: 22px; border-top: 1px solid var(--line); }
.organization-inspector h2 { margin: 0 0 14px; color: var(--ink); font-size: 16px; font-weight: 600; }
.organization-inspector label { display: block; color: #4d637f; font-size: 12px; font-weight: 500; margin: 12px 0 7px; }
.organization-inspector input, .organization-inspector select { min-width: 0; max-width: 100%; width: 100%; padding: 9px 12px; border: 1px solid #e1e9f3; border-radius: var(--r-md); background: #fff; color: #33445e; font: inherit; font-size: 13px; }
.position-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.position-fields input { margin-top: 6px; }
.parent-fields { display: flex; gap: 10px; }
.parent-fields select { flex: 1; }
.parent-fields button { flex-shrink: 0; }
.organization-panel :is(button, input, select):focus-visible { outline: 2px solid var(--brand-hover); outline-offset: 2px; }
@media (max-width: 700px) { .organization-panel { padding: 20px; } .position-fields { grid-template-columns: 1fr; gap: 0; } .parent-fields { flex-wrap: wrap; } .organization-toolbar .save-layout { margin-left: 0; } }
</style>
