import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from './api'
import { clearSession, session } from './auth'
import { adminTaskRange, type AdminTask, type AdminTaskStep } from './admin-tasks'
import type { Page } from './materials'

export function useAdminTaskMonitor() {
  const route = useRoute(), router = useRouter()
  const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, routePath = route.fullPath
  const status = ref(''), fromDate = ref(''), toDate = ref(''), page = ref(1), results = ref<Page<AdminTask> | null>(null)
  const selected = ref<AdminTask | null>(null), steps = ref<Page<AdminTaskStep> | null>(null), stepPage = ref(1)
  const loading = ref(false), stepsLoading = ref(false), error = ref(''), stepsError = ref('')
  let active = true, selection = 0, appliedStatus = '', appliedRange: { from?: string; to?: string } = {}
  const current = () => active && Boolean(ownerId && ownerToken) && session.value?.user.id === ownerId &&
    session.value?.accessToken === ownerToken && session.value?.user.role === 'ADMIN' && route.fullPath === routePath
  function clearSelection() {
    selection++; selected.value = null; steps.value = null; stepPage.value = 1; stepsError.value = ''; stepsLoading.value = false
  }
  function invalidate() { active = false; results.value = null; error.value = ''; loading.value = false; clearSelection() }
  watch(() => [session.value?.user.id, session.value?.accessToken, session.value?.user.role, route.fullPath], invalidate, { flush: 'sync' })
  onUnmounted(invalidate)
  onMounted(() => { void load() })

  async function handleError(cause: unknown, fallback: string, target: typeof error) {
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
      invalidate()
      if (cause.status === 401) { clearSession(); await router.replace({ name: 'login', query: { redirect: routePath } }) }
      else await router.replace({ name: 'forbidden' })
    } else target.value = cause instanceof Error ? cause.message : fallback
  }
  async function load() {
    if (!current() || loading.value) return
    clearSelection(); loading.value = true; results.value = null; error.value = ''
    const query = new URLSearchParams({ status: appliedStatus, page: String(page.value), pageSize: '20', ...appliedRange })
    try {
      const result = await api<Page<AdminTask>>(`/admin/ai-tasks?${query}`)
      if (current()) results.value = result
    } catch (cause) { if (current()) await handleError(cause, '任务列表读取失败，请刷新重试', error) }
    finally { if (current()) loading.value = false }
  }
  async function applyFilters() {
    if (!current() || loading.value) return
    let range: { from?: string; to?: string }
    try { range = adminTaskRange(fromDate.value, toDate.value) }
    catch (cause) { error.value = (cause as Error).message; return }
    appliedStatus = status.value; appliedRange = range; page.value = 1
    await load()
  }
  async function changePage(next: number) {
    if (!current() || loading.value || next < 1) return
    page.value = next; await load()
  }
  async function loadSteps() {
    if (!current() || !selected.value || stepsLoading.value) return
    const item = selected.value, generation = selection
    const selectedNow = () => current() && selection === generation && selected.value?.id === item.id
    stepsLoading.value = true; steps.value = null; stepsError.value = ''
    const query = new URLSearchParams({ page: String(stepPage.value), pageSize: '20' })
    try {
      const result = await api<Page<AdminTaskStep>>(`/admin/ai-tasks/${encodeURIComponent(item.id)}/steps?${query}`)
      if (selectedNow()) steps.value = result
    } catch (cause) { if (selectedNow()) await handleError(cause, '步骤读取失败，请刷新步骤重试', stepsError) }
    finally { if (selectedNow()) stepsLoading.value = false }
  }
  async function selectTask(item: AdminTask) {
    if (!current() || loading.value || selected.value?.id === item.id) return
    clearSelection(); selected.value = item; await loadSteps()
  }
  async function changeStepPage(next: number) {
    if (!current() || !selected.value || stepsLoading.value || next < 1) return
    stepPage.value = next; await loadSteps()
  }
  return { status, fromDate, toDate, page, results, selected, steps, stepPage, loading, stepsLoading, error, stepsError,
    load, applyFilters, changePage, selectTask, clearSelection, loadSteps, changeStepPage }
}
