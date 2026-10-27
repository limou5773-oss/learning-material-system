export interface CountBucket { key: string; count: number }
export interface DailyCount { date: string; count: number }
export interface StatisticsRange { from: string; to: string; timezone: 'Asia/Shanghai' }
export interface Statistics extends StatisticsRange {
  activeMaterialCount: number
  restrictedMaterialCount: number
  subjects: CountBucket[]
  uploadTrend: DailyCount[]
  modelAttemptCount: number
  ocrAttemptCount: number
  retryAttemptCount: number
  unknownRetryAttempts: number
  taskStatuses: CountBucket[]
  taskCompletionRate: number | null
  candidateTaskCount: number
  knownCosts: { currency: string; amount: string; knownAttempts: number }[]
  unknownCostAttempts: number
  metricNotes: string[]
}

const day = 86_400_000
const shanghaiClock = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', hourCycle: 'h23',
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
function dateValue(value: string) {
  const timestamp = Date.parse(`${value}T00:00:00Z`)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(timestamp) || new Date(timestamp).toISOString().slice(0, 10) !== value) {
    throw new Error('请选择有效日期，开始和结束日期均为必填')
  }
  return timestamp
}

function shanghaiMidnight(wallTime: number) {
  let instant = wallTime
  for (let attempt = 0; attempt < 3; attempt++) {
    const parts = shanghaiClock.formatToParts(new Date(instant))
    const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(value => value.type === type)!.value
    const observed = Date.parse(`${part('year').padStart(4, '0')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}Z`)
    const correction = wallTime - observed
    if (correction === 0) return new Date(instant).toISOString()
    instant += correction
  }
  throw new Error('所选日期在上海时区没有有效的午夜，请选择其他日期')
}

export function shanghaiDateStart(value: string) {
  return shanghaiMidnight(dateValue(value))
}

export function defaultStatisticsDates(now = new Date(), days = 7) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(value => value.type === type)!.value
  const today = dateValue(`${part('year')}-${part('month')}-${part('day')}`)
  return { from: new Date(today - (days - 1) * day).toISOString().slice(0, 10), to: new Date(today + day).toISOString().slice(0, 10) }
}

export function statisticsRange(from: string, to: string): StatisticsRange {
  const start = dateValue(from), end = dateValue(to)
  if (start >= end) throw new Error('结束日期须晚于开始日期，结束日不含在统计范围内')
  if (end - start > 90 * day) throw new Error('每次最多查询 90 个自然日')
  return { from: shanghaiMidnight(start), to: shanghaiMidnight(end), timezone: 'Asia/Shanghai' }
}

export function shanghaiDate(value: string) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
}

export const statisticsTaskLabels: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '运行中', WAIT_CONFIRM: '待确认', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}
