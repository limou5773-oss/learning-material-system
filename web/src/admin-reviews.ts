import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError, reviewFileBlob } from './api'
import { clearSession, session } from './auth'
import type { Page } from './materials'
import type { ContentReview, ReviewContent } from './reviews'

export function useAdminReviews() {
  const route = useRoute(), router = useRouter()
  const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, routePath = route.fullPath
  const status = ref('PENDING'), page = ref(1), results = ref<Page<ContentReview> | null>(null)
  const selected = ref<ContentReview | null>(null), content = ref<ReviewContent | null>(null), fileUrl = ref('')
  const accessReason = ref(''), reason = ref(''), decision = ref<'APPROVED' | 'REJECTED'>('APPROVED')
  const loading = ref(false), accessing = ref(false), saving = ref(false), fileLoading = ref(false)
  const error = ref(''), actionError = ref(''), notice = ref('')
  const busy = computed(() => loading.value || accessing.value || saving.value || fileLoading.value)
  const dirty = computed(() => accessReason.value !== '' || reason.value !== '')
  const actionable = computed(() => selected.value?.status === 'PENDING' && selected.value.isCurrent)
  let active = true, appliedStatus = 'PENDING'
  const current = () => active && Boolean(ownerId && ownerToken) && session.value?.user.id === ownerId &&
    session.value?.accessToken === ownerToken && session.value?.user.role === 'ADMIN' && route.fullPath === routePath
  function clearContent() {
    content.value = null
    if (fileUrl.value) URL.revokeObjectURL(fileUrl.value)
    fileUrl.value = ''
  }
  function clearDraft() { clearContent(); selected.value = null; accessReason.value = ''; reason.value = ''; decision.value = 'APPROVED' }
  function invalidate() {
    active = false; clearDraft(); results.value = null; error.value = ''; actionError.value = ''; notice.value = ''
  }
  watch(() => [session.value?.user.id, session.value?.accessToken, session.value?.user.role, route.fullPath], invalidate, { flush: 'sync' })
  function confirmDiscard() { return !dirty.value || window.confirm('理由尚未提交，确定放弃当前理由吗？') }
  function confirmLeave() { return !current() || (!saving.value && confirmDiscard()) }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (!current() || (!dirty.value && !saving.value)) return
    event.preventDefault(); event.returnValue = ''
  }
  onBeforeRouteLeave(confirmLeave); onBeforeRouteUpdate(confirmLeave)
  onUnmounted(() => { invalidate(); window.removeEventListener('beforeunload', beforeUnload) })
  onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
  async function message(cause: unknown, fallback: string) {
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
      invalidate()
      if (cause.status === 401) { clearSession(); await router.replace({ name: 'login', query: { redirect: routePath } }) }
      else await router.replace({ name: 'forbidden' })
      return ''
    }
    return cause instanceof Error ? cause.message : fallback
  }
  async function refreshList() {
    clearContent(); loading.value = true; error.value = ''; results.value = null
    const query = new URLSearchParams({ status: appliedStatus, page: String(page.value), pageSize: '20' })
    try { const value = await api<Page<ContentReview>>(`/admin/reviews?${query}`); if (current()) results.value = value }
    catch (cause) { if (current()) error.value = await message(cause, '审核队列读取失败，请刷新重试') }
    finally { if (current()) loading.value = false }
  }
  async function load() {
    if (!current() || busy.value || !confirmDiscard()) return
    clearDraft(); actionError.value = ''; await refreshList()
  }
  async function applyFilters() {
    if (!current() || busy.value || !confirmDiscard()) return
    clearDraft(); appliedStatus = status.value; page.value = 1; actionError.value = ''; notice.value = ''; await refreshList()
  }
  async function changePage(next: number) {
    if (!current() || busy.value || next < 1 || !confirmDiscard()) return
    clearDraft(); page.value = next; actionError.value = ''; notice.value = ''; await refreshList()
  }
  function selectReview(item: ContentReview) {
    if (!current() || busy.value || selected.value?.id === item.id || !confirmDiscard()) return
    clearDraft(); selected.value = item; actionError.value = ''; notice.value = ''
  }
  function cancelEdit() {
    if (!current() || busy.value || !confirmDiscard()) return
    clearDraft(); actionError.value = ''
  }
  function explanation(value: string) {
    const result = value.trim()
    if (!result || result.length > 1000) { actionError.value = '请输入 1～1000 个字符的理由'; return null }
    return result
  }
  async function viewContent() {
    if (!current() || busy.value || !selected.value || !actionable.value) return
    actionError.value = ''; const note = explanation(accessReason.value); if (!note) return
    const item = selected.value; clearContent(); accessing.value = true
    try {
      const value = await api<ReviewContent>(`/admin/reviews/${encodeURIComponent(item.id)}/content-accesses`, 'POST', { reason: note })
      if (!current()) return
      if (value.reviewId !== item.id || value.material.version !== item.materialVersion) { actionError.value = '送审版本已变化，请刷新审核队列'; return }
      content.value = value; accessReason.value = ''
    } catch (cause) { if (current()) { clearContent(); actionError.value = await message(cause, '正文读取失败，理由已保留') } }
    finally { if (current()) accessing.value = false }
  }
  async function openFile() {
    if (!current() || busy.value || !selected.value || !actionable.value || !content.value?.fileAccessPath) return
    const item = selected.value, path = content.value.fileAccessPath
    fileLoading.value = true; actionError.value = ''
    if (fileUrl.value) URL.revokeObjectURL(fileUrl.value)
    fileUrl.value = ''
    try { const value = await reviewFileBlob(item.id, path); if (current()) fileUrl.value = URL.createObjectURL(value) }
    catch (cause) {
      if (current()) {
        if (cause instanceof ApiRequestError && cause.status === 409) clearContent()
        actionError.value = await message(cause, '原文件读取失败')
      }
    } finally { if (current()) fileLoading.value = false }
  }
  async function submit() {
    if (!current() || busy.value || !selected.value || !actionable.value) return
    actionError.value = ''
    if (!content.value) { actionError.value = '请先填写查看理由并读取当前送审正文'; return }
    const note = explanation(reason.value); if (!note) return
    const item = selected.value, result = decision.value
    if (result !== 'APPROVED' && result !== 'REJECTED') { actionError.value = '请选择有效处置'; return }
    if (!window.confirm(`确认${result === 'APPROVED' ? '通过' : '驳回'}资料「${item.title}」版本 ${item.materialVersion}？${result === 'REJECTED' ? '驳回将限制 AI 整理并取消未完成任务。' : ''}`)) return
    saving.value = true; notice.value = ''
    try {
      try { await api<ContentReview>(`/admin/reviews/${encodeURIComponent(item.id)}/decision`, 'PUT', { version: item.version, decision: result, reason: note }) }
      catch (cause) {
        if (current()) { clearContent(); actionError.value = await message(cause, '处置保存失败，理由已保留') }
        return
      }
      if (!current()) return
      clearDraft(); notice.value = '处置已保存，站内通知已记录。'; await refreshList()
    } finally { if (current()) saving.value = false }
  }
  return { status, page, results, selected, content, fileUrl, accessReason, reason, decision, loading, busy,
    dirty, actionable, error, actionError, notice, load, applyFilters, changePage, selectReview, cancelEdit, viewContent, openFile, submit }
}
