import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from './api'
import { clearSession, session, type User } from './auth'
import type { Page } from './materials'

export function useAdminUsers() {
  const route = useRoute(), router = useRouter()
  const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, routePath = route.fullPath
  const keyword = ref(''), status = ref(''), page = ref(1), results = ref<Page<User> | null>(null)
  const loading = ref(false), saving = ref(false), error = ref(''), actionError = ref(''), notice = ref('')
  const selected = ref<User | null>(null), targetStatus = ref<User['status']>('DISABLED'), reason = ref('')
  const busy = computed(() => loading.value || saving.value)
  const dirty = computed(() => reason.value !== '')
  let active = true, appliedKeyword = '', appliedStatus = ''
  const current = () => active && Boolean(ownerId && ownerToken) && session.value?.user.id === ownerId &&
    session.value?.accessToken === ownerToken && session.value?.user.role === 'ADMIN' && route.fullPath === routePath
  function invalidate() {
    active = false; results.value = null; error.value = ''; actionError.value = ''; notice.value = ''
    clearDraft()
  }
  watch(() => [session.value?.user.id, session.value?.accessToken, session.value?.user.role, route.fullPath], invalidate, { flush: 'sync' })
  function clearDraft() { selected.value = null; targetStatus.value = 'DISABLED'; reason.value = '' }
  function confirmDiscard() { return !dirty.value || window.confirm('原因尚未保存，确定放弃当前原因吗？') }
  function confirmLeave() { return !current() || (!saving.value && confirmDiscard()) }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (!current() || (!dirty.value && !saving.value)) return
    event.preventDefault(); event.returnValue = ''
  }
  onBeforeRouteLeave(confirmLeave)
  onBeforeRouteUpdate(confirmLeave)
  onUnmounted(() => { invalidate(); window.removeEventListener('beforeunload', beforeUnload) })
  onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
  async function message(cause: unknown, fallback: string) {
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
      invalidate()
      if (cause.status === 401) {
        clearSession(); await router.replace({ name: 'login', query: { redirect: routePath } })
      } else await router.replace({ name: 'forbidden' })
      return ''
    }
    return cause instanceof Error ? cause.message : fallback
  }
  async function refreshList() {
    loading.value = true; error.value = ''; results.value = null
    const query = new URLSearchParams({ keyword: appliedKeyword, status: appliedStatus, page: String(page.value), pageSize: '20' })
    try {
      const value = await api<Page<User>>(`/admin/users?${query}`)
      if (current()) results.value = value
    } catch (cause) { if (current()) error.value = await message(cause, '用户列表读取失败，请刷新重试') }
    finally { if (current()) loading.value = false }
  }
  async function load() { if (current() && !busy.value) await refreshList() }
  async function applyFilters() {
    if (!current() || busy.value) return
    if (keyword.value.trim().length > 100) { error.value = '关键词最多 100 个字符'; return }
    if (!confirmDiscard()) return
    clearDraft()
    appliedKeyword = keyword.value.trim(); appliedStatus = status.value
    page.value = 1; notice.value = ''; actionError.value = ''; await refreshList()
  }
  async function changePage(next: number) {
    if (!current() || busy.value || next < 1) return
    if (!confirmDiscard()) return
    clearDraft()
    page.value = next; notice.value = ''; actionError.value = ''; await refreshList()
  }
  function selectUser(item: User) {
    if (!current() || busy.value || selected.value?.id === item.id || !confirmDiscard()) return
    clearDraft(); selected.value = item
    targetStatus.value = item.id === ownerId || item.status === 'DISABLED' ? 'ENABLED' : 'DISABLED'
    actionError.value = ''; notice.value = ''
  }
  function cancelEdit() {
    if (!current() || busy.value || !confirmDiscard()) return
    clearDraft(); actionError.value = ''
  }
  async function submit() {
    if (!current() || busy.value || !selected.value) return
    const item = selected.value, status = targetStatus.value, explanation = reason.value.trim()
    actionError.value = ''
    if (!explanation || explanation.length > 1000) { actionError.value = '请输入 1～1000 个字符的原因'; return }
    if (status !== 'ENABLED' && status !== 'DISABLED') { actionError.value = '请选择有效的目标状态'; return }
    if (item.id === ownerId && status === 'DISABLED') { actionError.value = '不能停用当前登录账号'; return }
    if (!window.confirm(`确认将账号 ${item.username}（${item.displayName}）设为${status === 'ENABLED' ? '启用' : '停用'}？`)) return
    saving.value = true; notice.value = ''
    try {
      let saved: User
      try { saved = await api<User>(`/admin/users/${encodeURIComponent(item.id)}/status`, 'PATCH', { status, reason: explanation }) }
      catch (cause) { if (current()) actionError.value = await message(cause, '状态保存失败，当前输入已保留'); return }
      if (!current()) return
      if (results.value) results.value.items = results.value.items.map(value => value.id === saved.id ? saved : value)
      notice.value = `账号 ${saved.username} 的状态已保存为${saved.status === 'ENABLED' ? '启用' : '停用'}。`
      clearDraft()
      await refreshList()
    } finally { if (current()) saving.value = false }
  }
  function formatDate(value: string) {
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  }
  return { ownerId, keyword, status, page, results, selected, targetStatus, reason, dirty, busy, loading,
    error, actionError, notice, load, applyFilters, changePage, selectUser, cancelEdit, submit, formatDate }
}
