<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session } from '../auth'
import type { Material, Page } from '../materials'
import { reviewStatus, type ContentReview } from '../reviews'

const props = defineProps<{ material: Material }>()
const emit = defineEmits<{ updated: [material: Material] }>()
const route = useRoute(), router = useRouter(), routePath = route.fullPath
const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, materialId = props.material.id
const reason = ref(''), results = ref<Page<ContentReview> | null>(null), page = ref(1)
const loading = ref(false), saving = ref(false), refreshing = ref(false), error = ref(''), actionError = ref(''), notice = ref('')
const busy = computed(() => loading.value || saving.value || refreshing.value)
const alreadySubmitted = ref(false)
let active = true, sequence = 0
const current = () => active && session.value?.user.id === ownerId && session.value?.accessToken === ownerToken && route.fullPath === routePath && props.material.id === materialId
function invalidate() { active = false; sequence++; results.value = null; reason.value = ''; error.value = ''; actionError.value = ''; notice.value = '' }
watch(() => [session.value?.user.id, session.value?.accessToken, route.fullPath], invalidate, { flush: 'sync' })
watch(() => props.material.version, () => { sequence++; results.value = null; alreadySubmitted.value = false; page.value = 1; void loadHistory() })
function confirmLeave() { return !current() || (!saving.value && (!reason.value || window.confirm('送审理由尚未提交，确定放弃当前理由吗？'))) }
function beforeUnload(event: BeforeUnloadEvent) {
  if (!current() || (!reason.value && !saving.value)) return
  event.preventDefault(); event.returnValue = ''
}
onBeforeRouteLeave(confirmLeave); onBeforeRouteUpdate(confirmLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void loadHistory() })
onUnmounted(() => { invalidate(); window.removeEventListener('beforeunload', beforeUnload) })
async function message(cause: unknown, fallback: string) {
  if (cause instanceof ApiRequestError && cause.status === 401) { invalidate(); clearSession(); await router.replace({ name: 'login', query: { redirect: routePath } }); return '' }
  return cause instanceof Error ? cause.message : fallback
}
async function loadHistory(next = page.value) {
  if (!current()) return
  const request = ++sequence; loading.value = true; error.value = ''; results.value = null; page.value = next
  try {
    const value = await api<Page<ContentReview>>(`/materials/${encodeURIComponent(materialId)}/review-requests?page=${next}&pageSize=20`)
    if (current() && request === sequence) {
      results.value = value
      if (next === 1) alreadySubmitted.value = value.items.some(item => item.materialVersion === props.material.version)
    }
  } catch (cause) { if (current() && request === sequence) error.value = await message(cause, '审核历史读取失败，请重试') }
  finally { if (current() && request === sequence) loading.value = false }
}
async function changePage(next: number) { if (!busy.value && next >= 1) await loadHistory(next) }
async function refreshMaterial() {
  if (!current() || busy.value) return
  refreshing.value = true; actionError.value = ''
  try { const value = await api<Material>(`/materials/${encodeURIComponent(materialId)}`); if (current()) emit('updated', value) }
  catch (cause) { if (current()) actionError.value = await message(cause, '资料版本刷新失败，理由已保留') }
  finally { if (current()) refreshing.value = false }
}
async function submit() {
  if (!current() || busy.value || !results.value || alreadySubmitted.value) return
  const explanation = reason.value.trim(), version = props.material.version
  actionError.value = ''
  if (!explanation || explanation.length > 1000) { actionError.value = '请输入 1～1000 个字符的送审理由'; return }
  if (!window.confirm(`确认授权管理员查看资料「${props.material.title}」版本 ${version} 的正文和原文件并进行审核？编辑资料会使此版本授权失效。`)) return
  saving.value = true; notice.value = ''
  try {
    try { await api<ContentReview>(`/materials/${encodeURIComponent(materialId)}/review-requests`, 'POST', { version, reason: explanation }) }
    catch (cause) { if (current()) actionError.value = await message(cause, '送审失败，当前理由已保留'); return }
    if (!current()) return
    reason.value = ''; notice.value = `版本 ${version} 的送审已提交。`; await loadHistory(1)
  } finally { if (current()) saving.value = false }
}
</script>

<template>
  <section class="owner-review editor-panel" :aria-busy="busy" aria-labelledby="owner-review-heading">
    <h2 id="owner-review-heading">资料审核</h2>
    <p>主动提交后，授权管理员查看当前版本 {{ material.version }} 的正文和原文件并作出处置。编辑会使旧版本授权失效。</p>
    <p v-if="material.accessState === 'RESTRICTED'" class="restriction" role="status">此资料已限制 AI 整理。你仍可查阅原文和原文件；请修正后重新送审，通过审核后再使用 AI。</p>
    <p v-if="notice" class="review-notice" role="status">{{ notice }}</p><p v-if="actionError" class="inline-error" role="alert">{{ actionError }} <button type="button" :disabled="busy" @click="refreshMaterial">刷新资料版本（保留理由）</button></p>
    <label for="owner-review-reason">送审理由（必填）</label><textarea id="owner-review-reason" v-model="reason" :disabled="busy || alreadySubmitted" rows="3" maxlength="1000"></textarea>
    <p v-if="alreadySubmitted" class="field-help">当前版本已送审，请查看历史；需要再次送审时，先编辑资料产生新版本。</p>
    <div class="review-actions"><button type="button" :disabled="busy || !results || alreadySubmitted" @click="submit">授权并提交当前版本</button><button type="button" :disabled="busy" @click="loadHistory()">刷新审核历史</button></div>
    <p v-if="loading" class="list-state" role="status">正在读取审核历史…</p><p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <template v-else-if="results"><p v-if="!results.items.length" class="field-help">{{ page > 1 ? '本页暂无审核记录，请返回上一页。' : '尚未提交审核。' }}</p>
      <ol v-else class="review-history"><li v-for="item in results.items" :key="item.id"><strong>版本 {{ item.materialVersion }} · {{ reviewStatus(item.status) }}</strong><span>{{ item.isCurrent ? '当前版本' : '旧版本授权已失效' }}</span><p>送审理由：{{ item.reason }}</p><p v-if="item.decisionReason">处置原因：{{ item.decisionReason }}</p><time :datetime="item.createdAt">{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time></li></ol>
    </template>
    <div v-if="page > 1 || (results && results.total > results.pageSize)" class="pager"><button type="button" :disabled="busy || page <= 1" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} 页</span><button type="button" :disabled="busy || !results || page * results.pageSize >= results.total" @click="changePage(page + 1)">下一页</button></div>
  </section>
</template>

<style scoped>
.owner-review { margin-top: 28px; border-top: 1px solid #eef1f6; padding-top: 24px; }.owner-review h2 { font-size: 20px; }.owner-review p { line-height: 1.7; overflow-wrap: anywhere; }.restriction { padding: 12px 16px; background: #fff6e8; color: #815016; border-radius: 8px; }.review-notice { color: #286448; }.field-help, time { color: #64748b; font-size: 13px; }.review-actions { display: flex; flex-wrap: wrap; gap: 12px; }.review-history { padding-left: 24px; }.review-history li { padding: 16px 0; border-bottom: 1px solid #eef1f6; }.review-history li span { margin-left: 12px; font-size: 13px; }.review-history p { margin: 8px 0; } button { cursor: pointer; } :disabled { opacity: .5; cursor: default; }
</style>
