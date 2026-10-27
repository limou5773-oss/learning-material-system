import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from './api'
import { clearSession, session } from './auth'
import { defaultStatisticsDates, statisticsRange, type Statistics, type StatisticsRange } from './statistics'

export function useAdminStatistics() {
  const route = useRoute(), router = useRouter()
  const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, routePath = route.fullPath
  const dates = defaultStatisticsDates()
  const fromDate = ref(dates.from), toDate = ref(dates.to)
  const statistics = ref<Statistics | null>(null), appliedRange = ref<StatisticsRange | null>(null)
  const loading = ref(false), error = ref('')
  let active = true, requestedRange = statisticsRange(dates.from, dates.to)
  const current = () => active && Boolean(ownerId && ownerToken) && session.value?.user.id === ownerId &&
    session.value?.accessToken === ownerToken && session.value?.user.role === 'ADMIN' && route.fullPath === routePath
  function invalidate() { active = false; statistics.value = null; appliedRange.value = null; error.value = ''; loading.value = false }
  watch(() => [session.value?.user.id, session.value?.accessToken, session.value?.user.role, route.fullPath], invalidate, { flush: 'sync' })
  onUnmounted(invalidate)
  onMounted(() => { void load() })

  async function load() {
    if (!current() || loading.value) return
    loading.value = true; statistics.value = null; appliedRange.value = null; error.value = ''
    const query = new URLSearchParams({ ...requestedRange })
    try {
      const result = await api<Statistics>(`/admin/statistics?${query}`)
      if (current()) { statistics.value = result; appliedRange.value = { from: result.from, to: result.to, timezone: result.timezone } }
    } catch (cause) {
      if (!current()) return
      if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        invalidate()
        if (cause.status === 401) { clearSession(); await router.replace({ name: 'login', query: { redirect: routePath } }) }
        else await router.replace({ name: 'forbidden' })
      } else error.value = cause instanceof Error ? cause.message : '统计读取失败，请重试'
    } finally { if (current()) loading.value = false }
  }
  async function applyRange() {
    if (!current() || loading.value) return
    try { requestedRange = statisticsRange(fromDate.value, toDate.value) }
    catch (cause) { error.value = (cause as Error).message; return }
    await load()
  }
  async function preset(days: 7 | 30) {
    if (!current() || loading.value) return
    const range = defaultStatisticsDates(new Date(), days)
    fromDate.value = range.from; toDate.value = range.to
    await applyRange()
  }
  return { fromDate, toDate, statistics, appliedRange, loading, error, load, applyRange, preset }
}
